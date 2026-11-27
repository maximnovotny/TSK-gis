package cz.tsk.artrack

import android.media.Image

/** Converts an ARCore YUV_420_888 camera image into a packed NV21 buffer. */
object YuvUtil {

    fun toNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 3 / 2)

        // Luma: copy row by row, the plane may be padded.
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var offset = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.position(0)
            yBuffer.get(out, 0, width * height)
            offset = width * height
        } else {
            val row = ByteArray(yRowStride)
            for (y in 0 until height) {
                yBuffer.position(y * yRowStride)
                val len = minOf(yRowStride, yBuffer.remaining())
                yBuffer.get(row, 0, len)
                if (yPixelStride == 1) {
                    System.arraycopy(row, 0, out, offset, width)
                    offset += width
                } else {
                    for (x in 0 until width) out[offset++] = row[x * yPixelStride]
                }
            }
        }

        // Chroma: NV21 wants V and U interleaved, V first, at half resolution.
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (y in 0 until chromaHeight) {
            val uRow = y * uPlane.rowStride
            val vRow = y * vPlane.rowStride
            for (x in 0 until chromaWidth) {
                val uIndex = uRow + x * uPlane.pixelStride
                val vIndex = vRow + x * vPlane.pixelStride
                out[offset++] = if (vIndex < vBuffer.limit()) vBuffer.get(vIndex) else 0
                out[offset++] = if (uIndex < uBuffer.limit()) uBuffer.get(uIndex) else 0
            }
        }
        return out
    }
}
