package com.metiri.armeasure

/**
 * Phase 2 capability-decision logic (gate 2.1).
 *
 * Deliberately free of Android/ARCore types so the (ARCore supported x Depth
 * supported) combinations and sensor-failure cases are unit-testable on the
 * JVM with plain JUnit — "Robolectric or similar" per the plan. The Activity
 * derives the three booleans from ArCoreApk/Session/SensorManager and funnels
 * them through [evaluate].
 */
data class CapabilityReport(
    val arcoreSupported: Boolean,
    val depthSupported: Boolean,
    val sensorsOk: Boolean,
) {
    /** Measure mode requires ARCore tracking + motion sensors; Depth is optional (Phase 5). */
    val canMeasure: Boolean get() = arcoreSupported && sensorsOk
}

object CapabilityEvaluator {
    fun evaluate(arcoreSupported: Boolean, depthSupported: Boolean, sensorsOk: Boolean): CapabilityReport =
        CapabilityReport(arcoreSupported, depthSupported, sensorsOk)
}