package com.metiri.armeasure

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.Availability
import uniffi.core.Point3
import uniffi.core.distance

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var overlayText: TextView
    private lateinit var measureButton: TextView
    private lateinit var actionButton: TextView
    private var glSurfaceView: GLSurfaceView? = null
    private var isResumed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Injectable distance function: the machine and renderer never touch FFI directly. */
    private val distanceFn: (Point3, Point3) -> Float = { a, b -> distance(a, b) }

    @VisibleForTesting var sessionReady = false; private set
    @VisibleForTesting var sessionRunning = false; private set
    @VisibleForTesting var lastSessionState = "created"; private set
    @VisibleForTesting var planeFoundCount = 0; private set
    @VisibleForTesting var capabilityReport: CapabilityReport? = null; private set
    @VisibleForTesting var measureState: MeasureState = MeasureState.Idle; private set
    @VisibleForTesting var liveDistance: Float? = null; private set
    @VisibleForTesting var measurementAnchors: List<Anchor> = emptyList(); private set

    /**
     * One-shot tap consumed by the renderer on the GL thread (normalized
     * [0,1] view coords, origin top-left, y down — no flip vs touch coords).
     */
    @Volatile private var pendingTap: FloatArray? = null

    /** Last touch position, drives the live line preview while Measuring. */
    @Volatile private var lastTouch: FloatArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(48, 48, 48, 48)
            // Semi-transparent dark scrim keeps the status readable over the
            // camera feed (A20 dark-frame fix: text sits on top of AR view).
            setBackgroundColor(0x99000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
        overlayText = TextView(this).apply {
            textSize = 14f
            setPadding(48, 24, 48, 24)
            setBackgroundColor(0x99000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
        measureButton = TextView(this).apply {
            text = "Measure"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 12, 32, 12)
            setBackgroundColor(0xFF3F51B5.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { dispatch(MeasureAction.BeginMeasure) }
        }
        actionButton = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 12, 32, 12)
            setBackgroundColor(0xFFB00020.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                dispatch(if (measureState is MeasureState.Complete) MeasureAction.Reset else MeasureAction.Cancel)
            }
        }
        root.addView(
            statusText,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            overlayText,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.BOTTOM }
        )
        root.addView(
            measureButton,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                    bottomMargin = 96
                }
        )
        root.addView(
            actionButton,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                    bottomMargin = 96
                }
        )
        setContentView(root)
        lastSessionState = "created"
        updateButtons()
        updateOverlay()

        if (hasCameraPermission()) {
            startCapabilityFlow()
        } else {
            statusText.text = "Camera permission needed to measure."
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCapabilityFlow()
            } else {
                statusText.text = "Camera permission denied — AR measurement cannot start."
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        glSurfaceView?.onResume()
        if (ArSessionManager.ready) {
            ArSessionManager.resume()
            sessionRunning = true
        }
        lastSessionState = "resumed"
        updateOverlay()
    }

    override fun onPause() {
        if (ArSessionManager.ready) {
            ArSessionManager.pause()
            sessionRunning = false
        }
        glSurfaceView?.onPause()
        lastSessionState = "paused"
        updateOverlay()
        super.onPause()
    }

    override fun onDestroy() {
        // Explicit anchor cleanup + pending-tap reset before the session is
        // torn down: close() covers anchors via session teardown, but doing
        // both explicitly keeps cleanup unconditional and prevents a stale tap
        // left in the volatile field from being consumed on a relaunch.
        detachAllMeasurementAnchors()
        pendingTap = null
        ArSessionManager.close()
        sessionReady = false
        sessionRunning = false
        lastSessionState = "destroyed"
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCapabilityFlow() {
        statusText.text = "Checking ARCore support..."
        checkAvailabilityWithRetry(0)
    }

    private fun checkAvailabilityWithRetry(attempt: Int) {
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability ->
            runOnUiThread {
                when (availability) {
                    Availability.SUPPORTED_INSTALLED -> onArcoreReady()
                    Availability.SUPPORTED_NOT_INSTALLED,
                    Availability.SUPPORTED_APK_TOO_OLD,
                    -> {
                        statusText.text =
                            "ARCore service missing or outdated ($availability). Install Google Play Services for AR and retry."
                        try {
                            ArCoreApk.getInstance().requestInstall(this, true)
                        } catch (e: Exception) {
                            Log.e(TAG, "requestInstall failed", e)
                        }
                    }
                    Availability.UNKNOWN_CHECKING -> {
                        statusText.text = "Checking ARCore support (attempt ${attempt + 1})..."
                        if (shouldRetryAvailabilityCheck(attempt)) {
                            mainHandler.postDelayed({ checkAvailabilityWithRetry(attempt + 1) }, 500)
                        } else {
                            statusText.text = "ARCore support check timed out."
                        }
                    }
                    Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                        capabilityReport = CapabilityEvaluator.evaluate(false, false, sensorsOk())
                        statusText.text = reportText()
                    }
                    else -> statusText.text = "ARCore check failed: $availability"
                }
            }
        }
    }

    private fun onArcoreReady() {
        val session = ArSessionManager.create(this)
        val depth = session?.let { ArSessionManager.hasDepthSupport(it) } ?: false
        capabilityReport = CapabilityEvaluator.evaluate(true, depth, sensorsOk())
        statusText.text = reportText()

        if (capabilityReport!!.canMeasure && session != null) {
            showArView()
            if (isResumed) {
                ArSessionManager.resume()
                sessionRunning = true
            }
            sessionReady = true
        } else {
            val reason = if (session == null) "AR session failed to initialize" else "motion sensors missing"
            statusText.text = "${statusText.text}\nCannot start measuring: $reason"
        }
    }

    private fun sensorsOk(): Boolean {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        return sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    }

    private fun reportText(): String {
        val r = capabilityReport ?: return "Capability report unavailable."
        return buildString {
            append("ARCore supported: ${r.arcoreSupported}\n")
            append("Depth API supported: ${r.depthSupported}\n")
            append("Motion sensors: ${r.sensorsOk}\n")
            append("Can measure: ${r.canMeasure}")
        }
    }

    private fun showArView() {
        if (glSurfaceView != null) return
        val surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(
                ArRenderer(
                    activity = this@MainActivity,
                    measureStateProvider = { measureState },
                    pendingTapProvider = { pendingTap },
                    clearPendingTap = { pendingTap = null },
                    lastTouchProvider = { lastTouch },
                    distanceFn = this@MainActivity.distanceFn,
                    onPlaneFound = { count ->
                        runOnUiThread {
                            if (planeFoundCount != count) {
                                planeFoundCount = count
                                Log.i(TAG, "planes tracked: $count")
                                updateOverlay()
                            }
                        }
                    },
                    onTapHit = { point, anchor ->
                        runOnUiThread { handleTapHit(point, anchor) }
                    },
                    onTapMiss = {
                        runOnUiThread { handleTapMiss() }
                    },
                    onLiveDistance = { d ->
                        runOnUiThread {
                            if (liveDistance != d) {
                                liveDistance = d
                                updateOverlay()
                            }
                        }
                    },
                )
            )
            setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY)
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handleTapAt(event.x / v.width, event.y / v.height)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Live preview follows the finger while Measuring: only
                        // the touch position is updated — taps stay one-shot on
                        // ACTION_DOWN (handleTapAt handles those).
                        lastTouch = floatArrayOf(event.x / v.width, event.y / v.height)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Deliver the click (accessibility/children) while still
                        // claiming the gesture so the line preview doesn't drop.
                        v.performClick()
                        true
                    }
                    else -> false
                }
            }
        }
        glSurfaceView = surface
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        root.addView(surface, 0, lp)
        if (isResumed) surface.onResume()
        // Auto-arm: entering the AR view arms the machine so the first tap
        // places the first point (Idle -> SelectingFirstPoint).
        dispatch(MeasureAction.BeginMeasure)
    }

    /**
     * Records a tap (same code path the OnTouchListener uses). [xFraction]/
     * [yFraction] are normalized [0,1] view coords, origin top-left, y down —
     * matches ARCore's view coordinate convention, no flip. Test hook for
     * gate 3.2/3.5 (drives the listener logic directly, same machine path).
     */
    @VisibleForTesting
    fun handleTapAt(xFraction: Float, yFraction: Float) {
        pendingTap = floatArrayOf(xFraction, yFraction)
        lastTouch = floatArrayOf(xFraction, yFraction)
    }

    private fun handleTapHit(point: Point3, anchor: Anchor?) {
        val prev = measureState
        val next = MeasureMachine.transition(prev, MeasureAction.TapHit(point), distanceFn)
        if (next === prev) {
            // Rejected hit (e.g. tap while Idle or Complete): the renderer
            // already created an anchor for it — never track it, detach now so
            // it cannot leak (gate 3.5).
            anchor?.detach()
            return
        }
        if (anchor != null) measurementAnchors = measurementAnchors + anchor
        measureState = next
        updateButtons()
        updateOverlay()
    }

    private fun handleTapMiss() {
        // TapMiss is a no-op everywhere; nothing to do (machine stays put).
    }

    /**
     * Dispatches an action into the machine. On Cancel/Reset that leaves a
     * non-Idle state for Idle, all tracked measurement anchors are detached
     * (gate 3.5 — no dangling anchors). No-op transitions never detach.
     */
    @VisibleForTesting
    fun dispatch(action: MeasureAction) {
        val prev = measureState
        val next = MeasureMachine.transition(prev, action, distanceFn)
        measureState = next
        if (prev != MeasureState.Idle && next == MeasureState.Idle) {
            detachAllMeasurementAnchors()
        }
        updateButtons()
        updateOverlay()
    }

    private fun detachAllMeasurementAnchors() {
        for (anchor in measurementAnchors) {
            try {
                anchor.detach()
            } catch (t: Throwable) {
                Log.w(TAG, "anchor detach failed", t)
            }
        }
        measurementAnchors = emptyList()
    }

    private fun updateButtons() {
        when (measureState) {
            MeasureState.Idle -> {
                measureButton.visibility = View.VISIBLE
                actionButton.visibility = View.GONE
            }
            MeasureState.SelectingFirstPoint -> {
                measureButton.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Cancel"
            }
            is MeasureState.Measuring -> {
                measureButton.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Cancel"
            }
            is MeasureState.Complete -> {
                measureButton.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Reset"
            }
        }
    }

    private fun updateOverlay() {
        val stateLine = when (val s = measureState) {
            MeasureState.Idle -> "State: Idle"
            MeasureState.SelectingFirstPoint -> "State: Select first point"
            is MeasureState.Measuring -> "State: Measuring | ${formatDistance(liveDistance)}"
            is MeasureState.Complete -> "State: Complete | ${formatDistance(s.distance)}"
        }
        overlayText.text = "Session: $lastSessionState | Planes: $planeFoundCount\n$stateLine"
    }

    private fun formatDistance(d: Float?): String =
        if (d == null || d.isNaN()) "--" else String.format(java.util.Locale.US, "%.2f m", d)

    companion object {
        private const val TAG = "ArMeasure"
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }
}

/**
 * Gate 2.4 follow-up B: pure retry-decision helper for the async
 * ARCore availability check loop (attempts are 0-based). Extracted so the
 * timeout branch is JVM-unit-testable without the async path.
 */
internal fun shouldRetryAvailabilityCheck(attempt: Int, maxAttempts: Int = 10): Boolean =
    attempt < maxAttempts