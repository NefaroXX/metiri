package com.metiri.armeasure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.core.Point3
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import java.lang.Math

/**
 * Gate 4.2 (blank-wall fallback): JVM tests for the pure sensor-trig helper
 * [SensorEstimate.estimatePoint]. No device, no ARCore, no FFI — every input
 * is constructed by hand and the closed-form results are asserted against
 * hand-computed values (camera at (0, 1.5, 0), looking along -z).
 *
 * Ground truth for the standing cases (height h = 1.2 m ⇒ floor plane y=0.3):
 * - depression 45°, center tap → span h/tan45 = 1.2   → (0, 0.3, -1.2)
 * - depression 80°, center tap → span h/tan80 = 0.212 → (0, 0.3, -0.212)
 * - depression ≤ 0° (flat/up)  → span clamped to MAX_STANDOFF_M (2.0 m)
 */
class SensorEstimateTest {

    private val origin = Point3(0f, 1.5f, 0f)
    private val intrinsics = SensorEstimate.Intrinsics(fx = 1000f, fy = 1000f, cx = 500f, cy = 500f)
    private val identityPose = floatArrayOf(0f, 0f, 0f, 1f) // camera looks down -z
    private val tol = 0.01f

    /** Gravity sample (device frame, points down) for an optical axis depressed by [deg]. */
    private fun gravityFor(deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        return floatArrayOf(0f, (-9.81 * cos(r)).toFloat(), (-9.81 * sin(r)).toFloat())
    }

    private fun estimate(
        tapX: Float = 500f,
        tapY: Float = 500f,
        gravity: FloatArray = gravityFor(45.0),
        poseQ: FloatArray = identityPose,
        height: Float = 1.2f,
        intr: SensorEstimate.Intrinsics = intrinsics,
        pos: Point3 = origin,
    ): Point3? = SensorEstimate.estimatePoint(
        tapX = tapX,
        tapY = tapY,
        intrinsics = intr,
        gravity = gravity,
        poseQ = poseQ,
        cameraPos = pos,
        userHeightM = height,
    )

    // --- Floor-plane (ground-span) regime: steep aim, exact intersection ---

    @Test
    fun steepCenterTapHitsTheFloorPlaneExactly() {
        val p = estimate()!!
        assertEquals("x should stay on the ray azimuth", 0f, p.x, tol)
        assertEquals("y must sit on the floor (camY - h)", 0.3f, p.y, tol)
        assertEquals("horizontal span = h/tan(45deg) = 1.2 m", -1.2f, p.z, tol)
    }

    @Test
    fun verySteepTapStillLandsOnTheFloor() {
        val p = estimate(gravity = gravityFor(80.0))!!
        assertEquals(0.3f, p.y, tol) // still exactly the floor plane
        assertEquals(-1.2f / kotlin.math.tan(Math.toRadians(80.0)).toFloat(), p.z, tol)
    }

    // --- Flat / upward aim: clamped stand-off (the honest wall estimate) ---

    @Test
    fun flatAimClampsToMaxStandoffAtCameraHeight() {
        val p = estimate(gravity = gravityFor(0.0))!!
        assertEquals(
            "horizontal stand-off clamps to MAX_STANDOFF_M",
            -SensorEstimate.MAX_STANDOFF_M,
            p.z,
            tol,
        )
        assertEquals("horizontal ray stays at camera height", origin.y, p.y, tol)
        assertEquals(0f, p.x, tol)
    }

    @Test
    fun upwardAimAlsoClampsToMaxStandoff() {
        val p = estimate(gravity = gravityFor(-20.0))!!
        assertEquals(-SensorEstimate.MAX_STANDOFF_M, p.z, tol)
        assertTrue("point must be above the camera when aiming up", p.y > origin.y)
    }

    @Test
    fun clampBoundaryIsContinuous() {
        // theta where h/tan(theta) == MAX_STANDOFF exactly: tan = 1.2/2 = 0.6
        val boundary = Math.toDegrees(atan(0.6))
        val samples = listOf(boundary - 0.1, boundary, boundary + 0.1)
            .map { estimate(gravity = gravityFor(it))!! }
        assertEquals(-SensorEstimate.MAX_STANDOFF_M, samples[1].z, tol)
        for (p in samples) {
            assertTrue(
                "no jump across the clamp: y=${p.y}",
                kotlin.math.abs(p.y - 0.3f) < 0.05f,
            )
        }
    }

