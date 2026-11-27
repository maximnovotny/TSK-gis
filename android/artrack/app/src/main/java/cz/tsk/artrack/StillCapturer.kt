package cz.tsk.artrack

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Compresses full resolution camera frames to JPEG on a worker thread and stamps
 * the position, time, device and focal length into their EXIF, so the photos are
 * importable on their own.
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
    private val exifFailures = AtomicInteger(0)

    val saved: Int get() = savedCount.get()
    val dropped: Int get() = droppedCount.get()
    val exifErrors: Int get() = exifFailures.get()

    /** What goes into the EXIF of one photo. */
    class Tags(
        val fix: Location?,
        val focal35mm: Double,
        val width: Int,
        val height: Int,
        val wallClockMs: Long
    )

    /** Returns the file name the frame will be written to, or null if it was dropped. */
    fun offer(stillIndex: Int, nv21: ByteArray, width: Int, height: Int, tags: Tags): String? {
        if (inFlight.get() >= MAX_IN_FLIGHT) {
            droppedCount.incrementAndGet()
            return null
        }
        val name = String.format(Locale.US, "frame_%06d.jpg", stillIndex)
        inFlight.incrementAndGet()
        executor.execute {
            val file = File(framesDir, name)
            try {
                val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                BufferedOutputStream(FileOutputStream(file), 1 shl 16).use {
                    yuv.compressToJpeg(Rect(0, 0, width, height), quality, it)
                }
                savedCount.incrementAndGet()
            } catch (t: Throwable) {
                Log.e("ARTrack", "jpeg write failed", t)
                droppedCount.incrementAndGet()
                inFlight.decrementAndGet()
                return@execute
            }
            try {
                writeExif(file, tags)
            } catch (t: Throwable) {
                // The photo itself is fine; only its metadata is missing.
                Log.w("ARTrack", "exif write failed", t)
                exifFailures.incrementAndGet()
            } finally {
                inFlight.decrementAndGet()
            }
        }
        return name
    }

    private fun writeExif(file: File, tags: Tags) {
        val exif = ExifInterface(file)
        tags.fix?.let { exif.setGpsInfo(it) }
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "ARTrack ${BuildConfig.VERSION_NAME}")
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString()
        )
        val stamp = EXIF_DATE.format(Date(tags.wallClockMs))
        exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
        if (tags.focal35mm > 0.0) {
            exif.setAttribute(
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                tags.focal35mm.roundToInt().toString()
            )
        }
        if (tags.width > 0 && tags.height > 0) {
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, tags.width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, tags.height.toString())
        }
        exif.saveAttributes()
    }

    /** Waits for the queued frames to hit the disk. */
    fun shutdown() {
        executor.shutdown()
        runCatching { executor.awaitTermination(120, TimeUnit.SECONDS) }
    }

    private companion object {
        const val MAX_IN_FLIGHT = 3
        val EXIF_DATE = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    }
}
