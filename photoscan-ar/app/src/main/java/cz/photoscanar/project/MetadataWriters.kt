package cz.photoscanar.project

import cz.photoscanar.model.PhotoRecord
import cz.photoscanar.model.PoseSample
import cz.photoscanar.model.QualityFlag
import cz.photoscanar.model.TagObservation

/**
 * The CSV schemas of the export. Column names are stable: downstream scripts
 * (and the RealityScan / COLMAP import notes in metadata/README) rely on them.
 *
 * Every AR-derived column is explicitly qualified - `ar_pose_valid`,
 * `ar_tx_approx`, ... - so nobody can mistake visual-inertial odometry output
 * for surveyed exterior orientation.
 */
object MetadataWriters {

    val CAMERAS_HEADER = listOf(
        "photo_id", "file_name", "capture_timestamp_ns", "wall_clock_epoch_ms", "iso8601_utc",
        "width", "height", "file_size_bytes", "capture_mode", "camera_id",
        "focal_length_mm", "f_number", "exposure_time_s", "iso_sensitivity", "focus_distance_diopters",
        "ar_pose_valid", "ar_pose_quality", "ar_tracking_state",
        "ar_tx_approx", "ar_ty_approx", "ar_tz_approx",
        "ar_qx_approx", "ar_qy_approx", "ar_qz_approx", "ar_qw_approx",
        "ar_pose_time_offset_ms",
        "ar_intrinsics_fx_approx", "ar_intrinsics_fy_approx",
        "ar_intrinsics_cx_approx", "ar_intrinsics_cy_approx",
        "sharpness_laplacian_var", "sharpness_score", "mean_luma",
        "clipped_low_pct", "clipped_high_pct", "overlap_with_previous",
        "quality_analysed", "quality_ok", "quality_flags", "tag_ids"
    )

    val TRAJECTORY_HEADER = listOf(
        "timestamp_ns", "wall_clock_epoch_ms", "ar_pose_valid", "ar_pose_quality",
        "ar_tracking_state", "ar_tracking_failure_reason",
        "ar_tx_approx", "ar_ty_approx", "ar_tz_approx",
        "ar_qx_approx", "ar_qy_approx", "ar_qz_approx", "ar_qw_approx",
        "linear_speed_mps", "angular_speed_rad_s"
    )

    val DETECTIONS_HEADER = listOf(
        "timestamp_ns", "source", "photo_id", "tag_family", "tag_id",
        "center_x", "center_y",
        "corner0_x", "corner0_y", "corner1_x", "corner1_y",
        "corner2_x", "corner2_y", "corner3_x", "corner3_y",
        "source_width", "source_height", "decision_margin", "hamming_distance"
    )

    val TAG_DISTANCES_HEADER = listOf("from_tag_id", "to_tag_id", "distance_m", "note")

    fun cameraRow(p: PhotoRecord): List<Any?> {
        val pose = p.pose
        return listOf(
            p.id,
            p.fileName,
            p.captureTimestampNs,
            p.wallClockEpochMs,
            Iso8601.format(p.wallClockEpochMs),
            p.width,
            p.height,
            p.fileSizeBytes,
            p.mode.name,
            p.cameraId,
            p.focalLengthMm,
            p.fNumber,
            p.exposureTimeNs?.let { it / 1_000_000_000.0 },
            p.iso,
            p.focusDistanceDiopters,
            pose?.valid == true,
            pose?.quality?.name ?: "NO_AR",
            pose?.trackingStateName ?: "NO_AR",
            pose?.position?.x, pose?.position?.y, pose?.position?.z,
            pose?.rotation?.x, pose?.rotation?.y, pose?.rotation?.z, pose?.rotation?.w,
            p.poseTimeOffsetNs / 1_000_000.0,
            p.arIntrinsicsFx, p.arIntrinsicsFy, p.arIntrinsicsCx, p.arIntrinsicsCy,
            p.quality.laplacianVariance,
            p.quality.sharpnessScore,
            p.quality.meanLuma,
            p.quality.clippedLowPct,
            p.quality.clippedHighPct,
            if (p.quality.overlapWithPrevious < 0) null else p.quality.overlapWithPrevious,
            p.quality.analysed,
            p.quality.ok,
            flagsToString(p.quality.flags),
            p.tagIds.joinToString(";")
        )
    }

    fun trajectoryRow(s: PoseSample, wallClockEpochMs: Long): List<Any?> = listOf(
        s.timestampNs,
        wallClockEpochMs,
        s.valid,
        s.quality.name,
        s.trackingStateName,
        s.trackingFailureReason,
        s.position.x, s.position.y, s.position.z,
        s.rotation.x, s.rotation.y, s.rotation.z, s.rotation.w,
        s.linearSpeed,
        s.angularSpeed
    )

    fun detectionRow(d: TagObservation): List<Any?> = listOf(
        d.timestampNs,
        d.source,
        if (d.photoId >= 0) d.photoId else null,
        d.tagFamily,
        d.tagId,
        d.centerX, d.centerY,
        d.cornersX[0], d.cornersY[0],
        d.cornersX[1], d.cornersY[1],
        d.cornersX[2], d.cornersY[2],
        d.cornersX[3], d.cornersY[3],
        d.sourceWidth, d.sourceHeight,
        d.decisionMargin,
        d.hammingDistance
    )

    fun flagsToString(flags: Set<QualityFlag>): String =
        if (flags.isEmpty()) "" else flags.map { it.name }.sorted().joinToString(";")
}

/** Minimal ISO-8601 UTC formatter (no java.time dependency concerns on old APIs). */
object Iso8601 {
    fun format(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(epochMs))
    }
}
