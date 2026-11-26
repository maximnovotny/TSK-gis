package cz.photoscanar.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import android.os.SystemClock
import android.util.Log
import cz.photoscanar.coverage.OverlapEstimator
import cz.photoscanar.imaging.Exposure
import cz.photoscanar.imaging.ImageSignature
import cz.photoscanar.imaging.LumaImage
import cz.photoscanar.imaging.Sharpness
import cz.photoscanar.model.QualityFlag
import cz.photoscanar.model.QualityMetrics
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asynchronous per-photo quality pass.
 *
 * Runs on one background thread at below-normal priority with a short queue. If
 * analysis cannot keep up, photos are simply left unanalysed (and marked as such
 * in the export) - the analyser never throttles capture, and it never touches the
 * original file.
 *
 * The measures are computed on a fixed-scale decode of the JPEG (about 1200 px
 * wide) so that the numbers are comparable between photos of a session.
 */
class QualityAnalyzer(
    private val sharpnessThreshold: Double,
    private val maxPending: Int = 3,
    private val listener: (Result) -> Unit
) {
    class Job(
        val photoId: Int,
        val jpeg: ByteArray,
        val angularSpeedRadS: Float,
        val poseOverlap: Double
    )

    class Result(
        val photoId: Int,
        val metrics: QualityMetrics,
        val signature: ImageSignature?
    )

    private val queue = LinkedBlockingQueue<Job>()
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var previousSignature: ImageSignature? = null
    private var previousPhotoId: Int = -1

    val skipped = AtomicInteger(0)
    val analysed = AtomicInteger(0)

    val pending: Int get() = queue.size

    fun start() {
        if (running.getAndSet(true)) return
        val t = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            loop()
        }, "quality-analyzer")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        queue.clear()
        previousSignature = null
        previousPhotoId = -1
    }

    /** @return false when the job was dropped because the analyser is behind */
    fun submit(job: Job): Boolean {
        if (!running.get()) return false
        if (queue.size >= maxPending) {
            skipped.incrementAndGet()
            return false
        }
        return queue.offer(job)
    }

    private fun loop() {
        while (running.get()) {
            val job = try {
                queue.poll(250, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            try {
                listener(analyse(job))
            } catch (e: Throwable) {
                Log.w(TAG, "analysis failed for photo ${job.photoId}", e)
                listener(Result(job.photoId, QualityMetrics.NOT_ANALYSED, null))
            }
        }
    }

    private fun analyse(job: Job): Result {
        val t0 = SystemClock.elapsedRealtime()
        val luma = decodeLuma(job.jpeg) ?: return Result(job.photoId, QualityMetrics.NOT_ANALYSED, null)

        // sharpness is measured on the central region: corners of a wide lens are
        // softer by construction and would drag the figure down on every photo
        val center = luma.centerCrop(0.7)
        val lapVar = Sharpness.laplacianVariance(center, step = 1)
        val tenengrad = Sharpness.tenengrad(center, step = 2)
        val directional = Sharpness.directionalRatio(center, step = 3)
        val exposure = Exposure.analyse(luma, step = 2)
        val signature = ImageSignature.from(luma)

        var imageOverlap = -1.0
        var mad = Double.MAX_VALUE
        val prev = previousSignature
        if (prev != null) {
            imageOverlap = signature.imageOverlap(prev)
            mad = signature.meanAbsDiff(prev)
        }
        val overlap = OverlapEstimator.combine(job.poseOverlap, imageOverlap)

        val flags = HashSet<QualityFlag>()
        // a low Laplacian variance is only called blur when the gradient energy
        // agrees; a flat, textureless wall is not a blurred photo
        if (lapVar < sharpnessThreshold && tenengrad < sharpnessThreshold * 0.6) {
            flags.add(QualityFlag.BLURRY)
        }
        if (Exposure.isTooDark(exposure)) flags.add(QualityFlag.DARK)
        if (Exposure.isTooBright(exposure)) flags.add(QualityFlag.BRIGHT)
        if (Exposure.isLowContrast(exposure)) flags.add(QualityFlag.LOW_CONTRAST)
        if (job.angularSpeedRadS > FAST_MOTION_RAD_S) flags.add(QualityFlag.FAST_MOTION)
        if (prev != null && OverlapEstimator.isNearDuplicate(overlap, mad)) {
            flags.add(QualityFlag.DUPLICATE)
        }
        if (overlap in 0.0..OverlapEstimator.TARGET_MIN) flags.add(QualityFlag.LOW_OVERLAP)
        // strongly directional gradients plus softness is the signature of a
        // smear rather than a defocus
        if (QualityFlag.BLURRY in flags && (directional > 2.2 || directional < 0.45)) {
            flags.add(QualityFlag.FAST_MOTION)
        }

        previousSignature = signature
        previousPhotoId = job.photoId
        analysed.incrementAndGet()

        val metrics = QualityMetrics(
            analysed = true,
            laplacianVariance = lapVar,
            sharpnessScore = (lapVar / sharpnessThreshold).coerceIn(0.0, 4.0) / 4.0,
            meanLuma = exposure.meanLuma,
            clippedLowPct = exposure.clippedLowPct,
            clippedHighPct = exposure.clippedHighPct,
            contrast = exposure.contrast,
            overlapWithPrevious = overlap,
            flags = flags,
            analysisDurationMs = SystemClock.elapsedRealtime() - t0
        )
        return Result(job.photoId, metrics, signature)
    }

    /** Decodes the JPEG to a fixed working width so the measures stay comparable. */
    private fun decodeLuma(jpeg: ByteArray): LumaImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WORKING_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        return try {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            LumaImage.fromArgb(px, w, h)
        } finally {
            bmp.recycle()
        }
    }

    private companion object {
        const val TAG = "QualityAnalyzer"
        const val WORKING_WIDTH = 1200
        const val FAST_MOTION_RAD_S = 0.9f
    }
}
