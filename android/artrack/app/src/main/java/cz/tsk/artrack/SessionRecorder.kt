package cz.tsk.artrack

import android.location.Location
import android.os.SystemClock
import com.google.ar.core.Frame
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale

/**
 * Writes the per-frame camera trajectory and the GPS track of one recording into
 * plain text files next to the MP4 that ARCore records.
 *
 * Files produced in [dir]:
 *   video.mp4   - written by ARCore itself (camera stream, frame-for-frame with pose.csv)
 *   pose.csv    - one row per recorded video frame
 *   gps.csv     - one row per GPS fix
 *   track.gpx   - the same GPS track as GPX 1.1 (for QGIS and friends)
 *   meta.json   - device, camera intrinsics, counters, clock bases
 *   README.txt  - column description
 */
class SessionRecorder(val dir: File, private val sessionName: String) {

    private val poseWriter: BufferedWriter = BufferedWriter(FileWriter(File(dir, "pose.csv")), 1 shl 16)
    private val gpsWriter: BufferedWriter = BufferedWriter(FileWriter(File(dir, "gps.csv")), 1 shl 14)
    private val gpsLock = Any()
    private var closed = false

    private val fixes = ArrayList<DoubleArray>()   // lat, lon, alt, sysTimeMs

    var frameCount = 0
        private set
    var gpsCount = 0
        private set
    var firstFrameTimestampNs = 0L
        private set
    var lastFrameTimestampNs = 0L
        private set
    private var lastRelSeconds = 0.0

    /** Intrinsics of the last recorded frame (fx, fy, cx, cy) and texture size. */
    var intrinsics = FloatArray(4)
        private set
    var textureWidth = 0
        private set
    var textureHeight = 0
        private set

    val wallClockStartMs: Long = System.currentTimeMillis()
    val elapsedRealtimeStartNs: Long = SystemClock.elapsedRealtimeNanos()

    init {
        poseWriter.write(
            "frame_index,t_rel_s,timestamp_ns,tx,ty,tz,qx,qy,qz,qw," +
                "tracking_state,failure_reason,fx,fy,cx,cy," +
                "lat,lon,alt_m,gps_acc_m,gps_age_ms\n"
        )
        gpsWriter.write(
            "t_rel_s_approx,sys_time_ms,elapsed_realtime_ns,lat,lon,alt_m," +
                "h_acc_m,v_acc_m,speed_mps,bearing_deg,provider\n"
        )
    }

