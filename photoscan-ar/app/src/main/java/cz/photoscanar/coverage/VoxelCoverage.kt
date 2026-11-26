package cz.photoscanar.coverage

import cz.photoscanar.geom.Vec3
import cz.photoscanar.geom.angleBetween

/**
 * Geometric coverage: a sparse voxel grid seeded by ARCore feature points,
 * where each voxel counts how many *distinct* viewpoints have actually seen it.
 *
 * This is the only coverage figure the app is allowed to call "verified", and
 * even then only in the weak sense that the voxel contained reconstructed 3D
 * points and fell inside the field of view of several photos. It is not a
 * photogrammetric reconstruction check.
 */
class VoxelCoverage(private val config: CoverageConfig = CoverageConfig()) {

    private class Cell {
        var points = 0
        val viewDirections = ArrayList<Vec3>(8)
        var distinctViews = 0
    }

    private val cells = HashMap<Long, Cell>()

    val voxelCount: Int get() = cells.size

    private fun key(p: Vec3): Long {
        val s = config.voxelSizeM
        val ix = kotlin.math.floor(p.x / s).toInt()
        val iy = kotlin.math.floor(p.y / s).toInt()
        val iz = kotlin.math.floor(p.z / s).toInt()
        return pack(ix, iy, iz)
    }

    private fun pack(ix: Int, iy: Int, iz: Int): Long =
        ((ix.toLong() and 0x1FFFFF) shl 42) or ((iy.toLong() and 0x1FFFFF) shl 21) or (iz.toLong() and 0x1FFFFF)

    private fun unpack(k: Long): Vec3 {
        fun sign21(v: Long): Int {
            val x = (v and 0x1FFFFF).toInt()
            return if (x >= 0x100000) x - 0x200000 else x
        }
        val ix = sign21(k shr 42)
        val iy = sign21(k shr 21)
        val iz = sign21(k)
        val s = config.voxelSizeM
        return Vec3((ix + 0.5f) * s, (iy + 0.5f) * s, (iz + 0.5f) * s)
    }

    /** Seeds voxels from the AR point cloud. */
    fun addPoints(points: List<Vec3>, minConfidence: Float = 0f, confidences: FloatArray? = null) {
        for (i in points.indices) {
            if (confidences != null && confidences.getOrElse(i) { 1f } < minConfidence) continue
            val c = cells.getOrPut(key(points[i])) { Cell() }
            c.points++
        }
    }

    /**
     * Records that a photo was taken from [frustum]; every seeded voxel inside the
     * frustum gains a view, but only if the new viewing direction differs from the
     * ones already recorded for that voxel (otherwise standing still would turn
     * everything green).
     */
    fun addView(frustum: ViewFrustum) {
        for ((k, cell) in cells) {
            val center = unpack(k)
            if (!frustum.contains(center)) continue
            val dir = (center - frustum.position).normalized()
            var distinct = true
            for (existing in cell.viewDirections) {
                if (angleBetween(existing, dir) < config.minDistinctAngleRad) { distinct = false; break }
            }
            if (distinct) {
                if (cell.viewDirections.size < 64) cell.viewDirections.add(dir)
                cell.distinctViews++
            }
        }
    }

    fun summary(): CoverageSummary {
        var g = 0; var y = 0; var r = 0
        for (c in cells.values) {
            when (config.stateFor(c.distinctViews)) {
                CoverageState.GREEN -> g++
                CoverageState.YELLOW -> y++
                CoverageState.RED -> r++
            }
        }
        return CoverageSummary(cells.size, g, y, r, geometricallyVerified = true)
    }

    /** Voxel centres with their state, for the AR overlay. */
    fun snapshot(maxCells: Int = 4000): List<Pair<Vec3, CoverageState>> {
        val out = ArrayList<Pair<Vec3, CoverageState>>(minOf(maxCells, cells.size))
        for ((k, c) in cells) {
            if (out.size >= maxCells) break
            out.add(unpack(k) to config.stateFor(c.distinctViews))
        }
        return out
    }

    /** Centre of the voxel cloud: a reasonable stand-in for "the object". */
    fun centroid(): Vec3? {
        if (cells.isEmpty()) return null
        var sx = 0f; var sy = 0f; var sz = 0f
        for (k in cells.keys) { val p = unpack(k); sx += p.x; sy += p.y; sz += p.z }
        val n = cells.size.toFloat()
        return Vec3(sx / n, sy / n, sz / n)
    }

    fun clear() = cells.clear()
}
