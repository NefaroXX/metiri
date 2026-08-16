package com.metiri.armeasure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate 2.1: capability-check logic returns correct booleans for all four
 * (ARCore supported x Depth supported) combinations, plus the sensor-failure
 * case. Pure JVM test — the evaluator holds no Android types.
 */
class CapabilityEvaluatorTest {

    @Test
    fun arcoreSupportedAndDepthSupported_canMeasure() {
        val r = CapabilityEvaluator.evaluate(arcoreSupported = true, depthSupported = true, sensorsOk = true)
        assertTrue(r.arcoreSupported)
        assertTrue(r.depthSupported)
        assertTrue(r.sensorsOk)
        assertTrue(r.canMeasure)
    }

    @Test
    fun arcoreSupportedNoDepth_canMeasureWithoutDepth() {
        val r = CapabilityEvaluator.evaluate(arcoreSupported = true, depthSupported = false, sensorsOk = true)
        assertTrue(r.arcoreSupported)
        assertFalse(r.depthSupported)
        assertTrue(r.canMeasure)
    }

    @Test
    fun noArcoreDepthSupported_cannotMeasure() {
        val r = CapabilityEvaluator.evaluate(arcoreSupported = false, depthSupported = true, sensorsOk = true)
        assertFalse(r.arcoreSupported)
        assertTrue(r.depthSupported)
        assertFalse(r.canMeasure)
    }

    @Test
    fun neitherSupported_cannotMeasure() {
        val r = CapabilityEvaluator.evaluate(arcoreSupported = false, depthSupported = false, sensorsOk = true)
        assertFalse(r.arcoreSupported)
        assertFalse(r.depthSupported)
        assertFalse(r.canMeasure)
    }

    @Test
    fun sensorsMissing_cannotMeasureEvenWithArcoreAndDepth() {
        val r = CapabilityEvaluator.evaluate(arcoreSupported = true, depthSupported = true, sensorsOk = false)
        assertTrue(r.arcoreSupported)
        assertTrue(r.depthSupported)
        assertFalse(r.sensorsOk)
        assertFalse(r.canMeasure)
    }
}