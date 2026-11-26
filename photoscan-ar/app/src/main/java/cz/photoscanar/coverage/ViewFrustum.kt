package cz.photoscanar.coverage

import cz.photoscanar.geom.Quat
import cz.photoscanar.geom.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.tan

/** A camera pose plus its field of view, used for visibility tests. */
class ViewFrustum(
    val position: Vec3,
    val rotation: Quat,
    val hFovRad: Float,
    val vFovRad: Float,
    val nearM: Float,
    val farM: Float
) {
    private val forward = rotation.forward()
    private val right = rotation.right()
    private val up = rotation.up()

    val viewDirection: Vec3 get() = forward

    /** True when [p] lies inside the frustum. */
    fun contains(p: Vec3): Boolean {
        val d = p - position
        val depth = d.dot(forward)
        if (depth < nearM || depth > farM) return false
        val h = atan2(d.dot(right), depth)
        if (abs(h) > hFovRad / 2f) return false
        val v = atan2(d.dot(up), depth)
        return abs(v) <= vFovRad / 2f
    }

    /** Half-width of the frustum at a given depth, in metres. */
    fun halfWidthAt(depthM: Float): Float = depthM * tan(hFovRad / 2f)

    /** Half-height of the frustum at a given depth, in metres. */
    fun halfHeightAt(depthM: Float): Float = depthM * tan(vFovRad / 2f)
}
