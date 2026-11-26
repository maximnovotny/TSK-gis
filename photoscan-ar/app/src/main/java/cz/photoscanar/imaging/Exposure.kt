package cz.photoscanar.imaging

import kotlin.math.sqrt

data class ExposureStats(
    val meanLuma: Double,
    val stdDev: Double,
    val clippedLowPct: Double,
    val clippedHighPct: Double,
    val p05: Int,
    val p50: Int,
    val p95: Int
) {
    /** Contrast proxy, 0..1. */
    val contrast: Double get() = ((p95 - p05).toDouble() / 255.0).coerceIn(0.0, 1.0)
}

/** Histogram based under/over exposure detection. */
object Exposure {

    const val DARK_MEAN_THRESHOLD = 42.0
    const val BRIGHT_MEAN_THRESHOLD = 215.0
    const val CLIP_LOW_PCT_THRESHOLD = 28.0
    const val CLIP_HIGH_PCT_THRESHOLD = 12.0
    const val LOW_CONTRAST_THRESHOLD = 0.12

    fun analyse(img: LumaImage, step: Int = 2): ExposureStats {
        val hist = IntArray(256)
        var n = 0L
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                hist[img.at(x, y)]++
                n++
                x += step
            }
            y += step
        }
        if (n == 0L) return ExposureStats(0.0, 0.0, 0.0, 0.0, 0, 0, 0)

        var sum = 0.0
        var sumSq = 0.0
        for (v in 0..255) {
            val c = hist[v]
            if (c == 0) continue
            sum += v.toDouble() * c
            sumSq += v.toDouble() * v * c
        }
        val mean = sum / n
        val variance = (sumSq / n) - mean * mean
        val low = (hist[0] + hist[1] + hist[2] + hist[3]).toDouble() * 100.0 / n
        val high = (hist[252] + hist[253] + hist[254] + hist[255]).toDouble() * 100.0 / n

        fun percentile(p: Double): Int {
            val target = (n * p).toLong()
            var acc = 0L
            for (v in 0..255) {
                acc += hist[v]
                if (acc >= target) return v
            }
            return 255
        }

        return ExposureStats(
            meanLuma = mean,
            stdDev = sqrt(if (variance < 0) 0.0 else variance),
            clippedLowPct = low,
            clippedHighPct = high,
            p05 = percentile(0.05),
            p50 = percentile(0.50),
            p95 = percentile(0.95)
        )
    }

    fun isTooDark(s: ExposureStats) =
        s.meanLuma < DARK_MEAN_THRESHOLD || s.clippedLowPct > CLIP_LOW_PCT_THRESHOLD

    fun isTooBright(s: ExposureStats) =
        s.meanLuma > BRIGHT_MEAN_THRESHOLD || s.clippedHighPct > CLIP_HIGH_PCT_THRESHOLD

    fun isLowContrast(s: ExposureStats) = s.contrast < LOW_CONTRAST_THRESHOLD
}
