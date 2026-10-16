package com.metiri.armeasure

import android.app.Activity
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import uniffi.core.Point3
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView renderer: camera background passthrough + debug plane overlay
 * (Phase 2) plus Phase 3 measurement: consumes one-shot taps via [Frame.hitTest]
 * (feature-point hits only — plane finding disabled), creates
 * anchors for accepted hits, and draws a live line + distance while a
 * measurement is in progress.
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
 * results are posted back through [onTapHit]/[onTapMiss]/[onTapEstimate]/
 * [onLiveDistance], which MainActivity routes to the main thread.
 *
 * Gate 4.2 blank-wall fallback: when the hit-test tiers find nothing at the
 * tap, the renderer tries a sensor-trig ray-plane estimate
 * ([SensorEstimate.estimatePoint]) from injected gravity sample, user height
 * and camera intrinsics, posting it via [onTapEstimate] (tagged
 * `MeasurementSource.SENSOR` downstream, no anchor). Only when the estimate
 * is impossible does [onTapMiss] fire. [cameraPosSink] receives the tracked
 * camera position every frame (written to a volatile — no main-thread post).
 */
class ArRenderer(
    private val activity: Activity,
    private val measureStateProvider: () -> MeasureState,
    private val pendingTapProvider: () -> FloatArray?,
    private val clearPendingTap: () -> Unit,
    private val lastTouchProvider: () -> FloatArray?,
    private val planeOverlayProvider: () -> Boolean,
    private val distanceFn: (Point3, Point3) -> Float,
    private val onPlaneFound: (Int) -> Unit,
    private val onCameraTracking: (Boolean) -> Unit,
    private val onTapHit: (Point3, Anchor?) -> Unit,
    private val onTapMiss: () -> Unit,
    private val onTapEstimate: (Point3) -> Unit,
    private val onLiveDistance: (Float) -> Unit,
    /** Latest static gravity sample, device frame; null until the sensor delivers. */
    private val gravityProvider: () -> FloatArray?,
    /** Stored camera height above the surface plane, meters. */
    private val userHeightProvider: () -> Float,
    /** CameraCharacteristics pinhole intrinsics in ARCore image pixels, or null. */
    private val cameraIntrinsicsProvider: () -> SensorEstimate.Intrinsics?,
    /** GL-thread sink for the tracked camera world position (volatile write). */
    private val cameraPosSink: (Point3) -> Unit,
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
                reportCameraTracking(frame)
                publishCameraPosition(frame)
                background.draw(frame)
                // Planes are a debug overlay only (default OFF): instantiate/
                // create always, but draw — and count — only while enabled.
                planes.overlayEnabled = planeOverlayProvider()
                if (planes.overlayEnabled) {
                    planes.onDrawFrame(frame, s, onPlaneFound)
                }
                consumePendingTap(frame, s)
                drawMeasurementLine(frame)
            } catch (t: Throwable) {
                Log.e("ArMeasure.Renderer", "frame update failed", t)
            }
        }
    }

    /**
     * Reports camera tracking readiness (true once `frame.camera` reaches
     * TRACKING) on every change, driving the tests' readiness wait — planes
     * are no longer a readiness signal (gate 4.2: overlay-only, default OFF).
     * Deduped so the UI thread is only posted when the state flips.
     */
    private var lastReportedTracking: Boolean? = null
    private fun reportCameraTracking(frame: Frame) {
        val tracking = frame.camera.trackingState == TrackingState.TRACKING
        if (tracking != lastReportedTracking) {
            lastReportedTracking = tracking
            onCameraTracking(tracking)
        }
    }

    /**
     * Publishes the tracked camera world position through [cameraPosSink] on
     * every flip-free frame. The sink must be non-blocking (it writes a
     * volatile on the GL thread); MainActivity uses it as the
     * camera-to-point input for `score_confidence`.
     */
    private fun publishCameraPosition(frame: Frame) {
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        val pose = frame.camera.pose
        cameraPosSink(Point3(pose.tx(), pose.ty(), pose.tz()))
    }

    /**
     * Consumes the one-shot tap (if any): hit-test, keep the nearest hit,
     * create its anchor, and post the result. When no hit tier matches, the
     * gate 4.2 blank-wall fallback tries a sensor-trig ray-plane estimate —
     * posted via [onTapEstimate] (no anchor: an estimated point has no
     * geometry to anchor to). Only if the estimate is also impossible does
     * [onTapMiss] fire (machine no-op + "no point here" feedback). The
     * pending tap is always cleared.
     */
    private fun consumePendingTap(frame: Frame, session: Session) {
        val tap = pendingTapProvider() ?: return
        clearPendingTap()
        val hit = nearestHit(frame, tap[0], tap[1])
        if (hit == null) {
            val estimate = estimateTapPoint(frame, session, tap[0], tap[1])
            if (estimate != null) {
                logFirstEstimate()
                onTapEstimate(estimate)
            } else {
                onTapMiss()
            }
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
     * Sensor-trig ray-plane estimate for a tap that hit nothing (blank wall).
     * See [SensorEstimate.estimatePoint] for the formula. Returns null — the
     * caller then posts [onTapMiss] — when:
     * - the camera is not TRACKING (pose untrustworthy),
     * - no static gravity sample has arrived yet,
     * - the tap cannot be placed in a pixel frame with valid intrinsics.
     *
     * Intrinsics: the injected CameraCharacteristics pinhole model (ARCore
     * image pixels; the view tap is mapped through
     * `frame.transformCoordinates2d(VIEW -> IMAGE_PIXELS)` so the frames
     * match exactly). When those are unavailable, derive image-pixel
     * intrinsics from ARCore's own projection matrix
     * ("ARCore camera texture geometry" fallback): the tap is mapped
     * VIEW -> IMAGE_PIXELS first, then for a symmetric projection
     * `P[0] = 1/tan(fovX/2)` in NDC, so `fx = P[0] * imageW / 2`
     * (and fy likewise from `P[5]`), with cx/cy at the image centre —
     * the same image-pixel frame the primary path uses. Returns null when
     * the image size cannot be resolved (never crashes).
     */
    private fun estimateTapPoint(frame: Frame, session: Session, nx: Float, ny: Float): Point3? {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val gravity = gravityProvider() ?: return null
        val viewTap = floatArrayOf(nx * viewWidth, ny * viewHeight)

        // Preferred: CameraCharacteristics intrinsics in ARCore image pixels —
        // the view tap is mapped through ARCore so the frames match exactly.
        // Any failure (mapping or absent calibration) degrades to the
        // projection-matrix path below, never to a hard miss.
        val imageIntrinsics = cameraIntrinsicsProvider()
        if (imageIntrinsics != null) {
            val mapped = FloatArray(2)
            try {
                frame.transformCoordinates2d(
                    Coordinates2d.VIEW,
                    viewTap,
                    Coordinates2d.IMAGE_PIXELS,
                    mapped,
                )
                val pose = camera.pose
                val estimate = SensorEstimate.estimatePoint(
                    tapX = mapped[0],
                    tapY = mapped[1],
                    intrinsics = imageIntrinsics,
                    gravity = gravity,
                    poseQ = floatArrayOf(pose.qx(), pose.qy(), pose.qz(), pose.qw()),
                    cameraPos = Point3(pose.tx(), pose.ty(), pose.tz()),
                    userHeightM = userHeightProvider(),
                )
                if (estimate != null) return estimate
            } catch (t: Throwable) {
                Log.w("ArMeasure.Renderer", "characteristics-intrinsics path failed", t)
            }
        }

        // Fallback (review B1): the tap is mapped VIEW -> IMAGE_PIXELS so the
        // tap and the derived intrinsics live in the SAME frame — ARCore image
        // pixels. If the image size cannot be resolved, return null: the
        // caller posts [onTapMiss] rather than estimating in a frame that may
        // not match the downstream projection.
        val image = imageSizePx(session, camera) ?: return null
        val imageTap = FloatArray(2)
        try {
            frame.transformCoordinates2d(
                Coordinates2d.VIEW,
                viewTap,
                Coordinates2d.IMAGE_PIXELS,
                imageTap,
            )
        } catch (t: Throwable) {
            Log.w("ArMeasure.Renderer", "VIEW -> IMAGE_PIXELS mapping failed", t)
            return null
        }
        if (!imageTap[0].isFinite() || !imageTap[1].isFinite()) return null

        // Pinhole intrinsics in image pixels: for a symmetric projection
        // P[0] = 1/tan(fovX/2) in NDC, so fx = P[0] * imageW / 2 (fy likewise
        // from P[5]); the principal point is the image centre.
        val projection = FloatArray(16)
        camera.getProjectionMatrix(projection, 0, 0.1f, 100.0f)
        val p0 = projection[0]
        val p5 = projection[5]
        if (!p0.isFinite() || !p5.isFinite() || p0 <= 0f || p5 <= 0f) return null
        val imageW = image[0].toFloat()
        val imageH = image[1].toFloat()
        val pose = camera.pose
        return SensorEstimate.estimatePoint(
            tapX = imageTap[0],
            tapY = imageTap[1],
            intrinsics = SensorEstimate.Intrinsics(
                fx = p0 * imageW / 2f,
                fy = p5 * imageH / 2f,
                cx = imageW / 2f,
                cy = imageH / 2f,
            ),
            gravity = gravity,
            poseQ = floatArrayOf(pose.qx(), pose.qy(), pose.qz(), pose.qw()),
            cameraPos = Point3(pose.tx(), pose.ty(), pose.tz()),
            userHeightM = userHeightProvider(),
        )
    }

    /**
     * Camera image size in pixels for the fallback intrinsics: the session's
     * camera config first ([Session.getCameraConfig] -> `imageSize`), then the
     * frame's image intrinsics as a backup. Null when neither resolves (or a
     * guard throws) — the caller then returns null and the tap degrades to
     * [onTapMiss] instead of crashing or guessing a pixel frame.
     */
    private fun imageSizePx(session: Session, camera: Camera): IntArray? {
        val configured = try {
            session.cameraConfig.imageSize
        } catch (t: Throwable) {
            Log.w("ArMeasure.Renderer", "cameraConfig.imageSize unavailable", t)
            null
        }
        if (configured != null && configured.width > 0 && configured.height > 0) {
            return intArrayOf(configured.width, configured.height)
        }
        val dims = try {
            camera.imageIntrinsics.imageDimensions
        } catch (t: Throwable) {
            Log.w("ArMeasure.Renderer", "imageIntrinsics unavailable", t)
            null
        }
        if (dims != null && dims.size >= 2 && dims[0] > 0 && dims[1] > 0) {
            return intArrayOf(dims[0], dims[1])
        }
        return null
    }

    /** One-line log on the first estimate of the session (fallback proof). */
    private var firstEstimateLogged = false
    private fun logFirstEstimate() {
        if (!firstEstimateLogged) {
            firstEstimateLogged = true
            Log.i(TAG, "first blank-wall sensor estimate posted (Sensor source)")
        }
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
                val hit = nearestHit(frame, last[0], last[1])
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

    /**
     * Nearest hit for a normalized tap position (origin top-left, y down).
     *
     * Plane-free priority order (plane finding disabled):
     * 1. Feature-point with estimated surface normal (real surface)
     * 2. Any tracking feature-point (last resort)
     */
    private fun nearestHit(frame: Frame, nx: Float, ny: Float): HitResult? {
        val hits = frame.hitTest(nx * viewWidth, ny * viewHeight)

        // --- Tier 1: feature-point with estimated surface normal ---
        val normalPoint = hits
            .filter {
                val pt = it.trackable as? Point ?: return@filter false
                pt.trackingState == TrackingState.TRACKING &&
                    pt.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            }
            .minByOrNull { it.distance }
        if (normalPoint != null) {
            logFirstHit(estimatedNormal = true)
            return normalPoint
        }

        // --- Tier 2: any tracking feature-point (last resort) ---
        val anyPoint = hits
            .filter {
                it.trackable is Point && it.trackable.trackingState == TrackingState.TRACKING
            }
            .minByOrNull { it.distance }
        if (anyPoint != null) {
            logFirstHit(estimatedNormal = false)
        }
        return anyPoint
    }

    /** One-line log on the first hit of the session to confirm the fallback tier. */
    private var firstHitLogged = false
    private fun logFirstHit(estimatedNormal: Boolean) {
        if (!firstHitLogged) {
            firstHitLogged = true
            Log.d(TAG, "firstHit estimatedSurfaceNormal=$estimatedNormal")
        }
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