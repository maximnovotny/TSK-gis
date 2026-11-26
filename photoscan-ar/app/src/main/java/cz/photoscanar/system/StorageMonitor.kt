package cz.photoscanar.system

import java.io.File

/**
 * Watches free space on the volume the project is being written to.
 *
 * A long survey at two full-resolution photos per second eats roughly 0.5 GB per
 * minute, so running out of space is a realistic failure and the session has to
 * stop cleanly rather than leave half-written files.
 */
class StorageMonitor(private val dir: File) {

    /** Stop capturing below this much free space. */
    var reserveBytes: Long = 400L * 1024 * 1024

    private var averagePhotoBytes: Long = 4L * 1024 * 1024

    fun onPhotoWritten(bytes: Long) {
        if (bytes <= 0) return
        // exponential moving average, so the estimate adapts to the real file size
        averagePhotoBytes = (averagePhotoBytes * 7 + bytes) / 8
    }

    fun freeBytes(): Long = try {
        dir.mkdirs()
        dir.usableSpace
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    fun isLow(): Boolean = freeBytes() < reserveBytes

    fun estimatedRemainingPhotos(): Int {
        val free = freeBytes() - reserveBytes
        if (free <= 0) return 0
        return (free / averagePhotoBytes.coerceAtLeast(1)).toInt()
    }

    fun averagePhotoSizeBytes(): Long = averagePhotoBytes

    companion object {
        fun humanReadable(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
            else -> "%d kB".format(bytes / 1024)
        }
    }
}
