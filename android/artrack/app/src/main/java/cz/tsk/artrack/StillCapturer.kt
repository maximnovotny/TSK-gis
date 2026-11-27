package cz.tsk.artrack

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compresses full resolution camera frames to JPEG on a worker thread.
 *
 * The caller copies the YUV data on the render thread (cheap) and hands the buffer
 * over; if compression cannot keep up, the frame is dropped instead of queued, so
 * recording never stalls.
 */
class StillCapturer(sessionDir: File, private val quality: Int = 92) {

    val framesDir = File(sessionDir, "frames").apply { mkdirs() }

    private val executor = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicInteger(0)
    private val savedCount = AtomicInteger(0)
    private val droppedCount = AtomicInteger(0)

    val saved: Int get() = savedCount.get()
    val dropped: Int get() = droppedCount.get()

    /** Returns the file name the frame will be written to, or null if it was dropped. */
    fun offer(stillIndex: Int, nv21: ByteArray, width: Int, height: Int): String? {
        if (inFlight.get() >= MAX_IN_FLIGHT) {
            droppedCount.incrementAndGet()
            return null
        }
        val name = String.format(Locale.US, "frame_%06d.jpg", stillIndex)
        inFlight.incrementAndGet()
        executor.execute {
            try {
                val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                BufferedOutputStream(FileOutputStream(File(framesDir, name)), 1 shl 16).use {
                    yuv.compressToJpeg(Rect(0, 0, width, height), quality, it)
                }
                savedCount.incrementAndGet()
            } catch (t: Throwable) {
                Log.e("ARTrack", "jpeg write failed", t)
                droppedCount.incrementAndGet()
            } finally {
                inFlight.decrementAndGet()
            }
        }
        return name
    }

    /** Waits for the queued frames to hit the disk. */
    fun shutdown() {
        executor.shutdown()
        runCatching { executor.awaitTermination(60, TimeUnit.SECONDS) }
    }

    private companion object {
        const val MAX_IN_FLIGHT = 3
    }
}
