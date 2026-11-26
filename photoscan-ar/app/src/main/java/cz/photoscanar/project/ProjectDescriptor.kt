package cz.photoscanar.project

import cz.photoscanar.coverage.CoverageSummary
import cz.photoscanar.model.CaptureMode

/**
 * Everything that goes into project.json.
 *
 * The honesty-critical part is [arPosesAreApproximate] and the accompanying
 * notes: ARCore poses are visual-inertial odometry in a session-local frame.
 * They drift, they are not metric-accurate over long walks, they carry no
 * geodetic reference, and they are exported as a navigation aid - never as
 * ready-made exterior orientation for RealityScan or COLMAP.
 */
data class ProjectDescriptor(
    val projectName: String,
    val appVersion: String,
    val createdEpochMs: Long,
    val finishedEpochMs: Long,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidRelease: String,
    val androidSdkInt: Int,
    val cameraId: String,
    val cameraFacing: String,
    val sensorOrientationDeg: Int,
    val sensorTimestampSource: String,
    val sensorPhysicalWidthMm: Float?,
    val sensorPhysicalHeightMm: Float?,
    val activeArrayWidth: Int?,
    val activeArrayHeight: Int?,
    val availableFocalLengthsMm: List<Float>,
    val captureWidth: Int,
    val captureHeight: Int,
    val jpegQuality: Int,
    val mode: CaptureMode,
    val targetIntervalMs: Long,
    val photosSaved: Int,
    val capturesIssued: Long,
    val capturesDropped: Long,
    val measuredFpsAverage: Double,
    val measuredFpsPeak: Double,
    val sessionDurationMs: Long,
    val arRequested: Boolean,
    val arUsed: Boolean,
    val arSharedCamera: Boolean,
    val arUnavailableReason: String,
    val arPosesValidCount: Int,
    val arTrackingLostCount: Int,
    val arPosesAreApproximate: Boolean = true,
    val tagFamily: String,
    val tagDetectionEnabled: Boolean,
    val uniqueTagIds: List<Int>,
    val voxelCoverage: CoverageSummary,
    val shellCoverage: CoverageSummary,
    val qualityAnalysisEnabled: Boolean,
    val photosFlaggedBlurry: Int,
    val photosFlaggedExposure: Int,
    val photosNotAnalysed: Int,
    val exifOrientationPolicy: String,
    val notes: List<String>
) {

    fun toJson(): String {
        val w = JsonWriter()
        w.beginObject()
        w.value("format", "PhotoScanAR/project.json")
        w.value("format_version", 1)
        w.value("project_name", projectName)
        w.value("app_version", appVersion)
        w.value("created_utc", Iso8601.format(createdEpochMs))
        w.value("finished_utc", Iso8601.format(finishedEpochMs))
        w.value("session_duration_ms", sessionDurationMs)

        w.beginObject("device")
        w.value("manufacturer", deviceManufacturer)
        w.value("model", deviceModel)
        w.value("android_release", androidRelease)
        w.value("android_sdk_int", androidSdkInt)
        w.endObject()

        w.beginObject("camera")
        w.value("camera_id", cameraId)
        w.value("facing", cameraFacing)
        w.value("sensor_orientation_deg", sensorOrientationDeg)
        w.value("sensor_timestamp_source", sensorTimestampSource)
        w.value("sensor_physical_width_mm", sensorPhysicalWidthMm)
        w.value("sensor_physical_height_mm", sensorPhysicalHeightMm)
        w.value("active_array_width", activeArrayWidth)
        w.value("active_array_height", activeArrayHeight)
        w.floatArray("available_focal_lengths_mm", availableFocalLengthsMm)
        w.value("capture_width", captureWidth)
        w.value("capture_height", captureHeight)
        w.value("jpeg_quality", jpegQuality)
        w.value("exif_orientation_policy", exifOrientationPolicy)
        w.endObject()

        w.beginObject("capture")
        w.value("mode", mode.name)
        w.value("target_interval_ms", targetIntervalMs)
        w.value("target_fps", 1000.0 / targetIntervalMs, 3)
        w.value("photos_saved", photosSaved)
        w.value("captures_issued", capturesIssued)
        w.value("captures_dropped", capturesDropped)
        w.value("measured_fps_average", measuredFpsAverage, 3)
        w.value("measured_fps_peak", measuredFpsPeak, 3)
        w.endObject()

        w.beginObject("ar")
        w.value("requested", arRequested)
        w.value("used", arUsed)
        w.value("shared_camera", arSharedCamera)
        w.value("unavailable_reason", arUnavailableReason)
        w.value("poses_valid_count", arPosesValidCount)
        w.value("tracking_lost_events", arTrackingLostCount)
        w.value("poses_are_approximate", arPosesAreApproximate)
        w.value("pose_frame", "ARCore session-local, Y up, right-handed; origin at session start")
        w.value(
            "pose_warning",
            "ARCore poses come from visual-inertial odometry. They drift, have no geodetic " +
                "reference and must not be used as surveyed exterior orientation. Treat them as " +
                "initial estimates or discard them and let the photogrammetric solver align the images."
        )
        w.endObject()

        w.beginObject("tags")
        w.value("family", tagFamily)
        w.value("detection_enabled", tagDetectionEnabled)
        w.intArray("unique_ids", uniqueTagIds)
        w.value(
            "note",
            "Tag detections are image observations with pixel coordinates. The app does not " +
                "compute geodetic coordinates and does not scale the AR trajectory from tags."
        )
        w.endObject()

        w.beginObject("coverage")
        w.beginObject("geometric_voxels")
        w.value("geometrically_verified", voxelCoverage.geometricallyVerified)
        w.value("cells", voxelCoverage.cells)
        w.value("green", voxelCoverage.green)
        w.value("yellow", voxelCoverage.yellow)
        w.value("red", voxelCoverage.red)
        w.endObject()
        w.beginObject("direction_shell_estimate")
        w.value("geometrically_verified", shellCoverage.geometricallyVerified)
        w.value("cells", shellCoverage.cells)
        w.value("green", shellCoverage.green)
        w.value("yellow", shellCoverage.yellow)
        w.value("red", shellCoverage.red)
        w.endObject()
        w.value(
            "note",
            "Coverage is an acquisition aid. The voxel figure counts distinct viewpoints over " +
                "ARCore feature points; the direction shell is inferred from the phone trajectory " +
                "only. Neither proves photogrammetric completeness."
        )
        w.endObject()

        w.beginObject("quality")
        w.value("analysis_enabled", qualityAnalysisEnabled)
        w.value("flagged_blurry", photosFlaggedBlurry)
        w.value("flagged_exposure", photosFlaggedExposure)
        w.value("not_analysed", photosNotAnalysed)
        w.value("originals_deleted", false)
        w.value("note", "Flagged photos are kept. Nothing is ever deleted automatically.")
        w.endObject()

        w.stringArray("notes", notes)
        w.endObject()
        return w.toString()
    }

    companion object {
        val DEFAULT_NOTES = listOf(
            "images/ holds the untouched JPEGs as produced by the camera pipeline. No AR graphics are drawn into them.",
            "cameras.csv, trajectory.csv and detections.csv share one monotonic clock (capture_timestamp_ns / timestamp_ns).",
            "Rows whose ar_pose_valid is 0 have no usable AR pose; the position columns are meaningless there.",
            "For RealityScan / COLMAP: import images/ and let the solver compute alignment. Use the AR poses only as a sanity check."
        )
    }
}
