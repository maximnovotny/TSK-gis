package cz.photoscanar.model

/**
 * A single AprilTag 36h11 detection.
 *
 * Image coordinates are stored in the coordinate frame of the image they were
 * detected in, plus the size of that image, so that the export can be rescaled
 * to the full-resolution photo without guessing.
 */
data class TagObservation(
    val timestampNs: Long,
    val tagFamily: String,
    val tagId: Int,
    /** 4 corners, clockwise, in pixels of [sourceWidth] x [sourceHeight]. */
    val cornersX: FloatArray,
    val cornersY: FloatArray,
    val centerX: Float,
    val centerY: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** 0..1, how confidently the payload matched (1 = exact code match). */
    val decisionMargin: Float,
    val hammingDistance: Int,
    /** "preview" or "photo:<id>" */
    val source: String,
    val photoId: Int = -1
) {
    override fun equals(other: Any?): Boolean =
        other is TagObservation && other.tagId == tagId && other.timestampNs == timestampNs &&
            other.source == source
    override fun hashCode(): Int = (tagId * 31 + timestampNs.hashCode()) * 31 + source.hashCode()
}

/** A distance between two physical tags, typed in by the user (metres). */
data class TagDistance(val fromId: Int, val toId: Int, val metres: Double, val note: String = "")
