package cz.photoscanar.analysis

import android.content.Context
import android.os.Process
import android.util.Log
import cz.photoscanar.apriltag.AprilTagDetector
import cz.photoscanar.apriltag.AprilTagFamily
import cz.photoscanar.imaging.LumaImage
import cz.photoscanar.model.TagObservation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * AprilTag 36h11 detection on the live preview stream.
 *
 * Runs on one background thread at the lowest useful priority and keeps only the
 * newest frame: if detection takes longer than the frame interval, intermediate
 * frames are dropped rather than queued. Tag detection therefore cannot slow
 * capture down - the worst case is that fewer frames get searched.
 */
class TagAnalyzer(
    context: Context,
    private val listener: (List<TagObservation>) -> Unit
) {
    private val family: AprilTagFamily? = try {
        context.assets.open(AprilTagFamily.ASSET_36H11).use { AprilTagFamily.parse(it) }
    } catch (e: Throwable) {
        Log.e(TAG, "cannot load the 36h11 codebook", e)
        null
    }

    private val detector = family?.let {
        AprilTagDetector(it, AprilTagDetector.Config(decimate = 0))
    }

    private class Frame(val luma: ByteArray, val w: Int, val h: Int, val timestampNs: Long)

    private val latest = AtomicReference<Frame?>(null)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    val framesProcessed = AtomicInteger(0)
    val framesDropped = AtomicInteger(0)
    val detectionCount = AtomicInteger(0)

    /** Every tag id seen in this session. */
    private val seenIds = java.util.Collections.synchronizedSet(LinkedHashSet<Int>())

    val available: Boolean get() = detector != null

    fun uniqueIds(): List<Int> = synchronized(seenIds) { seenIds.sorted() }

    @Volatile var lastDetections: List<TagObservation> = emptyList()
        private set

    fun start() {
        if (detector == null) return
        if (running.getAndSet(true)) return
        val t = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            loop()
        }, "tag-analyzer")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        latest.set(null)
    }

    fun reset() {
        synchronized(seenIds) { seenIds.clear() }
        lastDetections = emptyList()
    }

    /** Offers a frame; an older unprocessed frame is discarded. */
    fun offer(luma: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (!running.get()) return
        val previous = latest.getAndSet(Frame(luma, width, height, timestampNs))
        if (previous != null) framesDropped.incrementAndGet()
    }

    private fun loop() {
        val det = detector ?: return
        while (running.get()) {
            val frame = latest.getAndSet(null)
            if (frame == null) {
                try { Thread.sleep(20) } catch (_: InterruptedException) { return }
                continue
            }
            try {
                val img = LumaImage(frame.w, frame.h, frame.luma)
                val found = det.detect(img)
                framesProcessed.incrementAndGet()
                if (found.isEmpty()) {
                    lastDetections = emptyList()
                    continue
                }
                val observations = found.map { d ->
                    synchronized(seenIds) { seenIds.add(d.id) }
                    TagObservation(
                        timestampNs = frame.timestampNs,
                        tagFamily = "tag36h11",
                        tagId = d.id,
                        cornersX = d.cornersX.copyOf(),
                        cornersY = d.cornersY.copyOf(),
                        centerX = d.centerX,
                        centerY = d.centerY,
                        sourceWidth = frame.w,
                        sourceHeight = frame.h,
                        decisionMargin = d.decisionMargin,
                        hammingDistance = d.hamming,
                        source = "preview"
                    )
                }
                detectionCount.addAndGet(observations.size)
                lastDetections = observations
                listener(observations)
            } catch (e: Throwable) {
                Log.w(TAG, "tag detection failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "TagAnalyzer"
    }
}
