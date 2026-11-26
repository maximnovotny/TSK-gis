package cz.photoscanar.ar

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.SharedCamera
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import cz.photoscanar.geom.Quat
import cz.photoscanar.geom.Vec3
import cz.photoscanar.model.PoseQuality
import cz.photoscanar.model.PoseSample
import java.util.EnumSet
import kotlin.math.abs

/**
 * Wraps the ARCore session.
 *
 * Design rule for the whole app: AR is an aid, never a dependency. Every entry
 * point here either succeeds or reports failure, and the capture path keeps
 * running either way. ARCore is also never allowed to touch the still-capture
 * surface, so an AR stall cannot delay a photo.
 */
class ArEngine(private val context: Context) {

    enum class Availability { SUPPORTED_INSTALLED, SUPPORTED_NOT_INSTALLED, UNSUPPORTED, UNKNOWN }

    /** A per-frame snapshot handed to the renderer; no ARCore objects escape. */
    class FrameSnapshot(
        val timestampNs: Long,
        val cameraTimestampNs: Long,
        val trackingState: TrackingState,
        val failureReason: TrackingFailureReason,
        val pose: PoseSample,
        val viewMatrix: FloatArray,
        val projectionMatrix: FloatArray,
        /** 8 floats: NDC quad -> texture UVs, for the camera background shader */
        val backgroundUv: FloatArray,
        val displayGeometryChanged: Boolean,
        val intrinsicsFx: Float,
        val intrinsicsFy: Float,
        val intrinsicsCx: Float,
        val intrinsicsCy: Float,
        val intrinsicsWidth: Int,
        val intrinsicsHeight: Int,
        /** Packed Y plane of the AR camera image, when it was requested. */
        val luma: ByteArray? = null,
        val lumaWidth: Int = 0,
        val lumaHeight: Int = 0
    )

    var session: Session? = null
        private set
    var sharedCamera: SharedCamera? = null
        private set
    var arCameraId: String? = null
        private set

    @Volatile var lastError: String = ""
        private set
    @Volatile var isResumed: Boolean = false
        private set
    @Volatile var usesSharedCamera: Boolean = false
        private set
    @Volatile var trackingLostEvents: Int = 0
        private set
    @Volatile var validPoseCount: Int = 0
        private set

    private val poseBuffer = PoseHistory(POSE_HISTORY)
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val ndcQuad = floatArrayOf(-1f, -1f, +1f, -1f, -1f, +1f, +1f, +1f)
    private val texUv = FloatArray(8)
    private var lastTracking = false
    private var lastPose: PoseSample? = null

    /** Points accumulated from the AR point cloud, consumed by the coverage model. */
    private val pendingPoints = ArrayList<Vec3>(2048)

    fun availability(): Availability = try {
        when (ArCoreApk.getInstance().checkAvailability(context)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> Availability.SUPPORTED_INSTALLED
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> Availability.SUPPORTED_NOT_INSTALLED
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> Availability.UNSUPPORTED
            else -> Availability.UNKNOWN
        }
    } catch (e: Throwable) {
        lastError = e.message ?: e.javaClass.simpleName
        Availability.UNKNOWN
    }

