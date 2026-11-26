package cz.photoscanar.ui.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import cz.photoscanar.session.CaptureController
import cz.photoscanar.settings.Prefs
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The GL renderer: camera image plus the AR overlay.
 *
 * It also owns the ARCore update call, because ARCore has to be advanced on the
 * thread that holds the GL context. Everything it hands to the controller is a
 * plain snapshot, and if ARCore produces nothing this frame the camera image is
 * still drawn - the preview never freezes because tracking is struggling.
 */
class ArRenderer(
    private val controller: CaptureController,
    private val prefs: Prefs,
    private val callbacks: Callbacks
) : GLSurfaceView.Renderer {

    interface Callbacks {
        /** Plain mode only: the camera may now stream into this surface. */
        fun onPreviewSurfaceReady(surface: Surface, width: Int, height: Int)
        fun onGlError(message: String)
    }

    enum class Source { AR, PLAIN }

    @Volatile var source: Source = Source.PLAIN
    @Volatile var sensorOrientation: Int = 90
    @Volatile var previewBufferWidth: Int = 1920
    @Volatile var previewBufferHeight: Int = 1080
    @Volatile var displayRotationDegrees: Int = 0

    private val background = CameraBackground()
    private val overlay = OverlayDraw()
    val mapper = PreviewMapper()

    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    private val mvp = FloatArray(16)
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var surfaceAnnounced = false

    private val plainUvs = FloatArray(8)
    private val fallbackUvs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private var lastArUvs: FloatArray? = null

    private val whiteish = floatArrayOf(0.62f, 0.78f, 0.95f)
    private val adviceColor = floatArrayOf(0.98f, 0.82f, 0.25f)
    private val centerColor = floatArrayOf(0.55f, 0.85f, 1.0f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.05f, 0.06f, 1f)
        try {
            background.create()
            overlay.create()
        } catch (e: Throwable) {
            Log.e(TAG, "GL setup failed", e)
            callbacks.onGlError("OpenGL se nepodařilo inicializovat: ${e.message}")
            return
        }
        surfaceAnnounced = false
        if (source == Source.PLAIN) createPreviewTexture()
    }

    private fun createPreviewTexture() {
        releasePreviewTexture()
        val st = SurfaceTexture(background.textureId)
        st.setDefaultBufferSize(previewBufferWidth, previewBufferHeight)
        previewTexture = st
        previewSurface = Surface(st)
    }

    private fun releasePreviewTexture() {
        try { previewSurface?.release() } catch (_: Throwable) { }
        try { previewTexture?.release() } catch (_: Throwable) { }
        previewSurface = null
        previewTexture = null
    }

    /** Switches between the ARCore-driven and the plain preview path. */
    fun switchSource(newSource: Source) {
        if (source == newSource) return
        source = newSource
        surfaceAnnounced = false
        needsPreviewRebuild = true
    }

    /**
     * Applies the real camera geometry. Called when the camera reports what it
     * actually opened, which may differ from the defaults assumed at start-up.
     */
    fun configurePreview(sensorOrientationDeg: Int, bufferWidth: Int, bufferHeight: Int) {
        if (sensorOrientation == sensorOrientationDeg &&
            previewBufferWidth == bufferWidth && previewBufferHeight == bufferHeight
        ) {
            return
        }
        sensorOrientation = sensorOrientationDeg
        previewBufferWidth = bufferWidth
        previewBufferHeight = bufferHeight
        needsPreviewRebuild = true
        surfaceAnnounced = false
    }

    @Volatile private var needsPreviewRebuild = false

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        controller.arEngine.setDisplayGeometry(displayRotationDegrees / 90, width, height)
        mapper.configure(previewBufferWidth, previewBufferHeight, width, height, sensorOrientation)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        try {
            if (source == Source.AR) drawArFrame() else drawPlainFrame()
        } catch (e: Throwable) {
            Log.e(TAG, "draw failed", e)
        }
    }

    private fun drawArFrame() {
        releasePreviewTextureIfNeeded()
        controller.arEngine.setCameraTextureName(background.textureId)
        val snapshot = controller.arEngine.update(
            collectPoints = controller.wantsPointCloud(),
            wantLuma = controller.wantsLumaNow()
        )
        if (snapshot != null) {
            controller.recordIntrinsics(snapshot)
            controller.onArFrame(snapshot)
            if (snapshot.displayGeometryChanged) {
                controller.arEngine.setDisplayGeometry(
                    displayRotationDegrees / 90, viewportWidth, viewportHeight
                )
            }
            lastArUvs = snapshot.backgroundUv.copyOf()
        }
        background.draw(lastArUvs ?: fallbackUvs, null)

        if (!prefs.showArOverlay) return
        val snap = snapshot ?: return
        if (!snap.pose.valid) return
        Matrix.multiplyMM(mvp, 0, snap.projectionMatrix, 0, snap.viewMatrix, 0)
        drawOverlay()
    }

    private fun drawPlainFrame() {
        if (needsPreviewRebuild) {
            needsPreviewRebuild = false
            createPreviewTexture()
        }
        val st = previewTexture ?: run { createPreviewTexture(); previewTexture } ?: return
        if (!surfaceAnnounced) {
            surfaceAnnounced = true
            previewSurface?.let { callbacks.onPreviewSurfaceReady(it, previewBufferWidth, previewBufferHeight) }
            st.setOnFrameAvailableListener { /* the view renders continuously */ }
        }
        try { st.updateTexImage() } catch (_: Throwable) { }
        st.getTransformMatrix(stMatrix)
        mapper.configure(previewBufferWidth, previewBufferHeight, viewportWidth, viewportHeight, sensorOrientation)
        val uvs = mapper.quadUvs()
        System.arraycopy(uvs, 0, plainUvs, 0, 8)
        background.draw(plainUvs, stMatrix)
        // no 3D overlay without a pose; the HUD and the mini-map carry the
        // information instead
    }

    private fun releasePreviewTextureIfNeeded() {
        if (previewTexture != null) releasePreviewTexture()
    }

    private fun drawOverlay() {
        val scene = controller.sceneSnapshot()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        if (scene.coverageVoxels.size >= 3) {
            overlay.drawPoints(mvp, scene.coverageVoxels, scene.coverageColors, null, 9f)
        }
        if (scene.trajectory.size >= 6) {
            overlay.drawLineStrip(mvp, scene.trajectory, whiteish, 4f)
        }
        if (scene.viewRays.size >= 6) {
            overlay.drawLines(mvp, scene.viewRays, whiteish, 2f)
        }
        if (scene.captureMarkers.size >= 3) {
            overlay.drawPoints(mvp, scene.captureMarkers, scene.captureColors, null, 16f)
        }
        scene.objectCenter?.let { overlay.drawPoints(mvp, it, null, centerColor, 26f) }
        val advice = scene.adviceTarget
        val center = scene.objectCenter
        if (advice != null && center != null) {
            overlay.drawLines(mvp, floatArrayOf(
                center[0], center[1], center[2], advice[0], advice[1], advice[2]
            ), adviceColor, 6f)
            overlay.drawPoints(mvp, advice, null, adviceColor, 30f)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        releasePreviewTexture()
    }

    private companion object {
        const val TAG = "ArRenderer"
    }
}
