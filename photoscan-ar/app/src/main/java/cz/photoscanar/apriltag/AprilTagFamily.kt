package cz.photoscanar.apriltag

import java.io.InputStream

/**
 * A coded-target family: the set of valid payloads plus the decoder.
 *
 * The 36h11 codebook shipped in assets/apriltag36h11.txt holds the 587 official
 * tag36h11 payloads (minimum Hamming distance 11 across all four rotations).
 * Each line is the 6x6 inner grid read row-major from the top-left corner, with
 * bit 1 meaning a *white* cell; line index equals the tag id.
 */
class AprilTagFamily(
    val name: String,
    val codes: LongArray,
    val gridSize: Int = 6,
    val maxHammingDistance: Int = 2
) {
    private val bitCount = gridSize * gridSize
    private val mask = if (bitCount >= 64) -1L else (1L shl bitCount) - 1L

    /** code (for every rotation) -> packed id*4 + rotation */
    private val exact = HashMap<Long, Int>(codes.size * 8)
    private val rotated = LongArray(codes.size * 4)

    init {
        for (id in codes.indices) {
            var c = codes[id] and mask
            for (r in 0 until 4) {
                rotated[id * 4 + r] = c
                exact.putIfAbsent(c, id * 4 + r)
                c = rotate90(c)
            }
        }
    }

    data class Decoded(val id: Int, val rotation: Int, val hamming: Int)

    /** Rotates a packed grid by 90 degrees clockwise. */
    fun rotate90(code: Long): Long {
        var out = 0L
        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                // destination (r, c) takes source (gridSize-1-c, r)
                val srcIdx = (gridSize - 1 - c) * gridSize + r
                val bit = (code shr (bitCount - 1 - srcIdx)) and 1L
                out = (out shl 1) or bit
            }
        }
        return out
    }

    /** Decodes a sampled grid, allowing up to [maxHammingDistance] bit errors. */
    fun decode(bits: Long): Decoded? {
        val b = bits and mask
        exact[b]?.let { return Decoded(it shr 2, it and 3, 0) }
        if (maxHammingDistance <= 0) return null
        var bestId = -1
        var bestRot = 0
        var bestDist = Int.MAX_VALUE
        for (i in rotated.indices) {
            val d = java.lang.Long.bitCount(rotated[i] xor b)
            if (d < bestDist) {
                bestDist = d; bestId = i shr 2; bestRot = i and 3
                if (d == 1) break
            }
        }
        return if (bestId >= 0 && bestDist <= maxHammingDistance) {
            Decoded(bestId, bestRot, bestDist)
        } else {
            null
        }
    }

    val size: Int get() = codes.size

    companion object {
        const val ASSET_36H11 = "apriltag36h11.txt"

        /** Parses the hexadecimal codebook format (one code per line, '#' comments). */
        fun parse(stream: InputStream, name: String = "tag36h11", gridSize: Int = 6): AprilTagFamily {
            val list = ArrayList<Long>(600)
            stream.bufferedReader().forEachLine { raw ->
                val line = raw.trim()
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    list.add(java.lang.Long.parseLong(line, 16))
                }
            }
            require(list.isNotEmpty()) { "empty codebook" }
            return AprilTagFamily(name, list.toLongArray(), gridSize)
        }
    }
}
