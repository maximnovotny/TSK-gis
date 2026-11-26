package cz.photoscanar.coverage

/**
 * Coverage verdict for one cell of either coverage model.
 *
 * GREEN/YELLOW/RED describe how many distinct viewpoints have seen a cell. They
 * are an acquisition aid, not a reconstruction guarantee: a green cell only means
 * the cell was inside the field of view of at least [CoverageConfig.greenViews]
 * photos taken from sufficiently different directions.
 */
enum class CoverageState { RED, YELLOW, GREEN }

data class CoverageConfig(
    val greenViews: Int = 3,
    val yellowViews: Int = 1,
    /** two views count as distinct only beyond this angular separation (radians) */
    val minDistinctAngleRad: Float = 0.12f,
    val voxelSizeM: Float = 0.25f,
    val azimuthBins: Int = 24,
    val elevationBins: Int = 6,
    val nearPlaneM: Float = 0.25f,
    val farPlaneM: Float = 30f
) {
    fun stateFor(distinctViews: Int): CoverageState = when {
        distinctViews >= greenViews -> CoverageState.GREEN
        distinctViews >= yellowViews -> CoverageState.YELLOW
        else -> CoverageState.RED
    }
}

data class CoverageSummary(
    val cells: Int,
    val green: Int,
    val yellow: Int,
    val red: Int,
    /** true when this came from geometry (ARCore feature points), false when it is
     *  only inferred from the phone's trajectory */
    val geometricallyVerified: Boolean
) {
    val greenPct: Double get() = if (cells == 0) 0.0 else green * 100.0 / cells
    val yellowPct: Double get() = if (cells == 0) 0.0 else yellow * 100.0 / cells
    val redPct: Double get() = if (cells == 0) 0.0 else red * 100.0 / cells

    companion object {
        val EMPTY = CoverageSummary(0, 0, 0, 0, false)
    }
}
