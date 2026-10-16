package com.metiri.armeasure

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import uniffi.core.Confidence
import uniffi.core.MeasurementSource
import uniffi.core.Point3
import uniffi.core.distance
import uniffi.core.scoreConfidence

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
    /** Camera readiness (renderer-reported): true once the camera TRACKs. Replaces plane count as the tests' readiness signal (gate 4.2). */
    @VisibleForTesting var cameraTracking = false; private set
    @VisibleForTesting var capabilityReport: CapabilityReport? = null; private set
    @VisibleForTesting var measureState: MeasureState = MeasureState.Idle; private set
    @VisibleForTesting var liveDistance: Float? = null; private set
    @VisibleForTesting var measurementAnchors: List<Anchor> = emptyList(); private set

    /**
     * Debug plane-overlay toggle (Sol decision: default OFF — planes are a
     * debug affordance only, gate 4.2). Written on the UI thread (status-line
     * long-press), read on the GL thread via the renderer's provider.
     */
    @Volatile private var planeOverlayEnabled = false

    /**
     * One-shot tap consumed by the renderer on the GL thread (normalized
     * [0,1] view coords, origin top-left, y down — no flip vs touch coords).
     */
    @Volatile private var pendingTap: FloatArray? = null

    /** Last touch position, drives the live line preview while Measuring. */
    @Volatile private var lastTouch: FloatArray? = null

    // --- Gate 4.2 blank-wall sensor fallback inputs ---

    private lateinit var sensorManager: SensorManager

    /**
     * Latest static gravity sample (device frame, points down), continuously
     * refreshed while resumed — read at tap time as "the static sample".
     * Falls back to the accelerometer (sign-flipped: a static accelerometer
     * reads +g UP) when TYPE_GRAVITY is unavailable.
     */
    @Volatile private var gravitySample: FloatArray? = null

    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            gravitySample = if (event.sensor.type == Sensor.TYPE_GRAVITY) {
                floatArrayOf(v[0], v[1], v[2])
            } else {
                // Static accel reaction points UP; gravity points DOWN.
                floatArrayOf(-v[0], -v[1], -v[2])
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Stored camera height above the surface plane, meters — settings value,
     * defaulted to [SensorEstimate.DEFAULT_USER_HEIGHT_M] on first launch and
     * persisted so later launches reuse it. (No height-entry UI yet; the
     * pref is the settings surface.)
     */
    private val userHeightM: Float by lazy {
        val stored = try {
            prefs.getFloat(KEY_USER_HEIGHT_M, Float.NaN)
        } catch (t: Throwable) {
            Log.w(TAG, "user height pref read failed", t)
            Float.NaN
        }
        if (stored.isFinite() && stored > 0f) {
            stored
        } else {
            SensorEstimate.DEFAULT_USER_HEIGHT_M.also { fallback ->
                try {
                    prefs.edit().putFloat(KEY_USER_HEIGHT_M, fallback).apply()
                } catch (t: Throwable) {
                    Log.w(TAG, "user height pref write failed", t)
                }
            }
        }
    }

    /**
     * CameraCharacteristics pinhole intrinsics scaled to the ARCore image
     * frame, or null when unavailable/inconsistent (renderer then falls back
     * to ARCore projection-matrix intrinsics). Loaded once after session
     * creation — logged either way so gate reports can state availability.
     */
    @Volatile private var cameraIntrinsics: SensorEstimate.Intrinsics? = null

    /** Tracked camera world position (GL-thread volatile write) — score_confidence input. */
    @Volatile private var cameraPos: Point3? = null

    /** Transient "no point here" note for taps where neither hit nor estimate worked. */
    @Volatile private var missHint: String? = null

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        root = FrameLayout(this)
        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(48, 48, 48, 48)
            // Semi-transparent dark scrim keeps the status readable over the
            // camera feed (A20 dark-frame fix: text sits on top of AR view).
            setBackgroundColor(0x99000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            // Debug affordance (gate 4.2): long-press the status line to flip
            // the plane overlay. Default OFF; planes are debug-only.
            setOnLongClickListener {
                planeOverlayEnabled = !planeOverlayEnabled
                if (!planeOverlayEnabled) {
                    // Counting pauses with the overlay; drop the stale value so
                    // a re-enable starts from 0 and re-arms the renderer emit.
                    planeFoundCount = 0
                }
                Log.i(TAG, "plane overlay ${if (planeOverlayEnabled) "ON" else "OFF"}")
                updateOverlay()
                true
            }
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
        registerGravitySensor()
        glSurfaceView?.onResume()
        if (ArSessionManager.ready) {
            ArSessionManager.resume()
            sessionRunning = true
        }
        lastSessionState = "resumed"
        updateOverlay()
    }

    override fun onPause() {
        // Static-pitch sampling stops with the activity; a stale sample is
        // never reused because the listener only writes while registered and
        // a non-null (possibly slightly aged) value is fine for an estimate.
        sensorManager.unregisterListener(gravityListener)
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
        // Blank-wall fallback inputs: intrinsics need the session's camera
        // config (ARCore image size) to scale CameraCharacteristics values.
        loadCameraIntrinsics()

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

    /**
     * Starts the static-pitch sample stream: TYPE_GRAVITY when the device
     * has it (software-synthesized on most devices, points down), else the
     * accelerometer sign-flipped in the listener. Registered on resume,
     * unregistered on pause.
     */
    private fun registerGravitySensor() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            Log.w(TAG, "no gravity/accelerometer sensor — blank-wall estimate disabled")
            return
        }
        sensorManager.registerListener(gravityListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    /**
     * Loads CameraCharacteristics pinhole intrinsics for the back camera
     * (gate 4.2 blank-wall fallback), scaled from the sensor pixel-array
     * frame to the active ARCore image frame. Leaves [cameraIntrinsics] null
     * — and logs why — when the calibration is absent, the ARCore image size
     * is unknown, or the sensor frame is a 90°-rotation of the ARCore frame
     * (ambiguous principal point; the renderer's projection-matrix fallback
     * covers all three cases).
     */
    private fun loadCameraIntrinsics() {
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val backId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (backId == null) {
                Log.i(TAG, "intrinsics: no back camera reported — projection fallback")
                return
            }
            val characteristics = manager.getCameraCharacteristics(backId)
            val calibration = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val arcoreImage = ArSessionManager.session?.cameraConfig?.imageSize
            if (calibration == null || calibration.size < 4) {
                Log.i(
                    TAG,
                    "intrinsics: LENS_INTRINSIC_CALIBRATION unavailable on camera $backId — projection fallback",
                )
                return
            }
            if (pixelArray == null || pixelArray.width <= 0 || arcoreImage == null) {
                Log.i(
                    TAG,
                    "intrinsics: LENS_INTRINSIC_CALIBRATION present but pixel-array/ARCore image size unknown — projection fallback",
                )
                return
            }
            val imgW = arcoreImage.width
            val imgH = arcoreImage.height
            val (fx, fy, cx, cy) = when {
                imgW == pixelArray.width && imgH == pixelArray.height ->
                    Quad(calibration[0], calibration[1], calibration[2], calibration[3])
                imgW == pixelArray.height && imgH == pixelArray.width -> {
                    // 90°-rotated stream: principal point would need an
                    // unknown rotation direction — defer to projection.
                    Log.i(
                        TAG,
                        "intrinsics: calibration present but ARCore image is rotated vs sensor frame — projection fallback",
                    )
                    return
                }
                else -> {
                    // Uniform downscale (aspect preserved): scale calibration.
                    val scale = imgW.toFloat() / pixelArray.width
                    Quad(
                        calibration[0] * scale,
                        calibration[1] * scale,
                        calibration[2] * scale,
                        calibration[3] * scale,
                    )
                }
            }
            if (!(fx > 0f) || !(fy > 0f)) {
                Log.i(TAG, "intrinsics: non-positive focal length — projection fallback")
                return
            }
            cameraIntrinsics = SensorEstimate.Intrinsics(fx, fy, cx, cy)
            Log.i(
                TAG,
                "intrinsics: LENS_INTRINSIC_CALIBRATION applied (ARCore image ${imgW}x$imgH, " +
                    "pixel array ${pixelArray.width}x${pixelArray.height})",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "camera characteristics read failed — projection fallback", t)
            cameraIntrinsics = null
        }
    }

    /** Small quad to destructure the four calibration values without a library type. */
    private data class Quad(val fx: Float, val fy: Float, val cx: Float, val cy: Float)

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
                    planeOverlayProvider = { planeOverlayEnabled },
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
                    onCameraTracking = { tracking ->
                        runOnUiThread {
                            if (cameraTracking != tracking) {
                                cameraTracking = tracking
                                Log.i(TAG, "camera tracking: $tracking")
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
                    onTapEstimate = { point ->
                        runOnUiThread { handleTapEstimate(point) }
                    },
                    onLiveDistance = { d ->
                        runOnUiThread {
                            if (liveDistance != d) {
                                liveDistance = d
                                updateOverlay()
                            }
                        }
                    },
                    gravityProvider = { gravitySample },
                    userHeightProvider = { userHeightM },
                    cameraIntrinsicsProvider = { cameraIntrinsics },
                    cameraPosSink = { p ->
                        // GL thread -> volatile, no main-thread post per frame.
                        cameraPos = p
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
        missHint = null
        updateButtons()
        updateOverlay()
    }

    /**
     * Gate 4.2 blank-wall fallback: a ray-plane estimate from the renderer
     * (no ARCore hit, no anchor). Dispatched exactly like a real hit but
     * tagged [MeasurementSource.SENSOR] so the machine records provenance.
     * Rejected taps (Idle/Complete) are silent no-ops — nothing to clean up.
     */
    private fun handleTapEstimate(point: Point3) {
        val prev = measureState
        val next = MeasureMachine.transition(
            prev,
            MeasureAction.TapHit(point, MeasurementSource.SENSOR),
            distanceFn,
        )
        if (next === prev) return
        measureState = next
        missHint = null
        Log.i(TAG, "sensor estimate accepted into machine: $prev -> $next")
        updateButtons()
        updateOverlay()
    }

    private fun handleTapMiss() {
        // TapMiss is a machine no-op everywhere; the renderer only gets here
        // when NEITHER a feature point NOR a sensor estimate was possible.
        // Surface explicit "no point here" feedback (plan 15.7.1) instead of
        // a silent no-op.
        missHint = "No point here — hold still, aim lower, or find a textured spot"
        updateOverlay()
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
        // The "no point here" note belongs to the tap that produced it; any
        // explicit user action (Measure/Cancel/Reset) moves past it.
        missHint = null
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
            is MeasureState.Measuring ->
                "State: Measuring | ${formatDistance(liveDistance)}${estimateTag(s.startSource)}"
            is MeasureState.Complete ->
                "State: Complete | ${formatDistance(s.distance)}" +
                    completeEstimateTag(s.source, s.end, s.distance)
        }
        // Plane count is only claimed while the debug overlay is enabled
        // (it is only counted then — gate 4.2). Otherwise show provider and
        // camera-tracking state instead, since planes are debug-only.
        val debugLine = if (planeOverlayEnabled) {
            "Planes: $planeFoundCount"
        } else {
            "Provider: ARCore | Camera: ${if (cameraTracking) "tracking" else "not tracking"}"
        }
        val hintLine = missHint?.let { "\n$it" } ?: ""
        overlayText.text = "Session: $lastSessionState | $debugLine\n$stateLine$hintLine"
    }

    /** UI tag for an estimated endpoint while a measurement is in progress. */
    private fun estimateTag(source: MeasurementSource): String =
        if (source == MeasurementSource.SENSOR) " ~estimated" else ""

    /**
     * UI tag for a completed measurement with at least one estimated endpoint:
     * "~estimated (Medium|Low)" — the confidence comes from
     * [estimateTagConfidence]: the existing `score_confidence` table (Sensor
     * row mirrors Plane: Medium in-bracket with tracking, Low otherwise) with
     * the clamp-honesty override (a stand-off that hit [SensorEstimate.MAX_STANDOFF_M]
     * is always Low — see the helper's docs). Camera-to-end distance is used as
     * the bracket input; when the camera position is unknown (never tracked yet)
     * the measured distance stands in.
     */
    private fun completeEstimateTag(
        source: MeasurementSource,
        end: Point3,
        distance: Float,
    ): String {
        if (source != MeasurementSource.SENSOR) return ""
        val cameraDistance = cameraPos
            ?.let { distanceFn(it, end) }
            ?.takeIf { it.isFinite() && it > 0f }
            ?: distance
        val confidence = estimateTagConfidence(source, cameraDistance, cameraTracking)
        val label = when (confidence) {
            Confidence.MEDIUM -> "Medium"
            Confidence.LOW -> "Low"
            Confidence.HIGH -> "High" // unreachable for Sensor (capped at Medium)
        }
        return " ~estimated ($label)"
    }

    private fun formatDistance(d: Float?): String =
        if (d == null || d.isNaN()) "--" else String.format(java.util.Locale.US, "%.2f m", d)

    companion object {
        private const val TAG = "ArMeasure"
        private const val CAMERA_PERMISSION_REQUEST = 1001
        private const val PREFS_NAME = "metiri_settings"
        private const val KEY_USER_HEIGHT_M = "user_height_m"
    }
}

/**
 * Gate 2.4 follow-up B: pure retry-decision helper for the async
 * ARCore availability check loop (attempts are 0-based). Extracted so the
 * timeout branch is JVM-unit-testable without the async path.
 */
internal fun shouldRetryAvailabilityCheck(attempt: Int, maxAttempts: Int = 10): Boolean =
    attempt < maxAttempts

/**
 * Confidence behind the "~estimated (...)" tag of a COMPLETED measurement
 * (gate 4.2 review, B4 "clamped confidence honesty").
 *
 * The clamp-honesty rule: a [MeasurementSource.SENSOR] endpoint whose
 * horizontal stand-off hit [SensorEstimate.MAX_STANDOFF_M] did not measure its
 * depth — the span was ASSUMED (flat/upward aim gives no depth cue) — so the
 * tag must never report Medium off that clamp. The condition is a plain
 * `cameraDistance >= MAX_STANDOFF_M - epsilon` because the 3D camera-to-end
 * distance is `span / |ray.xz| >= span`; a span clamped to MAX_STANDOFF_M
 * therefore always lands at (or above) the threshold. The epsilon only absorbs
 * float noise around the 2 m magnitude.
 *
 * Every other case goes through the UNCHANGED Rust `score_confidence` table —
 * this is a UI-honesty gate in front of the table, not a table edit (plan 1.2 /
 * AGENTS.md confidence-table decision stay authoritative).
 *
 * [scoreFn] is injectable so JVM unit tests never touch FFI (same convention
 * as `MeasureMachine.transition`'s `distanceFn`); production callers use the
 * default, which is the real FFI binding.
 *
 * @param cameraDistance camera-to-end distance used as the bracket input
 *   (falling back to the measured distance when the camera pose is unknown)
 * @param trackingOk current camera tracking state, second `score_confidence` input
 */
internal fun estimateTagConfidence(
    source: MeasurementSource,
    cameraDistance: Float,
    trackingOk: Boolean,
    scoreFn: (MeasurementSource, Float, Boolean) -> Confidence = ::scoreConfidence,
): Confidence =
    if (source == MeasurementSource.SENSOR &&
        cameraDistance >= SensorEstimate.MAX_STANDOFF_M - CLAMP_EPSILON_M
    ) {
        Confidence.LOW
    } else {
        scoreFn(source, cameraDistance, trackingOk)
    }

/** Clamp-honesty tolerance in metres (1 mm — float noise at 2 m is ~1e-6). */
internal const val CLAMP_EPSILON_M = 1e-3f