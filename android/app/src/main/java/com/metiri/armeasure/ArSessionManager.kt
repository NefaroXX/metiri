package com.metiri.armeasure

import android.content.Context
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session

/**
 * Phase 2 ARCore session wrapper: create/configure, pause/resume/close lifecycle
 * tied to the Activity, and capability queries.
 *
 * Plane finding is enabled for both horizontal and vertical planes. Depth mode
 * is AUTOMATIC only when the device supports it, else DISABLED — requesting
 * AUTOMATIC unconditionally makes [Session.configure] throw
 * UnsupportedConfigurationException on devices without Depth support
 * (observed on Galaxy A20 / SM-A205F). Capability surfaced via
 * [hasDepthSupport] for the capability screen.
 */
object ArSessionManager {
    private const val TAG = "ArMeasure.ArSession"

    var session: Session? = null
        private set
    var ready: Boolean = false
        private set

    fun create(context: Context): Session? {
        close()
        return try {
            val s = Session(context)
            val depth = if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                Config.DepthMode.AUTOMATIC
            } else {
                Config.DepthMode.DISABLED
            }
            s.configure(
                Config(s).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    depthMode = depth
                }
            )
            session = s
            ready = true
            Log.i(TAG, "session created + configured (h+v planes, depth=$depth)")
            s
        } catch (t: Throwable) {
            session = null
            ready = false
            Log.e(TAG, "session create/configure failed", t)
            null
        }
    }

    fun resume() {
        try {
            session?.resume()
            Log.i(TAG, "session resumed")
        } catch (t: Throwable) {
            Log.e(TAG, "session resume failed", t)
        }
    }

    fun pause() {
        try {
            session?.pause()
            Log.i(TAG, "session paused")
        } catch (t: Throwable) {
            Log.e(TAG, "session pause failed", t)
        }
    }

    fun close() {
        try {
            session?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "session close failed", t)
        }
        session = null
        ready = false
    }

    fun hasDepthSupport(s: Session): Boolean =
        try {
            s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        } catch (t: Throwable) {
            Log.e(TAG, "depth support query failed", t)
            false
        }
}