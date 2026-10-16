package com.metiri.armeasure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.core.Confidence
import uniffi.core.MeasurementSource
import uniffi.core.Point3
import kotlin.math.sqrt

/**
 * Gate 4.2 review (B4): the clamp-honesty rule of [estimateTagConfidence] —
 * a SENSOR stand-off that hit [SensorEstimate.MAX_STANDOFF_M] must be tagged
 * Low, never Medium, because that span was ASSUMED rather than measured.
 *
 * The Rust `score_confidence` arm itself is unchanged (plan 1.2 / AGENTS.md);
 * the table is exercised on-device by `MeasurementRoundTripTest` and in Rust by
 * the `score_confidence` table test. Here the table is injected as a fake so
 * the JVM stays FFI-free (same convention as `MeasureMachine`'s `distanceFn`).
 */
class EstimateTagConfidenceTest {

    /** Table stand-in: Sensor+tracking is Medium in-bracket (the pre-B4 bug). */
    private val mediumTable: (MeasurementSource, Float, Boolean) -> Confidence =
        { _, _, _ -> Confidence.MEDIUM }

    // --- Clamp honesty: never Medium off the 2 m assumed stand-off ---

    @Test
    fun clampedSensorStandoffIsAlwaysLow() {
        for (distance in listOf(
            SensorEstimate.MAX_STANDOFF_M, // exactly the clamp
            SensorEstimate.MAX_STANDOFF_M - CLAMP_EPSILON_M, // threshold, inclusive
            SensorEstimate.MAX_STANDOFF_M + 0.5f, // past the clamp
        )) {
            assertEquals(
                "cameraDistance=$distance must not inherit the table's Medium",
                Confidence.LOW,
                estimateTagConfidence(MeasurementSource.SENSOR, distance, true, mediumTable),
            )
        }
    }

    @Test
    fun clampThresholdIsInclusiveAndSensorSpecific() {
        // At/above the threshold: Low (clamp rule wins over the table).
        assertEquals(
            Confidence.LOW,
            estimateTagConfidence(
                MeasurementSource.SENSOR,
                SensorEstimate.MAX_STANDOFF_M - CLAMP_EPSILON_M,
                true,
                mediumTable,
            ),
        )
        // Just below it: the table decides again (Medium survives for a
        // genuinely in-bracket, tracking sensor reading — pre-B4 behaviour).
        val below = SensorEstimate.MAX_STANDOFF_M - CLAMP_EPSILON_M - 0.01f
        assertEquals(
            Confidence.MEDIUM,
            estimateTagConfidence(MeasurementSource.SENSOR, below, true, mediumTable),
        )
        // The rule is SENSOR-only: a real hit at the very same range still
        // gets its table tier (no clamp exists for a measured endpoint).
        assertEquals(
            Confidence.MEDIUM,
            estimateTagConfidence(
                MeasurementSource.FEATURE_POINT,
                SensorEstimate.MAX_STANDOFF_M,
                true,
                mediumTable,
            ),
        )
    }

    @Test
    fun tableInputsArePassedThroughUnchanged() {
        var seen: Triple<MeasurementSource, Float, Boolean>? = null
        val recording: (MeasurementSource, Float, Boolean) -> Confidence = { src, dist, tracking ->
            seen = Triple(src, dist, tracking)
            Confidence.LOW
        }
        assertEquals(
            Confidence.LOW,
            estimateTagConfidence(MeasurementSource.SENSOR, 1.0f, false, recording),
        )
        assertEquals(
            Triple(MeasurementSource.SENSOR, 1.0f, false),
            seen,
        )
    }

    // --- Geometry-backed: a genuinely clamped endpoint scores Low ---

    @Test
    fun flatAimClampedEndpointScoresLow() {
        // Flat aim ⇒ SensorEstimate clamps the span to MAX_STANDOFF_M; the
        // resulting endpoint's camera distance can only be >= that clamp
        // (3D distance = span / |ray.xz| >= span), so it must tag Low even
        // though the unmodified table would say Medium for it.
        val clamped = SensorEstimate.estimatePoint(
            tapX = 500f,
            tapY = 500f,
            intrinsics = SensorEstimate.Intrinsics(fx = 1000f, fy = 1000f, cx = 500f, cy = 500f),
            gravity = floatArrayOf(0f, -9.81f, 0f), // no depression ⇒ flat aim
            poseQ = floatArrayOf(0f, 0f, 0f, 1f), // identity: looks down -z
            cameraPos = Point3(0f, 1.5f, 0f),
            userHeightM = 1.2f,
        )!!
        val camera = Point3(0f, 1.5f, 0f)
        val dx = clamped.x - camera.x
        val dy = clamped.y - camera.y
        val dz = clamped.z - camera.z
        val cameraDistance = sqrt(dx * dx + dy * dy + dz * dz)

        assertTrue(
            "flat-aim endpoint must be at/behind the MAX_STANDOFF_M clamp, was $cameraDistance",
            cameraDistance >= SensorEstimate.MAX_STANDOFF_M - CLAMP_EPSILON_M,
        )
        assertEquals(
            Confidence.LOW,
            estimateTagConfidence(MeasurementSource.SENSOR, cameraDistance, true, mediumTable),
        )
    }
}
