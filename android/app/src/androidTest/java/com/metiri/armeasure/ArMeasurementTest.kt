package com.metiri.armeasure

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate 3.2: simulated taps on a plane-detected scene drive the machine through
 * the full path (touch -> hitTest -> anchor -> reducer) to a [MeasureState.Complete]
 * with a non-null, positive-distance measurement.
 *
 * Taps are driven through MainActivity's touch-listener hook (same pendingTap
 * -> renderer -> reducer path the real OnTouchListener uses); the renderer is
 * running continuously so each tap is consumed on the next GL frame.
 */
@RunWith(AndroidJUnit4::class)
class ArMeasurementTest {

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

    /**
     * Taps (normalized view coords) cycling through [positions] until [probe]
     * passes. Retrying guards against a tap landing where no plane is currently
     * hit (tracking wobble on the A20) without changing the code path.
     */
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
    fun twoTapsOnPlaneProduceCompleteMeasurement() {
        grantCameraPermission()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue(
                "AR session did not initialize",
                waitFor(scenario) { it.sessionReady && it.sessionRunning }
            )
            // A20 tracking can take minutes to leave initialization; planes may
            // be slow to appear. (SM-A205F, see AGENTS.md)
            assertTrue(
                "no tracking plane detected within timeout",
                waitFor(scenario, 240_000) { it.planeFoundCount > 0 }
            )

            // First tap: place the start point (auto-armed to SelectingFirstPoint
            // on AR-view entry, so one tap -> Measuring).
            assertTrue(
                "first tap never armed Measuring",
                tapUntil(
                    scenario,
                    listOf(0.5f to 0.5f, 0.45f to 0.5f, 0.5f to 0.45f, 0.55f to 0.5f, 0.5f to 0.55f),
                    60_000,
                ) { it.measureState is MeasureState.Measuring }
            )

            // Second tap away from the start point: -> Complete with distance > 0.
            assertTrue(
                "second tap never produced Complete",
                tapUntil(
                    scenario,
                    listOf(0.5f to 0.35f, 0.35f to 0.5f, 0.65f to 0.5f, 0.5f to 0.65f),
                    60_000,
                ) { it.measureState is MeasureState.Complete }
            )

            scenario.onActivity {
                val s = it.measureState as MeasureState.Complete
                assertNotNull("start point must be present", s.start)
                assertNotNull("end point must be present", s.end)
                assertTrue("measured distance must be > 0, was ${s.distance}", s.distance > 0f)
            }
        } finally {
            scenario.close()
        }
    }
}