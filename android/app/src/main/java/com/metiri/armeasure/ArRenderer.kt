package com.metiri.armeasure

import android.app.Activity
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import uniffi.core.Point3
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView renderer: camera background passthrough + debug plane overlay
 * (Phase 2) plus Phase 3 measurement: consumes one-shot taps via [Frame.hitTest]
 * (plane hits only), creates anchors for accepted hits, and draws a live line +
 * distance while a measurement is in progress.
 *
 * Session access is lazy (the session may be created after the GL thread
 * starts, since the capability flow is async) — texture/display setup is
 * deferred until the session is available. The session is always accessed
 * through [ArSessionManager.withSession], which holds the session lock for
 * the duration of the frame so the UI thread's create/close can never race a
 * live [Session.update] (the lock is released before any posted callbacks run).
 *
 * Tap/state inputs are injected from MainActivity so the GL thread never owns
 * them: [pendingTapProvider]/[clearPendingTap] deliver one-shot normalized taps
 * (consumed once per frame), [lastTouchProvider] drives the live preview,
 * [measureStateProvider] exposes the machine state, and [distanceFn] is the
 * injectable distance function (production: `uniffi.core.distance`). Hit
 * results are posted back through [onTapHit]/[onTapMiss]/[onLiveDistance],
 * which MainActivity routes to the main thread.
 */
