package cz.photoscanar.imaging

import kotlin.math.max
import kotlin.math.min

/**
 * An 8-bit grayscale image with an explicit row stride, which is what both
 * Camera2's YUV_420_888 Y plane and a decoded JPEG give us.
 *
 * Pure JVM: no Android types, so every analyser built on it is unit-testable.
 */
class LumaImage(
    val width: Int,
    val height: Int,
    val data: ByteArray,
    val rowStride: Int = width,
    val pixelStride: Int = 1
) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(data.size >= (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride + 1) {
            "buffer too small: ${data.size} for ${width}x$height stride=$rowStride"
        }
    }

    inline fun at(x: Int, y: Int): Int = data[y * rowStride + x * pixelStride].toInt() and 0xFF

    fun atClamped(x: Int, y: Int): Int =
        at(min(width - 1, max(0, x)), min(height - 1, max(0, y)))

    /** True when rows are packed back to back with one byte per pixel. */
    val isPacked: Boolean get() = rowStride == width && pixelStride == 1

    /** Returns a packed copy (stride == width, pixelStride == 1). */
    fun packed(): LumaImage {
        if (isPacked) return this
        val out = ByteArray(width * height)
        var o = 0
        for (y in 0 until height) {
            val base = y * rowStride
            for (x in 0 until width) out[o++] = data[base + x * pixelStride]
        }
        return LumaImage(width, height, out)
    }

    /**
     * Box-averaged downscale by an integer [factor]. Averaging (rather than
     * nearest-neighbour) matters: it keeps the sharpness measure stable instead of
     * aliasing high-frequency detail into noise.
     */
    fun downscale(factor: Int): LumaImage {
        require(factor >= 1)
        if (factor == 1) return packed()
        val w = width / factor
        val h = height / factor
        require(w > 0 && h > 0) { "downscale factor too large" }
        val out = ByteArray(w * h)
        val area = factor * factor
        for (y in 0 until h) {
            val sy = y * factor
            for (x in 0 until w) {
                val sx = x * factor
                var sum = 0
                for (dy in 0 until factor) {
                    val base = (sy + dy) * rowStride
                    for (dx in 0 until factor) {
                        sum += data[base + (sx + dx) * pixelStride].toInt() and 0xFF
                    }
                }
                out[y * w + x] = (sum / area).toByte()
            }
        }
        return LumaImage(w, h, out)
    }

    /** Central crop covering [fraction] of each dimension. */
    fun centerCrop(fraction: Double): LumaImage {
        val f = fraction.coerceIn(0.05, 1.0)
        val cw = max(8, (width * f).toInt())
        val ch = max(8, (height * f).toInt())
        val x0 = (width - cw) / 2
        val y0 = (height - ch) / 2
        val out = ByteArray(cw * ch)
        for (y in 0 until ch) {
            val base = (y0 + y) * rowStride
            for (x in 0 until cw) out[y * cw + x] = data[base + (x0 + x) * pixelStride]
        }
        return LumaImage(cw, ch, out)
    }

    companion object {
        /** Builds an image from ARGB pixels (as returned by Bitmap.getPixels). */
        fun fromArgb(pixels: IntArray, width: Int, height: Int): LumaImage {
            val out = ByteArray(width * height)
            for (i in 0 until width * height) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Rec.601 luma, the same weighting the camera pipeline uses
                out[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
            }
            return LumaImage(width, height, out)
        }
    }
}
