package com.metiri.armeasure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate 2.4 follow-up B: pure retry-decision logic for the async ARCore
 * availability check. The full async path stays deferred (per the audit); this
 * exercises the extracted helper in isolation on the JVM.
 */
class AvailabilityRetryTest {

    @Test
    fun attemptBelowMax_retries() {
        assertTrue("attempt 0 (default max 10) should retry", shouldRetryAvailabilityCheck(0))
        assertTrue("attempt 9 (default max 10) should retry", shouldRetryAvailabilityCheck(9))
    }

    @Test
    fun attemptEqualToMax_stops() {
        assertFalse("attempt 10 (default max 10) must stop", shouldRetryAvailabilityCheck(10))
    }

    @Test
    fun attemptAboveMax_stops() {
        assertFalse("attempt 11 (default max 10) must stop", shouldRetryAvailabilityCheck(11))
        assertFalse("attempt 100 must stop", shouldRetryAvailabilityCheck(100))
    }

    @Test
    fun customMaxAttemptsEdgeCases() {
        assertTrue("maxAttempts=1, attempt 0 should retry", shouldRetryAvailabilityCheck(0, maxAttempts = 1))
        assertFalse("maxAttempts=1, attempt 1 must stop", shouldRetryAvailabilityCheck(1, maxAttempts = 1))
        assertFalse("maxAttempts=0, attempt 0 must stop", shouldRetryAvailabilityCheck(0, maxAttempts = 0))
        assertFalse("maxAttempts=0, attempt 1 must stop", shouldRetryAvailabilityCheck(1, maxAttempts = 0))
    }

    @Test
    fun negativeAttempt_retries() {
        // Defensive: a negative (invalid) attempt index behaves like "below max".
        assertTrue(shouldRetryAvailabilityCheck(-1))
    }
}