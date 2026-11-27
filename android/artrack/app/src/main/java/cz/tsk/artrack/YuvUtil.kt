package cz.tsk.artrack

import android.media.Image

/** Gets the YUV planes of an ARCore camera image out of the Image as fast as possible. */
object YuvUtil {

    /**
     * Three bulk copies, nothing per-pixel: this runs on the render thread, where
     * every millisecond costs tracking frames.
     */
    fun copyPlanes(image: Image): YuvPlanes {
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        return YuvPlanes(
            y = copyOf(y.buffer),
            yRowStride = y.rowStride,
            yPixelStride = y.pixelStride,
            u = copyOf(u.buffer),
            uRowStride = u.rowStride,
            uPixelStride = u.pixelStride,
            v = copyOf(v.buffer),
            vRowStride = v.rowStride,
            vPixelStride = v.pixelStride,
            width = image.width,
            height = image.height
        )
    }

    private fun copyOf(buffer: java.nio.ByteBuffer): ByteArray {
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}
