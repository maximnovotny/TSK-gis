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
 * Writes the camera trajectory, the per-photo metadata and the GPS track of one
 * recording.
 *
 * Files produced in [dir]:
 *   frames/frame_XXXXXX.jpg - full resolution stills with GPS in EXIF (the
                             photogrammetry input)
 *   realityscan/            - trajectory.csv and import instructions
 *   frames.csv              - one row per saved still: pose, intrinsics, GPS
 *   pose.csv                - one row per tracked frame: the whole trajectory
 *   video.mp4               - optional ARCore recording (low resolution, for reference)
 *   gps.csv                 - one row per GPS fix
 *   track.gpx               - the same GPS track as GPX 1.1 (for QGIS and friends)
 *   meta.json               - device, camera config, intrinsics, counters, clock bases
 *   README.txt              - column description
 */
class SessionRecorder(val dir: File, private val sessionName: String) {

    private val poseWriter: BufferedWriter = BufferedWriter(FileWriter(File(dir, "pose.csv")), 1 shl 16)
    private val frameWriter: BufferedWriter = BufferedWriter(FileWriter(File(dir, "frames.csv")), 1 shl 14)
    private val gpsWriter: BufferedWriter = BufferedWriter(FileWriter(File(dir, "gps.csv")), 1 shl 14)
    private val lock = Any()
    private var closed = false

    private val fixes = ArrayList<DoubleArray>()   // lat, lon, alt, sysTimeMs

    /** Kept in memory so the RealityScan package can be built when recording stops. */
    val photoRows = ArrayList<PhotoRow>()
    val fixRows = ArrayList<FixRow>()

    var frameCount = 0
        private set
    var stillCount = 0
        private set
    var gpsCount = 0
        private set
    var firstFrameTimestampNs = 0L
        private set
    var lastFrameTimestampNs = 0L
        private set
    private var lastRelSeconds = 0.0

    /** Intrinsics (fx, fy, cx, cy) and size of the video stream of the last frame. */
    var videoIntrinsics = FloatArray(4)
        private set
    var videoWidth = 0
        private set
    var videoHeight = 0
        private set

    /** The same for the still (CPU) image stream. */
    var photoIntrinsics = FloatArray(4)
        private set
    var photoWidth = 0
        private set
    var photoHeight = 0
        private set

    val wallClockStartMs: Long = System.currentTimeMillis()
    val elapsedRealtimeStartNs: Long = SystemClock.elapsedRealtimeNanos()

    init {
        poseWriter.write(
            "frame_index,t_rel_s,timestamp_ns,tx,ty,tz,qx,qy,qz,qw," +
                "tracking_state,failure_reason,fx,fy,cx,cy," +
                "lat,lon,alt_m,gps_acc_m,gps_age_ms\n"
        )
        frameWriter.write(
            "image_name,frame_index,t_rel_s,timestamp_ns,tx,ty,tz,qx,qy,qz,qw," +
                "tracking_state,fx,fy,cx,cy,width,height," +
                "lat,lon,alt_m,gps_acc_m,gps_age_ms\n"
        )
        gpsWriter.write(
            "t_rel_s_approx,sys_time_ms,elapsed_realtime_ns,lat,lon,alt_m," +
                "h_acc_m,v_acc_m,speed_mps,bearing_deg,provider\n"
        )
    }

    /** Relative time of a frame inside this recording, in seconds. */
    fun relativeSeconds(timestampNs: Long): Double =
        (timestampNs - firstFrameTimestampNs) / 1_000_000_000.0

    /** One row per recorded frame - this is the full trajectory. */
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
        val rel = relativeSeconds(ts)
        lastRelSeconds = rel

        val ti = camera.textureIntrinsics
        val focal = ti.focalLength
        val principal = ti.principalPoint
        videoIntrinsics = floatArrayOf(focal[0], focal[1], principal[0], principal[1])
        videoWidth = ti.imageDimensions[0]
        videoHeight = ti.imageDimensions[1]

