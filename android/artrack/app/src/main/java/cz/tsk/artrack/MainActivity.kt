package cz.tsk.artrack

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusView: TextView
    private lateinit var recButton: Button
    private lateinit var intervalButton: Button
    private lateinit var videoBox: CheckBox
    private lateinit var autofocusBox: CheckBox

    private lateinit var rotationHelper: DisplayRotationHelper
    private lateinit var locationTracker: LocationTracker

    private val background = BackgroundRenderer()

    /** Everything that touches [session] is serialised on this lock. */
    private val sessionLock = Any()
    private var session: Session? = null
    private var installRequested = false
    private var glReady = false

    @Volatile private var recorder: SessionRecorder? = null
    @Volatile private var stills: StillCapturer? = null
    @Volatile private var recording = false
    @Volatile private var videoRecording = false
    @Volatile private var frameIndex = 0
    @Volatile private var stillIndex = 0
    @Volatile private var lastStillNs = 0L
    @Volatile private var recordingStartedAtMs = 0L
    @Volatile private var trackingState: TrackingState = TrackingState.STOPPED
    @Volatile private var failureReason = ""
    @Volatile private var ioError = false
    @Volatile private var lastMessage = ""
    @Volatile private var stillIntervalMs = 500L
    @Volatile private var cameraInfo = ""

    private var intervalChoice = 1

    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            uiHandler.postDelayed(this, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceview)
        statusView = findViewById(R.id.status)
        recButton = findViewById(R.id.rec)
        intervalButton = findViewById(R.id.interval)
        videoBox = findViewById(R.id.video)
        autofocusBox = findViewById(R.id.autofocus)
        videoBox.isChecked = true

        rotationHelper = DisplayRotationHelper(this)
        locationTracker = LocationTracker(this)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        recButton.setOnClickListener {
            if (recording) stopRecording("zastaveno uživatelem") else startRecording()
        }
        intervalButton.setOnClickListener {
            if (recording) return@setOnClickListener
            intervalChoice = (intervalChoice + 1) % INTERVALS.size
            stillIntervalMs = INTERVALS[intervalChoice]
            intervalButton.text = INTERVAL_LABELS[intervalChoice]
        }
        intervalButton.text = INTERVAL_LABELS[intervalChoice]
        stillIntervalMs = INTERVALS[intervalChoice]
        autofocusBox.setOnCheckedChangeListener { _, _ -> if (!recording) applyConfig() }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST)
            return
        }

        synchronized(sessionLock) {
            if (session == null) {
                try {
                    when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                            installRequested = true
                            return
                        }
                        ArCoreApk.InstallStatus.INSTALLED -> Unit
                    }
                    val created = Session(this)
                    selectBestCameraConfig(created)
                    session = created
                    applyConfigLocked(created)
                } catch (e: UnavailableException) {
                    fail("ARCore není dostupné: ${e.javaClass.simpleName}")
                    return
                } catch (t: Throwable) {
                    fail("Nelze vytvořit ARCore session: ${t.message}")
                    return
                }
            }

            try {
                session?.resume()
            } catch (e: CameraNotAvailableException) {
                session = null
                fail("Kamera není dostupná, zkus to znovu")
                return
            }
        }

        surfaceView.onResume()
        rotationHelper.onResume()
        locationTracker.start()
        uiHandler.post(statusTicker)
    }

    override fun onPause() {
        if (recording) stopRecording("aplikace přešla do pozadí")
        uiHandler.removeCallbacks(statusTicker)
        locationTracker.stop()
        rotationHelper.onPause()
        surfaceView.onPause()
        synchronized(sessionLock) { session?.pause() }
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!hasPermissions()) {
            Toast.makeText(
                this,
                "Bez přístupu ke kameře a poloze aplikace nic nezaznamená.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------- ARCore setup

    /**
     * Pick the camera config with the largest CPU image, because that is the resolution
     * of the stills we save. The GPU texture resolution is the same in every config.
     */
    private fun selectBestCameraConfig(session: Session) {
        try {
            val filter = CameraConfigFilter(session)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
            val configs = session.getSupportedCameraConfigs(filter)
            val best = configs.maxByOrNull {
                it.imageSize.width.toLong() * it.imageSize.height.toLong()
            }
            if (best != null) {
                session.cameraConfig = best
                cameraInfo = "foto ${best.imageSize.width}x${best.imageSize.height}" +
                    ", video ${best.textureSize.width}x${best.textureSize.height}"
            }
        } catch (t: Throwable) {
            Log.w(TAG, "camera config selection failed", t)
        }
    }

    private fun applyConfig() {
        synchronized(sessionLock) { session?.let { applyConfigLocked(it) } }
    }

    private fun applyConfigLocked(session: Session) {
        val config = session.config
        config.focusMode =
            if (autofocusBox.isChecked) Config.FocusMode.AUTO else Config.FocusMode.FIXED
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        config.updateMode = Config.UpdateMode.BLOCKING
        session.configure(config)
    }

    // ---------------------------------------------------------------- recording

    private fun startRecording() {
        val wantVideo = videoBox.isChecked
        synchronized(sessionLock) {
            val session = this.session ?: run { fail("Session ještě není připravená"); return }

            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(getExternalFilesDir(null), "ARTrack/$name")
            if (!dir.mkdirs() && !dir.isDirectory) {
                fail("Nelze vytvořit složku záznamu")
                return
            }

            try {
                applyConfigLocked(session)
            } catch (t: Throwable) {
                fail("Nelze nastavit session: ${t.message}")
                return
            }

            videoRecording = false
            if (wantVideo && session.recordingStatus == RecordingStatus.NONE) {
                try {
                    val config = RecordingConfig(session)
                        .setMp4DatasetFilePath(File(dir, "video.mp4").absolutePath)
                        .setAutoStopOnPause(true)
                    session.startRecording(config)
                    videoRecording = true
                } catch (t: Throwable) {
                    lastMessage = "Video se nespustilo (${t.javaClass.simpleName}), " +
                        "jedou jen fotky a trajektorie"
                }
            }

            frameIndex = 0
            stillIndex = 0
            lastStillNs = 0L
            ioError = false
            recordingStartedAtMs = SystemClock.elapsedRealtime()
            val rec = SessionRecorder(dir, name)
            recorder = rec
            stills = if (stillIntervalMs > 0) StillCapturer(dir) else null
            recording = true
            locationTracker.last?.let { rec.writeFix(it) }
            locationTracker.onFix = { fix -> recorder?.writeFix(fix) }
        }
        recButton.setBackgroundResource(R.drawable.rec_button_active)
        recButton.text = "STOP"
    }

    private fun stopRecording(reason: String) {
        val rec: SessionRecorder?
        val capturer: StillCapturer?
        synchronized(sessionLock) {
            if (!recording) return
            recording = false
            locationTracker.onFix = null
            if (videoRecording) {
                runCatching { session?.stopRecording() }
                    .onFailure { Log.w(TAG, "stopRecording failed", it) }
                videoRecording = false
            }
            rec = recorder
            capturer = stills
            recorder = null
            stills = null
        }
        recButton.setBackgroundResource(R.drawable.rec_button)
        recButton.text = "REC"
        if (rec == null) return

        lastMessage = "Ukládám…"
        Thread {
            capturer?.shutdown()
            val rsReport = runCatching {
                RealityScanPackage.write(rec.dir, rec.photoRows, rec.fixRows)
            }.onFailure { Log.w(TAG, "RealityScan package failed", it) }.getOrNull()
            rec.finish(buildMeta(rec, capturer, reason, rsReport))
            val result = Exporter.export(this, rec.dir, rec.dir.name)
            uiHandler.post {
                lastMessage = if (result.error == null) {
                    "Hotovo: ${result.relativePath}\n${rec.stillCount} fotek, " +
                        "${result.files} souborů, ${result.bytes / (1024 * 1024)} MB" +
                        (rsReport?.let { "\n" + it.summary() } ?: "")
                } else {
                    "Export selhal (${result.error}); soubory zůstaly v " +
                        "Android/data/$packageName/files/ARTrack/${rec.dir.name}"
                }
                Toast.makeText(this, lastMessage, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun buildMeta(
        rec: SessionRecorder,
        capturer: StillCapturer?,
        stopReason: String,
        rsReport: RealityScanPackage.Report?
    ): JSONObject {
        val cameraConfig = synchronized(sessionLock) { session?.cameraConfig }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        val json = JSONObject()
        json.put("app", "ARTrack")
        json.put("app_version", BuildConfig.VERSION_NAME)
        json.put("arcore_client", ARCORE_VERSION)
        json.put("session", rec.dir.name)
        json.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        json.put("android_release", Build.VERSION.RELEASE)
        json.put("android_sdk", Build.VERSION.SDK_INT)
        json.put("frames_tracked", rec.frameCount)
        json.put("photos", rec.stillCount)
        json.put("photos_dropped", capturer?.dropped ?: 0)
        json.put("photos_without_exif", capturer?.exifErrors ?: 0)
        json.put("photo_interval_ms", stillIntervalMs)
        json.put("gps_fixes", rec.gpsCount)
        json.put("video_file", if (rec.dir.resolve("video.mp4").exists()) "video.mp4" else null)
        json.put(
            "duration_s",
            (rec.lastFrameTimestampNs - rec.firstFrameTimestampNs) / 1_000_000_000.0
        )
        json.put("first_frame_timestamp_ns", rec.firstFrameTimestampNs)
        json.put("start_wall_clock", iso.format(Date(rec.wallClockStartMs)))
        json.put("start_wall_clock_ms", rec.wallClockStartMs)
        json.put("start_elapsed_realtime_ns", rec.elapsedRealtimeStartNs)
        json.put("stop_reason", stopReason)
        json.put("focus_mode", if (autofocusBox.isChecked) "AUTO" else "FIXED")
        json.put("io_error", ioError)

        json.put("photo_intrinsics", intrinsicsJson(
            rec.photoIntrinsics, rec.photoWidth, rec.photoHeight,
            "Pinhole intrinsics of the JPEGs in frames/ (ARCore image intrinsics). " +
                "Per-photo values are in frames.csv. No lens distortion model is " +
                "provided - let the photogrammetry software refine it."
        ))
        json.put("video_intrinsics", intrinsicsJson(
            rec.videoIntrinsics, rec.videoWidth, rec.videoHeight,
            "Pinhole intrinsics of the video stream (ARCore texture intrinsics); " +
                "per-frame values are in pose.csv."
        ))

        if (cameraConfig != null) {
            val cc = JSONObject()
            cc.put("camera_id", cameraConfig.cameraId)
            cc.put("cpu_image_size", "${cameraConfig.imageSize.width}x${cameraConfig.imageSize.height}")
            cc.put("texture_size", "${cameraConfig.textureSize.width}x${cameraConfig.textureSize.height}")
            cc.put("fps_range", cameraConfig.fpsRange.toString())
            cc.put("facing", cameraConfig.facingDirection.name)
            json.put("camera_config", cc)
        }

        if (rsReport != null) {
            val rs = JSONObject()
            rs.put("images", rsReport.images)
            rs.put("crs", rsReport.crs)
            rs.put("epsg", rsReport.epsg ?: JSONObject.NULL)
            rs.put("georeferenced", !rsReport.local)
            rs.put("fit_fixes", rsReport.fitFixes)
            rsReport.fit?.let { fit ->
                rs.put("heading_deg_from_east", fit.headingDeg)
                rs.put("fit_rms_m", fit.rms)
                rs.put("track_extent_m", fit.extent)
                fit.sigmaDeg?.let { rs.put("heading_sigma_deg", it) }
            }
            rs.put("altitude_offset_m", rsReport.altOffset)
            rs.put(
                "note",
                "Camera position priors for RealityScan are in realityscan/; the " +
                    "photos carry their WGS84 position in EXIF. Only a rotation about " +
                    "the vertical axis and a translation are fitted from GPS - the " +
                    "scale stays at 1, because ARCore's metric scale beats GPS."
            )
            json.put("realityscan", rs)
        }

        json.put(
            "conventions",
            "Poses are camera-to-world of the physical camera (Camera.getPose()): " +
                "+X right, +Y up, -Z forward (OpenGL style), with right/up relative to the " +
                "image readout, so they match the saved JPEGs no matter how the phone was " +
                "held. World origin is where the session started, +Y is up (gravity), " +
                "heading is arbitrary. Row i of pose.csv = video frame i; frames.csv links " +
                "each JPEG to its pose.csv row via frame_index."
        )
        return json
    }

    private fun intrinsicsJson(
        values: FloatArray,
        width: Int,
        height: Int,
        note: String
    ): JSONObject = JSONObject().apply {
        put("model", "pinhole")
        put("fx", values[0].toDouble())
        put("fy", values[1].toDouble())
        put("cx", values[2].toDouble())
        put("cy", values[3].toDouble())
        put("width", width)
        put("height", height)
        put("note", note)
    }

    private fun fail(message: String) {
        lastMessage = message
        uiHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------- status

    private fun updateStatus() {
        val rec = recorder
        val capturer = stills
        val durationS =
            if (recording) (SystemClock.elapsedRealtime() - recordingStartedAtMs) / 1000.0 else 0.0
        val fix = locationTracker.last
        val freeMb = getExternalFilesDir(null)?.usableSpace?.div(1024 * 1024) ?: 0L
        val sb = StringBuilder()
        sb.append(if (recording) "● REC " else "○ připraveno ")
        if (recording) sb.append(String.format(Locale.US, "%.1f s", durationS))
        sb.append('\n')
        sb.append("tracking: ").append(trackingState.name)
        if (failureReason.isNotEmpty() && failureReason != "NONE") {
            sb.append(" (").append(failureReason).append(')')
        }
        sb.append('\n')
        sb.append("fotky: ").append(capturer?.saved ?: 0)
        if ((capturer?.dropped ?: 0) > 0) sb.append(" (-").append(capturer?.dropped).append(')')
        sb.append("   snímky: ").append(rec?.frameCount ?: 0)
        sb.append("   fixy: ").append(rec?.gpsCount ?: 0).append('\n')
        if (cameraInfo.isNotEmpty()) sb.append(cameraInfo).append('\n')
        sb.append("GPS: ")
        if (fix == null) {
            sb.append("čekám na fix")
        } else {
            sb.append(
                String.format(
                    Locale.US, "%.6f, %.6f  ±%.0f m", fix.latitude, fix.longitude, fix.accuracy
                )
            )
        }
        sb.append('\n')
        sb.append("volné místo: ").append(freeMb).append(" MB")
        if (ioError) sb.append("\nCHYBA ZÁPISU VIDEA")
        if (lastMessage.isNotEmpty()) sb.append('\n').append(lastMessage)
        statusView.text = sb.toString()
    }

    // ------------------------------------------------------------ GL renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            background.createOnGlThread()
            glReady = true
        } catch (t: Throwable) {
            Log.e(TAG, "GL init failed", t)
            fail("Chyba inicializace OpenGL: ${t.message}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        rotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!glReady) return
        synchronized(sessionLock) {
            val session = this.session ?: return
            try {
                rotationHelper.updateSessionIfNeeded(session)
                session.setCameraTextureName(background.textureId)
                val frame = session.update()
                background.draw(frame)

                val camera = frame.camera
                trackingState = camera.trackingState
                failureReason = camera.trackingFailureReason.name

                if (!recording) return
                if (videoRecording) {
                    when (session.recordingStatus) {
                        RecordingStatus.OK -> Unit
                        RecordingStatus.IO_ERROR -> {
                            ioError = true
                            return
                        }
                        else -> return      // recording has not started yet
                    }
                }

                val rec = recorder ?: return
                val fix = locationTracker.last
                rec.writeFrame(frameIndex, frame, fix)
                maybeSaveStill(rec, frame, fix)
                frameIndex++
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "camera not available", e)
            } catch (t: Throwable) {
                Log.e(TAG, "frame error", t)
            }
        }
    }

    /** Save a full resolution JPEG if the configured interval has elapsed. */
    private fun maybeSaveStill(rec: SessionRecorder, frame: Frame, fix: android.location.Location?) {
        val capturer = stills ?: return
        if (stillIntervalMs <= 0L) return
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        val ts = frame.timestamp
        if (lastStillNs != 0L && ts - lastStillNs < stillIntervalMs * 1_000_000L) return
        try {
            frame.acquireCameraImage().use { image ->
                val nv21 = YuvUtil.toNv21(image)
                val intrinsics = frame.camera.imageIntrinsics
                val focal35 = if (intrinsics.imageDimensions[0] > 0) {
                    intrinsics.focalLength[0].toDouble() /
                        intrinsics.imageDimensions[0].toDouble() * 36.0
                } else 0.0
                val tags = StillCapturer.Tags(
                    fix = fix,
                    focal35mm = focal35,
                    width = image.width,
                    height = image.height,
                    wallClockMs = System.currentTimeMillis()
                )
                val name = capturer.offer(stillIndex, nv21, image.width, image.height, tags)
                if (name != null) {
                    rec.writeStill(name, frameIndex, frame, fix)
                    stillIndex++
                    lastStillNs = ts
                }
            }
        } catch (t: Throwable) {
            // NotYetAvailableException and friends: just try again next frame.
            Log.d(TAG, "camera image not available: ${t.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "ARTrack"
        const val ARCORE_VERSION = "1.57.0"
        const val PERMISSION_REQUEST = 42
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val INTERVALS = longArrayOf(250L, 500L, 1000L, 2000L, 0L)
        val INTERVAL_LABELS = arrayOf(
            "Foto: 0,25 s", "Foto: 0,5 s", "Foto: 1 s", "Foto: 2 s", "Foto: vypnuto"
        )
    }
}
