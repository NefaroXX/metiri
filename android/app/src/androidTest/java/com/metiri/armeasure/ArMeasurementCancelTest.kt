package com.metiri.armeasure

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate 3.5: cancel/reset returns the machine cleanly to [MeasureState.Idle]
 * with no dangling AR anchors (session anchor count back to baseline).
 *
 * Two paths are covered:
 *  - mid-measurement [MeasureAction.Cancel] (the plan's "mid-measurement cancel");
 *  - tap -> tap -> Complete leaves anchors behind, then [MeasureAction.Reset]
 *    (Cancel from Complete is a no-op per the locked transition table).
 */
@RunWith(AndroidJUnit4::class)
class ArMeasurementCancelTest {

    private fun grantCameraPermission() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
            Manifest.permission.CAMERA
        )
    }

    private fun waitFor(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = 60_000,
        probe: (MainActivity) -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ref = AtomicReference(false)
            scenario.onActivity { ref.set(probe(it)) }
            if (ref.get()) return true
            SystemClock.sleep(250)
        }
        return false
    }

    private fun sessionAnchorCount(scenario: ActivityScenario<MainActivity>): Int {
        val ref = AtomicReference(0)
        scenario.onActivity { ref.set(ArSessionManager.session?.getAllAnchors()?.size ?: 0) }
        return ref.get()
    }

    private fun waitForAnchorCount(
        scenario: ActivityScenario<MainActivity>,
        expected: Int,
        timeoutMs: Long = 15_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sessionAnchorCount(scenario) == expected) return true
            SystemClock.sleep(250)
        }
        return false
    }

    private fun tapUntil(
        scenario: ActivityScenario<MainActivity>,
        positions: List<Pair<Float, Float>>,
        timeoutMs: Long,
        probe: (MainActivity) -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var i = 0
        while (System.currentTimeMillis() < deadline) {
            val (x, y) = positions[i % positions.size]
            scenario.onActivity { it.handleTapAt(x, y) }
            if (waitFor(scenario, 3000, probe)) return true
            i++
            SystemClock.sleep(300)
        }
        return false
    }

    @Test
    fun midMeasurementCancelReturnsToIdleWithoutLeakingAnchors() {
        grantCameraPermission()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue("AR session did not initialize", waitFor(scenario) { it.sessionReady && it.sessionRunning })
            assertTrue("no tracking plane detected", waitFor(scenario, 240_000) { it.planeFoundCount > 0 })

            val baseline = sessionAnchorCount(scenario)

            // Tap once -> Measuring (one anchor tracked).
            assertTrue(
                "first tap never armed Measuring",
                tapUntil(
                    scenario,
                    listOf(0.5f to 0.5f, 0.45f to 0.5f, 0.5f to 0.45f, 0.55f to 0.5f, 0.5f to 0.55f),
                    60_000,
                ) { it.measureState is MeasureState.Measuring }
            )
            var trackedAfterTap = -1
            scenario.onActivity { trackedAfterTap = it.measurementAnchors.size }
            assertEquals("start-point anchor should be tracked", 1, trackedAfterTap)

            // Cancel mid-measurement -> Idle + detach.
            scenario.onActivity { it.dispatch(MeasureAction.Cancel) }
            assertTrue("cancel must return to Idle", waitFor(scenario) { it.measureState == MeasureState.Idle })
            var trackedAfterCancel = -1
            scenario.onActivity { trackedAfterCancel = it.measurementAnchors.size }
            assertEquals("tracked anchors must be cleared", 0, trackedAfterCancel)
            assertTrue(
                "dangling anchors after cancel: ${sessionAnchorCount(scenario)} != baseline $baseline",
                waitForAnchorCount(scenario, baseline),
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun completeThenResetReturnsToIdleWithoutLeakingAnchors() {
        grantCameraPermission()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue("AR session did not initialize", waitFor(scenario) { it.sessionReady && it.sessionRunning })
            assertTrue("no tracking plane detected", waitFor(scenario, 240_000) { it.planeFoundCount > 0 })

            val baseline = sessionAnchorCount(scenario)

            assertTrue(
                "first tap never armed Measuring",
                tapUntil(
                    scenario,
                    listOf(0.5f to 0.5f, 0.45f to 0.5f, 0.5f to 0.45f, 0.55f to 0.5f, 0.5f to 0.55f),
                    60_000,
                ) { it.measureState is MeasureState.Measuring }
            )
            assertTrue(
                "second tap never produced Complete",
                tapUntil(
                    scenario,
                    listOf(0.5f to 0.35f, 0.35f to 0.5f, 0.65f to 0.5f, 0.5f to 0.65f),
                    60_000,
                ) { it.measureState is MeasureState.Complete }
            )

            // Stronger check: the tap-tap path must have left anchors behind.
            var tracked = -1
            scenario.onActivity { tracked = it.measurementAnchors.size }
            assertEquals("two accepted taps should have tracked 2 anchors", 2, tracked)
            assertTrue(
                "session should hold anchors before reset (${sessionAnchorCount(scenario)} vs baseline $baseline)",
                sessionAnchorCount(scenario) > baseline,
            )

            // Cancel from Complete is a no-op per the locked transition table.
            scenario.onActivity { it.dispatch(MeasureAction.Cancel) }
            var stateAfterCancel: MeasureState? = null
            scenario.onActivity { stateAfterCancel = it.measureState }
            assertTrue(
                "Cancel from Complete must be a no-op (stays Complete)",
                stateAfterCancel is MeasureState.Complete,
            )

            // Reset clears a completed measurement -> Idle + detach.
            scenario.onActivity { it.dispatch(MeasureAction.Reset) }
            assertTrue("Reset must return to Idle", waitFor(scenario) { it.measureState == MeasureState.Idle })
            var trackedAfterReset = -1
            scenario.onActivity { trackedAfterReset = it.measurementAnchors.size }
            assertEquals("tracked anchors must be cleared", 0, trackedAfterReset)
            assertTrue(
                "dangling anchors after reset: ${sessionAnchorCount(scenario)} != baseline $baseline",
                waitForAnchorCount(scenario, baseline),
            )
        } finally {
            scenario.close()
        }
    }
}