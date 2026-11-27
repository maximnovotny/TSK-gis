package cz.tsk.artrack

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The geodesy needed to put an ARCore trajectory on a map: a forward UTM
 * projection and a least squares fit of the one unknown ARCore leaves open -
 * the azimuth of its world frame.
 */
object Geo {

    private const val A = 6378137.0                   // WGS84 semi-major axis [m]
    private const val F = 1.0 / 298.257223563         // WGS84 flattening
    private const val K0 = 0.9996                     // UTM scale factor

    fun utmZoneFor(lon: Double): Int = floor((lon + 180.0) / 6.0).toInt() + 1

    fun epsgFor(lat: Double, zone: Int): Int = (if (lat >= 0) 32600 else 32700) + zone

    /**
     * Forward UTM for WGS84 (Snyder, USGS Professional Paper 1395).
     * Returns [easting, northing] in metres.
     */
    fun latLonToUtm(lat: Double, lon: Double, zone: Int): DoubleArray {
        val e2 = 2 * F - F * F
        val ep2 = e2 / (1 - e2)
        val latR = Math.toRadians(lat)
        val lonR = Math.toRadians(lon)
        val lon0 = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())

        val sinLat = sin(latR)
        val cosLat = cos(latR)
        val tanLat = tan(latR)

        val n = A / sqrt(1 - e2 * sinLat * sinLat)
        val t = tanLat * tanLat
        val c = ep2 * cosLat * cosLat
        val a = (lonR - lon0) * cosLat

        val m = A * (
            (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * latR
                - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * latR)
                + (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * latR)
                - (35 * e2 * e2 * e2 / 3072) * sin(6 * latR)
            )

        val easting = 500000.0 + K0 * n * (
            a + (1 - t + c) * a * a * a / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a * a * a * a * a / 120
            )
        var northing = K0 * (
            m + n * tanLat * (
                a * a / 2 +
                    (5 - t + 9 * c + 4 * c * c) * a * a * a * a / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * ep2) *
                    a * a * a * a * a * a / 720
                )
            )
        if (lat < 0) northing += 10000000.0
        return doubleArrayOf(easting, northing)
    }

    /**
     * ARCore's world is Y-up and right handed, so (x, -z) is the horizontal pair
     * that keeps the handedness of (east, north, up). Using (x, z) would mirror
     * the whole track.
     */
    fun arHorizontal(tx: Double, tz: Double): DoubleArray = doubleArrayOf(tx, -tz)

    class Fit(
        val theta: Double,
        val e0: Double,
        val n0: Double,
        val rms: Double,
        val extent: Double
    ) {
        val headingDeg: Double get() = (Math.toDegrees(theta) + 360.0) % 360.0
        /** Rough azimuth uncertainty: the fit residual spread over the track length. */
        val sigmaDeg: Double?
            get() = if (extent > 0.1) Math.toDegrees(rms / extent) else null
    }

    /**
     * Rotation about the vertical axis plus translation, scale locked at 1.
     * The scale is deliberately not fitted: ARCore's metric scale is far better
     * than anything a handful of GPS fixes could say about it.
     */
    fun fitHeading(ar: List<DoubleArray>, enu: List<DoubleArray>): Fit {
        val n = ar.size
        val ax = ar.sumOf { it[0] } / n
        val ay = ar.sumOf { it[1] } / n
        val ex = enu.sumOf { it[0] } / n
        val ey = enu.sumOf { it[1] } / n
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = ar[i][0] - ax
            val dy = ar[i][1] - ay
            val de = enu[i][0] - ex
            val dn = enu[i][1] - ey
            num += dx * dn - dy * de       // cross product
            den += dx * de + dy * dn       // dot product
        }
        val theta = atan2(num, den)
        val cosT = cos(theta)
        val sinT = sin(theta)
        val e0 = ex - (cosT * ax - sinT * ay)
        val n0 = ey - (sinT * ax + cosT * ay)
        var sq = 0.0
        for (i in 0 until n) {
            val pe = e0 + cosT * ar[i][0] - sinT * ar[i][1]
            val pn = n0 + sinT * ar[i][0] + cosT * ar[i][1]
            sq += (pe - enu[i][0]) * (pe - enu[i][0]) + (pn - enu[i][1]) * (pn - enu[i][1])
        }
        var extent = 0.0
        for (i in ar.indices) {
            for (j in i + 1 until ar.size) {
                val d = hypot(ar[i][0] - ar[j][0], ar[i][1] - ar[j][1])
                if (d > extent) extent = d
            }
        }
        return Fit(theta, e0, n0, sqrt(sq / n), extent)
    }

    /** Projects an AR position with a fitted transform. Returns [x, y, z]. */
    fun project(fit: Fit, tx: Double, ty: Double, tz: Double, altOffset: Double): DoubleArray {
        val h = arHorizontal(tx, tz)
        val c = cos(fit.theta)
        val s = sin(fit.theta)
        return doubleArrayOf(
            fit.e0 + c * h[0] - s * h[1],
            fit.n0 + s * h[0] + c * h[1],
            ty + altOffset
        )
    }
}
