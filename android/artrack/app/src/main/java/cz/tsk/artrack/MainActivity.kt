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
    @Volatile private var recording = false
    @Volatile private var frameIndex = 0
    @Volatile private var recordingStartedAtMs = 0L
    @Volatile private var trackingState: TrackingState = TrackingState.STOPPED
    @Volatile private var failureReason: String = ""
    @Volatile private var ioError = false
    @Volatile private var lastMessage = ""

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
        autofocusBox = findViewById(R.id.autofocus)

        rotationHelper = DisplayRotationHelper(this)
        locationTracker = LocationTracker(this)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)

        recButton.setOnClickListener {
            if (recording) stopRecording("zastaveno uživatelem") else startRecording()
        }
        autofocusBox.setOnCheckedChangeListener { _, _ ->
            if (!recording) applyConfig()
        }
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

    /** Pick the camera config with the largest GPU texture - that is what gets recorded. */
    private fun selectBestCameraConfig(session: Session) {
        try {
            val filter = CameraConfigFilter(session)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
            val configs = session.getSupportedCameraConfigs(filter)
            val best = configs.maxByOrNull {
                it.textureSize.width.toLong() * it.textureSize.height.toLong()
            }
            if (best != null) session.cameraConfig = best
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
        synchronized(sessionLock) {
            val session = this.session ?: run { fail("Session ještě není připravená"); return }
            if (session.recordingStatus != RecordingStatus.NONE) return

            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(getExternalFilesDir(null), "ARTrack/$name")
            if (!dir.mkdirs() && !dir.isDirectory) {
                fail("Nelze vytvořit složku záznamu")
                return
            }
            val mp4 = File(dir, "video.mp4")
            try {
                applyConfigLocked(session)
                val config = RecordingConfig(session)
                    .setMp4DatasetFilePath(mp4.absolutePath)
                    .setAutoStopOnPause(true)
                session.startRecording(config)
            } catch (t: Throwable) {
                fail("Záznam se nepodařilo spustit: ${t.message}")
                return
            }

            frameIndex = 0
            ioError = false
            recordingStartedAtMs = SystemClock.elapsedRealtime()
            val rec = SessionRecorder(dir, name)
            recorder = rec
            recording = true
            locationTracker.last?.let { rec.writeFix(it) }
            locationTracker.onFix = { fix -> recorder?.writeFix(fix) }
        }
        recButton.setBackgroundResource(R.drawable.rec_button_active)
        recButton.text = "STOP"
        lastMessage = ""
    }

    private fun stopRecording(reason: String) {
        val rec: SessionRecorder?
        val frames: Int
        synchronized(sessionLock) {
            if (!recording) return
            recording = false
            locationTracker.onFix = null
            runCatching { session?.stopRecording() }
                .onFailure { Log.w(TAG, "stopRecording failed", it) }
            rec = recorder
            recorder = null
            frames = frameIndex
            if (rec != null) rec.finish(buildMeta(rec, reason))
        }
        recButton.setBackgroundResource(R.drawable.rec_button)
        recButton.text = "REC"
        if (rec == null) return

        lastMessage = "Ukládám $frames snímků…"
        Thread {
            val result = Exporter.export(this, rec.dir, rec.dir.name)
            uiHandler.post {
                lastMessage = if (result.error == null) {
                    "Hotovo: ${result.relativePath} (${result.files} souborů, " +
                        "${result.bytes / (1024 * 1024)} MB)"
                } else {
                    "Export selhal (${result.error}); soubory zůstaly v " +
                        "Android/data/$packageName/files/ARTrack/${rec.dir.name}"
                }
                Toast.makeText(this, lastMessage, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun buildMeta(rec: SessionRecorder, stopReason: String): JSONObject {
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
        json.put("video_file", "video.mp4")
        json.put("frames", rec.frameCount)
        json.put("gps_fixes", rec.gpsCount)
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

        val intr = JSONObject()
        intr.put("model", "pinhole")
        intr.put("fx", rec.intrinsics[0].toDouble())
        intr.put("fy", rec.intrinsics[1].toDouble())
        intr.put("cx", rec.intrinsics[2].toDouble())
        intr.put("cy", rec.intrinsics[3].toDouble())
        intr.put("width", rec.textureWidth)
        intr.put("height", rec.textureHeight)
        intr.put(
            "note",
            "Intrinsics of the recorded video frames (ARCore texture intrinsics) of the " +
                "last recorded frame; per-frame values are in pose.csv. No lens distortion " +
                "model is provided - let the photogrammetry software refine it."
        )
        json.put("video_intrinsics", intr)

        if (cameraConfig != null) {
            val cc = JSONObject()
            cc.put("camera_id", cameraConfig.cameraId)
            cc.put("texture_size", "${cameraConfig.textureSize.width}x${cameraConfig.textureSize.height}")
            cc.put("cpu_image_size", "${cameraConfig.imageSize.width}x${cameraConfig.imageSize.height}")
            cc.put("fps_range", cameraConfig.fpsRange.toString())
            cc.put("facing", cameraConfig.facingDirection.name)
            json.put("camera_config", cc)
        }

        json.put(
            "conventions",
            "pose.csv holds camera-to-world poses of the physical camera (Camera.getPose()): " +
                "+X right, +Y up, -Z forward, i.e. OpenGL style, independent of display " +
                "rotation and matching the recorded video frames. World origin is where the " +
                "session started, +Y is up (gravity-aligned), heading is arbitrary. " +
                "Row i of pose.csv = video frame i; t_rel_s is the frame time in seconds."
        )
        return json
    }

    private fun fail(message: String) {
        lastMessage = message
        uiHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------- status

    private fun updateStatus() {
        val rec = recorder
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
        sb.append("snímky: ").append(rec?.frameCount ?: frameIndex)
        sb.append("   fixy: ").append(rec?.gpsCount ?: 0).append('\n')
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

                if (recording) {
                    when (session.recordingStatus) {
                        RecordingStatus.OK ->
                            recorder?.writeFrame(frameIndex++, frame, locationTracker.last)
                        RecordingStatus.IO_ERROR -> ioError = true
                        else -> Unit
                    }
                }
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "camera not available", e)
            } catch (t: Throwable) {
                Log.e(TAG, "frame error", t)
            }
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
    }
}
