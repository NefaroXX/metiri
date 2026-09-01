package com.metiri.armeasure

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
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
 *
 * Thread safety: every session lifecycle op ([create]/[resume]/[pause]/
 * [close]) and all session reads run under [sessionLock]. The GL renderer may
 * only touch the session inside [withSession], so a UI-thread close/reassign
 * can never run while the GL thread is mid-[Session.update] on the same
 * object — previously the renderer held a raw [Session] reference across the
 * close, which produced a use-after-close SIGSEGV (libarcore_c.so) in
 * `ArSessionCloseTest`. The UI thread briefly blocks for the in-flight GL
 * frame during create/close; that is rare and acceptable.
 */
object ArSessionManager {
    private const val TAG = "ArMeasure.ArSession"

    /**
     * Serializes session lifecycle ops against GL-frame session use. Monitor
     * locks are reentrant, so [create] may call [close] while holding it.
     */
    private val sessionLock = Any()

    var session: Session? = null
        private set
    var ready: Boolean = false
        private set

    /**
     * Number of live sessions this process has closed via [close] while a
     * session was actually assigned (gate 2.4 follow-up A). Lets instrumented
     * tests prove [create] closes the prior session before assigning a new one.
     */
    @VisibleForTesting var closedSessionCount: Int = 0
        private set

    fun create(context: Context): Session? = synchronized(sessionLock) {
        // Reentrant: close() takes the same lock again and is a no-op ordering
        // guarantee that the prior session (if any) is closed before the new
        // one is assigned.
        close()
        try {
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
        synchronized(sessionLock) {
            try {
                session?.resume()
                Log.i(TAG, "session resumed")
            } catch (t: Throwable) {
                Log.e(TAG, "session resume failed", t)
            }
        }
    }

    fun pause() {
        synchronized(sessionLock) {
            try {
                session?.pause()
                Log.i(TAG, "session paused")
            } catch (t: Throwable) {
                Log.e(TAG, "session pause failed", t)
            }
        }
    }

    fun close() {
        synchronized(sessionLock) {
            if (session != null) {
                closedSessionCount++
                Log.i(TAG, "session closed")
                try {
                    session?.close()
                } catch (t: Throwable) {
                    Log.e(TAG, "session close failed", t)
                }
            }
            session = null
            ready = false
        }
    }

    /**
     * Runs [block] with the current session while holding [sessionLock] — the
     * only sanctioned way the GL renderer touches the session. The block is
     * atomic with respect to [create]/[resume]/[pause]/[close], so the session
     * it observes cannot be closed or reassigned mid-block. The block must not
     * perform long/blocking work; callbacks that merely post work to another
     * thread (e.g. `runOnUiThread`) are fine because the post returns
     * immediately and runs after the lock is released.
     */
    fun <T> withSession(block: (Session?) -> T): T = synchronized(sessionLock) { block(session) }

    fun hasDepthSupport(s: Session): Boolean =
        try {
            s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        } catch (t: Throwable) {
            Log.e(TAG, "depth support query failed", t)
            false
        }
}