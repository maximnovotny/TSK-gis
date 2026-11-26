package cz.photoscanar.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import kotlin.math.abs
import kotlin.math.atan

/**
 * The real, queried capabilities of one physical camera - never assumptions.
 *
 * Everything the export calls a "camera parameter" comes from here or from the
 * capture result, so a project never claims a focal length or a sensor size the
 * device did not report.
 */
class CameraInfo(
    val cameraId: String,
    val characteristics: CameraCharacteristics
) {
    val sensorOrientation: Int =
        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    val facing: Int =
        characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK

    val facingName: String get() = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        else -> "EXTERNAL"
    }

    val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

    val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

    val focalLengths: FloatArray =
        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)

    val apertures: FloatArray =
        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: FloatArray(0)

    val timestampSource: Int =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ?: -1

    /**
     * True when sensor timestamps share the SystemClock.elapsedRealtimeNanos base,
     * which is what lets photos and AR poses be matched on one clock.
     */
    val timestampIsRealtime: Boolean =
        timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    val timestampSourceName: String get() = when (timestampSource) {
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
        else -> "UNSPECIFIED"
    }

    private val capabilities: IntArray =
        characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

    val supportsManualSensor: Boolean = capabilities.contains(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
    )

    val isLogicalMultiCamera: Boolean = capabilities.contains(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
    )

    val exposureTimeRange =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

    val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

    val aeCompensationRange =
        characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

    val aeCompensationStep =
        characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

    val availableAfModes: IntArray =
        characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)

    val supportsContinuousPictureAf: Boolean = availableAfModes.contains(
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    )

    val maxZoomRatioRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)

    private val streamMap: StreamConfigurationMap? =
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

    /** JPEG output sizes, largest first. */
    val jpegSizes: List<Size> =
        streamMap?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            ?.sortedByDescending { it.width.toLong() * it.height }
            ?: emptyList()

    /** Sizes the camera can stream into a SurfaceTexture (the GL preview). */
    val previewSizes: List<Size> =
        streamMap?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.sortedByDescending { it.width.toLong() * it.height }
            ?: emptyList()

    /**
     * Preview size for the plain path: the largest option up to 1920 px wide that
     * shares the capture aspect ratio, so the viewfinder frames exactly what the
     * photo will contain.
     */
    fun previewSizeFor(captureSize: Size): Size {
        if (previewSizes.isEmpty()) return Size(1920, 1080)
        val wanted = captureSize.width.toFloat() / captureSize.height
        val matching = previewSizes.filter {
            abs(it.width.toFloat() / it.height - wanted) < 0.03f && it.width <= 1920
        }
        return matching.firstOrNull() ?: previewSizes.firstOrNull { it.width <= 1920 } ?: previewSizes.last()
    }

    /** YUV sizes suitable for the cheap analysis stream. */
    val yuvSizes: List<Size> =
        streamMap?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            ?.sortedBy { it.width.toLong() * it.height }
            ?: emptyList()

    /** Minimum frame duration for a JPEG stream of this size, nanoseconds. */
    fun minJpegFrameDurationNs(size: Size): Long =
        try {
            streamMap?.getOutputMinFrameDuration(android.graphics.ImageFormat.JPEG, size) ?: 0L
        } catch (_: Exception) {
            0L
        }

    fun jpegStallDurationNs(size: Size): Long =
        try {
            streamMap?.getOutputStallDuration(android.graphics.ImageFormat.JPEG, size) ?: 0L
        } catch (_: Exception) {
            0L
        }

    /**
     * Theoretical upper bound on full-size JPEG captures per second from what the
     * driver advertises (frame duration plus stall duration). The achieved rate is
     * always measured separately - this is only used to warn up front.
     */
    fun theoreticalMaxFps(size: Size): Double {
        val period = minJpegFrameDurationNs(size) + jpegStallDurationNs(size)
        if (period <= 0L) return Double.NaN
        return 1_000_000_000.0 / period
    }

    /** Horizontal and vertical field of view in radians for a given output size. */
    fun fieldOfView(size: Size): Pair<Float, Float> {
        val f = focalLengths.firstOrNull() ?: return 1.1f to 0.85f
        val phys = physicalSize ?: return 1.1f to 0.85f
        val sensorAspect = phys.width / phys.height
        val outAspect = size.width.toFloat() / size.height
        val usedW: Float
        val usedH: Float
        if (outAspect > sensorAspect) {
            usedW = phys.width
            usedH = phys.width / outAspect
        } else {
            usedH = phys.height
            usedW = phys.height * outAspect
        }
        val h = 2f * atan(usedW / (2f * f))
        val v = 2f * atan(usedH / (2f * f))
        return h to v
    }

    /** Largest JPEG size, optionally stepped down by [level] for the adaptive path. */
    fun jpegSizeForLevel(level: Int): Size? {
        if (jpegSizes.isEmpty()) return null
        if (level <= 0) return jpegSizes[0]
        // step down to roughly half the pixel count per level, keeping the aspect ratio
        val target = jpegSizes[0]
        val targetAspect = target.width.toFloat() / target.height
        val sameAspect = jpegSizes.filter {
            abs(it.width.toFloat() / it.height - targetAspect) < 0.03f
        }
        val list = if (sameAspect.size > level) sameAspect else jpegSizes
        return list[level.coerceAtMost(list.size - 1)]
    }

    fun describe(): String = buildString {
        append("camera $cameraId ($facingName)")
        append(", orientation ${sensorOrientation}deg")
        physicalSize?.let { append(", sensor %.2fx%.2f mm".format(it.width, it.height)) }
        if (focalLengths.isNotEmpty()) append(", f=%.2f mm".format(focalLengths[0]))
        append(", timestamps $timestampSourceName")
        jpegSizes.firstOrNull()?.let { append(", max JPEG ${it.width}x${it.height}") }
    }

    companion object {
        /**
         * Picks the main rear camera at its native 1x framing: the back-facing
         * logical camera with the largest JPEG output. On a Pixel this is the main
         * wide module, which is exactly what photogrammetry wants - the ultrawide
         * and the tele are different optical systems and mixing them inside one
         * project makes alignment harder.
         */
        fun selectMainRearCamera(manager: CameraManager): CameraInfo? {
            val candidates = ArrayList<CameraInfo>()
            for (id in manager.cameraIdList) {
                val info = try {
                    CameraInfo(id, manager.getCameraCharacteristics(id))
                } catch (_: Exception) {
                    continue
                }
                if (info.facing != CameraCharacteristics.LENS_FACING_BACK) continue
                if (info.jpegSizes.isEmpty()) continue
                candidates.add(info)
            }
            if (candidates.isEmpty()) return null
            // prefer a logical multi-camera (the one the stock camera app uses at 1x)
            val logical = candidates.filter { it.isLogicalMultiCamera }
            val pool = logical.ifEmpty { candidates }
            return pool.maxByOrNull {
                val s = it.jpegSizes[0]
                s.width.toLong() * s.height
            }
        }

        fun byId(manager: CameraManager, id: String): CameraInfo? = try {
            CameraInfo(id, manager.getCameraCharacteristics(id))
        } catch (_: Exception) {
            null
        }
    }
}
