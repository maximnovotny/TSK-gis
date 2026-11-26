package cz.photoscanar.capture

import cz.photoscanar.coverage.OverlapEstimator
import cz.photoscanar.geom.Quat
import cz.photoscanar.geom.Vec3

/**
 * The SMART mode decision: shoot when the viewpoint actually changed enough.
 *
 * In AUTO the app fires on a clock. In SMART it fires on *geometry* - a minimum
 * baseline, a minimum rotation, or the estimated overlap dropping out of the
 * target band - so walking slowly around a facade does not produce two hundred
 * near-identical frames, and swinging the phone quickly does not leave gaps.
 *
 * Without a valid AR pose there is nothing to reason about, so it falls back to
 * the fixed cadence.
 */
class SmartTrigger(
    private val minIntervalMs: Long = 350,
    private val maxIntervalMs: Long = 2500,
    private val minBaselineM: Float = 0.12f,
    private val minRotationRad: Float = 0.085f,     // ~5 degrees
    private val overlapCeiling: Double = OverlapEstimator.TARGET_MAX,
    private val maxAngularSpeedRad: Float = 1.4f
) {
    data class Decision(val capture: Boolean, val reason: String)

    private var lastPos: Vec3? = null
    private var lastRot: Quat? = null
    private var lastCaptureMs: Long = 0

    fun reset() {
        lastPos = null
        lastRot = null
        lastCaptureMs = 0
    }

    fun onCaptured(pos: Vec3?, rot: Quat?, nowMs: Long) {
        lastPos = pos
        lastRot = rot
        lastCaptureMs = nowMs
    }

    /**
     * @param estimatedOverlap overlap with the previous photo, negative if unknown
     */
    fun shouldCapture(
        nowMs: Long,
        pos: Vec3?,
        rot: Quat?,
        poseValid: Boolean,
        angularSpeedRad: Float,
        estimatedOverlap: Double
    ): Decision {
        val sinceLast = nowMs - lastCaptureMs
        if (lastCaptureMs == 0L) return Decision(true, "first")
        if (sinceLast < minIntervalMs) return Decision(false, "too soon")

        // a fast rotation guarantees motion blur - wait it out unless we are about
        // to open a gap in coverage
        if (angularSpeedRad > maxAngularSpeedRad && sinceLast < maxIntervalMs) {
            return Decision(false, "rotating too fast")
        }
        if (sinceLast >= maxIntervalMs) return Decision(true, "max interval")

        if (!poseValid || pos == null || rot == null || lastPos == null || lastRot == null) {
            // no usable geometry: behave like the fixed cadence
            return if (sinceLast >= 500) Decision(true, "no pose, fixed cadence")
            else Decision(false, "no pose, waiting")
        }

        val baseline = (pos - lastPos!!).length()
        if (baseline >= minBaselineM) return Decision(true, "baseline %.2f m".format(baseline))

        val rotated = lastRot!!.angleTo(rot)
        if (rotated >= minRotationRad) return Decision(true, "rotated %.1f deg".format(Math.toDegrees(rotated.toDouble())))

        if (estimatedOverlap in 0.0..overlapCeiling) {
            return Decision(true, "overlap %.0f %%".format(estimatedOverlap * 100))
        }
        return Decision(false, "viewpoint unchanged")
    }
}
