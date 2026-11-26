package cz.photoscanar.model

/** Flags attached to a photo by the asynchronous quality analyser. */
enum class QualityFlag { BLURRY, DARK, BRIGHT, LOW_CONTRAST, DUPLICATE, LOW_OVERLAP, FAST_MOTION }

/**
 * Result of the asynchronous quality pass over a saved photo.
 *
 * Nothing here ever deletes an original file: a photo marked [recommendRetake]
 * is only flagged in the metadata and in the HUD.
 */
data class QualityMetrics(
    val analysed: Boolean,
    /** Variance of the Laplacian response - higher means sharper. */
    val laplacianVariance: Double = 0.0,
    /** Same measure normalised to 0..1 against the configured acceptance threshold. */
    val sharpnessScore: Double = 0.0,
    val meanLuma: Double = 0.0,
    val clippedLowPct: Double = 0.0,
    val clippedHighPct: Double = 0.0,
    val contrast: Double = 0.0,
    /** Estimated overlap with the previous accepted photo, 0..1, negative when unknown. */
    val overlapWithPrevious: Double = -1.0,
    val flags: Set<QualityFlag> = emptySet(),
    val analysisDurationMs: Long = 0
) {
    val recommendRetake: Boolean
        get() = QualityFlag.BLURRY in flags || QualityFlag.DARK in flags || QualityFlag.BRIGHT in flags

    val ok: Boolean get() = analysed && !recommendRetake

    companion object {
        val NOT_ANALYSED = QualityMetrics(analysed = false)
    }
}