    /**
     * Creates the session. [shared] asks for the shared-camera feature so Camera2
     * can issue full-resolution stills on the same camera ARCore is tracking with.
     *
     * @return true on success; on failure [lastError] explains why and the caller
     *         continues without AR.
     */
    fun create(shared: Boolean): Boolean {
        close()
        return try {
            val features = if (shared) EnumSet.of(Session.Feature.SHARED_CAMERA) else EnumSet.noneOf(Session.Feature::class.java)
            val s = Session(context, features)
            val config = s.config
            // AUTO focus: a fixed-focus AR config would ruin close-up detail shots
            config.focusMode = Config.FocusMode.AUTO
            // never block the GL thread waiting for a camera frame
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            // we need poses and feature points, nothing else - this saves real CPU
            config.planeFindingMode = Config.PlaneFindingMode.DISABLED
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            config.depthMode = Config.DepthMode.DISABLED
            config.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
            config.geospatialMode = Config.GeospatialMode.DISABLED
            config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
            // electronic stabilisation warps the image; it must stay off
            try { config.imageStabilizationMode = Config.ImageStabilizationMode.OFF } catch (_: Throwable) { }
            s.configure(config)
            session = s
            usesSharedCamera = shared
            if (shared) {
                sharedCamera = s.sharedCamera
                arCameraId = s.cameraConfig.cameraId
            } else {
                sharedCamera = null
                arCameraId = s.cameraConfig.cameraId
            }
            lastError = ""
            Log.i(TAG, "ARCore session created (shared=$shared, cameraId=$arCameraId)")
            true
        } catch (e: Throwable) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            Log.w(TAG, "ARCore session creation failed", e)
            session = null
            sharedCamera = null
            usesSharedCamera = false
            false
        }
    }

    /** The CPU/GPU image size ARCore is configured for. */
    fun arImageSize(): Pair<Int, Int>? = session?.let {
        try { it.cameraConfig.imageSize.let { s -> s.width to s.height } } catch (_: Throwable) { null }
    }

    fun arSurfaces(): List<Surface> = try {
        sharedCamera?.arCoreSurfaces ?: emptyList()
    } catch (e: Throwable) {
        lastError = e.message ?: "arCoreSurfaces failed"
        emptyList()
    }

    /**
     * Surfaces ARCore should include in its own repeating request.
     *
     * The still-capture JPEG surface is deliberately NOT passed here: it is only
     * added to the capture session, so ARCore's 30 fps repeating request never
     * targets it and we keep full control of when a photo is taken.
     */
    fun setAppSurfaces(cameraId: String, surfaces: List<Surface>) {
        try {
            sharedCamera?.setAppSurfaces(cameraId, surfaces)
        } catch (e: Throwable) {
            lastError = e.message ?: "setAppSurfaces failed"
            Log.w(TAG, "setAppSurfaces failed", e)
        }
    }

    fun wrapDeviceCallback(
        cb: android.hardware.camera2.CameraDevice.StateCallback,
        handler: Handler
    ): android.hardware.camera2.CameraDevice.StateCallback =
        try {
            sharedCamera?.createARDeviceStateCallback(cb, handler) ?: cb
        } catch (e: Throwable) {
            Log.w(TAG, "createARDeviceStateCallback failed", e); cb
        }

    fun wrapSessionCallback(
        cb: android.hardware.camera2.CameraCaptureSession.StateCallback,
        handler: Handler
    ): android.hardware.camera2.CameraCaptureSession.StateCallback =
        try {
            sharedCamera?.createARSessionStateCallback(cb, handler) ?: cb
        } catch (e: Throwable) {
            Log.w(TAG, "createARSessionStateCallback failed", e); cb
        }

    /** Lets ARCore see the capture results of the repeating request. */
    fun setCaptureCallback(
        cb: android.hardware.camera2.CameraCaptureSession.CaptureCallback,
        handler: Handler
    ) {
        try {
            sharedCamera?.setCaptureCallback(cb, handler)
        } catch (e: Throwable) {
            Log.w(TAG, "setCaptureCallback failed", e)
        }
    }

    fun setCameraTextureName(textureId: Int) {
        try { session?.setCameraTextureName(textureId) } catch (e: Throwable) {
            Log.w(TAG, "setCameraTextureName failed", e)
        }
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        try { session?.setDisplayGeometry(rotation, width, height) } catch (_: Throwable) { }
    }

    fun resume(): Boolean {
        val s = session ?: return false
        return try {
            s.resume()
            isResumed = true
            lastError = ""
            true
        } catch (e: Throwable) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            Log.w(TAG, "ARCore resume failed", e)
            isResumed = false
            false
        }
    }

    /**
     * Pauses tracking. In shared-camera mode this also hands the camera back, so
     * the app can carry on with its own repeating request.
     */
    fun pause() {
        try { session?.pause() } catch (_: Throwable) { }
        isResumed = false
    }

    fun close() {
        pause()
        try { session?.close() } catch (_: Throwable) { }
        session = null
        sharedCamera = null
        arCameraId = null
        isResumed = false
        usesSharedCamera = false
        poseBuffer.clear()
        synchronized(pendingPoints) { pendingPoints.clear() }
    }

    /**
     * Advances ARCore by one frame. Must be called from the GL thread.
     * Returns null when no frame was produced; callers simply render the previous
     * state and keep capturing.
     */
    fun update(collectPoints: Boolean, wantLuma: Boolean = false): FrameSnapshot? {
        val s = session ?: return null
        if (!isResumed) return null
        val frame: Frame = try {
            s.update()
        } catch (e: Throwable) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            return null
        }
        val cam = frame.camera
        val state = cam.trackingState
        val reason = try { cam.trackingFailureReason } catch (_: Throwable) { TrackingFailureReason.NONE }
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val cameraTs = try { frame.androidCameraTimestamp } catch (_: Throwable) { frame.timestamp }
        val stampNs = if (cameraTs > 0) cameraTs else nowNs

        val tracking = state == TrackingState.TRACKING
        if (lastTracking && !tracking) trackingLostEvents++
        lastTracking = tracking

        val sample = if (tracking) {
            val p = cam.pose
            val pos = Vec3(p.tx(), p.ty(), p.tz())
            val rot = Quat(p.qx(), p.qy(), p.qz(), p.qw())
            val prev = lastPose
            var linear = 0f
            var angular = 0f
            if (prev != null) {
                val dt = (stampNs - prev.timestampNs) / 1e9f
                if (dt > 1e-4f && dt < 1f) {
                    linear = (pos - prev.position).length() / dt
                    angular = abs(prev.rotation.angleTo(rot)) / dt
                }
            }
            validPoseCount++
            PoseSample(stampNs, pos, rot, PoseQuality.VALID_TRACKING, state.name, reason.name, linear, angular)
        } else {
            PoseSample(
                stampNs, Vec3.ZERO, Quat.IDENTITY,
                if (state == TrackingState.PAUSED) PoseQuality.LIMITED else PoseQuality.INVALID,
                state.name, reason.name
            )
        }
        lastPose = sample
        poseBuffer.add(sample)

        try { cam.getViewMatrix(viewMatrix, 0) } catch (_: Throwable) { }
        try { cam.getProjectionMatrix(projMatrix, 0, 0.1f, 100f) } catch (_: Throwable) { }

        val geometryChanged = try { frame.hasDisplayGeometryChanged() } catch (_: Throwable) { false }
        try {
            frame.transformCoordinates2d(
                com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndcQuad,
                com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED, texUv
            )
        } catch (_: Throwable) { }

        var fx = 0f; var fy = 0f; var cx = 0f; var cy = 0f; var iw = 0; var ih = 0
        try {
            val ins = cam.imageIntrinsics
            val fl = ins.focalLength
            val pp = ins.principalPoint
            val dim = ins.imageDimensions
            fx = fl[0]; fy = fl[1]; cx = pp[0]; cy = pp[1]; iw = dim[0]; ih = dim[1]
        } catch (_: Throwable) { }

        if (collectPoints && tracking) {
            try {
                frame.acquirePointCloud().use { pc ->
                    val buf = pc.points
                    synchronized(pendingPoints) {
                        var i = 0
                        while (i + 3 < buf.limit() && pendingPoints.size < 20000) {
                            val confidence = buf.get(i + 3)
                            if (confidence > 0.35f) {
                                pendingPoints.add(Vec3(buf.get(i), buf.get(i + 1), buf.get(i + 2)))
                            }
                            i += 4
                        }
                    }
                }
            } catch (_: Throwable) { }
        }

        var luma: ByteArray? = null
        var lumaW = 0
        var lumaH = 0
        if (wantLuma) {
            try {
                frame.acquireCameraImage().use { image ->
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
                    luma = out; lumaW = w; lumaH = h
                }
            } catch (_: Throwable) {
                // NotYetAvailableException is normal on the first frames
            }
        }

        return FrameSnapshot(
            timestampNs = nowNs,
            cameraTimestampNs = stampNs,
            trackingState = state,
            failureReason = reason,
            pose = sample,
            viewMatrix = viewMatrix,
            projectionMatrix = projMatrix,
            backgroundUv = texUv,
            displayGeometryChanged = geometryChanged,
            intrinsicsFx = fx, intrinsicsFy = fy, intrinsicsCx = cx, intrinsicsCy = cy,
            intrinsicsWidth = iw, intrinsicsHeight = ih,
            luma = luma, lumaWidth = lumaW, lumaHeight = lumaH
        )
    }

    /** Hands over the points gathered since the last call. */
    fun takePoints(): List<Vec3> = synchronized(pendingPoints) {
        if (pendingPoints.isEmpty()) emptyList()
        else {
            val copy = ArrayList(pendingPoints)
            pendingPoints.clear()
            copy
        }
    }

    /** Pose closest in time to [timestampNs], with the time offset in ns. */
    fun poseAt(timestampNs: Long): Pair<PoseSample, Long>? = poseBuffer.nearest(timestampNs)

    fun trajectorySnapshot(maxPoints: Int = 2000): List<Vec3> = poseBuffer.positions(maxPoints)

    fun latestPose(): PoseSample? = poseBuffer.latest()

    private companion object {
        const val TAG = "ArEngine"
        const val POSE_HISTORY = 4096
    }
}
