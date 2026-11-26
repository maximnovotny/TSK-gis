package cz.photoscanar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cz.photoscanar.databinding.ActivityMainBinding
import cz.photoscanar.model.CaptureMode
import cz.photoscanar.model.TagObservation
import cz.photoscanar.session.CaptureController
import cz.photoscanar.session.SessionSummary
import cz.photoscanar.session.UiState
import cz.photoscanar.settings.Prefs
import cz.photoscanar.system.StorageMonitor
import cz.photoscanar.ui.ProjectsActivity
import cz.photoscanar.ui.SettingsSheet
import cz.photoscanar.ui.TagDialog
import cz.photoscanar.ui.gl.ArRenderer

/**
 * The capture screen.
 *
 * Everything the operator needs is in the bottom third so the phone can be
 * driven one-handed on site, and the AR layer can be switched off entirely for a
 * clean viewfinder. The AR graphics live only in the preview - they are never
 * drawn into a saved photo.
 */
class MainActivity : AppCompatActivity(), CaptureController.Listener, ArRenderer.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var controller: CaptureController
    private lateinit var renderer: ArRenderer

    private var orientationListener: OrientationEventListener? = null
    private var lastWarningAtMs = 0L
    private var permissionAsked = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startEverything() else showPermissionDialog()
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) openProjectsWithTree(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        controller = CaptureController(this, prefs, this)
        renderer = ArRenderer(controller, prefs, this)

        binding.glView.apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        binding.tagOverlay.setMapper(renderer.mapper)

        binding.btnCapture.setOnClickListener { toggleRecording() }
        binding.btnSettings.setOnClickListener { showSettings() }
        binding.btnProjects.setOnClickListener { openProjects() }
        binding.btnTags.setOnClickListener { showTagDialog() }
        binding.btnToggleAr.setOnClickListener { toggleArOverlay() }
        binding.miniMap.setOnClickListener { showHistory() }
        binding.chipAuto.setOnClickListener { setMode(CaptureMode.AUTO_2FPS) }
        binding.chipSmart.setOnClickListener { setMode(CaptureMode.SMART) }
        binding.chipDetail.setOnClickListener { setMode(CaptureMode.DETAIL) }

        applyModeChips(prefs.mode)
        applyArToggleIcon()
    }

    override fun onResume() {
        super.onResume()
        binding.glView.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startEverything()
        } else if (!permissionAsked) {
            permissionAsked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        orientationListener?.disable()
        controller.stopCamera()
        binding.glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        controller.release()
        renderer.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lifecycle

    private fun startEverything() {
        // Start on the plain path and only hand the texture to ARCore once the
        // camera has actually opened in shared mode. If AR fails there is then no
        // moment where the preview is black.
        renderer.switchSource(ArRenderer.Source.PLAIN)
        controller.startCamera()
        startOrientationListener()
    }

    private fun startOrientationListener() {
        if (orientationListener == null) {
            orientationListener = object : OrientationEventListener(this) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    // quantise to the nearest quarter turn; this is what the EXIF
                    // orientation tag is derived from
                    val rounded = ((orientation + 45) / 90 * 90) % 360
                    val deviceRotation = when (rounded) {
                        0 -> 0
                        90 -> 270
                        180 -> 180
                        else -> 90
                    }
                    controller.setDeviceOrientation(deviceRotation)
                }
            }
        }
        orientationListener?.enable()
        renderer.displayRotationDegrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_camera_title)
            .setMessage(R.string.perm_camera_msg)
            .setPositiveButton(R.string.perm_grant) { _, _ ->
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ controls

    private fun toggleRecording() {
        if (controller.recording) {
            controller.stopRecording()
        } else {
            if (!controller.startRecording()) return
        }
        updateCaptureButton(controller.recording)
    }

    private fun updateCaptureButton(recording: Boolean) {
        binding.btnCapture.setBackgroundResource(
            if (recording) R.drawable.btn_capture_active else R.drawable.btn_capture_idle
        )
        binding.btnCapture.contentDescription =
            getString(if (recording) R.string.stop else R.string.start)
    }

    private fun setMode(mode: CaptureMode) {
        if (controller.recording) {
            toast(getString(R.string.stop) + " → " + getString(R.string.settings_title))
            return
        }
        prefs.mode = mode
        applyModeChips(mode)
    }

    private fun applyModeChips(mode: CaptureMode) {
        binding.chipAuto.isSelected = mode == CaptureMode.AUTO_2FPS
        binding.chipSmart.isSelected = mode == CaptureMode.SMART
        binding.chipDetail.isSelected = mode == CaptureMode.DETAIL
    }

    private fun toggleArOverlay() {
        prefs.showArOverlay = !prefs.showArOverlay
        applyArToggleIcon()
        if (!prefs.showArOverlay) {
            binding.tagOverlay.clear()
            binding.miniMap.visibility = View.GONE
            binding.txtAdvice.visibility = View.GONE
        } else {
            binding.miniMap.visibility = View.VISIBLE
        }
    }

    private fun applyArToggleIcon() {
        binding.btnToggleAr.setImageResource(
            if (prefs.showArOverlay) R.drawable.ic_eye else R.drawable.ic_eye_off
        )
        binding.btnToggleAr.contentDescription =
            getString(if (prefs.showArOverlay) R.string.btn_hide_ar else R.string.btn_show_ar)
    }

    private fun showSettings() {
        SettingsSheet(this, prefs, controller) { restartNeeded ->
            applyArToggleIcon()
            if (restartNeeded && !controller.recording) {
                controller.stopCamera()
                startEverything()
            }
        }.show()
    }

    private fun showTagDialog() {
        TagDialog(this, controller).show()
    }

    private fun showHistory() {
        val photos = controller.recentPhotos(40)
        val items = if (photos.isEmpty()) {
            arrayOf(getString(R.string.projects_empty))
        } else {
            photos.map { p ->
                val flags = if (p.quality.flags.isEmpty()) "ok" else
                    p.quality.flags.joinToString(",") { it.name.take(4) }
                val sharp = if (p.quality.analysed) "%.0f".format(p.quality.laplacianVariance) else "-"
                val pose = if (p.pose?.valid == true) "AR" else "--"
                "#%05d  %s  sharp=%s  %s  %s".format(p.id, pose, sharp, flags, p.fileName)
            }.toTypedArray()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.history_title)
            .setItems(items, null)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun openProjects() {
        startActivity(Intent(this, ProjectsActivity::class.java))
    }

    private fun openProjectsWithTree(uri: Uri) {
        startActivity(
            Intent(this, ProjectsActivity::class.java)
                .putExtra(ProjectsActivity.EXTRA_TREE_URI, uri.toString())
        )
    }

    // -------------------------------------------------- CaptureController events

    override fun onUiState(state: UiState) {
        runOnUiThread { renderUiState(state) }
    }

    private fun renderUiState(s: UiState) {
        binding.txtPhotos.text = s.photoCount.toString()
        binding.txtRate.text = "%.1f/%.1f".format(s.measuredFps, s.targetFps)
        binding.txtAr.text = getString(stringKey(s.arStatusKey))
        binding.txtAr.setTextColor(
            ContextCompat.getColor(
                this,
                if (s.arTracking) R.color.ok_green
                else if (s.arStatusKey == "ar_off") R.color.on_bg_dim
                else R.color.warn
            )
        )
        binding.txtStorage.text = StorageMonitor.humanReadable(s.storageFreeBytes)

        binding.txtSharpness.text =
            if (s.sharpnessScore < 0) "-" else "%.2f".format(s.sharpnessScore)
        binding.txtSharpness.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    s.sharpnessScore < 0 -> R.color.on_bg_dim
                    s.sharpnessScore < 0.25 -> R.color.bad_red
                    s.sharpnessScore < 0.4 -> R.color.warn
                    else -> R.color.ok_green
                }
            )
        )
        binding.txtOverlap.text = if (s.overlapPct < 0) "-" else "%.0f %%".format(s.overlapPct)
        binding.txtOverlap.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    s.overlapPct < 0 -> R.color.on_bg_dim
                    s.overlapPct in 78.0..88.0 -> R.color.ok_green
                    s.overlapPct < 70.0 -> R.color.bad_red
                    else -> R.color.warn
                }
            )
        )
        binding.txtCoverage.text = if (s.voxelCells == 0) {
            "~%.0f %%".format(s.shellGreenPct)
        } else {
            "%.0f %%".format(s.voxelGreenPct)
        }
        binding.txtTags.text = if (s.tagIdsSeen == 0) "-" else "${s.tagCount}/${s.tagIdsSeen}"

        binding.txtTech.text = buildString {
            if (s.captureWidth > 0) append("${s.captureWidth}x${s.captureHeight}")
            append("  q ${s.writeQueueDepth}")
            if (s.pendingAnalysis > 0) append("  a ${s.pendingAnalysis}")
            if (s.droppedCaptures > 0) append("  drop ${s.droppedCaptures}")
            if (s.thermalStatus >= 2) append("  temp ${s.thermalStatus}")
        }

        val advice = s.adviceKey
        if (prefs.showArOverlay && advice.isNotEmpty() && s.recording) {
            binding.txtAdvice.visibility = View.VISIBLE
            binding.txtAdvice.text = if (advice == "advice_ok") {
                getString(R.string.advice_ok)
            } else {
                getString(R.string.advice_move_to, getString(stringKey(advice)))
            }
        } else {
            binding.txtAdvice.visibility = View.GONE
        }

        binding.txtStatusLine.text = buildString {
            append(
                when (s.mode) {
                    CaptureMode.AUTO_2FPS -> getString(R.string.mode_auto)
                    CaptureMode.SMART -> getString(R.string.mode_smart)
                    CaptureMode.DETAIL -> getString(R.string.mode_detail)
                }
            )
            if (s.projectName.isNotEmpty()) append("  ·  ${s.projectName}")
            if (s.recording) append("  ·  %02d:%02d".format(s.sessionSeconds / 60, s.sessionSeconds % 60))
            if (s.estimatedRemainingPhotos in 1..2000) append("  ·  ~${s.estimatedRemainingPhotos} snímků")
        }

        updateCaptureButton(s.recording)

        if (prefs.showArOverlay) {
            val pose = controller.arEngine.latestPose()
            val fwd = pose?.rotation?.forward()
            binding.miniMap.update(
                controller.sceneSnapshot(),
                pose?.position?.x ?: 0f,
                pose?.position?.z ?: 0f,
                fwd?.x ?: 0f,
                fwd?.z ?: -1f,
                pose?.valid == true
            )
        }
    }

    override fun onMessageKey(key: String, warning: Boolean) {
        runOnUiThread { showWarning(getString(stringKey(key)), warning) }
    }

    override fun onMessageText(text: String, warning: Boolean) {
        runOnUiThread { if (warning) showWarning(text, true) else toast(text) }
    }

    private fun showWarning(text: String, warning: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (warning && now - lastWarningAtMs < 600) return
        lastWarningAtMs = now
        binding.txtWarning.text = text
        binding.txtWarning.visibility = View.VISIBLE
        binding.txtWarning.removeCallbacks(hideWarning)
        binding.txtWarning.postDelayed(hideWarning, 2200)
    }

    private val hideWarning = Runnable { binding.txtWarning.visibility = View.GONE }

    override fun onSessionFinished(summary: SessionSummary) {
        runOnUiThread { showSummary(summary) }
    }

    private fun showSummary(s: SessionSummary) {
        val message = buildString {
            appendLine(
                getString(
                    R.string.session_summary, s.projectName, s.photoCount,
                    s.durationMs / 1000, "%.2f".format(s.averageFps)
                )
            )
            appendLine()
            appendLine("Velikost: ${StorageMonitor.humanReadable(s.bytes)}")
            if (s.blurryCount > 0) appendLine("Označeno jako neostré: ${s.blurryCount}")
            if (s.exposureFlagged > 0) appendLine("Problém s expozicí: ${s.exposureFlagged}")
            if (s.notAnalysed > 0) appendLine("Neanalyzováno: ${s.notAnalysed}")
            if (s.tagIds.isNotEmpty()) appendLine("Terče: ${s.tagIds.joinToString(", ")}")
            appendLine("AR pozice: ${if (s.arUsed) "k dispozici (přibližné)" else "nejsou"}")
            appendLine()
            appendLine("Originály zůstávají v projektu, nic se nemaže.")
        }
        AlertDialog.Builder(this)
            .setTitle(s.projectName)
            .setMessage(message)
            .setPositiveButton(R.string.project_export_zip) { _, _ -> openProjects() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    override fun onFatalError(text: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(text)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    override fun onTagDetections(detections: List<TagObservation>, sourceW: Int, sourceH: Int) {
        runOnUiThread {
            if (prefs.showArOverlay) binding.tagOverlay.setDetections(detections)
        }
    }

    // -------------------------------------------------------- renderer callbacks

    override fun onEngineConfigured(
        sharedAr: Boolean,
        sensorOrientation: Int,
        previewWidth: Int,
        previewHeight: Int
    ) {
        runOnUiThread {
            binding.glView.queueEvent {
                renderer.configurePreview(sensorOrientation, previewWidth, previewHeight)
                renderer.switchSource(
                    if (sharedAr) ArRenderer.Source.AR else ArRenderer.Source.PLAIN
                )
            }
        }
    }

    override fun onPreviewSurfaceReady(surface: Surface, width: Int, height: Int) {
        controller.setPreviewSurface(surface, width, height)
    }

    override fun onGlError(message: String) {
        runOnUiThread { toast(message) }
    }

    // ------------------------------------------------------------------- helpers

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun stringKey(key: String): Int = when (key) {
        "ar_off" -> R.string.ar_off
        "ar_unavailable" -> R.string.ar_unavailable
        "ar_tracking" -> R.string.ar_tracking
        "ar_paused" -> R.string.ar_paused
        "ar_init" -> R.string.ar_init
        "ar_lost" -> R.string.ar_lost
        "ar_excessive_motion" -> R.string.ar_excessive_motion
        "ar_insufficient_light" -> R.string.ar_insufficient_light
        "ar_insufficient_features" -> R.string.ar_insufficient_features
        "ar_fallback" -> R.string.ar_fallback
        "warn_too_fast" -> R.string.warn_too_fast
        "warn_blurry" -> R.string.warn_blurry
        "warn_dark" -> R.string.warn_dark
        "warn_bright" -> R.string.warn_bright
        "warn_duplicate" -> R.string.warn_duplicate
        "warn_low_overlap" -> R.string.warn_low_overlap
        "warn_storage" -> R.string.warn_storage
        "warn_thermal" -> R.string.warn_thermal
        "warn_queue" -> R.string.warn_queue
        "advice_ok" -> R.string.advice_ok
        "dir_left" -> R.string.dir_left
        "dir_right" -> R.string.dir_right
        "dir_up" -> R.string.dir_up
        "dir_down" -> R.string.dir_down
        "dir_back" -> R.string.dir_back
        "dir_forward" -> R.string.dir_forward
        else -> R.string.app_name
    }
}
