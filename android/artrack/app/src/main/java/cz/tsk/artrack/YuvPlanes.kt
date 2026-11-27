package cz.tsk.artrack

/**
 * The raw YUV_420_888 planes of one camera frame, copied out of the Image.
 *
 * The copy happens on the render thread (the Image must be closed before the next
 * ARCore update), but it is only three bulk memcpys. Turning the planes into NV21
 * - the part with per-pixel work - then runs on the JPEG worker thread.
 */
class YuvPlanes(
    private val y: ByteArray,
    private val yRowStride: Int,
    private val yPixelStride: Int,
    private val u: ByteArray,
    private val uRowStride: Int,
    private val uPixelStride: Int,
    private val v: ByteArray,
    private val vRowStride: Int,
    private val vPixelStride: Int,
    val width: Int,
    val height: Int
) {

    /**
     * True when the U plane is the V plane shifted by one byte, i.e. both are views
     * of a single interleaved chroma buffer. Checked on samples rather than assumed,
     * because a device with two separate chroma planes would silently get garbage.
     */
    private fun looksInterleaved(): Boolean {
        if (v.size < 2 || u.size < 1) return false
        var checked = 0
        var index = 0
        while (checked < 8 && index + 1 < v.size && index < u.size) {
            if (v[index + 1] != u[index]) return false
            index += maxOf(1, v.size / 8)
            checked++
        }
        return checked > 0
    }

    /** NV21 = all luma, then V and U interleaved at half resolution. */
    fun toNv21(): ByteArray {
        val out = ByteArray(width * height * 3 / 2)
        var offset = 0

        if (yPixelStride == 1 && yRowStride == width) {
            System.arraycopy(y, 0, out, 0, width * height)
            offset = width * height
        } else if (yPixelStride == 1) {
            for (row in 0 until height) {
                System.arraycopy(y, row * yRowStride, out, offset, width)
                offset += width
            }
        } else {
            for (row in 0 until height) {
                var src = row * yRowStride
                for (col in 0 until width) {
                    out[offset++] = y[src]
                    src += yPixelStride
                }
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2

        // Most devices hand out semi-planar chroma (pixelStride 2) where U and V
        // are two views of one interleaved buffer; then reading from the V plane
        // already yields V,U,V,U - exactly NV21 - and a row copies in one go.
        if (vPixelStride == 2 && uPixelStride == 2 && looksInterleaved()) {
            for (row in 0 until chromaHeight) {
                val src = row * vRowStride
                val length = minOf(chromaWidth * 2 - 1, v.size - src)
                if (length > 0) System.arraycopy(v, src, out, offset, length)
                // The very last byte of the row is the U sample the V plane does
                // not cover; take it from the U plane.
                val uIndex = row * uRowStride + (chromaWidth - 1) * 2
                if (length == chromaWidth * 2 - 1 && uIndex < u.size) {
                    out[offset + length] = u[uIndex]
                }
                offset += chromaWidth * 2
            }
            return out
        }

        for (row in 0 until chromaHeight) {
            val uRow = row * uRowStride
            val vRow = row * vRowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRow + col * uPixelStride
                val vIndex = vRow + col * vPixelStride
                out[offset++] = if (vIndex < v.size) v[vIndex] else 0
                out[offset++] = if (uIndex < u.size) u[uIndex] else 0
            }
        }
        return out
    }
}