    // --- Sensor pitch wins over pose pitch (plan-locked static tilt) ---

    @Test
    fun sensorPitchOverridesPosePitch() {
        // Pose is level (identity) while gravity says 45° down: the sensor decides.
        val p = estimate(gravity = gravityFor(45.0))!!
        assertEquals("sensor pitch must place the point on the floor", 0.3f, p.y, tol)
        assertEquals(-1.2f, p.z, tol)
    }

    // --- Intrinsics: an off-center tap changes the ray ---

    @Test
    fun bottomScreenTapIsCloserThanCenterTap() {
        val center = estimate(gravity = gravityFor(45.0))!!
        val lower = estimate(tapY = 700f, gravity = gravityFor(45.0))!!
        assertTrue("lower pixel must land closer than the center tap", kotlin.math.abs(lower.z) < kotlin.math.abs(center.z))
        assertEquals("unclamped span keeps it on the floor", 0.3f, lower.y, tol)
        assertEquals(-0.8f, lower.z, tol) // hand-computed: h/tan(56.3deg) = 0.8
    }

    // --- Pose roll affects the bearing (gravity cannot sense roll) ---

    @Test
    fun poseRollRotatesTheBearingInWorld() {
        val half = Math.toRadians(45.0)
        val rolled = floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat())
        val p = estimate(tapY = 700f, gravity = gravityFor(0.0), poseQ = rolled)!!
        // +90° roll maps the "below center" pixel to world +x; horizontal
        // stand-off stays exactly MAX_STANDOFF_M (2 m) along the rotated ray.
        assertEquals(0.3922f, p.x, tol)
        assertEquals(origin.y, p.y, tol)
        assertEquals(-1.9612f, p.z, tol)
        val horizontal = kotlin.math.sqrt(p.x * p.x + p.z * p.z)
        assertEquals("stand-off is horizontal distance, not the z component", 2.0f, horizontal, tol)
    }

    // --- Input guards: degenerate inputs must return null, never NaN ---

    @Test
    fun degenerateInputsReturnNull() {
        assertNull("zero gravity", estimate(gravity = floatArrayOf(0f, 0f, 0f)))
        assertNull("NaN gravity", estimate(gravity = floatArrayOf(Float.NaN, 0f, 0f)))
        assertNull(
            "azimuth-free pose (camera straight down)",
            estimate(
                poseQ = floatArrayOf(
                    sin(-Math.toRadians(45.0)).toFloat(),
                    0f,
                    0f,
                    cos(-Math.toRadians(45.0)).toFloat(),
                ),
            ),
        )
        assertNull(
            "zero focal length",
            estimate(intr = SensorEstimate.Intrinsics(0f, 1000f, 500f, 500f)),
        )
        assertNull(
            "short gravity array",
            SensorEstimate.estimatePoint(
                tapX = 500f, tapY = 500f, intrinsics = intrinsics,
                gravity = floatArrayOf(1f), poseQ = identityPose,
                cameraPos = origin, userHeightM = 1.2f,
            ),
        )
        assertNull(
            "short pose array",
            SensorEstimate.estimatePoint(
                tapX = 500f, tapY = 500f, intrinsics = intrinsics,
                gravity = gravityFor(45.0), poseQ = floatArrayOf(0f, 0f, 0f),
                cameraPos = origin, userHeightM = 1.2f,
            ),
        )
    }

    @Test
    fun nonPositiveHeightFallsBackToDefault() {
        val badHeight = estimate(height = -1f)!!
        val defaultHeight = estimate(height = SensorEstimate.DEFAULT_USER_HEIGHT_M)!!
        assertEquals(defaultHeight, badHeight)
        assertEquals(1.2f, SensorEstimate.DEFAULT_USER_HEIGHT_M, 0f)
    }

    @Test
    fun estimateIsDeterministic() {
        assertEquals(estimate(), estimate())
    }
}
