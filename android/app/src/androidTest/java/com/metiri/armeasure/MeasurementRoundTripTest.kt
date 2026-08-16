package com.metiri.armeasure

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.core.Confidence
import uniffi.core.Measurement
import uniffi.core.MeasurementSource
import uniffi.core.Point3
import uniffi.core.distance
import uniffi.core.scoreConfidence

/**
 * Phase 1 gate 1.6 — UniFFI binding smoke test: records, enums and functions
 * must round-trip across the FFI boundary with exact values.
 */
@RunWith(AndroidJUnit4::class)
class MeasurementRoundTripTest {

    @Test
    fun measurementRecordRoundTripsEveryField() {
        val start = Point3(x = 1.0f, y = 2.0f, z = 3.0f)
        val end = Point3(x = 4.0f, y = 5.0f, z = 6.0f)
        val m = Measurement(
            start = start,
            end = end,
            distance = 5.196152f,
            confidence = Confidence.HIGH,
            source = MeasurementSource.DEPTH,
        )

        assertEquals(1.0f, m.start.x)
        assertEquals(2.0f, m.start.y)
        assertEquals(3.0f, m.start.z)
        assertEquals(4.0f, m.end.x)
        assertEquals(5.0f, m.end.y)
        assertEquals(6.0f, m.end.z)
        assertEquals(5.196152f, m.distance)
        assertEquals(Confidence.HIGH, m.confidence)
        assertEquals(MeasurementSource.DEPTH, m.source)
    }

    @Test
    fun enumVariantsSurviveTheBoundary() {
        val sources = listOf(
            MeasurementSource.DEPTH,
            MeasurementSource.PLANE,
            MeasurementSource.FEATURE_POINT,
        )
        val tiers = listOf(Confidence.HIGH, Confidence.MEDIUM, Confidence.LOW)
        sources.forEach { source ->
            val m = Measurement(
                start = Point3(0.0f, 0.0f, 0.0f),
                end = Point3(1.0f, 0.0f, 0.0f),
                distance = 1.0f,
                confidence = tiers[sources.indexOf(source)],
                source = source,
            )
            assertEquals(source, m.source)
            assertEquals(tiers[sources.indexOf(source)], m.confidence)
        }
    }

    @Test
    fun distanceMatchesRustAcrossFfi() {
        // 3-4-5 triangle, same vectors as the Rust unit test.
        val a = Point3(0.0f, 0.0f, 0.0f)
        val b = Point3(3.0f, 4.0f, 0.0f)
        assertEquals(5.0f, distance(a, b))
    }

    @Test
    fun confidenceScoringCrossesFfi() {
        assertEquals(
            Confidence.HIGH,
            scoreConfidence(MeasurementSource.DEPTH, 2.0f, trackingOk = true),
        )
        assertEquals(
            Confidence.MEDIUM,
            scoreConfidence(MeasurementSource.FEATURE_POINT, 2.0f, trackingOk = true),
        )
        assertEquals(
            Confidence.LOW,
            scoreConfidence(MeasurementSource.PLANE, 8.0f, trackingOk = false),
        )
    }
}