package cz.photoscanar.session

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import cz.photoscanar.analysis.QualityAnalyzer
import cz.photoscanar.analysis.TagAnalyzer
import cz.photoscanar.ar.ArEngine
import cz.photoscanar.camera.CaptureEngine
import cz.photoscanar.camera.CameraInfo
import cz.photoscanar.camera.PhotoWriteQueue
import cz.photoscanar.capture.RateController
import cz.photoscanar.capture.SmartTrigger
import cz.photoscanar.coverage.CoverageConfig
import cz.photoscanar.coverage.CoverageState
import cz.photoscanar.coverage.DirectionShell
import cz.photoscanar.coverage.OverlapEstimator
import cz.photoscanar.coverage.ViewFrustum
import cz.photoscanar.coverage.VoxelCoverage
import cz.photoscanar.geom.Vec3
import cz.photoscanar.model.CaptureMode
import cz.photoscanar.model.PhotoRecord
import cz.photoscanar.model.PoseSample
import cz.photoscanar.model.QualityFlag
import cz.photoscanar.model.QualityMetrics
import cz.photoscanar.model.TagDistance
import cz.photoscanar.model.TagObservation
import cz.photoscanar.project.ProjectDescriptor
import cz.photoscanar.project.ProjectLayout
import cz.photoscanar.project.ProjectWriter
import cz.photoscanar.project.ReadmeText
import cz.photoscanar.sensors.MotionMonitor
import cz.photoscanar.settings.Prefs
import cz.photoscanar.system.StorageMonitor
import cz.photoscanar.system.ThermalMonitor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Wires the camera, ARCore, the analysers, the coverage models and the project
 * writer into one capture session.
 *
 * Threading, in order of importance:
 *   camera thread   - issues captures, copies JPEGs, hands them to the writers
 *   writer threads  - the only thing that touches photo files
 *   meta executor   - CSV rows, coverage maths, EXIF checks
 *   analyser threads- quality and tag detection, both droppable
 *   GL thread       - ARCore update and rendering
 *   main thread     - UI only
 *
 * Nothing on the first two ever waits for anything on the last four.
 */
