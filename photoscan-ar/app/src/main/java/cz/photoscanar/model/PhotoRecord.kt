package cz.photoscanar.model

/**
 * Everything the project export knows about one saved photo.
 *
 * [captureTimestampNs] is the camera sensor timestamp; it shares a clock with
 * [PoseSample.timestampNs] whenever the camera reports a REALTIME timestamp
 * source, which is what makes the photo <-> pose association meaningful.
 */
data class PhotoRecord(
    val id: Int,
    val fileName: String,
    val captureTimestampNs: Long,
    val wallClockEpochMs: Long,
    val width: Int,
    val height: Int,
    var fileSizeBytes: Long,
    val mode: CaptureMode,
    // real camera parameters, read back from the capture result / EXIF
    val focalLengthMm: Float? = null,
    val fNumber: Float? = null,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val focusDistanceDiopters: Float? = null,
    val cameraId: String = "",
    // AR metadata, explicitly flagged as approximate
    val pose: PoseSample? = null,
    val poseTimeOffsetNs: Long = 0,
    /** ARCore image intrinsics rescaled to this photo, or null when unavailable. */
    val arIntrinsicsFx: Float? = null,
    val arIntrinsicsFy: Float? = null,
    val arIntrinsicsCx: Float? = null,
    val arIntrinsicsCy: Float? = null,
    // quality, filled in asynchronously
    var quality: QualityMetrics = QualityMetrics.NOT_ANALYSED,
    var tagIds: List<Int> = emptyList()
)
