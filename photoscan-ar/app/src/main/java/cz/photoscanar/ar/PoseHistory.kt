package cz.photoscanar.ar

import cz.photoscanar.geom.Vec3
import cz.photoscanar.model.PoseSample
import kotlin.math.abs

/**
 * Lock-protected ring buffer of poses.
 *
 * Written from the GL thread (where ARCore is updated) and read from the camera
 * thread (to stamp a photo) and the UI thread (to draw), so it has to be cheap
 * and thread-safe. A photo is matched to the pose nearest in time, and the offset
 * is exported so a consumer can judge how stale the association is.
 */
class PoseHistory(private val capacity: Int) {

    private val lock = Any()
    private val items = arrayOfNulls<PoseSample>(capacity)
    private var head = 0
    private var size = 0

    fun add(sample: PoseSample) = synchronized(lock) {
        items[head] = sample
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() = synchronized(lock) {
        head = 0; size = 0
        java.util.Arrays.fill(items, null)
    }

    fun latest(): PoseSample? = synchronized(lock) {
        if (size == 0) null else items[(head - 1 + capacity) % capacity]
    }

    /** @return the nearest sample and the signed offset (sample - requested), ns */
    fun nearest(timestampNs: Long): Pair<PoseSample, Long>? = synchronized(lock) {
        if (size == 0) return null
        var best: PoseSample? = null
        var bestDiff = Long.MAX_VALUE
        for (i in 0 until size) {
            val s = items[(head - 1 - i + capacity * 2) % capacity] ?: continue
            val diff = abs(s.timestampNs - timestampNs)
            if (diff < bestDiff) { bestDiff = diff; best = s }
            // samples are ordered, so once we start moving away we can stop
            else if (best != null && s.timestampNs < timestampNs) break
        }
        val b = best ?: return null
        return b to (b.timestampNs - timestampNs)
    }

    fun positions(maxPoints: Int): List<Vec3> = synchronized(lock) {
        if (size == 0) return emptyList()
        val step = maxOf(1, size / maxPoints)
        val out = ArrayList<Vec3>(minOf(size, maxPoints) + 1)
        var i = 0
        while (i < size) {
            val s = items[(head - size + i + capacity * 2) % capacity]
            if (s != null && s.valid) out.add(s.position)
            i += step
        }
        return out
    }

    fun count(): Int = synchronized(lock) { size }
}