class CaptureController(
    private val context: Context,
    private val prefs: Prefs,
    private val listener: Listener
) : CaptureEngine.Listener {

    interface Listener {
        fun onUiState(state: UiState)
        fun onMessageKey(key: String, warning: Boolean)
        fun onMessageText(text: String, warning: Boolean)
        fun onSessionFinished(summary: SessionSummary)
        fun onFatalError(text: String)
        fun onTagDetections(detections: List<TagObservation>, sourceW: Int, sourceH: Int)
        /**
         * How the renderer must be set up: which path owns the camera texture,
         * the sensor mounting angle and the preview stream size.
         */
        fun onEngineConfigured(sharedAr: Boolean, sensorOrientation: Int, previewWidth: Int, previewHeight: Int)
    }

    // ------------------------------------------------------------------- members

    val arEngine = ArEngine(context)
    private val engine = CaptureEngine(context, prefs, arEngine, this)
    private val writeQueue = PhotoWriteQueue(threadCount = 2, capacity = 16)
    private val metaExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "metadata") }
    private val motion = MotionMonitor(context)
    private val thermal = ThermalMonitor(context)

    private val projectsDir = File(context.getExternalFilesDir(null), "projects")
    private val storage = StorageMonitor(projectsDir)

    private var rateController = RateController(prefs.mode, prefs.adaptResolution)
    private val smartTrigger = SmartTrigger()
    private val coverageConfig = CoverageConfig()
    private val voxelCoverage = VoxelCoverage(coverageConfig)
    private val shellCoverage = DirectionShell(coverageConfig)

    private var qualityAnalyzer: QualityAnalyzer? = null
    private var tagAnalyzer: TagAnalyzer? = null

    private var pacerThread: HandlerThread? = null
    private var pacerHandler: Handler? = null

    private val records = ConcurrentHashMap<Int, PhotoRecord>()
    private val recordOrder = java.util.Collections.synchronizedList(ArrayList<Int>())
    private val nextPhotoId = AtomicInteger(1)

    private var layout: ProjectLayout? = null
    private var writer: ProjectWriter? = null
    private val tagDistances = java.util.Collections.synchronizedList(ArrayList<TagDistance>())

    @Volatile var recording: Boolean = false
        private set
    @Volatile private var sessionStartedAtMs = 0L
    @Volatile private var lastCaptureAtMs = 0L
    @Volatile private var lastPoseForOverlap: PoseSample? = null
    @Volatile private var lastUiPushAtMs = 0L
    @Volatile private var lastTrajectoryAppendAtMs = 0L
    @Volatile private var lastPointHarvestAtMs = 0L
    @Volatile private var lastSceneBuildAtMs = 0L
    @Volatile private var lastLumaRequestAtMs = 0L
    @Volatile private var peakFps = 0.0
    @Volatile private var arStatusKey = "ar_off"
    @Volatile private var arTracking = false
    @Volatile private var warningKey = ""
    @Volatile private var warningUntilMs = 0L
    @Volatile private var lastQuality: QualityMetrics = QualityMetrics.NOT_ANALYSED
    @Volatile private var objectCenter: Vec3? = null
    @Volatile private var estimatedObjectDistance: Float = 3f
    @Volatile private var exifSummary: String = "not checked"
    @Volatile private var scene: SceneSnapshot = SceneSnapshot.EMPTY
    @Volatile private var fieldOfView: Pair<Float, Float> = 1.1f to 0.85f
    @Volatile private var cameraInfo: CameraInfo? = null
    @Volatile private var captureSize: Size? = null
    @Volatile private var engineMode: CaptureEngine.Mode = CaptureEngine.Mode.PLAIN
    @Volatile private var arFallbackReason = ""

    private val markerPositions = java.util.Collections.synchronizedList(ArrayList<Vec3>())
    private val markerForwards = java.util.Collections.synchronizedList(ArrayList<Vec3>())
    private val markerOk = java.util.Collections.synchronizedList(ArrayList<Boolean>())

    // --------------------------------------------------------------- camera life

    /** Opens the camera (and ARCore if wanted). Call from onResume. */
    fun startCamera() {
        storage.reserveBytes = 400L * 1024 * 1024
        motion.start()
        thermal.start { pushUiState(force = true) }
        writeQueue.start()

        var requestedMode = CaptureEngine.Mode.PLAIN
        if (prefs.useAr && !prefs.sharedCameraBlacklisted) {
            when (arEngine.availability()) {
                ArEngine.Availability.SUPPORTED_INSTALLED -> {
                    if (arEngine.create(shared = true)) {
                        requestedMode = CaptureEngine.Mode.SHARED_AR
                        arStatusKey = "ar_init"
                    } else {
                        arFallbackReason = arEngine.lastError
                        arStatusKey = "ar_unavailable"
                        listener.onMessageKey("ar_fallback", true)
                    }
                }
                ArEngine.Availability.SUPPORTED_NOT_INSTALLED -> {
                    arStatusKey = "ar_unavailable"
                    arFallbackReason = "Google Play Services for AR is not installed"
                }
                else -> {
                    arStatusKey = "ar_unavailable"
                    arFallbackReason = "device does not support ARCore"
                }
            }
        } else {
            arStatusKey = if (prefs.useAr) "ar_unavailable" else "ar_off"
            if (prefs.sharedCameraBlacklisted) {
                arFallbackReason = "shared camera failed earlier on this device"
            }
        }
        engineMode = requestedMode
        engine.start(requestedMode, prefs.mode.targetFps)
        pushUiState(force = true)
    }

    /** Call from onPause. Stops recording cleanly first. */
    fun stopCamera() {
        if (recording) stopRecording(userRequested = false)
        engine.stop()
        arEngine.close()
        motion.stop()
        thermal.stop()
        pushUiState(force = true)
    }

    fun release() {
        stopCamera()
        writeQueue.shutdown(5000)
        metaExecutor.shutdown()
        qualityAnalyzer?.stop()
        tagAnalyzer?.stop()
    }

    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) =
        engine.setPreviewSurface(surface, width, height)

    fun setDeviceOrientation(degrees: Int) {
        val cam = cameraInfo ?: return
        // EXIF orientation from the physical device orientation plus the sensor
        // mounting, so a photo taken with the phone turned sideways still displays
        // the right way up without rotating a single pixel
        engine.jpegOrientationDegrees = ((cam.sensorOrientation + degrees) % 360 + 360) % 360
    }

    // ------------------------------------------------------------------ recording

    fun startRecording(): Boolean {
        if (recording) return true
        if (!engine.isReady) {
            listener.onMessageText("Fotoaparát ještě není připraven", true)
            return false
        }
        if (storage.isLow()) {
            listener.onMessageKey("warn_storage", true)
            return false
        }
        val l = try {
            ProjectLayout.createNext(projectsDir)
        } catch (e: Throwable) {
            listener.onFatalError("Nelze vytvořit projekt: ${e.message}")
            return false
        }
        layout = l
        writer = ProjectWriter(l)
        records.clear()
        recordOrder.clear()
        markerPositions.clear(); markerForwards.clear(); markerOk.clear()
        nextPhotoId.set(1)
        voxelCoverage.clear()
        shellCoverage.reset()
        peakFps = 0.0
        lastPoseForOverlap = null
        lastQuality = QualityMetrics.NOT_ANALYSED
        exifSummary = "not checked"

        rateController = RateController(prefs.mode, prefs.adaptResolution)
        rateController.reset()
        smartTrigger.reset()

        qualityAnalyzer?.stop()
        qualityAnalyzer = if (prefs.analyseQuality) {
            QualityAnalyzer(prefs.sharpnessThreshold.toDouble()) { r -> onQualityResult(r) }
                .also { it.start() }
        } else {
            null
        }

        if (prefs.detectTags) ensureTagAnalyzer()
        tagAnalyzer?.reset()

        writer?.log("session start: mode=${prefs.mode}, engine=$engineMode, camera=${cameraInfo?.describe()}")
        writer?.log("capture size=${captureSize?.width}x${captureSize?.height}, jpeg quality=${prefs.jpegQuality}")

        sessionStartedAtMs = SystemClock.elapsedRealtime()
        lastCaptureAtMs = 0L
        recording = true
        startPacer()
        listener.onMessageText("Projekt ${l.name}", false)
        pushUiState(force = true)
        return true
    }

    fun stopRecording(userRequested: Boolean = true) {
        if (!recording) return
        recording = false
        stopPacer()
        val l = layout
        val w = writer
        val durationMs = SystemClock.elapsedRealtime() - sessionStartedAtMs
        val avgFps = if (durationMs > 0) nextPhotoId.get().minus(1) * 1000.0 / durationMs else 0.0

        metaExecutor.execute {
            try {
                // photos first: nothing is reported as finished before the last
                // JPEG is actually on storage
                writeQueue.drain(30_000)
                val all = snapshotRecords()
                w?.rewriteCameras(all)
                w?.writeTagDistances(tagDistances.toList())
                if (l != null) {
                    w?.writeReadme(ReadmeText.build(l.name, all.size))
                    w?.writeDescriptor(buildDescriptor(l, all, durationMs, avgFps))
                }
                w?.log("session end: ${all.size} photos, ${durationMs / 1000} s, avg %.2f fps".format(avgFps))
                w?.flushAll()
                w?.close()
                val summary = SessionSummary(
                    projectName = l?.name ?: "",
                    projectPath = l?.root?.absolutePath ?: "",
                    photoCount = all.size,
                    durationMs = durationMs,
                    averageFps = avgFps,
                    blurryCount = all.count { QualityFlag.BLURRY in it.quality.flags },
                    exposureFlagged = all.count {
                        QualityFlag.DARK in it.quality.flags || QualityFlag.BRIGHT in it.quality.flags
                    },
                    notAnalysed = all.count { !it.quality.analysed },
                    tagIds = tagAnalyzer?.uniqueIds() ?: emptyList(),
                    arUsed = arEngine.isResumed || engineMode == CaptureEngine.Mode.SHARED_AR,
                    bytes = l?.totalBytes() ?: 0
                )
                listener.onSessionFinished(summary)
            } catch (e: Throwable) {
                Log.e(TAG, "finishing the session failed", e)
                listener.onMessageText("Uzavření projektu selhalo: ${e.message}", true)
            }
        }
        qualityAnalyzer?.stop()
        pushUiState(force = true)
    }

    private fun startPacer() {
        if (pacerThread == null) {
            val t = HandlerThread("capture-pacer")
            t.start()
            pacerThread = t
            pacerHandler = Handler(t.looper)
        }
        pacerHandler?.post(pacerTick)
    }

    private fun stopPacer() {
        pacerHandler?.removeCallbacks(pacerTick)
    }

    private val pacerTick = object : Runnable {
        override fun run() {
            if (!recording) return
            try { tick() } catch (e: Throwable) { Log.e(TAG, "pacer failed", e) }
            pacerHandler?.postDelayed(this, PACER_INTERVAL_MS)
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val load = RateController.Load(
            writeQueueFill = writeQueue.fill,
            inFlight = engine.inFlight.get(),
            thermalStatus = thermal.status,
            storageLow = storage.isLow()
        )
        if (load.storageLow) {
            flashWarning("warn_storage")
            stopRecording()
            return
        }
        if (!rateController.mayCapture(now, lastCaptureAtMs, load)) {
            pushUiState()
            return
        }

        val pose = arEngine.latestPose()
        val poseValid = pose?.valid == true

        if (prefs.mode == CaptureMode.SMART) {
            val overlap = estimateOverlapWith(pose)
            val decision = smartTrigger.shouldCapture(
                now, pose?.position, pose?.rotation, poseValid,
                motion.angularSpeedRadS, overlap
            )
            if (!decision.capture) {
                pushUiState()
                return
            }
        } else if (motion.isMovingTooFast()) {
            // AUTO and DETAIL: never skip a scheduled photo because of motion, but
            // do tell the operator - a gap in coverage is worse than a soft frame
            flashWarning("warn_too_fast")
        }

        if (engine.captureStill()) {
            lastCaptureAtMs = now
            rateController.onCaptureIssued()
            smartTrigger.onCaptured(pose?.position, pose?.rotation, now)
        } else {
            rateController.onCaptureDropped()
        }

        when (rateController.evaluate(load)) {
            RateController.Action.SLOW_DOWN -> flashWarning(
                if (thermal.isThrottling()) "warn_thermal" else "warn_queue"
            )
            RateController.Action.LOWER_RESOLUTION -> {
                engine.requestResolutionLevel(rateController.currentResolutionLevel)
                flashWarning("warn_queue")
            }
            else -> Unit
        }
        pushUiState()
    }

    // ------------------------------------------------------- CaptureEngine events

    override fun onEngineReady(info: CameraInfo, jpegSize: Size, mode: CaptureEngine.Mode) {
        cameraInfo = info
        captureSize = jpegSize
        engineMode = mode
        fieldOfView = info.fieldOfView(jpegSize)
        if (mode == CaptureEngine.Mode.SHARED_AR) {
            prefs.resetSharedCameraFailures()
            arStatusKey = "ar_init"
        } else if (prefs.useAr && arFallbackReason.isNotEmpty()) {
            arStatusKey = "ar_unavailable"
        }
        val preview = info.previewSizeFor(jpegSize)
        listener.onEngineConfigured(
            sharedAr = mode == CaptureEngine.Mode.SHARED_AR,
            sensorOrientation = info.sensorOrientation,
            previewWidth = preview.width,
            previewHeight = preview.height
        )
        val fps = info.theoreticalMaxFps(jpegSize)
        Log.i(TAG, "engine ready: ${info.describe()} as $mode, advertised max %.2f fps".format(fps))
        if (!fps.isNaN() && fps < 1.9) {
            listener.onMessageText(
                "Hardware udává max %.1f snímku/s při %dx%d".format(fps, jpegSize.width, jpegSize.height),
                true
            )
        }
        pushUiState(force = true)
    }

    override fun onEngineError(message: String, fatal: Boolean) {
        Log.e(TAG, "engine error: $message (fatal=$fatal)")
        if (fatal) {
            if (recording) stopRecording()
            listener.onFatalError(message)
        } else {
            listener.onMessageText(message, true)
        }
    }

    override fun onStillCaptured(jpeg: ByteArray, sensorTimestampNs: Long, size: Size) {
        val id = nextPhotoId.getAndIncrement()
        val l = layout
        if (l == null || !recording) return
        val file = l.imageFile(id)
        val wallClock = System.currentTimeMillis()

        val matched = arEngine.poseAt(sensorTimestampNs)
        val pose = matched?.first
        val offset = matched?.second ?: 0L
        val usablePose = if (pose != null && pose.valid && kotlin.math.abs(offset) < MAX_POSE_OFFSET_NS) pose else pose

        val intrinsics = scaleIntrinsics(size)
        val record = PhotoRecord(
            id = id,
            fileName = file.name,
            captureTimestampNs = sensorTimestampNs,
            wallClockEpochMs = wallClock,
            width = size.width,
            height = size.height,
            fileSizeBytes = jpeg.size.toLong(),
            mode = prefs.mode,
            cameraId = cameraInfo?.cameraId ?: "",
            pose = usablePose,
            poseTimeOffsetNs = offset,
            arIntrinsicsFx = intrinsics?.get(0),
            arIntrinsicsFy = intrinsics?.get(1),
            arIntrinsicsCx = intrinsics?.get(2),
            arIntrinsicsCy = intrinsics?.get(3)
        )
        records[id] = record
        recordOrder.add(id)

        val poseOverlap = estimateOverlapWith(usablePose)
        lastPoseForOverlap = usablePose

        // 1) straight onto the write queue - this is the critical path
        writeQueue.submit(
            PhotoWriteQueue.Task(
                bytes = jpeg,
                target = file,
                onWritten = { f, len ->
                    record.fileSizeBytes = len
                    storage.onPhotoWritten(len)
                    rateController.onPhotoSaved(SystemClock.elapsedRealtime())
                    val fps = rateController.measuredFps()
                    if (fps > peakFps) peakFps = fps
                    metaExecutor.execute {
                        writer?.appendPhoto(record)
                        if (record.id == 1) checkExif(f)
                    }
                },
                onError = { f, e ->
                    listener.onMessageText("Zápis ${f.name} selhal: ${e.message}", true)
                }
            )
        )

        // 2) everything else is optional and runs elsewhere
        qualityAnalyzer?.submit(
            QualityAnalyzer.Job(id, jpeg, motion.angularSpeedRadS, poseOverlap)
        )

        if (usablePose != null && usablePose.valid) {
            val position = usablePose.position
            val forward = usablePose.rotation.forward()
            markerPositions.add(position)
            markerForwards.add(forward)
            markerOk.add(true)
            if (markerPositions.size > MAX_MARKERS) {
                markerPositions.removeAt(0); markerForwards.removeAt(0); markerOk.removeAt(0)
            }
            val fov = fieldOfView
            metaExecutor.execute {
                shellCoverage.setCenter(currentObjectCenter(position, forward))
                shellCoverage.addView(position)
                voxelCoverage.addView(
                    ViewFrustum(
                        position, usablePose.rotation, fov.first, fov.second,
                        coverageConfig.nearPlaneM, coverageConfig.farPlaneM
                    )
                )
            }
        }
    }

    override fun onStillMetadata(sensorTimestampNs: Long, result: TotalCaptureResult) {
        metaExecutor.execute {
            // find the record with the closest capture timestamp
            val ids = synchronized(recordOrder) { recordOrder.toList() }
            var best: PhotoRecord? = null
            var bestDiff = Long.MAX_VALUE
            for (i in ids.indices.reversed()) {
                val r = records[ids[i]] ?: continue
                val d = kotlin.math.abs(r.captureTimestampNs - sensorTimestampNs)
                if (d < bestDiff) { bestDiff = d; best = r }
                if (bestDiff == 0L) break
                if (ids.size - i > 8) break
            }
            val r = best ?: return@execute
            if (bestDiff > 20_000_000L) return@execute
            val updated = r.copy(
                focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
                fNumber = result.get(CaptureResult.LENS_APERTURE),
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            )
            updated.quality = r.quality
            updated.tagIds = r.tagIds
            records[r.id] = updated
        }
    }

    override fun onStillFailed(reason: String) {
        rateController.onCaptureDropped()
        Log.w(TAG, "still failed: $reason")
        writer?.log("capture failed: $reason")
    }

    override fun onAnalysisFrame(luma: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (prefs.detectTags) {
            ensureTagAnalyzer()
            tagAnalyzer?.offer(luma, width, height, timestampNs)
        }
    }

    override fun onArFallback(reason: String) {
        arFallbackReason = reason
        arStatusKey = "ar_unavailable"
        arTracking = false
        engineMode = CaptureEngine.Mode.PLAIN
        writer?.log("AR fallback: $reason")
        listener.onMessageKey("ar_fallback", true)
        cameraInfo?.let { info ->
            val size = captureSize ?: Size(1920, 1080)
            val preview = info.previewSizeFor(size)
            listener.onEngineConfigured(false, info.sensorOrientation, preview.width, preview.height)
        }
        pushUiState(force = true)
    }

    override fun onResolutionChanged(size: Size) {
        captureSize = size
        cameraInfo?.let { fieldOfView = it.fieldOfView(size) }
        listener.onMessageText("Rozlišení ${size.width}x${size.height}", true)
        pushUiState(force = true)
    }

    // --------------------------------------------------------------- AR frame in

    /** Called from the GL thread once per rendered frame. */
    fun onArFrame(snapshot: ArEngine.FrameSnapshot?) {
        if (snapshot == null) return
        val now = SystemClock.elapsedRealtime()
        val tracking = snapshot.pose.valid
        if (tracking != arTracking) {
            arTracking = tracking
            if (!tracking) {
                flashWarning(
                    when (snapshot.failureReason.name) {
                        "EXCESSIVE_MOTION" -> "ar_excessive_motion"
                        "INSUFFICIENT_LIGHT" -> "ar_insufficient_light"
                        "INSUFFICIENT_FEATURES" -> "ar_insufficient_features"
                        else -> "ar_lost"
                    }
                )
                writer?.log("AR tracking lost: ${snapshot.failureReason.name}")
            }
        }
        arStatusKey = if (tracking) "ar_tracking" else "ar_paused"

        if (recording && now - lastTrajectoryAppendAtMs >= TRAJECTORY_INTERVAL_MS) {
            lastTrajectoryAppendAtMs = now
            val sample = snapshot.pose
            val wall = System.currentTimeMillis()
            metaExecutor.execute { writer?.appendPose(sample, wall) }
        }

        if (now - lastPointHarvestAtMs >= POINT_HARVEST_INTERVAL_MS) {
            lastPointHarvestAtMs = now
            val points = arEngine.takePoints()
            if (points.isNotEmpty()) {
                metaExecutor.execute {
                    voxelCoverage.addPoints(points)
                    voxelCoverage.centroid()?.let { objectCenter = it }
                }
            }
        }

        if (tracking) {
            objectCenter?.let { c ->
                estimatedObjectDistance = (c - snapshot.pose.position).length()
                    .coerceIn(0.4f, 40f)
            }
        }

        if (snapshot.luma != null && prefs.detectTags) {
            ensureTagAnalyzer()
            tagAnalyzer?.offer(snapshot.luma, snapshot.lumaWidth, snapshot.lumaHeight, snapshot.cameraTimestampNs)
        }
        pushUiState()
    }

    /** Whether the renderer should ask ARCore for a CPU image this frame. */
    fun wantsLumaNow(): Boolean {
        if (!prefs.detectTags) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastLumaRequestAtMs < LUMA_INTERVAL_MS) return false
        lastLumaRequestAtMs = now
        return true
    }

    fun wantsPointCloud(): Boolean = true

    // ------------------------------------------------------------------ analysers

    private fun ensureTagAnalyzer() {
        if (tagAnalyzer != null) return
        val a = TagAnalyzer(context) { detections -> onTagDetections(detections) }
        a.start()
        tagAnalyzer = a
    }

    private fun onTagDetections(detections: List<TagObservation>) {
        if (detections.isEmpty()) return
        val latestPhoto = recordOrder.lastOrNull()
        val stamped = if (recording && latestPhoto != null) {
            detections.map { it.copy(photoId = latestPhoto) }
        } else {
            detections
        }
        if (recording) metaExecutor.execute { writer?.appendDetections(stamped) }
        val first = detections.first()
        listener.onTagDetections(stamped, first.sourceWidth, first.sourceHeight)
    }

    private fun onQualityResult(result: QualityAnalyzer.Result) {
        val r = records[result.photoId] ?: return
        r.quality = result.metrics
        lastQuality = result.metrics
        val idx = recordOrder.indexOf(result.photoId)
        if (idx >= 0 && idx < markerOk.size) {
            try { markerOk[idx] = result.metrics.ok } catch (_: Exception) { }
        }
        metaExecutor.execute { writer?.appendQuality(r) }
        if (QualityFlag.BLURRY in result.metrics.flags) flashWarning("warn_blurry")
        else if (QualityFlag.DARK in result.metrics.flags) flashWarning("warn_dark")
        else if (QualityFlag.BRIGHT in result.metrics.flags) flashWarning("warn_bright")
        else if (QualityFlag.DUPLICATE in result.metrics.flags) flashWarning("warn_duplicate")
        else if (QualityFlag.LOW_OVERLAP in result.metrics.flags) flashWarning("warn_low_overlap")
        pushUiState()
    }

    // --------------------------------------------------------------------- helpers

    private fun estimateOverlapWith(current: PoseSample?): Double {
        val prev = lastPoseForOverlap ?: return -1.0
        if (current == null || !current.valid || !prev.valid) return -1.0
        val fov = fieldOfView
        return OverlapEstimator.fromPoses(
            prev.position, prev.rotation, current.position, current.rotation,
            estimatedObjectDistance, fov.first, fov.second
        )
    }

    private fun currentObjectCenter(cameraPos: Vec3, forward: Vec3): Vec3 {
        objectCenter?.let { return it }
        // no feature points yet: assume the subject sits in front of the camera at
        // the configured distance
        return cameraPos + forward * prefs.objectDistanceM
    }

    private fun scaleIntrinsics(photoSize: Size): FloatArray? {
        val latest = arEngine.latestPose() ?: return null
        if (!latest.valid) return null
        val snap = lastIntrinsics ?: return null
        if (snap[4] <= 0f || snap[5] <= 0f) return null
        val sx = photoSize.width / snap[4]
        val sy = photoSize.height / snap[5]
        return floatArrayOf(snap[0] * sx, snap[1] * sy, snap[2] * sx, snap[3] * sy)
    }

    @Volatile private var lastIntrinsics: FloatArray? = null

    fun recordIntrinsics(snapshot: ArEngine.FrameSnapshot) {
        if (snapshot.intrinsicsWidth > 0) {
            lastIntrinsics = floatArrayOf(
                snapshot.intrinsicsFx, snapshot.intrinsicsFy,
                snapshot.intrinsicsCx, snapshot.intrinsicsCy,
                snapshot.intrinsicsWidth.toFloat(), snapshot.intrinsicsHeight.toFloat()
            )
        }
    }

    private fun checkExif(file: File) {
        exifSummary = try {
            val exif = ExifInterface(file.absolutePath)
            val make = exif.getAttribute(ExifInterface.TAG_MAKE)
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)
            val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
            val exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            val orientation = exif.getAttribute(ExifInterface.TAG_ORIENTATION)
            "make=$make model=$model focal=$focal exposure=$exposure iso=$iso orientation=$orientation"
        } catch (e: Throwable) {
            "EXIF unreadable: ${e.message}"
        }
        writer?.log("EXIF of the first photo: $exifSummary")
    }

    private fun snapshotRecords(): List<PhotoRecord> {
        val ids = synchronized(recordOrder) { recordOrder.toList() }
        return ids.mapNotNull { records[it] }
    }

    private fun buildDescriptor(
        l: ProjectLayout,
        all: List<PhotoRecord>,
        durationMs: Long,
        avgFps: Double
    ): ProjectDescriptor {
        val cam = cameraInfo
        val size = captureSize
        val notes = ArrayList(ProjectDescriptor.DEFAULT_NOTES)
        notes.add("EXIF of the first photo: $exifSummary")
        if (arFallbackReason.isNotEmpty()) notes.add("AR note: $arFallbackReason")
        if (cam != null && !cam.timestampIsRealtime) {
            notes.add(
                "This camera reports ${cam.timestampSourceName} timestamps, not REALTIME, so the " +
                    "photo-to-pose association is less reliable than usual."
            )
        }
        notes.add("Theoretical maximum rate advertised by the driver for this size: " +
            (size?.let { s -> cam?.theoreticalMaxFps(s)?.let { "%.2f fps".format(it) } } ?: "unknown"))
        notes.add("Write queue: max depth ${writeQueue.maxObservedDepth.get()} of ${writeQueue.capacity}, " +
            "${writeQueue.writeFailures.get()} failures, blocked ${writeQueue.totalBlockedMs.get()} ms in total")

        return ProjectDescriptor(
            projectName = l.name,
            appVersion = appVersion(),
            createdEpochMs = System.currentTimeMillis() - durationMs,
            finishedEpochMs = System.currentTimeMillis(),
            deviceManufacturer = android.os.Build.MANUFACTURER,
            deviceModel = android.os.Build.MODEL,
            androidRelease = android.os.Build.VERSION.RELEASE,
            androidSdkInt = android.os.Build.VERSION.SDK_INT,
            cameraId = cam?.cameraId ?: "",
            cameraFacing = cam?.facingName ?: "",
            sensorOrientationDeg = cam?.sensorOrientation ?: 0,
            sensorTimestampSource = cam?.timestampSourceName ?: "UNKNOWN",
            sensorPhysicalWidthMm = cam?.physicalSize?.width,
            sensorPhysicalHeightMm = cam?.physicalSize?.height,
            activeArrayWidth = cam?.activeArray?.width(),
            activeArrayHeight = cam?.activeArray?.height(),
            availableFocalLengthsMm = cam?.focalLengths?.toList() ?: emptyList(),
            captureWidth = size?.width ?: 0,
            captureHeight = size?.height ?: 0,
            jpegQuality = prefs.jpegQuality,
            mode = prefs.mode,
            targetIntervalMs = rateController.currentIntervalMs,
            photosSaved = all.size,
            capturesIssued = rateController.issuedCount,
            capturesDropped = rateController.droppedCount,
            measuredFpsAverage = avgFps,
            measuredFpsPeak = peakFps,
            sessionDurationMs = durationMs,
            arRequested = prefs.useAr,
            arUsed = arEngine.validPoseCount > 0,
            arSharedCamera = engineMode == CaptureEngine.Mode.SHARED_AR,
            arUnavailableReason = arFallbackReason,
            arPosesValidCount = arEngine.validPoseCount,
            arTrackingLostCount = arEngine.trackingLostEvents,
            tagFamily = "tag36h11",
            tagDetectionEnabled = prefs.detectTags,
            uniqueTagIds = tagAnalyzer?.uniqueIds() ?: emptyList(),
            voxelCoverage = voxelCoverage.summary(),
            shellCoverage = shellCoverage.summary(),
            qualityAnalysisEnabled = prefs.analyseQuality,
            photosFlaggedBlurry = all.count { QualityFlag.BLURRY in it.quality.flags },
            photosFlaggedExposure = all.count {
                QualityFlag.DARK in it.quality.flags || QualityFlag.BRIGHT in it.quality.flags
            },
            photosNotAnalysed = all.count { !it.quality.analysed },
            exifOrientationPolicy = if (prefs.exifOrientationZero) "always 0" else "from device orientation",
            notes = notes
        )
    }

    private fun appVersion(): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName}"
    } catch (_: Throwable) {
        "1.0.0"
    }

    fun addTagDistance(d: TagDistance) {
        tagDistances.add(d)
        metaExecutor.execute { writer?.writeTagDistances(tagDistances.toList()) }
    }

    fun tagDistanceList(): List<TagDistance> = tagDistances.toList()

    fun detectedTagIds(): List<Int> = tagAnalyzer?.uniqueIds() ?: emptyList()

    fun currentLayout(): ProjectLayout? = layout

    fun recentPhotos(limit: Int): List<PhotoRecord> {
        val ids = synchronized(recordOrder) { recordOrder.toList() }
        return ids.takeLast(limit).reversed().mapNotNull { records[it] }
    }

    private fun flashWarning(key: String) {
        warningKey = key
        warningUntilMs = SystemClock.elapsedRealtime() + WARNING_HOLD_MS
        listener.onMessageKey(key, true)
    }

    // --------------------------------------------------------------- UI plumbing

    private fun pushUiState(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUiPushAtMs < UI_INTERVAL_MS) return
        lastUiPushAtMs = now
        val size = captureSize
        val voxel = voxelCoverage.summary()
        val shell = shellCoverage.summary()
        val state = UiState(
            recording = recording,
            projectName = layout?.name ?: "",
            mode = prefs.mode,
            photoCount = recordOrder.size,
            measuredFps = rateController.measuredFps(),
            targetFps = rateController.targetFps(),
            captureWidth = size?.width ?: 0,
            captureHeight = size?.height ?: 0,
            sharpnessScore = if (lastQuality.analysed) lastQuality.sharpnessScore else -1.0,
            lastLaplacianVar = lastQuality.laplacianVariance,
            overlapPct = lastQuality.overlapWithPrevious * 100.0,
            arEnabled = prefs.useAr,
            arTracking = arTracking,
            arStatusKey = arStatusKey,
            arSharedCamera = engineMode == CaptureEngine.Mode.SHARED_AR,
            voxelGreenPct = voxel.greenPct,
            voxelCells = voxel.cells,
            shellGreenPct = shell.greenPct,
            adviceKey = adviceKey(),
            tagCount = tagAnalyzer?.lastDetections?.size ?: 0,
            tagIdsSeen = tagAnalyzer?.uniqueIds()?.size ?: 0,
            storageFreeBytes = storage.freeBytes(),
            estimatedRemainingPhotos = storage.estimatedRemainingPhotos(),
            thermalStatus = thermal.status,
            writeQueueFill = writeQueue.fill,
            writeQueueDepth = writeQueue.depth,
            pendingAnalysis = qualityAnalyzer?.pending ?: 0,
            movingTooFast = motion.isMovingTooFast(),
            sessionSeconds = if (recording) (now - sessionStartedAtMs) / 1000 else 0,
            droppedCaptures = rateController.droppedCount,
            notAnalysed = qualityAnalyzer?.skipped?.get() ?: 0,
            warningKey = if (now < warningUntilMs) warningKey else ""
        )
        listener.onUiState(state)
    }

    private fun adviceKey(): String {
        val pose = arEngine.latestPose() ?: return ""
        if (!pose.valid) return ""
        val target = shellCoverage.worstDirectionNear(pose.position) ?: return "advice_ok"
        val center = shellCoverage.centerOrNull() ?: return ""
        val desired = (center + target * max(1f, estimatedObjectDistance)) - pose.position
        if (desired.length() < 0.3f) return "advice_ok"
        val right = pose.rotation.right()
        val up = pose.rotation.up()
        val forward = pose.rotation.forward()
        val dr = desired.dot(right)
        val du = desired.dot(up)
        val df = desired.dot(forward)
        return when {
            kotlin.math.abs(dr) > kotlin.math.abs(du) && kotlin.math.abs(dr) > kotlin.math.abs(df) ->
                if (dr > 0) "dir_right" else "dir_left"
            kotlin.math.abs(du) > kotlin.math.abs(df) -> if (du > 0) "dir_up" else "dir_down"
            else -> if (df > 0) "dir_forward" else "dir_back"
        }
    }

    /** Rebuilt at most a few times per second; called from the GL thread. */
    fun sceneSnapshot(): SceneSnapshot {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSceneBuildAtMs < SCENE_INTERVAL_MS) return scene
        lastSceneBuildAtMs = now
        val trajectory = arEngine.trajectorySnapshot(1200)
        val traj = FloatArray(trajectory.size * 3)
        for (i in trajectory.indices) {
            traj[i * 3] = trajectory[i].x; traj[i * 3 + 1] = trajectory[i].y; traj[i * 3 + 2] = trajectory[i].z
        }
        val positions = synchronized(markerPositions) { ArrayList(markerPositions) }
        val forwards = synchronized(markerForwards) { ArrayList(markerForwards) }
        val oks = synchronized(markerOk) { ArrayList(markerOk) }
        val markers = FloatArray(positions.size * 3)
        val colors = FloatArray(positions.size * 3)
        val rays = FloatArray(positions.size * 6)
        for (i in positions.indices) {
            val p = positions[i]
            markers[i * 3] = p.x; markers[i * 3 + 1] = p.y; markers[i * 3 + 2] = p.z
            val ok = oks.getOrElse(i) { true }
            colors[i * 3] = if (ok) 0.21f else 1.0f
            colors[i * 3 + 1] = if (ok) 0.82f else 0.75f
            colors[i * 3 + 2] = if (ok) 0.50f else 0.29f
            val f = forwards.getOrElse(i) { Vec3(0f, 0f, -1f) }
            val e = p + f * 0.22f
            rays[i * 6] = p.x; rays[i * 6 + 1] = p.y; rays[i * 6 + 2] = p.z
            rays[i * 6 + 3] = e.x; rays[i * 6 + 4] = e.y; rays[i * 6 + 5] = e.z
        }
        val voxels = voxelCoverage.snapshot(1500)
        val vox = FloatArray(voxels.size * 3)
        val voxColors = FloatArray(voxels.size * 3)
        for (i in voxels.indices) {
            val (p, st) = voxels[i]
            vox[i * 3] = p.x; vox[i * 3 + 1] = p.y; vox[i * 3 + 2] = p.z
            when (st) {
                CoverageState.GREEN -> { voxColors[i * 3] = 0.21f; voxColors[i * 3 + 1] = 0.82f; voxColors[i * 3 + 2] = 0.50f }
                CoverageState.YELLOW -> { voxColors[i * 3] = 0.96f; voxColors[i * 3 + 1] = 0.75f; voxColors[i * 3 + 2] = 0.29f }
                CoverageState.RED -> { voxColors[i * 3] = 0.90f; voxColors[i * 3 + 1] = 0.28f; voxColors[i * 3 + 2] = 0.30f }
            }
        }
        val center = objectCenter ?: shellCoverage.centerOrNull()
        val advice = arEngine.latestPose()?.takeIf { it.valid }?.let { pose ->
            val dir = shellCoverage.worstDirectionNear(pose.position)
            val c = shellCoverage.centerOrNull()
            if (dir != null && c != null) c + dir * max(1f, estimatedObjectDistance) else null
        }
        scene = SceneSnapshot(
            trajectory = traj,
            captureMarkers = markers,
            captureColors = colors,
            viewRays = rays,
            coverageVoxels = vox,
            coverageColors = voxColors,
            objectCenter = center?.let { floatArrayOf(it.x, it.y, it.z) },
            adviceTarget = advice?.let { floatArrayOf(it.x, it.y, it.z) }
        )
        return scene
    }

    fun projectsDirectory(): File = projectsDir

    private companion object {
        const val TAG = "CaptureController"
        const val PACER_INTERVAL_MS = 20L
        const val UI_INTERVAL_MS = 150L
        const val TRAJECTORY_INTERVAL_MS = 100L
        const val POINT_HARVEST_INTERVAL_MS = 400L
        const val SCENE_INTERVAL_MS = 180L
        const val LUMA_INTERVAL_MS = 130L
        const val WARNING_HOLD_MS = 1800L
        const val MAX_MARKERS = 3000
        const val MAX_POSE_OFFSET_NS = 60_000_000L
    }
}
