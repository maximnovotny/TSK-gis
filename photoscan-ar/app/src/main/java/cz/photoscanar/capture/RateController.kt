package cz.photoscanar.capture

import cz.photoscanar.model.CaptureMode
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps the capture cadence at the mode's target while the phone can sustain it,
 * and backs off when it cannot.
 *
 * The declared "2 photos per second" is a target, not a promise: full-resolution
 * JPEG capture competes with the ISP, the write queue, ARCore and the thermal
 * budget. Rather than silently dropping frames, the controller slows down (or,
 * if the user allows it, asks for a smaller capture size) and the HUD always
 * shows the rate that was actually achieved.
 *
 * Priority order, as required: sharp images and reliable storage first, declared
 * rate last.
 */
class RateController(
    private var mode: CaptureMode,
    private val allowResolutionDrop: Boolean,
    private val maxIntervalMs: Long = 2000
) {
    enum class Action { KEEP, SLOW_DOWN, SPEED_UP, LOWER_RESOLUTION }

    data class Load(
        /** 0..1 fill level of the disk write queue */
        val writeQueueFill: Double,
        /** still captures handed to the camera but not yet delivered */
        val inFlight: Int,
        /** android.os.PowerManager thermal status, 0 = none */
        val thermalStatus: Int,
        val storageLow: Boolean
    )

    private var intervalMs: Long = mode.targetIntervalMs
    private var resolutionLevel: Int = 0      // 0 = largest supported size
    private var consecutiveHealthy = 0
    private val saveTimestamps = ArrayDeque<Long>()
    private var issued = 0L
    private var saved = 0L
    private var dropped = 0L

    val currentIntervalMs: Long get() = intervalMs
    val currentResolutionLevel: Int get() = resolutionLevel
    val issuedCount: Long get() = issued
    val savedCount: Long get() = saved
    val droppedCount: Long get() = dropped

    fun setMode(newMode: CaptureMode) {
        mode = newMode
        intervalMs = newMode.targetIntervalMs
        resolutionLevel = 0
        consecutiveHealthy = 0
    }

    fun onCaptureIssued() { issued++ }

    fun onCaptureDropped() { dropped++ }

    fun onPhotoSaved(elapsedRealtimeMs: Long) {
        saved++
        saveTimestamps.addLast(elapsedRealtimeMs)
        while (saveTimestamps.size > 24) saveTimestamps.removeFirst()
        // forget samples older than 6 s so the figure reacts to a real slowdown
        while (saveTimestamps.size > 2 && elapsedRealtimeMs - saveTimestamps.first() > 6000) {
            saveTimestamps.removeFirst()
        }
    }

    /** Photos actually written per second over the recent window. */
    fun measuredFps(): Double {
        if (saveTimestamps.size < 2) return 0.0
        val span = saveTimestamps.last() - saveTimestamps.first()
        if (span <= 0) return 0.0
        return (saveTimestamps.size - 1) * 1000.0 / span
    }

    fun targetFps(): Double = 1000.0 / intervalMs

    /** Decides whether the next capture may go out now. */
    fun mayCapture(nowMs: Long, lastCaptureMs: Long, load: Load): Boolean {
        if (load.storageLow) return false
        if (nowMs - lastCaptureMs < intervalMs) return false
        // never pile up more than a few outstanding captures: that is what turns a
        // burst into dropped frames and stale autofocus
        if (load.inFlight >= MAX_IN_FLIGHT) return false
        if (load.writeQueueFill > 0.92) return false
        return true
    }

    /** Adjusts the cadence from the current load; call once per capture cycle. */
    fun evaluate(load: Load): Action {
        val overloaded = load.writeQueueFill > 0.70 ||
            load.inFlight >= MAX_IN_FLIGHT ||
            load.thermalStatus >= THERMAL_MODERATE
        val severe = load.writeQueueFill > 0.88 || load.thermalStatus >= THERMAL_SEVERE

        if (overloaded) {
            consecutiveHealthy = 0
            if (intervalMs >= maxIntervalMs && allowResolutionDrop && mode.allowResolutionDrop) {
                if (resolutionLevel < MAX_RESOLUTION_LEVEL) {
                    resolutionLevel++
                    // a smaller frame writes faster, so give the rate another chance
                    intervalMs = max(mode.minIntervalMs, (intervalMs * 0.75).toLong())
                    return Action.LOWER_RESOLUTION
                }
                return Action.KEEP
            }
            val factor = if (severe) 1.6 else 1.25
            val next = min(maxIntervalMs, (intervalMs * factor).toLong())
            if (next != intervalMs) {
                intervalMs = next
                return Action.SLOW_DOWN
            }
            return Action.KEEP
        }

        consecutiveHealthy++
        if (consecutiveHealthy >= HEALTHY_BEFORE_SPEEDUP && intervalMs > mode.targetIntervalMs) {
            consecutiveHealthy = 0
            intervalMs = max(mode.targetIntervalMs, (intervalMs * 0.80).toLong())
            return Action.SPEED_UP
        }
        return Action.KEEP
    }

    fun reset() {
        intervalMs = mode.targetIntervalMs
        resolutionLevel = 0
        consecutiveHealthy = 0
        saveTimestamps.clear()
        issued = 0; saved = 0; dropped = 0
    }

    companion object {
        const val MAX_IN_FLIGHT = 3
        const val MAX_RESOLUTION_LEVEL = 2
        const val THERMAL_MODERATE = 2   // PowerManager.THERMAL_STATUS_MODERATE
        const val THERMAL_SEVERE = 3     // PowerManager.THERMAL_STATUS_SEVERE
        /**
         * Healthy cycles before the cadence is pushed back up. evaluate() runs once
         * per capture, so at two photos per second this is a recovery step every
         * three seconds - quick enough that a momentary write stall does not cost
         * the operator half a minute of reduced rate, slow enough not to oscillate.
         */
        const val HEALTHY_BEFORE_SPEEDUP = 6
    }
}
