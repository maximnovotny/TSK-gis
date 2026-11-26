package cz.photoscanar.ui.gl

import kotlin.math.max

/**
 * Maps between the camera buffer and the on-screen preview for the plain
 * (non-AR) path: rotate by the sensor mounting, then centre-crop to fill the
 * view. The AR path gets its UVs from ARCore instead.
 *
 * The same mapping is reused by the tag overlay so the boxes it draws sit over
 * the tags in the preview.
 */
class PreviewMapper {

    var bufferWidth = 0
        private set
    var bufferHeight = 0
        private set
    var viewWidth = 0
        private set
    var viewHeight = 0
        private set
    var rotationDegrees = 0
        private set

    private var u0 = 0f
    private var v0 = 0f
    private var u1 = 1f
    private var v1 = 1f

    fun configure(bufferW: Int, bufferH: Int, viewW: Int, viewH: Int, rotationDeg: Int) {
        bufferWidth = bufferW; bufferHeight = bufferH
        viewWidth = viewW; viewHeight = viewH
        rotationDegrees = ((rotationDeg % 360) + 360) % 360
        if (bufferW <= 0 || bufferH <= 0 || viewW <= 0 || viewH <= 0) return

        // buffer dimensions as they appear after rotation
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val effW = if (rotated) bufferH else bufferW
        val effH = if (rotated) bufferW else bufferH

        val scale = max(viewW.toFloat() / effW, viewH.toFloat() / effH)
        val visibleFracX = (viewW / (effW * scale)).coerceIn(0f, 1f)
        val visibleFracY = (viewH / (effH * scale)).coerceIn(0f, 1f)

        // the visible window in rotated-normalised coordinates
        val rx0 = 0.5f - visibleFracX / 2f
        val rx1 = 0.5f + visibleFracX / 2f
        val ry0 = 0.5f - visibleFracY / 2f
        val ry1 = 0.5f + visibleFracY / 2f

        // map back to buffer-normalised coordinates
        when (rotationDegrees) {
            90, 270 -> { u0 = ry0; u1 = ry1; v0 = rx0; v1 = rx1 }
            else -> { u0 = rx0; u1 = rx1; v0 = ry0; v1 = ry1 }
        }
    }

    /**
     * Texture coordinates for the NDC quad in the order
     * (-1,-1), (+1,-1), (-1,+1), (+1,+1).
     */
    fun quadUvs(): FloatArray {
        // corner lookup per rotation, in buffer-normalised space (origin top-left)
        return when (rotationDegrees) {
            90 -> floatArrayOf(u0, v0, u0, v1, u1, v0, u1, v1)
            180 -> floatArrayOf(u1, v0, u0, v0, u1, v1, u0, v1)
            270 -> floatArrayOf(u1, v1, u1, v0, u0, v1, u0, v0)
            else -> floatArrayOf(u0, v1, u1, v1, u0, v0, u1, v0)
        }
    }

    /**
     * Maps a pixel in the analysis/camera image to view pixels, so an overlay can
     * be drawn over the preview.
     */
    fun imageToView(x: Float, y: Float, imageW: Int, imageH: Int, out: FloatArray) {
        if (imageW <= 0 || imageH <= 0 || viewWidth <= 0) { out[0] = 0f; out[1] = 0f; return }
        val nu = x / imageW
        val nv = y / imageH
        // buffer-normalised -> rotated-normalised
        var rx: Float
        var ry: Float
        when (rotationDegrees) {
            90 -> { rx = nv; ry = 1f - nu }
            180 -> { rx = 1f - nu; ry = 1f - nv }
            270 -> { rx = 1f - nv; ry = nu }
            else -> { rx = nu; ry = nv }
        }
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val effW = if (rotated) bufferHeight else bufferWidth
        val effH = if (rotated) bufferWidth else bufferHeight
        val scale = max(viewWidth.toFloat() / effW, viewHeight.toFloat() / effH)
        val drawW = effW * scale
        val drawH = effH * scale
        out[0] = rx * drawW - (drawW - viewWidth) / 2f
        out[1] = ry * drawH - (drawH - viewHeight) / 2f
    }
}
