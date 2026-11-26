package cz.photoscanar.apriltag

/**
 * 3x3 planar homography estimated from exactly four point correspondences
 * (direct linear transform with h33 fixed to 1, solved by Gaussian elimination
 * with partial pivoting).
 */
class Homography(private val h: DoubleArray) {

    /** Maps a point in source space to destination space. */
    fun map(u: Double, v: Double): DoubleArray {
        val w = h[6] * u + h[7] * v + 1.0
        if (w == 0.0) return doubleArrayOf(Double.NaN, Double.NaN)
        return doubleArrayOf(
            (h[0] * u + h[1] * v + h[2]) / w,
            (h[3] * u + h[4] * v + h[5]) / w
        )
    }

    companion object {
        /**
         * @param src four source points as x0,y0,x1,y1,...
         * @param dst four destination points, same order
         * @return the homography, or null when the system is degenerate
         */
        fun fromFourPoints(src: DoubleArray, dst: DoubleArray): Homography? {
            require(src.size == 8 && dst.size == 8)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val u = src[i * 2]; val v = src[i * 2 + 1]
                val x = dst[i * 2]; val y = dst[i * 2 + 1]
                val r0 = a[i * 2]
                r0[0] = u; r0[1] = v; r0[2] = 1.0; r0[3] = 0.0; r0[4] = 0.0; r0[5] = 0.0
                r0[6] = -u * x; r0[7] = -v * x; r0[8] = x
                val r1 = a[i * 2 + 1]
                r1[0] = 0.0; r1[1] = 0.0; r1[2] = 0.0; r1[3] = u; r1[4] = v; r1[5] = 1.0
                r1[6] = -u * y; r1[7] = -v * y; r1[8] = y
            }
            // Gaussian elimination with partial pivoting
            for (col in 0 until 8) {
                var piv = col
                for (r in col + 1 until 8) {
                    if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[piv][col])) piv = r
                }
                if (kotlin.math.abs(a[piv][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
                val d = a[col][col]
                for (c in col until 9) a[col][c] /= d
                for (r in 0 until 8) {
                    if (r == col) continue
                    val f = a[r][col]
                    if (f == 0.0) continue
                    for (c in col until 9) a[r][c] -= f * a[col][c]
                }
            }
            val h = DoubleArray(8) { a[it][8] }
            return Homography(h)
        }
    }
}
