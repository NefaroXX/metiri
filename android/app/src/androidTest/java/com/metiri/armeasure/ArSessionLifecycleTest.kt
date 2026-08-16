package com.metiri.armeasure

import android.Manifest
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 gates on the device/emulator:
 *  - Gate 2.2: app launches, camera permission granted, AR session initializes without crash.
 *  - Gate 2.4: session pauses/resumes across the Activity lifecycle (recreate +
 *    background/foreground) without leaking the native session or crashing.
 */
@RunWith(AndroidJUnit4::class)
class ArSessionLifecycleTest {

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

    @Test
    fun appLaunchesAndSessionInitializes() {
        grantCameraPermission()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue(
                "gate 2.2: AR session did not initialize on launch",
                waitFor(scenario) { it.sessionReady && it.sessionRunning }
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun sessionSurvivesRecreateAndBackgrounding() {
        grantCameraPermission()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue("initial session init failed", waitFor(scenario) { it.sessionReady && it.sessionRunning })

            // Rotation-style lifecycle: destroy + recreate the Activity.
            scenario.recreate()
            assertTrue("session did not re-initialize after recreate", waitFor(scenario) { it.sessionReady && it.sessionRunning })

            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(500)
            var running = true
            scenario.onActivity { running = it.sessionRunning }
            assertTrue("gate 2.4: session still running while backgrounded", !running)
            assertTrue("activity destroyed on background", scenario.state == Lifecycle.State.CREATED)

            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue("gate 2.4: session did not resume after foreground", waitFor(scenario) { it.sessionRunning })
        } finally {
            scenario.close()
        }
    }
}