    /** One row per frame that ARCore actually put into the MP4. */
    fun writeFrame(index: Int, frame: Frame, fix: Location?) {
        if (closed) return
        val camera = frame.camera
        val pose = camera.pose
        val t = FloatArray(3)
        val q = FloatArray(4)
        pose.getTranslation(t, 0)
        pose.getRotationQuaternion(q, 0)

        val ts = frame.timestamp
        if (frameCount == 0) firstFrameTimestampNs = ts
        lastFrameTimestampNs = ts
        val rel = (ts - firstFrameTimestampNs) / 1_000_000_000.0
        lastRelSeconds = rel

        val ti = camera.textureIntrinsics
        val focal = ti.focalLength
        val principal = ti.principalPoint
        val dims = ti.imageDimensions
        intrinsics = floatArrayOf(focal[0], focal[1], principal[0], principal[1])
        textureWidth = dims[0]
        textureHeight = dims[1]

        val ageMs = if (fix != null) {
            (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L
        } else -1L

        poseWriter.write(
            String.format(
                Locale.US,
                "%d,%.6f,%d,%.6f,%.6f,%.6f,%.8f,%.8f,%.8f,%.8f,%s,%s,%.3f,%.3f,%.3f,%.3f,%s,%s,%s,%s,%d\n",
                index, rel, ts,
                t[0], t[1], t[2],
                q[0], q[1], q[2], q[3],
                camera.trackingState.name,
                camera.trackingFailureReason.name,
                focal[0], focal[1], principal[0], principal[1],
                fix?.let { String.format(Locale.US, "%.8f", it.latitude) } ?: "",
                fix?.let { String.format(Locale.US, "%.8f", it.longitude) } ?: "",
                fix?.let { String.format(Locale.US, "%.3f", it.altitude) } ?: "",
                fix?.let { String.format(Locale.US, "%.2f", it.accuracy) } ?: "",
                ageMs
            )
        )
        frameCount++
    }

    /** One row per GPS fix that arrived while recording. */
    fun writeFix(fix: Location) {
        synchronized(gpsLock) {
            if (closed) return
            gpsWriter.write(
                String.format(
                    Locale.US,
                    "%.6f,%d,%d,%.8f,%.8f,%.3f,%.2f,%.2f,%.3f,%.2f,%s\n",
                    lastRelSeconds,
                    fix.time,
                    fix.elapsedRealtimeNanos,
                    fix.latitude,
                    fix.longitude,
                    fix.altitude,
                    fix.accuracy,
                    if (fix.hasVerticalAccuracy()) fix.verticalAccuracyMeters else -1f,
                    if (fix.hasSpeed()) fix.speed else -1f,
                    if (fix.hasBearing()) fix.bearing else -1f,
                    fix.provider ?: "?"
                )
            )
            fixes.add(doubleArrayOf(fix.latitude, fix.longitude, fix.altitude, fix.time.toDouble()))
            gpsCount++
        }
    }

    /** Flush and close everything, then write meta.json, track.gpx and README.txt. */
    fun finish(meta: JSONObject) {
        synchronized(gpsLock) {
            if (closed) return
            closed = true
            runCatching { poseWriter.flush(); poseWriter.close() }
            runCatching { gpsWriter.flush(); gpsWriter.close() }
        }
        runCatching { File(dir, "meta.json").writeText(meta.toString(2)) }
        runCatching { File(dir, "track.gpx").writeText(buildGpx()) }
        runCatching { File(dir, "README.txt").writeText(readmeText()) }
    }

    private fun buildGpx(): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"ARTrack\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk><name>").append(sessionName).append("</name><trkseg>\n")
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        iso.timeZone = java.util.TimeZone.getTimeZone("UTC")
        for (f in fixes) {
            sb.append(
                String.format(
                    Locale.US,
                    "    <trkpt lat=\"%.8f\" lon=\"%.8f\"><ele>%.2f</ele><time>%s</time></trkpt>\n",
                    f[0], f[1], f[2], iso.format(java.util.Date(f[3].toLong()))
                )
            )
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun readmeText(): String = """
ARTrack session $sessionName
=====================================================

video.mp4
  Camera stream recorded by ARCore. Row i of pose.csv belongs to video frame i
  (t_rel_s is the presentation time of that frame in seconds from the start of
  the video, so you can also seek by time instead of by index).

pose.csv
  frame_index      0-based index of the video frame
  t_rel_s          time of this frame from the first recorded frame [s]
  timestamp_ns     raw ARCore/camera timestamp [ns]
  tx,ty,tz         camera position in the ARCore world frame [m]
  qx,qy,qz,qw      camera orientation as a quaternion (camera -> world)
  tracking_state   TRACKING / PAUSED / STOPPED (use TRACKING rows only)
  failure_reason   why tracking is not TRACKING
  fx,fy,cx,cy      pinhole intrinsics of the recorded video frame [px]
  lat,lon,alt_m    last GPS fix known at that frame (WGS84, alt above ellipsoid)
  gps_acc_m        horizontal accuracy of that fix [m]
  gps_age_ms       how old the fix was at that frame [ms]

  Camera convention: +X right, +Y up, -Z forward (OpenGL style), pose is
  camera-to-world. World frame: +Y up (gravity), origin at the start of the
  session, +X/+Z arbitrary (no compass alignment).

gps.csv / track.gpx
  All GPS fixes that arrived while recording. t_rel_s_approx is the video time
  of the frame that was current when the fix arrived (+-1 frame).

meta.json
  Device, camera config, intrinsics, counters, clock bases.

Photogrammetry
  Cut frames out of video.mp4 (ffmpeg), then use pose.csv rows with the same
  index/time as camera priors, and gps.csv/alt for georeferencing. See
  tools/artrack_export.py in the repository for a COLMAP / RealityCapture
  export.
"""
}