class ArRenderer(
    private val activity: Activity,
    private val measureStateProvider: () -> MeasureState,
    private val pendingTapProvider: () -> FloatArray?,
    private val clearPendingTap: () -> Unit,
    private val lastTouchProvider: () -> FloatArray?,
    private val distanceFn: (Point3, Point3) -> Float,
    private val onPlaneFound: (Int) -> Unit,
    private val onTapHit: (Point3, Anchor?) -> Unit,
    private val onTapMiss: () -> Unit,
    private val onLiveDistance: (Float) -> Unit,
) : GLSurfaceView.Renderer {

    private val background = BackgroundRenderer()
    private val planes = PlaneRenderer()
    private val lines = LineRenderer()
    private val markers = MarkerRenderer()
    private var textureId = -1
    private var textureNameSet = false
    private var boundSessionId = -1
    private var displaySet = false
    private var viewWidth = 0
    private var viewHeight = 0

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private var lastLiveDistance = Float.NaN

    @Suppress("DEPRECATION")
    private val displayRotation: Int
        get() = activity.windowManager.defaultDisplay.rotation

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f)
        // A NEW texture + a fresh GL context are created here. The old texture
        // id is dead in this context, so the camera must be rebound to the new
        // id even if the session object is unchanged (surface recreation).
        textureNameSet = false
        boundSessionId = -1
        textureId = createOesTexture()
        background.textureId = textureId
        background.createOnGlThread()
        planes.createOnGlThread()
        lines.createOnGlThread()
        markers.createOnGlThread()
        Log.i(TAG, "surface created, camera texture id = $textureId")
        checkGlError("surface created")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        displaySet = false
    }

    override fun onDrawFrame(gl: GL10?) {
        // The entire session-using section runs inside withSession: the
        // UI thread's create()/close() take the same lock, so this frame's
        // session cannot be closed/reassigned while we are inside
        // session.update(). The block only posts callbacks (runOnUiThread
        // returns immediately) and does no long/blocking work.
        ArSessionManager.withSession { s ->
            if (s == null) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                return@withSession
            }
            try {
                // Rebind whenever the texture or the session object changed.
                // textureNameSet is reset on every surface recreation; the
                // session id guard covers ArSessionManager.create() being called
                // again while the GL surface lives on.
                val sessionId = System.identityHashCode(s)
                if (!textureNameSet || sessionId != boundSessionId) {
                    s.setCameraTextureName(textureId)
                    textureNameSet = true
                    boundSessionId = sessionId
                    Log.i(TAG, "camera texture $textureId bound to session $sessionId")
                }
                if (!displaySet) {
                    s.setDisplayGeometry(displayRotation, viewWidth, viewHeight)
                    displaySet = true
                }
                val frame = s.update()
                background.draw(frame)
                planes.onDrawFrame(frame, s, onPlaneFound)
                consumePendingTap(frame)
                drawMeasurementLine(frame)
            } catch (t: Throwable) {
                Log.e("ArMeasure.Renderer", "frame update failed", t)
            }
        }
    }

    /**
     * Consumes the one-shot tap (if any): hit-test, keep the nearest plane hit,
     * create its anchor, and post the result. A tap with no plane hit posts
     * [onTapMiss] (machine no-op). The pending tap is always cleared.
     */
    private fun consumePendingTap(frame: Frame) {
        val tap = pendingTapProvider() ?: return
        clearPendingTap()
        val hit = nearestPlaneHit(frame, tap[0], tap[1])
        if (hit == null) {
            onTapMiss()
            return
        }
        val point = Point3(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())
        val anchor = try {
            hit.createAnchor()
        } catch (t: Throwable) {
            Log.w("ArMeasure.Renderer", "anchor creation failed", t)
            null
        }
        onTapHit(point, anchor)
    }

    /**
     * Live preview + completed measurement line. While [MeasureState.Measuring],
     * hit-tests the last touch position every frame and draws a line from the
     * start point to the current hit, posting the live distance (only when it
     * changed by more than 1mm, to avoid spamming the main thread). In
     * [MeasureState.Complete] the stored start/end line is drawn.
     */
    private fun drawMeasurementLine(frame: Frame) {
        val state = measureStateProvider()
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

        when (state) {
            is MeasureState.Measuring -> {
                val last = lastTouchProvider() ?: return
                val hit = nearestPlaneHit(frame, last[0], last[1])
                if (hit != null) {
                    val end = Point3(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())
                    val distance = distanceFn(state.start, end)
                    if (lastLiveDistance.isNaN() || kotlin.math.abs(distance - lastLiveDistance) > 0.001f) {
                        lastLiveDistance = distance
                        onLiveDistance(distance)
                    }
                    lines.drawLine(state.start, end, viewMatrix, projectionMatrix, LINE_COLOR)
                    // Live finger-follow reticle at the current hit + a marker
                    // pinning the start point so the user sees where the line
                    // begins and whether it matches object edges.
                    markers.drawMarker(end, viewMatrix, projectionMatrix, PREVIEW_RETICLE_COLOR, 14f, 3f, viewHeight)
                    markers.drawMarker(state.start, viewMatrix, projectionMatrix, START_MARKER_COLOR, 20f, 5f, viewHeight)
                }
            }
            is MeasureState.Complete -> {
                lastLiveDistance = Float.NaN
                lines.drawLine(state.start, state.end, viewMatrix, projectionMatrix, LINE_COLOR)
                markers.drawMarker(state.start, viewMatrix, projectionMatrix, START_MARKER_COLOR, 20f, 5f, viewHeight)
                markers.drawMarker(state.end, viewMatrix, projectionMatrix, END_MARKER_COLOR, 20f, 5f, viewHeight)
            }
            else -> lastLiveDistance = Float.NaN
        }
    }

    /** Nearest plane hit for a normalized tap position (origin top-left, y down). */
    private fun nearestPlaneHit(frame: Frame, nx: Float, ny: Float): HitResult? {
        val hits = frame.hitTest(nx * viewWidth, ny * viewHeight)
        val planeHits = hits.filter {
            it.trackable is Plane && it.trackable.trackingState == TrackingState.TRACKING
        }
        // Prefer stable planes (extent ≥ 0.08 m²) to avoid tiny floating fragments.
        val stable = planeHits.filter {
            val p = it.trackable as Plane
            p.extentX * p.extentZ >= MIN_PLANE_AREA
        }.minByOrNull { it.distance }
        if (stable != null) return stable
        // Fallback: nearest among all tracking planes so we don't return null
        // while stable planes are still forming.
        return planeHits.minByOrNull { it.distance }
    }

    private fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    companion object {
        private const val TAG = "ArMeasure.Renderer"
        /** Minimum plane area in m² — must match PlaneRenderer.MIN_PLANE_AREA. */
        private const val MIN_PLANE_AREA = 0.08f
        /** Bright yellow measurement line (live preview and completed). */
        private val LINE_COLOR = floatArrayOf(1f, 0.85f, 0f, 1f)
        /** White reticle at the finger-follow hit point while measuring. */
        private val PREVIEW_RETICLE_COLOR = floatArrayOf(1f, 1f, 1f, 1f)
        /** Bright green marker at the measurement start point. */
        private val START_MARKER_COLOR = floatArrayOf(0f, 1f, 0f, 1f)
        /** Red marker at the measurement end point (Complete). */
        private val END_MARKER_COLOR = floatArrayOf(1f, 0.25f, 0.25f, 1f)
    }
}