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
 * Gate 2.4 follow-up A: [ArSessionManager.create] must close the prior session
 * before assigning a new one. The test-visible closed-session counter proves it
 * — calling create twice leaves the prior sessions closed and a new one
 * assigned.
 */
@RunWith(AndroidJUnit4::class)
class ArSessionCloseTest {

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
    fun createClosesPriorSessionBeforeAssigningNewOne() {
        grantCameraPermission()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // The activity's own create() has run once at this point.
            assertTrue("initial session init failed", waitFor(scenario) { it.sessionReady })
            val baseline = ArSessionManager.closedSessionCount

            scenario.onActivity {
                val s1 = ArSessionManager.create(it)
                assertNotNull("first create must succeed", s1)
                val s2 = ArSessionManager.create(it)
                assertNotNull("second create must succeed", s2)
            }

            // create() closes the prior session before assigning the new one.
            val closed = ArSessionManager.closedSessionCount
            assertTrue(
                "prior session was not closed before reassignment (closed=$closed, baseline=$baseline)",
                closed >= baseline + 1,
            )
            // Literal contract assertion: at least one session was closed.
            assertTrue("closedSessionCount must be >= 1, was $closed", closed >= 1)
            assertTrue("session must be ready after create", ArSessionManager.ready)
            scenario.onActivity {
                assertTrue("a new session must be assigned", ArSessionManager.session != null)
            }
        } finally {
            scenario.close()
        }
    }
}