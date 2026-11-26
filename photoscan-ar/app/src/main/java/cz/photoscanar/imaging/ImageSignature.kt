package cz.photoscanar.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A tiny normalised thumbnail used for image-to-image matching.
 *
 * Full feature matching (SIFT/ORB) is far too expensive to run between every pair
 * of frames at two photos per second on a phone, so the overlap indicator uses a
 * zero-mean normalised cross correlation over a small luma grid with a shift
 * search. That is enough to tell "almost the same frame" from "moved on" and to
 * produce a usable overlap percentage, but it is an *indicator*, not a
 * photogrammetric tie-point count.
 */
class ImageSignature(val width: Int, val height: Int, val values: FloatArray) {

    init {
        require(values.size == width * height)
    }

    companion object {
        const val TIE_EPSILON = 0.004
        const val GRID_W = 32
        const val GRID_H = 24

        fun from(img: LumaImage, gridW: Int = GRID_W, gridH: Int = GRID_H): ImageSignature {
            val out = FloatArray(gridW * gridH)
            // area-average each cell so the signature is robust to noise
            for (gy in 0 until gridH) {
                val y0 = gy * img.height / gridH
                val y1 = max(y0 + 1, (gy + 1) * img.height / gridH)
                for (gx in 0 until gridW) {
                    val x0 = gx * img.width / gridW
                    val x1 = max(x0 + 1, (gx + 1) * img.width / gridW)
                    var sum = 0L
                    var n = 0
                    for (y in y0 until y1) {
                        for (x in x0 until x1) { sum += img.at(x, y); n++ }
                    }
                    out[gy * gridW + gx] = (sum.toDouble() / n).toFloat()
                }
            }
            return ImageSignature(gridW, gridH, out)
        }
    }

    /** Mean absolute difference, 0..255. Cheap duplicate screen. */
    fun meanAbsDiff(other: ImageSignature): Double {
        require(other.width == width && other.height == height)
        var s = 0.0
        for (i in values.indices) s += abs(values[i] - other.values[i]).toDouble()
        return s / values.size
    }

    /**
     * Zero-mean normalised cross correlation over the overlapping region after
     * shifting [other] by (dx, dy) grid cells. Returns -1..1.
     */
    fun zncc(other: ImageSignature, dx: Int, dy: Int): Double {
        require(other.width == width && other.height == height)
        val xs = max(0, dx); val xe = min(width, width + dx)
        val ys = max(0, dy); val ye = min(height, height + dy)
        if (xe - xs < 4 || ye - ys < 4) return -1.0
        var sa = 0.0; var sb = 0.0; var n = 0
        for (y in ys until ye) for (x in xs until xe) {
            sa += values[y * width + x]
            sb += other.values[(y - dy) * width + (x - dx)]
            n++
        }
        val ma = sa / n; val mb = sb / n
        var num = 0.0; var da = 0.0; var db = 0.0
        for (y in ys until ye) for (x in xs until xe) {
            val a = values[y * width + x] - ma
            val b = other.values[(y - dy) * width + (x - dx)] - mb
            num += a * b; da += a * a; db += b * b
        }
        if (da < 1e-6 || db < 1e-6) return 0.0
        return num / sqrt(da * db)
    }

    /**
     * Best correlation over a bounded shift search, plus the winning shift.
     *
     * Repetitive content (brickwork, cladding, formwork panels, railings) matches
     * equally well at several shifts, so near-equal candidates are broken in
     * favour of the smallest displacement. Without that tie-break a facade of
     * identical panels would report a large apparent movement - and therefore a
     * low overlap - while the operator was standing still.
     */
    fun bestMatch(other: ImageSignature, maxShiftX: Int = 12, maxShiftY: Int = 9): Match {
        var best = Match(-1.0, 0, 0)
        for (dy in -maxShiftY..maxShiftY) {
            for (dx in -maxShiftX..maxShiftX) {
                val c = zncc(other, dx, dy)
                if (c > best.correlation + TIE_EPSILON) {
                    best = Match(c, dx, dy)
                } else if (c > best.correlation - TIE_EPSILON &&
                    abs(dx) + abs(dy) < abs(best.shiftX) + abs(best.shiftY)
                ) {
                    best = Match(c, dx, dy)
                }
            }
        }
        return best
    }

    data class Match(val correlation: Double, val shiftX: Int, val shiftY: Int)

    /**
     * Overlap fraction estimated purely from images: the fraction of the frame
     * area still shared after the best-matching shift, damped by how well the
     * shifted frames actually correlate.
     */
    fun imageOverlap(other: ImageSignature): Double {
        val m = bestMatch(other)
        if (m.correlation < 0.25) return 0.0
        val ox = (width - abs(m.shiftX)).toDouble() / width
        val oy = (height - abs(m.shiftY)).toDouble() / height
        val geometric = (ox * oy).coerceIn(0.0, 1.0)
        // a poor correlation means the content changed even if the shift was small
        val confidence = ((m.correlation - 0.25) / 0.6).coerceIn(0.0, 1.0)
        return geometric * (0.55 + 0.45 * confidence)
    }
}
