package com.metiri.armeasure

import uniffi.core.Point3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Blank-wall sensor-trig fallback (gate 4.2, plan 15.7.1/15.7.3 item 3):
 * when `Frame.hitTest` yields no feature point at the tap, estimate where the
 * tap ray meets the surface using ONLY sensor + settings inputs, and tag the
 * result as [uniffi.core.MeasurementSource.SENSOR] downstream.
 *
 * Pure Kotlin (no Android/ARCore types) so every branch is JVM-unit-testable
 * — see `SensorEstimateTest`. Inputs:
 *
 * - **gravity**: static `TYPE_GRAVITY` sample in the DEVICE frame (points
 *   toward the earth). Provides the pitch of the camera optical axis; the
 *   back camera looks down device −z, so only `gz` matters and the value is
 *   immune to pan/roll — this is the plan-locked "static accelerometer tilt".
 * - **intrinsics** (fx, fy, cx, cy): pinhole model in the SAME pixel frame as
 *   the tap coordinates (ARCore IMAGE_PIXELS from `LENS_INTRINSIC_CALIBRATION`,
 *   or view pixels derived from the ARCore projection matrix).
 * - **poseQ / cameraPos**: ARCore camera pose — provides the world AZIMUTH
 *   (gravity cannot: no compass in scope) and the ray origin. Elevation is
 *   taken from gravity, not from the pose, so a stale/drifted VIO pitch cannot
 *   bias the estimate.
 * - **userHeightM**: stored camera height above the surface plane
 *   (settings/first-launch; default [DEFAULT_USER_HEIGHT_M]).
 *
 * Formula (all vectors unit unless noted):
 *
 * ```
 * pitch  = asin(-gz / |g|)                    depression of the optical axis (+ = down)
 * A      = cos(pitch)·F_h - ŷ·sin(pitch)       look axis: sensor pitch, pose azimuth F_h
 * W0     = sin(pitch)·F_h + ŷ·cos(pitch)       up axis at zero roll;  R0 = A x W0
 * rho    = atan2(V·R0, V·W0)                  pose roll about the axis (V = pose up)
 * W      = cos(rho)·W0 + sin(rho)·R0           camera up;  R = cos(rho)·R0 - sin(rho)·W0
 * b      = ((u-cx)/fx, -(v-cy)/fy, 1)          tap bearing (right, up, forward)
 * D      = normalize(b.x·R + b.y·W + A)        ray direction in world
 * theta  = asin(-D.y)                          ray depression
 * L      = theta > 0 ? min(h / tan(theta), MAX_STANDOFF_M) : MAX_STANDOFF_M
 * P      = O + (L / |D.xz|) · D                estimated world point
 * ```
 *
 * `L` is the horizontal ground-span (classic height+pitch trig): for a ray
 * steep enough to reach the floor within [MAX_STANDOFF_M] this is EXACTLY the
 * floor-plane intersection (`P.y == O.y - h`). Flat or upward aim — the true
 * blank-WALL case — has no depth cue in these inputs, so the span clamps to an
 * assumed [MAX_STANDOFF_M] surface distance; that clamp error is the
 * estimate's honest error band, which is why the point is tagged
 * `Sensor` (UI: "~estimated") and scored Low/Medium by `score_confidence`.
 *
 * Every degenerate input (missing gravity, azimuth-free aim, zero
 * intrinsics, non-finite results) returns null — the caller then falls back
 * to the plain TapMiss path ("no point here").
 */
object SensorEstimate {

    /** First-launch default for the stored camera height above the surface, m. */
    const val DEFAULT_USER_HEIGHT_M = 1.2f

    /**
     * Maximum horizontal surface distance for a flat/upward aim (m). Also the
     * exact span whenever `h / tan(theta)` exceeds it. ~arm's-length wall
     * range; picked as the compromise between "wall usually closer" and
     * "room usually not deeper".
     */
    const val MAX_STANDOFF_M = 2.0f

    private const val EPS = 1e-6f

