package com.metiri.armeasure

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ar.core.Pose
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.core.Point3
import uniffi.core.distance

/**
 * Gate 3.3: measurement distance computed by the Rust core across the FFI must
 * match an independently-computed reference. Expected distances are derived in
 * this test from known ARCore [Pose] data (Kotlin math, f32), converted to
 * [Point3], then compared within float epsilon.
 */
@RunWith(AndroidJUnit4::class)
class CrossFfiDistanceTest {

    private fun pointAt(x: Float, y: Float, z: Float): Point3 {
        val pose = Pose.makeTranslation(x, y, z)
        return Point3(pose.tx(), pose.ty(), pose.tz())
    }

    @Test
    fun threeFourFiveTriangle_matchesReference() {
        val a = pointAt(0f, 0f, 0f)
        val b = pointAt(3f, 4f, 0f)
        val expected = kotlin.math.sqrt(3f * 3f + 4f * 4f) // 5.0
        assertEquals(expected, distance(a, b), 1e-3f)
    }

    @Test
    fun zeroDistance_samePoint() {
        val a = pointAt(1f, 2f, 3f)
        assertEquals(0f, distance(a, a), 1e-3f)
    }

    @Test
    fun negativeCoordinates_matchesReference() {
        // (-3, 4, -5) from origin: sqrt(9 + 16 + 25) = sqrt(50)
        val a = pointAt(0f, 0f, 0f)
        val b = pointAt(-3f, 4f, -5f)
        val expected = kotlin.math.sqrt(9f + 16f + 25f)
        assertEquals(expected, distance(a, b), 1e-3f)
    }

    @Test
    fun nonAxisAlignedSegment_matchesReference() {
        val a = pointAt(1f, 1f, 1f)
        val b = pointAt(4f, 5f, 6f)
        val dx = 4f - 1f
        val dy = 5f - 1f
        val dz = 6f - 1f
        val expected = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        assertEquals(expected, distance(a, b), 1e-3f)
    }
}