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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.Availability

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var overlayText: TextView
    private var glSurfaceView: GLSurfaceView? = null
    private var isResumed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    @VisibleForTesting var sessionReady = false; private set
    @VisibleForTesting var sessionRunning = false; private set
    @VisibleForTesting var lastSessionState = "created"; private set
    @VisibleForTesting var planeFoundCount = 0; private set
    @VisibleForTesting var capabilityReport: CapabilityReport? = null; private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        statusText = TextView(this).apply { textSize = 16f; setPadding(48, 48, 48, 48) }
        overlayText = TextView(this).apply { textSize = 14f; setPadding(48, 24, 48, 24) }
        root.addView(
            statusText,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            overlayText,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.BOTTOM }
        )
        setContentView(root)
        lastSessionState = "created"
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
                        if (attempt < 10) {
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
                ArRenderer(this@MainActivity, { ArSessionManager.session }) { count ->
                    runOnUiThread {
                        if (planeFoundCount != count) {
                            planeFoundCount = count
                            Log.i(TAG, "planes tracked: $count")
                            updateOverlay()
                        }
                    }
                }
            )
            setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY)
        }
        glSurfaceView = surface
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        root.addView(surface, 0, lp)
        if (isResumed) surface.onResume()
    }

    private fun updateOverlay() {
        overlayText.text = "Session: $lastSessionState | Planes: $planeFoundCount"
    }

    companion object {
        private const val TAG = "ArMeasure"
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }
}