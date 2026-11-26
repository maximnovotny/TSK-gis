package cz.photoscanar.geom

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Minimal vector / quaternion maths. Deliberately free of any Android dependency so
 * that it can be covered by plain JVM unit tests.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-9f) Vec3(0f, 0f, 0f) else Vec3(x / l, y / l, z / l)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        fun centroid(points: List<Vec3>): Vec3 {
            if (points.isEmpty()) return ZERO
            var sx = 0f; var sy = 0f; var sz = 0f
            for (p in points) { sx += p.x; sy += p.y; sz += p.z }
            val n = points.size.toFloat()
            return Vec3(sx / n, sy / n, sz / n)
        }
    }
}

/** Unit quaternion in (x, y, z, w) order, matching the ARCore convention. */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {

    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l < 1e-9f) IDENTITY else Quat(x / l, y / l, z / l, w / l)
    }

    /** Rotates [v] by this quaternion. */
    fun rotate(v: Vec3): Vec3 {
        val q = normalized()
        val u = Vec3(q.x, q.y, q.z)
        val s = q.w
        return u * (2f * u.dot(v)) + v * (s * s - u.dot(u)) + u.cross(v) * (2f * s)
    }

    /**
     * Camera forward axis in world space. ARCore (like OpenGL) looks down its local -Z axis.
     */
    fun forward(): Vec3 = rotate(Vec3(0f, 0f, -1f)).normalized()

    fun right(): Vec3 = rotate(Vec3(1f, 0f, 0f)).normalized()

    fun up(): Vec3 = rotate(Vec3(0f, 1f, 0f)).normalized()

    /** Smallest rotation angle between two orientations, in radians. */
    fun angleTo(o: Quat): Float {
        val a = normalized(); val b = o.normalized()
        val d = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w)
        return 2f * acos(min(1f, max(-1f, d)))
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)
    }
}

/** Angle between two direction vectors, in radians. */
fun angleBetween(a: Vec3, b: Vec3): Float {
    val na = a.normalized(); val nb = b.normalized()
    val d = na.dot(nb)
    return acos(min(1f, max(-1f, d)))
}
