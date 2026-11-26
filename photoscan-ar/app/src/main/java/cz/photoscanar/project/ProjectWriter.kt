package cz.photoscanar.project

import cz.photoscanar.model.PhotoRecord
import cz.photoscanar.model.PoseSample
import cz.photoscanar.model.TagDistance
import cz.photoscanar.model.TagObservation
import java.io.Closeable
import java.io.File

/**
 * Owns the metadata files of a running session.
 *
 * Rows are appended as events happen so an interrupted session still leaves
 * valid files behind. Because the quality analyser finishes *after* a photo row
 * was already flushed, quality results are additionally streamed to
 * metadata/quality.csv, and cameras.csv is rewritten in full once at the end of
 * the session with everything merged in.
 */
class ProjectWriter(val layout: ProjectLayout) : Closeable {

    private val cameras = CsvAppender(layout.camerasCsv, MetadataWriters.CAMERAS_HEADER, flushEveryRows = 4)
    private val trajectory = CsvAppender(layout.trajectoryCsv, MetadataWriters.TRAJECTORY_HEADER, flushEveryRows = 25)
    private val detections = CsvAppender(layout.detectionsCsv, MetadataWriters.DETECTIONS_HEADER, flushEveryRows = 5)
    private val quality = CsvAppender(
        File(layout.metadata, "quality.csv"),
        listOf(
            "photo_id", "laplacian_var", "sharpness_score", "mean_luma",
            "clipped_low_pct", "clipped_high_pct", "contrast", "overlap_with_previous",
            "flags", "analysis_ms"
        ),
        flushEveryRows = 4
    )

    @Volatile private var closed = false

    init {
        layout.prepare()
    }

    @Synchronized
    fun appendPhoto(p: PhotoRecord) {
        if (closed) return
        cameras.append(MetadataWriters.cameraRow(p))
    }

    @Synchronized
    fun appendPose(s: PoseSample, wallClockEpochMs: Long) {
        if (closed) return
        trajectory.append(MetadataWriters.trajectoryRow(s, wallClockEpochMs))
    }

    @Synchronized
    fun appendDetections(list: List<TagObservation>) {
        if (closed) return
        for (d in list) detections.append(MetadataWriters.detectionRow(d))
    }

    @Synchronized
    fun appendQuality(p: PhotoRecord) {
        if (closed) return
        val q = p.quality
        quality.append(
            listOf(
                p.id, q.laplacianVariance, q.sharpnessScore, q.meanLuma,
                q.clippedLowPct, q.clippedHighPct, q.contrast,
                if (q.overlapWithPrevious < 0) null else q.overlapWithPrevious,
                MetadataWriters.flagsToString(q.flags), q.analysisDurationMs
            )
        )
    }

    @Synchronized
    fun writeTagDistances(list: List<TagDistance>) {
        if (list.isEmpty() && !layout.tagDistancesCsv.exists()) return
        CsvAppender(layout.tagDistancesCsv, MetadataWriters.TAG_DISTANCES_HEADER).use { w ->
            for (d in list) w.append(listOf(d.fromId, d.toId, d.metres, d.note))
        }
    }

    @Synchronized
    fun log(line: String) {
        try {
            layout.metadata.mkdirs()
            layout.sessionLog.appendText("${Iso8601.format(System.currentTimeMillis())}  $line\n")
        } catch (_: Exception) {
            // logging must never break a capture session
        }
    }

    /** Final pass: cameras.csv with the asynchronous quality results merged in. */
    @Synchronized
    fun rewriteCameras(records: List<PhotoRecord>) {
        cameras.close()
        val tmp = File(layout.root, "cameras.csv.tmp")
        CsvAppender(tmp, MetadataWriters.CAMERAS_HEADER, flushEveryRows = 200).use { w ->
            for (r in records.sortedBy { it.id }) w.append(MetadataWriters.cameraRow(r))
        }
        if (tmp.exists()) {
            if (layout.camerasCsv.exists()) layout.camerasCsv.delete()
            if (!tmp.renameTo(layout.camerasCsv)) tmp.copyTo(layout.camerasCsv, overwrite = true)
        }
    }

    @Synchronized
    fun writeDescriptor(descriptor: ProjectDescriptor) {
        layout.projectJson.writeText(descriptor.toJson())
    }

    @Synchronized
    fun writeReadme(text: String) {
        layout.metadata.mkdirs()
        layout.readme.writeText(text)
    }

    @Synchronized
    fun flushAll() {
        cameras.flushNow(); trajectory.flushNow(); detections.flushNow(); quality.flushNow()
    }

    @Synchronized
    override fun close() {
        closed = true
        cameras.close(); trajectory.close(); detections.close(); quality.close()
    }
}