    /** Pinhole intrinsics in the same pixel frame as the tap coordinates. */
    data class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float)

    /**
     * Ray-plane estimate for one tap. Returns null when the inputs cannot
     * yield a defensible point (caller shows "no point here" instead).
     *
     * @param tapX tap horizontal pixel, same frame as [intrinsics]
     * @param tapY tap vertical pixel (y DOWN), same frame as [intrinsics]
     * @param gravity static gravity sample, device frame, length >= 3
     * @param poseQ ARCore camera pose rotation quaternion qx, qy, qz, qw,
     *   length >= 4 (camera looks down its -z axis, OpenGL convention)
     * @param cameraPos ARCore camera world position (ray origin)
     * @param userHeightM camera height above the surface plane; non-positive
     *   or non-finite values fall back to [DEFAULT_USER_HEIGHT_M]
     */
    fun estimatePoint(
        tapX: Float,
        tapY: Float,
        intrinsics: Intrinsics,
        gravity: FloatArray,
        poseQ: FloatArray,
        cameraPos: Point3,
        userHeightM: Float,
    ): Point3? {
        if (gravity.size < 3 || poseQ.size < 4) return null
        if (intrinsics.fx == 0f || intrinsics.fy == 0f) return null
        if (!intrinsics.fx.isFinite() || !intrinsics.fy.isFinite()) return null

        // 1. Sensor pitch: depression of the optical axis (device -z).
        val gz = gravity[2]
        val gMag = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gz * gz)
        if (gMag < EPS) return null
        val pitch = asin((-gz / gMag).coerceIn(-1f, 1f))

        // 2. Pose basis from the quaternion (column 0 = right, 1 = up, 2 = back).
        val qx = poseQ[0]
        val qy = poseQ[1]
        val qz = poseQ[2]
        val qw = poseQ[3]
        val upX = 2f * (qx * qy - qz * qw)
        val upY = 1f - 2f * (qx * qx + qz * qz)
        val upZ = 2f * (qy * qz + qx * qw)
        val backX = 2f * (qx * qz + qy * qw)
        val backY = 2f * (qy * qz - qx * qw)
        val backZ = 1f - 2f * (qx * qx + qy * qy)
        // Look axis = -z column of the pose.

        // 3. Azimuth: horizontal projection of the pose look direction.
        val fLen = sqrt(backX * backX + backZ * backZ)
        if (fLen < EPS) return null // aiming straight up/down: no azimuth
        val fX = -backX / fLen
        val fZ = -backZ / fLen

        // 4. Sensor-pitch look axis A, zero-roll up W0, horizontal right R0.
        val c = cos(pitch)
        val s = sin(pitch)
        val aX = c * fX
        val aY = -s
        val aZ = c * fZ
        val w0X = s * fX
        val w0Y = c
        val w0Z = s * fZ
        val r0X = -fZ // closed form of A x W0
        val r0Y = 0f
        val r0Z = fX

        // 5. Pose roll about the look axis (gravity cannot sense roll).
        val rho = atan2(upX * r0X + upY * r0Y + upZ * r0Z, upX * w0X + upY * w0Y + upZ * w0Z)
        val cr = cos(rho)
        val sr = sin(rho)
        val wX = cr * w0X + sr * r0X
        val wY = cr * w0Y + sr * r0Y
        val wZ = cr * w0Z + sr * r0Z
        val rX = cr * r0X - sr * w0X
        val rY = cr * r0Y - sr * w0Y
        val rZ = cr * r0Z - sr * w0Z

        // 6. Ray through the tap pixel (bearing: right, up, forward).
        val bX = (tapX - intrinsics.cx) / intrinsics.fx
        val bY = -(tapY - intrinsics.cy) / intrinsics.fy
        var dX = bX * rX + bY * wX + aX
        var dY = bX * rY + bY * wY + aY
        var dZ = bX * rZ + bY * wZ + aZ
        val dLen = sqrt(dX * dX + dY * dY + dZ * dZ)
        if (dLen < EPS || !dLen.isFinite()) return null
        dX /= dLen
        dY /= dLen
        dZ /= dLen

        // 7. Ground-span clamp: horizontal distance to the assumed surface.
        val dh = sqrt(dX * dX + dZ * dZ)
        if (dh < EPS || !dh.isFinite()) return null
        val height =
            if (userHeightM.isFinite() && userHeightM > 0f) userHeightM else DEFAULT_USER_HEIGHT_M
        val theta = asin((-dY).coerceIn(-1f, 1f))
        val span =
            if (theta > 0f) min(height / tan(theta), MAX_STANDOFF_M) else MAX_STANDOFF_M
        if (!span.isFinite() || span <= 0f) return null
        val t = span / dh
        val point = Point3(
            cameraPos.x + t * dX,
            cameraPos.y + t * dY,
            cameraPos.z + t * dZ,
        )
        return if (point.x.isFinite() && point.y.isFinite() && point.z.isFinite()) point else null
    }
}
