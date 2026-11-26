package cz.photoscanar.coverage

import cz.photoscanar.geom.Vec3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The *indicative* coverage model: a shell of viewing directions around an
 * assumed object centre, filled in from where the photos were taken.
 *
 * Unlike [VoxelCoverage] this uses no scene geometry at all - it only knows
 * where the phone was. It therefore answers "have I walked around the object
 * and shot it from all sides" and nothing more. The UI labels it as an estimate
 * and the export marks it `geometrically_verified = false`.
 */
class DirectionShell(private val config: CoverageConfig = CoverageConfig()) {

    private val counts = IntArray(config.azimuthBins * config.elevationBins)

    /**
     * Elevation bands the operator has actually shot from.
     *
     * Only these are scored. A hand-held survey walks one or two rings around the
     * subject; counting the straight-up and straight-down bands as "uncovered"
     * would peg the figure at a quarter however carefully the object was shot,
     * and would keep pointing the operator at directions nobody can stand in.
     */
    private val visitedElevation = BooleanArray(config.elevationBins)
    private var center: Vec3? = null
    private val viewPositions = ArrayList<Vec3>(512)

    val cellCount: Int get() = counts.size

    /** Sets the assumed object centre; recomputes the shell from the stored views. */
    fun setCenter(c: Vec3) {
        if (center == c) return
        center = c
        counts.fill(0)
        visitedElevation.fill(false)
        for (p in viewPositions) accumulate(p)
    }

    fun centerOrNull(): Vec3? = center

    fun addView(cameraPosition: Vec3) {
        if (viewPositions.size < 20000) viewPositions.add(cameraPosition)
        if (center == null) {
            // until an object centre is known, use the mean of the camera path
            center = Vec3.centroid(viewPositions)
            counts.fill(0)
            visitedElevation.fill(false)
            for (p in viewPositions) accumulate(p)
        } else {
            accumulate(cameraPosition)
        }
    }

    private fun accumulate(cameraPosition: Vec3) {
        val c = center ?: return
        val d = cameraPosition - c
        if (d.length() < 1e-3f) return
        val idx = binOf(d)
        if (idx >= 0) {
            counts[idx]++
            visitedElevation[idx / config.azimuthBins] = true
        }
    }

    internal fun binOf(direction: Vec3): Int {
        val n = direction.normalized()
        // azimuth around the world up axis (ARCore world Y is up)
        var az = atan2(n.z.toDouble(), n.x.toDouble())
        if (az < 0) az += 2 * PI
        val el = asin(n.y.toDouble().coerceIn(-1.0, 1.0))   // -pi/2 .. pi/2
        val ai = ((az / (2 * PI)) * config.azimuthBins).toInt().coerceIn(0, config.azimuthBins - 1)
        val ei = (((el + PI / 2) / PI) * config.elevationBins).toInt()
            .coerceIn(0, config.elevationBins - 1)
        return ei * config.azimuthBins + ai
    }

    fun state(azimuthIndex: Int, elevationIndex: Int): CoverageState =
        config.stateFor(counts[elevationIndex * config.azimuthBins + azimuthIndex])

    /** Bands to score: the ones used, or the realistically reachable window. */
    private fun scoredBands(): List<Int> {
        val used = (0 until config.elevationBins).filter { visitedElevation[it] }
        if (used.isNotEmpty()) return used
        val lo = config.elevationBins / 6
        val hi = config.elevationBins - 1 - config.elevationBins / 6
        return (lo..hi).toList()
    }

    /** How many elevation bands were shot from; one ring is one band. */
    fun visitedElevationBands(): Int = visitedElevation.count { it }

    fun summary(): CoverageSummary {
        var g = 0; var y = 0; var r = 0; var n = 0
        for (ei in scoredBands()) {
            for (ai in 0 until config.azimuthBins) {
                n++
                when (state(ai, ei)) {
                    CoverageState.GREEN -> g++
                    CoverageState.YELLOW -> y++
                    CoverageState.RED -> r++
                }
            }
        }
        return CoverageSummary(n, g, y, r, geometricallyVerified = false)
    }

    /** Direction (unit vector from the centre) of the weakest nearby cell. */
    fun worstDirectionNear(currentCameraPosition: Vec3): Vec3? {
        val c = center ?: return null
        val cur = (currentCameraPosition - c)
        if (cur.length() < 1e-3f) return null
        val curBin = binOf(cur)
        val curAz = curBin % config.azimuthBins
        val curEl = curBin / config.azimuthBins
        var bestScore = Int.MAX_VALUE
        var bestAz = -1
        var bestEl = -1
        for (ei in scoredBands()) {
            for (ai in 0 until config.azimuthBins) {
                val count = counts[ei * config.azimuthBins + ai]
                if (count >= config.greenViews) continue
                var dAz = kotlin.math.abs(ai - curAz)
                if (dAz > config.azimuthBins / 2) dAz = config.azimuthBins - dAz
                val dEl = kotlin.math.abs(ei - curEl)
                // prefer empty cells, then nearby ones
                val score = count * 100 + dAz * 4 + dEl * 3
                if (score < bestScore) { bestScore = score; bestAz = ai; bestEl = ei }
            }
        }
        if (bestAz < 0) return null
        val az = (bestAz + 0.5) / config.azimuthBins * 2 * PI
        val el = (bestEl + 0.5) / config.elevationBins * PI - PI / 2
        return Vec3(
            (cos(el) * cos(az)).toFloat(),
            sin(el).toFloat(),
            (cos(el) * sin(az)).toFloat()
        ).normalized()
    }

    fun reset() {
        counts.fill(0)
        viewPositions.clear()
        center = null
    }
}
