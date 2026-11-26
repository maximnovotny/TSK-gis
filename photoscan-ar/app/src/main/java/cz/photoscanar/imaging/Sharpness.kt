package cz.photoscanar.imaging

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Focus / motion-blur measures.
 *
 * The primary indicator is the variance of the Laplacian response, which is the
 * standard cheap no-reference sharpness estimate: a blurred image has little
 * high-frequency energy, so the second derivative collapses towards zero and its
 * variance drops.
 *
 * Absolute values depend on resolution, scene texture and ISO noise, so the app
 * uses them as a *relative* indicator within one session and normalises against a
 * configurable threshold rather than claiming an absolute "sharp/not sharp" verdict.
 */
object Sharpness {

    /**
     * Variance of the 4-neighbour Laplacian over the image.
     * [step] subsamples the evaluation grid (1 = every pixel) to bound the cost.
     */
    fun laplacianVariance(img: LumaImage, step: Int = 1): Double {
        require(step >= 1)
        if (img.width < 3 || img.height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0L
        var y = 1
        while (y < img.height - 1) {
            var x = 1
            while (x < img.width - 1) {
                val lap = (img.at(x - 1, y) + img.at(x + 1, y) +
                    img.at(x, y - 1) + img.at(x, y + 1) - 4 * img.at(x, y)).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
                x += step
            }
            y += step
        }
        if (n < 2) return 0.0
        val mean = sum / n
        return (sumSq / n) - mean * mean
    }

    /**
     * Tenengrad (Sobel gradient energy). More robust to impulse noise than the
     * Laplacian, used as a cross-check so a single noisy frame is not reported sharp.
     */
    fun tenengrad(img: LumaImage, step: Int = 2): Double {
        require(step >= 1)
        if (img.width < 3 || img.height < 3) return 0.0
        var sum = 0.0
        var n = 0L
        var y = 1
        while (y < img.height - 1) {
            var x = 1
            while (x < img.width - 1) {
                val gx = (img.at(x + 1, y - 1) + 2 * img.at(x + 1, y) + img.at(x + 1, y + 1)) -
                    (img.at(x - 1, y - 1) + 2 * img.at(x - 1, y) + img.at(x - 1, y + 1))
                val gy = (img.at(x - 1, y + 1) + 2 * img.at(x, y + 1) + img.at(x + 1, y + 1)) -
                    (img.at(x - 1, y - 1) + 2 * img.at(x, y - 1) + img.at(x + 1, y - 1))
                sum += (gx * gx + gy * gy).toDouble()
                n++
                x += step
            }
            y += step
        }
        return if (n == 0L) 0.0 else sqrt(sum / n)
    }

    /**
     * Directional blur hint: ratio of horizontal to vertical gradient energy.
     * A value far from 1 together with low overall sharpness suggests motion blur
     * (smearing along one axis) rather than plain defocus.
     */
    fun directionalRatio(img: LumaImage, step: Int = 3): Double {
        if (img.width < 3 || img.height < 3) return 1.0
        var eh = 0.0
        var ev = 0.0
        // the difference is taken over the same distance as the sampling stride:
        // a 1-pixel difference evaluated every `step` pixels aliases badly on
        // regular structures such as cladding or formwork
        val d = step.coerceAtLeast(1)
        var y = d
        while (y < img.height - d) {
            var x = d
            while (x < img.width - d) {
                eh += abs(img.at(x + d, y) - img.at(x - d, y)).toDouble()
                ev += abs(img.at(x, y + d) - img.at(x, y - d)).toDouble()
                x += step
            }
            y += step
        }
        if (ev < 1e-6) return if (eh < 1e-6) 1.0 else 99.0
        return eh / ev
    }
}
