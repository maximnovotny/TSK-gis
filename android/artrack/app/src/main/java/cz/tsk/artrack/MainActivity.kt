package cz.tsk.artrack

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
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
    private lateinit var overlay: View
    private lateinit var statusCard: View
    private lateinit var trackingValue: TextView
    private lateinit var gpsValue: TextView
    private lateinit var countsValue: TextView
    private lateinit var targetChip: LinearLayout
    private lateinit var targetLabel: TextView
    private lateinit var recPill: View
    private lateinit var recDot: View
    private lateinit var timerValue: TextView
    private lateinit var messageView: TextView
    private lateinit var controls: View
    private lateinit var intervalControl: SegmentedControl
    private lateinit var shutter: ShutterButton
    private lateinit var videoSwitch: SwitchCompat
    private lateinit var autofocusSwitch: SwitchCompat

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
    @Volatile private var lastLoggedTimestampNs = 0L
    @Volatile private var recordingStartedAtMs = 0L
    @Volatile private var trackingState: TrackingState = TrackingState.STOPPED
    @Volatile private var failureReason: TrackingFailureReason = TrackingFailureReason.NONE
    @Volatile private var ioError = false
    @Volatile private var lastMessage = ""
    @Volatile private var stillIntervalMs = 500L
    @Volatile private var cameraInfo = ""

    /** Folder the finished session is moved to; null means Download/ARTrack. */
    private var targetUri: Uri? = null
    private val prefs by lazy { getSharedPreferences("artrack", MODE_PRIVATE) }
    private var dotAnimator: ObjectAnimator? = null

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                targetUri = uri
                val label = Exporter.labelOf(this, uri)
                prefs.edit()
                    .putString(KEY_TARGET, uri.toString())
                    .putString(KEY_TARGET_LABEL, label)
                    .apply()
                updateTargetLabel()
            } catch (t: Throwable) {
                showMessage("Složku nelze trvale povolit: ${t.message}")
            }
        }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            uiHandler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceview)
        overlay = findViewById(R.id.overlay)
        statusCard = findViewById(R.id.statusCard)
        trackingValue = findViewById(R.id.trackingValue)
        gpsValue = findViewById(R.id.gpsValue)
        countsValue = findViewById(R.id.countsValue)
        targetChip = findViewById(R.id.targetChip)
        targetLabel = findViewById(R.id.targetLabel)
        recPill = findViewById(R.id.recPill)
        recDot = findViewById(R.id.recDot)
        timerValue = findViewById(R.id.timerValue)
        messageView = findViewById(R.id.message)
        controls = findViewById(R.id.controls)
        intervalControl = findViewById(R.id.interval)
        shutter = findViewById(R.id.rec)
        videoSwitch = findViewById(R.id.video)
        autofocusSwitch = findViewById(R.id.autofocus)

        rotationHelper = DisplayRotationHelper(this)
        locationTracker = LocationTracker(this)

        goEdgeToEdge()

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        videoSwitch.isChecked = true
        intervalControl.items = INTERVAL_LABELS.toList()
        intervalControl.setSelectedSilently(DEFAULT_INTERVAL)
        stillIntervalMs = INTERVALS[DEFAULT_INTERVAL]
        intervalControl.onSelected = { index -> stillIntervalMs = INTERVALS[index] }

        shutter.setOnClickListener {
            if (recording) stopRecording("zastaveno uživatelem") else startRecording()
        }
        autofocusSwitch.setOnCheckedChangeListener { _, _ -> if (!recording) applyConfig() }

        restoreTarget()
        targetChip.setOnClickListener {
            if (recording) return@setOnClickListener
            ActionSheet(this)
                .title("Kam ukládat záznamy")
                .choice("Vybrat složku…") {
                    runCatching { pickFolder.launch(targetUri) }
                        .onFailure { showMessage("Výběr složky není dostupný: ${it.message}") }
                }
                .choice("Výchozí: Download/ARTrack") { clearTarget() }
                .show()
        }
    }

    /** Full-bleed preview, with the chrome kept out of the notch and the gestures. */
    private fun goEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, surfaceView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.systemGestures()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
    }

    // ------------------------------------------------------------ target folder

    /** Reuse the folder picked earlier, as long as the permission survived. */
    private fun restoreTarget() {
        val stored = prefs.getString(KEY_TARGET, null)
        if (stored == null) {
            updateTargetLabel()
            return
        }
        val uri = runCatching { Uri.parse(stored) }.getOrNull()
        val granted = uri != null && contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (granted) {
            targetUri = uri
        } else {
            prefs.edit().remove(KEY_TARGET).remove(KEY_TARGET_LABEL).apply()
            showMessage("Dřív vybraná složka už není povolená, ukládám do Download/ARTrack")
        }
        updateTargetLabel()
    }

    private fun clearTarget() {
        targetUri?.let { uri ->
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        targetUri = null
        prefs.edit().remove(KEY_TARGET).remove(KEY_TARGET_LABEL).apply()
        updateTargetLabel()
    }

    private fun updateTargetLabel() {
        targetLabel.text = if (targetUri == null) "Download"
        else prefs.getString(KEY_TARGET_LABEL, null) ?: "vybraná složka"
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
                    showMessage("ARCore není dostupné: ${e.javaClass.simpleName}")
                    return
                } catch (t: Throwable) {
                    showMessage("Nelze vytvořit ARCore session: ${t.message}")
                    return
                }
            }

            try {
                session?.resume()
            } catch (e: CameraNotAvailableException) {
                session = null
                showMessage("Kamera není dostupná, zkus to znovu")
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
                cameraInfo = "${best.imageSize.width}×${best.imageSize.height}"
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
            if (autofocusSwitch.isChecked) Config.FocusMode.AUTO else Config.FocusMode.FIXED
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        config.updateMode = Config.UpdateMode.BLOCKING
        session.configure(config)
    }

    // ---------------------------------------------------------------- recording

    private fun startRecording() {
        val wantVideo = videoSwitch.isChecked
        synchronized(sessionLock) {
            val session = this.session
                ?: run { showMessage("Session ještě není připravená"); return }

            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(getExternalFilesDir(null), "ARTrack/$name")
            if (!dir.mkdirs() && !dir.isDirectory) {
                showMessage("Nelze vytvořit složku záznamu")
                return
            }

            try {
                applyConfigLocked(session)
            } catch (t: Throwable) {
                showMessage("Nelze nastavit session: ${t.message}")
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
                    showMessage(
                        "Video se nespustilo (${t.javaClass.simpleName}), " +
                            "jedou jen fotky a trajektorie"
                    )
                }
            }

            frameIndex = 0
            stillIndex = 0
            lastStillNs = 0L
            lastLoggedTimestampNs = 0L
            ioError = false
            recordingStartedAtMs = SystemClock.elapsedRealtime()
            val rec = SessionRecorder(dir, name)
            recorder = rec
            stills = if (stillIntervalMs > 0) StillCapturer(dir) else null
            recording = true
            locationTracker.last?.let { fix ->
                val ageMs = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) /
                    1_000_000L
                // A fix from minutes ago says where the phone was, not where it is.
                if (ageMs <= RealityScanPackage.MAX_GPS_AGE_MS) rec.writeFix(fix)
            }
            locationTracker.onFix = { fix -> recorder?.writeFix(fix) }
        }
        setRecordingChrome(true)
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
        setRecordingChrome(false)
        if (rec == null) return

        showMessage("Ukládám…")
        Thread {
            capturer?.shutdown()
            val rsReport = runCatching {
                RealityScanPackage.write(rec.dir, rec.photoRows, rec.fixRows)
            }.onFailure { Log.w(TAG, "RealityScan package failed", it) }.getOrNull()
            rec.finish(buildMeta(rec, capturer, reason, rsReport))
            val result = Exporter.export(this, rec.dir, rec.dir.name, targetUri)
            uiHandler.post {
                if (result.error == null) {
                    showMessage(
                        "Uloženo do ${result.destination}\n" +
                            "${rec.stillCount} fotek · ${result.bytes / (1024 * 1024)} MB" +
                            (rsReport?.let { " · " + it.shortSummary() } ?: "")
                    )
                } else {
                    showMessage(
                        "Export selhal (${result.error}). Soubory zůstaly v " +
                            "Android/data/$packageName/files/ARTrack/${rec.dir.name}"
                    )
                }
            }
        }.start()
    }

    /** Recording mode: timer instead of the folder chip, controls out of the way. */
    private fun setRecordingChrome(active: Boolean) {
        shutter.isRecording = active
        recPill.animate().alpha(if (active) 1f else 0f).setDuration(180L).withStartAction {
            if (active) {
                recPill.alpha = 0f
                recPill.visibility = View.VISIBLE
            }
        }.withEndAction {
            if (!active) recPill.visibility = View.GONE
        }.start()
        targetChip.animate().alpha(if (active) 0f else 1f).setDuration(180L).withStartAction {
            if (!active) {
                targetChip.visibility = View.VISIBLE
            }
        }.withEndAction {
            if (active) targetChip.visibility = View.GONE
        }.start()
        controls.animate().alpha(if (active) 0.35f else 1f).setDuration(180L).start()
        intervalControl.isEnabled = !active
        videoSwitch.isEnabled = !active
        autofocusSwitch.isEnabled = !active

        if (active) {
            dotAnimator = ObjectAnimator.ofFloat(recDot, View.ALPHA, 1f, 0.25f).apply {
                duration = 800L
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            dotAnimator?.cancel()
            dotAnimator = null
            recDot.alpha = 1f
        }
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
        json.put("frames_skipped_estimate", rec.framesSkipped)
        json.put("track_length_m", rec.trackLengthM)
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
        json.put("focus_mode", if (autofocusSwitch.isChecked) "AUTO" else "FIXED")
        json.put(
            "export_target",
            if (targetUri == null) "Download/ARTrack"
            else prefs.getString(KEY_TARGET_LABEL, null) ?: "vybraná složka"
        )
        json.put("io_error", ioError)

        json.put(
            "photo_intrinsics",
            intrinsicsJson(
                rec.photoIntrinsics, rec.photoWidth, rec.photoHeight,
                "Pinhole intrinsics of the JPEGs in frames/ (ARCore image intrinsics). " +
                    "Per-photo values are in frames.csv. No lens distortion model is " +
                    "provided - let the photogrammetry software refine it."
            )
        )
        json.put(
            "video_intrinsics",
            intrinsicsJson(
                rec.videoIntrinsics, rec.videoWidth, rec.videoHeight,
                "Pinhole intrinsics of the video stream (ARCore texture intrinsics); " +
                    "per-frame values are in pose.csv."
            )
        )

        if (cameraConfig != null) {
            val cc = JSONObject()
            cc.put("camera_id", cameraConfig.cameraId)
            cc.put(
                "cpu_image_size",
                "${cameraConfig.imageSize.width}x${cameraConfig.imageSize.height}"
            )
            cc.put(
                "texture_size",
                "${cameraConfig.textureSize.width}x${cameraConfig.textureSize.height}"
            )
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
            rs.put("heading_reliable", rsReport.headingReliable)
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

    // ------------------------------------------------------------------- status

    private fun showMessage(text: String) {
        lastMessage = text
        uiHandler.post {
            messageView.text = text
            if (messageView.visibility != View.VISIBLE) {
                messageView.alpha = 0f
                messageView.visibility = View.VISIBLE
            }
            messageView.animate().alpha(1f).setDuration(160L).start()
            uiHandler.removeCallbacks(hideMessage)
            uiHandler.postDelayed(hideMessage, 9000L)
        }
    }

    private val hideMessage = Runnable {
        messageView.animate().alpha(0f).setDuration(220L).withEndAction {
            messageView.visibility = View.GONE
        }.start()
    }

    private fun updateStatus() {
        val rec = recorder
        val capturer = stills

        val problem = trackingState != TrackingState.TRACKING
        trackingValue.text = trackingText()
        trackingValue.setTextColor(
            ContextCompat.getColor(this, if (problem) R.color.system_yellow else R.color.label)
        )

        val fix = locationTracker.last
        gpsValue.text = if (fix == null) "čekám na fix"
        else String.format(Locale.US, "±%.0f m", fix.accuracy)

        countsValue.text = if (recording) {
            val dropped = capturer?.dropped ?: 0
            buildString {
                append(capturer?.saved ?: 0).append(" fotek")
                if (dropped > 0) append(" (−").append(dropped).append(')')
                append(" · ").append(rec?.gpsCount ?: 0).append(" fixů · ")
                append(String.format(Locale.US, "%.1f m", rec?.trackLengthM ?: 0.0))
                if (ioError) append(" · chyba zápisu videa")
            }
        } else {
            val freeMb = getExternalFilesDir(null)?.usableSpace?.div(1024 * 1024) ?: 0L
            buildString {
                if (cameraInfo.isNotEmpty()) append(cameraInfo).append(" · ")
                append(String.format(Locale.US, "%.0f GB", freeMb / 1024.0))
            }
        }

        if (recording) {
            val seconds = ((SystemClock.elapsedRealtime() - recordingStartedAtMs) / 1000L)
            timerValue.text = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
        }
    }

    private fun trackingText(): String = when {
        trackingState == TrackingState.TRACKING -> "tracking"
        else -> when (failureReason) {
            TrackingFailureReason.NONE -> "hledám scénu"
            TrackingFailureReason.BAD_STATE -> "chyba trackingu"
            TrackingFailureReason.INSUFFICIENT_LIGHT -> "málo světla"
            TrackingFailureReason.EXCESSIVE_MOTION -> "moc rychlý pohyb"
            TrackingFailureReason.INSUFFICIENT_FEATURES -> "málo textury"
            TrackingFailureReason.CAMERA_UNAVAILABLE -> "kamera nedostupná"
        }
    }

    // ------------------------------------------------------------ GL renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            background.createOnGlThread()
            glReady = true
        } catch (t: Throwable) {
            Log.e(TAG, "GL init failed", t)
            showMessage("Chyba inicializace OpenGL: ${t.message}")
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
                failureReason = camera.trackingFailureReason

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
                // ARCore can hand out the same frame twice; logging it again would
                // put two rows on one camera frame.
                if (frame.timestamp == lastLoggedTimestampNs) return
                lastLoggedTimestampNs = frame.timestamp
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
    private fun maybeSaveStill(rec: SessionRecorder, frame: Frame, fix: Location?) {
        val capturer = stills ?: return
        if (stillIntervalMs <= 0L) return
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        val ts = frame.timestamp
        if (lastStillNs != 0L && ts - lastStillNs < stillIntervalMs * 1_000_000L) return
        try {
            frame.acquireCameraImage().use { image ->
                val planes = YuvUtil.copyPlanes(image)
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
                val name = capturer.offer(stillIndex, planes, tags)
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
        const val KEY_TARGET = "target_tree_uri"
        const val KEY_TARGET_LABEL = "target_label"
        const val DEFAULT_INTERVAL = 1
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val INTERVALS = longArrayOf(250L, 500L, 1000L, 2000L, 0L)
        val INTERVAL_LABELS = arrayOf("0,25 s", "0,5 s", "1 s", "2 s", "vyp")
    }
}
