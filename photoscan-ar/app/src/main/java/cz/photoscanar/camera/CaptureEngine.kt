@file:Suppress("DEPRECATION")

package cz.photoscanar.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import cz.photoscanar.ar.ArEngine
import cz.photoscanar.model.CaptureMode
import cz.photoscanar.settings.Prefs
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The capture path: Camera2 still capture, optionally sharing the camera with
 * ARCore.
 *
 * Non-negotiable property of this class: a photo is never made to wait for AR,
 * for quality analysis or for tag detection. The JPEG leaves the ImageReader,
 * gets copied and is handed to the write queue; metadata that arrives later is
 * patched into the record afterwards.
 *
 * Two configurations are supported and the engine switches between them at
 * runtime:
 *   SHARED  - ARCore owns the repeating request and the preview texture; our
 *             JPEG surface is part of the capture session but not of ARCore's
 *             repeating request, so stills are fired exactly when we ask.
 *   PLAIN   - no ARCore: our own preview surface, our own repeating request and
 *             a small YUV stream for the analysers.
 */
class CaptureEngine(
    private val context: Context,
    private val prefs: Prefs,
    private val arEngine: ArEngine,
    private val listener: Listener
) {

    enum class Mode { SHARED_AR, PLAIN }

    interface Listener {
        fun onEngineReady(info: CameraInfo, jpegSize: Size, mode: Mode)
        fun onEngineError(message: String, fatal: Boolean)
        /** Called on a camera thread; must return quickly. */
        fun onStillCaptured(jpeg: ByteArray, sensorTimestampNs: Long, size: Size)
        /** Capture metadata, possibly a little after the image. */
        fun onStillMetadata(sensorTimestampNs: Long, result: TotalCaptureResult)
        fun onStillFailed(reason: String)
        /** Low-resolution luma for the analysers (PLAIN mode only). */
        fun onAnalysisFrame(luma: ByteArray, width: Int, height: Int, timestampNs: Long)
        fun onArFallback(reason: String)
        fun onResolutionChanged(size: Size)
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var analysisReader: ImageReader? = null

    private var previewSurface: Surface? = null

    var info: CameraInfo? = null
        private set
    var jpegSize: Size? = null
        private set
    var mode: Mode = Mode.PLAIN
        private set
    var analysisSize: Size? = null
        private set

    @Volatile var isReady: Boolean = false
        private set
    @Volatile private var closing = false
    @Volatile private var resolutionLevel = 0
    @Volatile private var requestedMode: Mode = Mode.PLAIN
    @Volatile private var desiredTargetFps: Double = 2.0

    /** Still captures handed to the camera but not yet delivered as images. */
    val inFlight = AtomicInteger(0)

    // last known auto-exposure result, used by the anti-blur path
    @Volatile private var lastExposureTimeNs: Long = 0
    @Volatile private var lastIso: Int = 0
    @Volatile private var lastAfState: Int = -1
    @Volatile private var lastAeState: Int = -1

    @Volatile var jpegOrientationDegrees: Int = 0

    private val pendingResults = HashMap<Long, TotalCaptureResult>()

    // ------------------------------------------------------------------ startup

    fun start(requestMode: Mode, targetFps: Double, mode1xOnly: Boolean = true) {
        closing = false
        requestedMode = requestMode
        desiredTargetFps = targetFps
        if (cameraThread == null) {
            val t = HandlerThread("photoscan-camera")
            t.start()
            cameraThread = t
            cameraHandler = Handler(t.looper)
        }
        cameraHandler!!.post { openCameraInternal(mode1xOnly) }
    }

    /** The renderer hands us its preview surface (PLAIN mode only). */
    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) {
        previewSurface = surface
        if (requestedMode == Mode.PLAIN && surface != null && cameraDevice != null && captureSession == null) {
            cameraHandler?.post { createSessionPlain() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraInternal(mode1xOnly: Boolean) {
        try {
            var chosen: CameraInfo? = null
            var effectiveMode = requestedMode

            if (requestedMode == Mode.SHARED_AR) {
                val arCamId = arEngine.arCameraId
                if (arCamId == null) {
                    effectiveMode = Mode.PLAIN
                    listener.onArFallback("ARCore session has no camera id")
                } else {
                    chosen = CameraInfo.byId(cameraManager, arCamId)
                    if (chosen == null) {
                        effectiveMode = Mode.PLAIN
                        listener.onArFallback("ARCore camera $arCamId not readable")
                    }
                }
            }
            if (chosen == null) {
                chosen = CameraInfo.selectMainRearCamera(cameraManager)
            }
            if (chosen == null) {
                listener.onEngineError("No usable rear camera on this device", true)
                return
            }
            mode = effectiveMode
            info = chosen
            val size = chooseInitialJpegSize(chosen)
            if (size == null) {
                listener.onEngineError("Camera reports no JPEG output size", true)
                return
            }
            jpegSize = size
            Log.i(TAG, "opening ${chosen.describe()} as $mode, JPEG ${size.width}x${size.height}")

            createReaders(chosen, size)

            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    if (mode == Mode.SHARED_AR) createSessionShared() else createSessionPlain()
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "camera disconnected")
                    device.close()
                    cameraDevice = null
                    isReady = false
                    if (!closing) listener.onEngineError("Camera disconnected", false)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "camera error $error")
                    device.close()
                    cameraDevice = null
                    isReady = false
                    if (!closing) listener.onEngineError("Camera error $error", true)
                }
            }

            val wrapped = if (mode == Mode.SHARED_AR) {
                arEngine.setAppSurfaces(chosen.cameraId, emptyList())
                arEngine.wrapDeviceCallback(stateCallback, cameraHandler!!)
            } else {
                stateCallback
            }
            cameraManager.openCamera(chosen.cameraId, wrapped, cameraHandler!!)
        } catch (e: SecurityException) {
            listener.onEngineError("Camera permission missing", true)
        } catch (e: Throwable) {
            Log.e(TAG, "openCamera failed", e)
            if (mode == Mode.SHARED_AR) {
                fallbackToPlain("openCamera failed in shared mode: ${e.message}")
            } else {
                listener.onEngineError("Cannot open camera: ${e.message}", true)
            }
        }
    }

    /**
     * Picks the capture size.
     *
     * Rather than assuming the sensor's biggest JPEG can sustain two frames per
     * second, this consults the driver's own advertised minimum frame duration and
     * stall duration for each size and takes the largest one that can plausibly
     * keep up. DETAIL mode always takes the biggest size and accepts a lower rate.
     */
    private fun chooseInitialJpegSize(cam: CameraInfo): Size? {
        val sizes = cam.jpegSizes
        if (sizes.isEmpty()) return null
        if (prefs.mode == CaptureMode.DETAIL) return sizes[0]
        val needed = desiredTargetFps * 1.05
        for (s in sizes) {
            val fps = cam.theoreticalMaxFps(s)
            if (fps.isNaN()) continue
            if (fps >= needed) {
                if (s != sizes[0]) {
                    Log.i(
                        TAG,
                        "largest JPEG ${sizes[0]} advertises only " +
                            "%.1f fps; using $s at %.1f fps".format(
                                cam.theoreticalMaxFps(sizes[0]), fps
                            )
                    )
                }
                return s
            }
        }
        // nothing advertises the target rate: keep full resolution and let the
        // rate controller find the sustainable cadence
        return sizes[0]
    }

    private fun createReaders(cam: CameraInfo, size: Size) {
        jpegReader?.close()
        jpegReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, JPEG_BUFFERS).apply {
            setOnImageAvailableListener({ reader -> onJpegAvailable(reader) }, cameraHandler)
        }
        if (mode == Mode.PLAIN) {
            val target = cam.yuvSizes.firstOrNull { it.width >= 480 && it.width <= 1024 }
                ?: cam.yuvSizes.firstOrNull()
            analysisSize = target
            analysisReader?.close()
            analysisReader = target?.let {
                ImageReader.newInstance(it.width, it.height, ImageFormat.YUV_420_888, 3).apply {
                    setOnImageAvailableListener({ reader -> onAnalysisAvailable(reader) }, cameraHandler)
                }
            }
        } else {
            analysisReader?.close()
            analysisReader = null
            analysisSize = arEngine.arImageSize()?.let { Size(it.first, it.second) }
        }
    }

    // ------------------------------------------------------------------ sessions

    private fun createSessionShared() {
        val device = cameraDevice ?: return
        val jpeg = jpegReader ?: return
        try {
            val arSurfaces = arEngine.arSurfaces()
            if (arSurfaces.isEmpty()) {
                fallbackToPlain("ARCore provided no surfaces")
                return
            }
            val surfaces = ArrayList<Surface>(arSurfaces.size + 1)
            surfaces.addAll(arSurfaces)
            // part of the session so capture() can target it, but intentionally not
            // registered as an ARCore app surface: ARCore must not shoot JPEGs at 30 fps
            surfaces.add(jpeg.surface)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            for (s in arSurfaces) builder.addTarget(s)
            applyCommonPreviewKeys(builder)

            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
                        arEngine.setCaptureCallback(previewCaptureCallback, cameraHandler!!)
                        if (!arEngine.resume()) {
                            fallbackToPlain("ARCore resume failed: ${arEngine.lastError}")
                            return
                        }
                        isReady = true
                        listener.onEngineReady(info!!, jpegSize!!, Mode.SHARED_AR)
                    } catch (e: Throwable) {
                        fallbackToPlain("shared repeating request failed: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    fallbackToPlain("shared capture session configuration failed")
                }
            }
            device.createCaptureSession(
                surfaces,
                arEngine.wrapSessionCallback(sessionCallback, cameraHandler!!),
                cameraHandler
            )
        } catch (e: Throwable) {
            Log.e(TAG, "createSessionShared failed", e)
            fallbackToPlain("shared session error: ${e.message}")
        }
    }

    private fun createSessionPlain() {
        val device = cameraDevice ?: return
        val jpeg = jpegReader ?: return
        val preview = previewSurface
        if (preview == null) {
            // the GL renderer has not handed us a surface yet; it will re-trigger
            return
        }
        try {
            val surfaces = ArrayList<Surface>(3)
            surfaces.add(preview)
            analysisReader?.let { surfaces.add(it.surface) }
            surfaces.add(jpeg.surface)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(preview)
            analysisReader?.let { builder.addTarget(it.surface) }
            applyCommonPreviewKeys(builder)

            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
                        isReady = true
                        mode = Mode.PLAIN
                        listener.onEngineReady(info!!, jpegSize!!, Mode.PLAIN)
                    } catch (e: Throwable) {
                        listener.onEngineError("Preview request failed: ${e.message}", true)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    listener.onEngineError("Capture session configuration failed", true)
                }
            }
            device.createCaptureSession(surfaces, sessionCallback, cameraHandler)
        } catch (e: Throwable) {
            Log.e(TAG, "createSessionPlain failed", e)
            listener.onEngineError("Cannot configure camera: ${e.message}", true)
        }
    }

    /** Tears ARCore out of the loop and restarts as a plain Camera2 session. */
    private fun fallbackToPlain(reason: String) {
        Log.w(TAG, "falling back to plain Camera2: $reason")
        prefs.sharedCameraFailures = prefs.sharedCameraFailures + 1
        listener.onArFallback(reason)
        requestedMode = Mode.PLAIN
        mode = Mode.PLAIN
        arEngine.close()
        cameraHandler?.post {
            closeSessionOnly()
            openCameraInternal(true)
        }
    }

    // ------------------------------------------------------------ request tuning

    private fun applyCommonPreviewKeys(b: CaptureRequest.Builder) {
        val cam = info ?: return
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        // electronic stabilisation warps geometry - always off for photogrammetry
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        if (cam.supportsContinuousPictureAf) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        // a high target frame rate forces the AE towards short exposures, which is
        // the cheapest motion-blur defence available without manual control
        b.set(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            android.util.Range(PREVIEW_FPS_MIN, PREVIEW_FPS_MAX)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cam.maxZoomRatioRange != null) {
            // stay at the native 1x framing of the main camera
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
        }
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
    }

    private fun buildStillRequest(): CaptureRequest? {
        val device = cameraDevice ?: return null
        val jpeg = jpegReader ?: return null
        val cam = info ?: return null
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(jpeg.surface)
        applyCommonPreviewKeys(b)

        b.set(CaptureRequest.JPEG_QUALITY, prefs.jpegQuality.toByte())
        b.set(
            CaptureRequest.JPEG_ORIENTATION,
            if (prefs.exifOrientationZero) 0 else jpegOrientationDegrees
        )
        // no GPS in the EXIF, ever: the app does not ask for the location permission
        b.set(CaptureRequest.JPEG_GPS_LOCATION, null)

        if (prefs.lockAe) b.set(CaptureRequest.CONTROL_AE_LOCK, true)
        if (prefs.lockAf) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        }

        // Anti-blur: if the sensor supports manual control and the auto exposure is
        // currently longer than the blur limit, take over and cap the shutter,
        // compensating with ISO. Noise is recoverable in photogrammetry; motion
        // blur is not.
        if (prefs.antiBlur && cam.supportsManualSensor && lastExposureTimeNs > 0) {
            val limit = MAX_EXPOSURE_NS
            if (lastExposureTimeNs > limit) {
                val isoRange = cam.isoRange
                val factor = lastExposureTimeNs.toDouble() / limit
                val baseIso = if (lastIso > 0) lastIso else 100
                var iso = (baseIso * factor).roundToLong().toInt()
                if (isoRange != null) iso = iso.coerceIn(isoRange.lower, isoRange.upper)
                val expRange = cam.exposureTimeRange
                var exp = limit
                if (expRange != null) exp = exp.coerceIn(expRange.lower, expRange.upper)
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, exp)
            }
        }
        return b.build()
    }

    // -------------------------------------------------------------- still capture

    /**
     * Fires one still capture. Returns false when the camera is not in a state to
     * take it, which the rate controller treats as back pressure rather than an
     * error.
     */
    fun captureStill(): Boolean {
        val session = captureSession ?: return false
        if (!isReady || closing) return false
        val request = try { buildStillRequest() } catch (e: Throwable) {
            Log.w(TAG, "buildStillRequest failed", e); null
        } ?: return false
        return try {
            inFlight.incrementAndGet()
            session.capture(request, stillCaptureCallback, cameraHandler)
            true
        } catch (e: CameraAccessException) {
            inFlight.decrementAndGet()
            listener.onStillFailed("capture failed: ${e.message}")
            false
        } catch (e: Throwable) {
            inFlight.decrementAndGet()
            listener.onStillFailed("capture failed: ${e.message}")
            false
        }
    }

    private val stillCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            synchronized(pendingResults) {
                pendingResults[ts] = result
                if (pendingResults.size > 16) {
                    val oldest = pendingResults.keys.minOrNull()
                    if (oldest != null) pendingResults.remove(oldest)
                }
            }
            listener.onStillMetadata(ts, result)
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            inFlight.decrementAndGet()
            listener.onStillFailed("capture dropped (reason ${failure.reason})")
        }
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureTimeNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.CONTROL_AF_STATE)?.let { lastAfState = it }
            result.get(CaptureResult.CONTROL_AE_STATE)?.let { lastAeState = it }
        }
    }

    private fun onJpegAvailable(reader: ImageReader) {
        while (true) {
            val image = try { reader.acquireNextImage() } catch (e: Throwable) { null } ?: break
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val ts = image.timestamp
                val size = Size(image.width, image.height)
                inFlight.decrementAndGet()
                // straight to the listener, which puts it on the write queue
                listener.onStillCaptured(bytes, ts, size)
            } catch (e: Throwable) {
                Log.e(TAG, "failed to read JPEG", e)
                inFlight.decrementAndGet()
            } finally {
                try { image.close() } catch (_: Throwable) { }
            }
        }
    }

    private var lastAnalysisAtMs = 0L

    private fun onAnalysisAvailable(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: Throwable) { null } ?: return
        try {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAnalysisAtMs < ANALYSIS_MIN_INTERVAL_MS) return
            lastAnalysisAtMs = now
            val plane = image.planes[0]
            val buf = plane.buffer
            val w = image.width
            val h = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val out = ByteArray(w * h)
            if (rowStride == w && pixelStride == 1) {
                buf.get(out, 0, minOf(out.size, buf.remaining()))
            } else {
                val row = ByteArray(rowStride)
                var o = 0
                for (y in 0 until h) {
                    buf.position(y * rowStride)
                    val n = minOf(rowStride, buf.remaining())
                    buf.get(row, 0, n)
                    if (pixelStride == 1) {
                        System.arraycopy(row, 0, out, o, minOf(w, n))
                    } else {
                        for (x in 0 until w) out[o + x] = row[x * pixelStride]
                    }
                    o += w
                }
            }
            listener.onAnalysisFrame(out, w, h, image.timestamp)
        } catch (e: Throwable) {
            Log.w(TAG, "analysis frame failed", e)
        } finally {
            try { image.close() } catch (_: Throwable) { }
        }
    }

    fun resultFor(sensorTimestampNs: Long): TotalCaptureResult? = synchronized(pendingResults) {
        pendingResults[sensorTimestampNs] ?: pendingResults.entries
            .minByOrNull { abs(it.key - sensorTimestampNs) }
            ?.takeIf { abs(it.key - sensorTimestampNs) < 5_000_000L }
            ?.value
    }

    // ------------------------------------------------------------ reconfiguration

    /** Switches to a smaller capture size; recreates the reader and the session. */
    fun requestResolutionLevel(level: Int) {
        val cam = info ?: return
        if (level == resolutionLevel) return
        val target = cam.jpegSizeForLevel(level) ?: return
        if (target == jpegSize) return
        resolutionLevel = level
        cameraHandler?.post {
            try {
                Log.i(TAG, "switching capture size to ${target.width}x${target.height} (level $level)")
                isReady = false
                closeSessionOnly()
                jpegSize = target
                createReaders(cam, target)
                if (mode == Mode.SHARED_AR) createSessionShared() else createSessionPlain()
                listener.onResolutionChanged(target)
            } catch (e: Throwable) {
                Log.e(TAG, "resolution switch failed", e)
                listener.onEngineError("Resolution change failed: ${e.message}", false)
            }
        }
    }

    fun triggerAfOnce() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        if (mode == Mode.SHARED_AR) return   // ARCore owns the repeating request
        try {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { b.addTarget(it) }
            applyCommonPreviewKeys(b)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(b.build(), null, cameraHandler)
        } catch (_: Throwable) { }
    }

    fun lastAutoExposureTimeNs(): Long = lastExposureTimeNs
    fun lastSensitivity(): Int = lastIso
    fun isFocusLocked(): Boolean =
        lastAfState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            lastAfState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED

    // -------------------------------------------------------------------- cleanup

    private fun closeSessionOnly() {
        try { captureSession?.close() } catch (_: Throwable) { }
        captureSession = null
        try { cameraDevice?.close() } catch (_: Throwable) { }
        cameraDevice = null
        try { jpegReader?.close() } catch (_: Throwable) { }
        jpegReader = null
        try { analysisReader?.close() } catch (_: Throwable) { }
        analysisReader = null
        synchronized(pendingResults) { pendingResults.clear() }
    }

    fun stop() {
        closing = true
        isReady = false
        val h = cameraHandler
        if (h != null) {
            val done = java.util.concurrent.CountDownLatch(1)
            h.post {
                try { closeSessionOnly() } finally { done.countDown() }
            }
            try { done.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        } else {
            closeSessionOnly()
        }
        cameraThread?.quitSafely()
        try { cameraThread?.join(1000) } catch (_: InterruptedException) { }
        cameraThread = null
        cameraHandler = null
        previewSurface = null
    }

    private companion object {
        const val TAG = "CaptureEngine"
        const val JPEG_BUFFERS = 5
        const val PREVIEW_FPS_MIN = 30
        const val PREVIEW_FPS_MAX = 60
        /** 1/125 s - the practical limit for hand-held walking shots. */
        const val MAX_EXPOSURE_NS = 8_000_000L
        const val ANALYSIS_MIN_INTERVAL_MS = 120L
    }
}