        poseWriter.write(
            String.format(
                Locale.US,
                "%d,%.6f,%d,%.6f,%.6f,%.6f,%.8f,%.8f,%.8f,%.8f,%s,%s,%.3f,%.3f,%.3f,%.3f,%s,%d\n",
                index, rel, ts,
                t[0], t[1], t[2],
                q[0], q[1], q[2], q[3],
                camera.trackingState.name,
                camera.trackingFailureReason.name,
                focal[0], focal[1], principal[0], principal[1],
                gpsColumns(fix),
                gpsAgeMs(fix)
            )
        )
        frameCount++
    }

    /** One row per saved JPEG, with the intrinsics of the still image stream. */
    fun writeStill(name: String, frameIndex: Int, frame: Frame, fix: Location?) {
        if (closed) return
        val camera = frame.camera
        val pose = camera.pose
        val t = FloatArray(3)
        val q = FloatArray(4)
        pose.getTranslation(t, 0)
        pose.getRotationQuaternion(q, 0)

        val ii = camera.imageIntrinsics
        val focal = ii.focalLength
        val principal = ii.principalPoint
        photoIntrinsics = floatArrayOf(focal[0], focal[1], principal[0], principal[1])
        photoWidth = ii.imageDimensions[0]
        photoHeight = ii.imageDimensions[1]

        frameWriter.write(
            String.format(
                Locale.US,
                "%s,%d,%.6f,%d,%.6f,%.6f,%.6f,%.8f,%.8f,%.8f,%.8f,%s," +
                    "%.3f,%.3f,%.3f,%.3f,%d,%d,%s,%d\n",
                name, frameIndex, relativeSeconds(frame.timestamp), frame.timestamp,
                t[0], t[1], t[2],
                q[0], q[1], q[2], q[3],
                camera.trackingState.name,
                focal[0], focal[1], principal[0], principal[1],
                photoWidth, photoHeight,
                gpsColumns(fix),
                gpsAgeMs(fix)
            )
        )
        photoRows.add(
            PhotoRow(
                name,
                relativeSeconds(frame.timestamp),
                t[0].toDouble(),
                t[1].toDouble(),
                t[2].toDouble()
            )
        )
        stillCount++
    }

    /** One row per GPS fix. */
    fun writeFix(fix: Location) {
        synchronized(lock) {
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
            fixRows.add(
                FixRow(lastRelSeconds, fix.latitude, fix.longitude, fix.altitude, fix.accuracy)
            )
            gpsCount++
        }
    }

    /** lat,lon,alt,acc as four CSV columns, empty when there is no fix yet. */
    private fun gpsColumns(fix: Location?): String =
        if (fix == null) ",,," else String.format(
            Locale.US, "%.8f,%.8f,%.3f,%.2f",
            fix.latitude, fix.longitude, fix.altitude, fix.accuracy
        )

    private fun gpsAgeMs(fix: Location?): Long =
        if (fix == null) -1L
        else (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L

    /** Flush and close everything, then write meta.json, track.gpx and README.txt. */
    fun finish(meta: JSONObject) {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { poseWriter.flush(); poseWriter.close() }
            runCatching { frameWriter.flush(); frameWriter.close() }
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

frames/ + frames.csv
  Full resolution JPEG stills and their camera poses - this is what you feed
  into the photogrammetry software. One row of frames.csv per image:

  image_name       file in frames/
  frame_index      index of the matching row in pose.csv
  t_rel_s          time from the first frame of the recording [s]
  timestamp_ns     raw ARCore/camera timestamp [ns]
  tx,ty,tz         camera position in the ARCore world frame [m]
  qx,qy,qz,qw      camera orientation as a quaternion (camera -> world)
  tracking_state   TRACKING / PAUSED / STOPPED (use TRACKING rows only)
  fx,fy,cx,cy      pinhole intrinsics OF THIS IMAGE [px]
  width,height     image size [px]
  lat,lon,alt_m    last GPS fix known at that frame (WGS84, alt above ellipsoid)
  gps_acc_m        horizontal accuracy of that fix [m]
  gps_age_ms       how old the fix was at that frame [ms]

  The JPEGs are saved unrotated, in camera sensor orientation (landscape), which
  is the orientation the poses and the intrinsics refer to. EXIF carries the
  WGS84 position of the frame, the capture time, the device and the 35 mm
  equivalent focal length; the orientation tag is always 1 (no rotation).

realityscan/
  trajectory.csv (+ a space separated and a local-metres variant) with the
  camera positions, and jak-importovat.txt with the import steps and the
  report of the AR -> map fit. See the repository README for the details.

pose.csv
  The same per-frame data for every tracked frame (not just the saved photos),
  i.e. the full trajectory. Same columns, but fx,fy,cx,cy belong to the video
  stream, not to the JPEGs.

video.mp4 (only if video recording was enabled)
  ARCore session recording. Beware: ARCore encodes this stream at low
  resolution (VGA on most devices), so it is a visual reference, not a
  photogrammetry source. Row i of pose.csv matches video frame i, and t_rel_s
  is that frame's time in the video.

gps.csv / track.gpx
  All GPS fixes that arrived while recording. t_rel_s_approx is the video time
  of the frame that was current when the fix arrived (+-1 frame).

meta.json
  Device, camera config, intrinsics, counters, clock bases.

Conventions
  Pose is camera-to-world of the physical camera (ARCore Camera.getPose()):
  +X right, +Y up, -Z forward (OpenGL style), with right/up relative to the
  image readout, so it matches the saved JPEGs regardless of how the phone was
  held. World origin is where the session started, +Y is up (gravity-aligned),
  heading is arbitrary - georeference from GPS.

  tools/artrack_export.py in the repository converts frames.csv into COLMAP
  cameras.txt/images.txt and a per-image WGS84 list.
"""
}
