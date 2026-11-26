package cz.photoscanar.coverage

import cz.photoscanar.geom.Quat
import cz.photoscanar.geom.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Estimates how much two consecutive photos overlap.
 *
 * Two independent estimators are combined:
 *
 *  * a *geometric* one from the AR poses, which models the lateral shift of the
 *    footprint at the assumed object distance plus the rotation between the two
 *    views, and
 *  * an *image* one from small normalised thumbnails (see ImageSignature),
 *    which needs no pose at all and therefore still works when AR is off or
 *    tracking is lost.
 *
 * Neither is a tie-point count. The figure in the HUD is an indicator used to
 * keep the operator inside the 80-85 % band that photogrammetric pipelines
 * like, not a measured redundancy.
 */
object OverlapEstimator {

    const val TARGET_MIN = 0.80
    const val TARGET_MAX = 0.85

    /**
     * @param objectDistanceM assumed distance to the subject; drives how much a
     *        given sideways step shifts the footprint
     */
    fun fromPoses(
        prevPos: Vec3,
        prevRot: Quat,
        curPos: Vec3,
        curRot: Quat,
        objectDistanceM: Float,
        hFovRad: Float,
        vFovRad: Float
    ): Double {
        val d = max(0.2f, objectDistanceM)
        val halfW = d * tan(hFovRad / 2f)
        val halfH = d * tan(vFovRad / 2f)
        if (halfW <= 0f || halfH <= 0f) return -1.0

        val t = curPos - prevPos
        val right = prevRot.right()
        val up = prevRot.up()
        val fwd = prevRot.forward()

        // translation measured in half-frame widths at the object distance
        val shiftX = t.dot(right) / (2f * halfW)
        val shiftY = t.dot(up) / (2f * halfH)

        // rotation: a yaw of hFov/2 shifts the footprint by half a frame
        val yaw = signedAngle(fwd, curRot.forward(), up)
        val pitch = signedAngle(fwd, curRot.forward(), right)
        val rotX = yaw / (hFovRad / 2f)
        val rotY = pitch / (vFovRad / 2f)

        val fx = (1.0 - abs(shiftX + rotX)).coerceAtLeast(0.0)
        val fy = (1.0 - abs(shiftY + rotY)).coerceAtLeast(0.0)

        // moving towards or away from the subject changes the scale
        val along = t.dot(fwd)
        val newDist = max(0.2f, d - along)
        val scale = min(d / newDist, newDist / d).toDouble().coerceIn(0.0, 1.0)

        // roll reduces the shared area as well
        val roll = abs(signedAngle(prevRot.up(), curRot.up(), fwd))
        val rollFactor = (1.0 - roll / 1.2f).coerceIn(0.0, 1.0)

        return (fx * fy * scale * rollFactor).coerceIn(0.0, 1.0)
    }

    private fun signedAngle(from: Vec3, to: Vec3, axis: Vec3): Float {
        val a = from.normalized()
        val b = to.normalized()
        val n = axis.normalized()
        // project both onto the plane perpendicular to the axis
        val ap = (a - n * a.dot(n)).normalized()
        val bp = (b - n * b.dot(n)).normalized()
        if (ap.length() < 1e-4f || bp.length() < 1e-4f) return 0f
        val dot = ap.dot(bp).coerceIn(-1f, 1f)
        val cross = ap.cross(bp).dot(n)
        return kotlin.math.atan2(cross, dot)
    }

    /**
     * Fuses the two estimators. When both are available the image measure is
     * trusted more at short baselines (it sees real content) and the pose measure
     * more at long ones (thumbnail correlation saturates).
     */
    fun combine(poseOverlap: Double, imageOverlap: Double): Double {
        val hasPose = poseOverlap >= 0.0
        val hasImage = imageOverlap >= 0.0
        return when {
            hasPose && hasImage -> {
                val w = if (imageOverlap > 0.6) 0.6 else 0.35
                (imageOverlap * w + poseOverlap * (1 - w)).coerceIn(0.0, 1.0)
            }
            hasImage -> imageOverlap
            hasPose -> poseOverlap
            else -> -1.0
        }
    }

    fun isInTargetBand(overlap: Double) = overlap in TARGET_MIN..TARGET_MAX

    /** True when consecutive frames are so similar that the photo adds nothing. */
    fun isNearDuplicate(overlap: Double, meanAbsDiff: Double) =
        overlap > 0.975 && meanAbsDiff < 2.0
}
