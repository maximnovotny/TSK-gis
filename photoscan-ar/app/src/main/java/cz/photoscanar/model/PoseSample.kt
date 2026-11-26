package cz.photoscanar.model

import cz.photoscanar.geom.Quat
import cz.photoscanar.geom.Vec3

/** How much we trust an AR pose. Exported verbatim so downstream tools can filter. */
enum class PoseQuality { VALID_TRACKING, LIMITED, INVALID, NO_AR }

/**
 * One sample of the estimated device pose.
 *
 * Positions come from ARCore's visual-inertial odometry. They are *approximate*,
 * drift over time and are expressed in an arbitrary session-local frame; they are
 * never geodetic coordinates and must not be fed into a photogrammetric solver as
 * if they were surveyed exterior orientations.
 */
data class PoseSample(
    /** Monotonic clock (SystemClock.elapsedRealtimeNanos) shared with camera timestamps. */
    val timestampNs: Long,
    val position: Vec3,
    val rotation: Quat,
    val quality: PoseQuality,
    val trackingStateName: String,
    val trackingFailureReason: String,
    /** metres per second, from consecutive poses */
    val linearSpeed: Float = 0f,
    /** radians per second */
    val angularSpeed: Float = 0f
) {
    val valid: Boolean get() = quality == PoseQuality.VALID_TRACKING

    companion object {
        fun none(timestampNs: Long) = PoseSample(
            timestampNs, Vec3.ZERO, Quat.IDENTITY, PoseQuality.NO_AR, "NO_AR", "NONE"
        )
    }
}
