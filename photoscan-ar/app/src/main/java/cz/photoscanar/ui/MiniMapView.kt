package cz.photoscanar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import cz.photoscanar.session.SceneSnapshot
import kotlin.math.max
import kotlin.math.min

/**
 * Top-down orientation map: where you have walked, where the photos were taken
 * and roughly where the subject is.
 *
 * It is drawn from the same snapshot the AR overlay uses, so it stays useful when
 * the AR layer is hidden for a clean preview - and it is the only coverage
 * feedback left when tracking is lost.
 */
class MiniMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0E1116"); style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9EC8F2"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8CD9FF"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#35D07F"); style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA3AE"); textSize = 22f
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7480"); textSize = 24f
    }

    private var scene: SceneSnapshot = SceneSnapshot.EMPTY
    private var currentX = 0f
    private var currentZ = 0f
    private var headingX = 0f
    private var headingZ = -1f
    private var hasPose = false
    private val path = Path()

    fun update(snapshot: SceneSnapshot, posX: Float, posZ: Float, fwdX: Float, fwdZ: Float, poseValid: Boolean) {
        scene = snapshot
        currentX = posX; currentZ = posZ
        headingX = fwdX; headingZ = fwdZ
        hasPose = poseValid
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 18f, 18f, bgPaint)
        canvas.drawRoundRect(1f, 1f, w - 1f, h - 1f, 18f, 18f, framePaint)

        val traj = scene.trajectory
        val markers = scene.captureMarkers
        if (traj.size < 6 && markers.size < 3 && !hasPose) {
            canvas.drawText("mapa", 16f, h / 2f, emptyPaint)
            return
        }

        // bounds over everything we are going to draw, in the X-Z plane
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        fun include(x: Float, z: Float) {
            minX = min(minX, x); maxX = max(maxX, x)
            minZ = min(minZ, z); maxZ = max(maxZ, z)
        }
        var i = 0
        while (i + 2 < traj.size) { include(traj[i], traj[i + 2]); i += 3 }
        i = 0
        while (i + 2 < markers.size) { include(markers[i], markers[i + 2]); i += 3 }
        scene.objectCenter?.let { include(it[0], it[2]) }
        if (hasPose) include(currentX, currentZ)
        if (minX > maxX) return

        val padding = 18f
        val spanX = max(0.8f, maxX - minX)
        val spanZ = max(0.8f, maxZ - minZ)
        val scale = min((w - 2 * padding) / spanX, (h - 2 * padding) / spanZ)
        val cx = (minX + maxX) / 2f
        val cz = (minZ + maxZ) / 2f
        fun sx(x: Float) = w / 2f + (x - cx) * scale
        fun sy(z: Float) = h / 2f + (z - cz) * scale

        scene.objectCenter?.let {
            canvas.drawCircle(sx(it[0]), sy(it[2]), 9f, centerPaint)
        }

        if (traj.size >= 6) {
            path.reset()
            path.moveTo(sx(traj[0]), sy(traj[2]))
            var k = 3
            while (k + 2 < traj.size) { path.lineTo(sx(traj[k]), sy(traj[k + 2])); k += 3 }
            canvas.drawPath(path, trackPaint)
        }

        val colors = scene.captureColors
        var m = 0
        while (m + 2 < markers.size) {
            val idx = m / 3
            markerPaint.color = if (colors.size >= m + 3) {
                Color.rgb(
                    (colors[m] * 255).toInt().coerceIn(0, 255),
                    (colors[m + 1] * 255).toInt().coerceIn(0, 255),
                    (colors[m + 2] * 255).toInt().coerceIn(0, 255)
                )
            } else {
                Color.parseColor("#35D07F")
            }
            canvas.drawCircle(sx(markers[m]), sy(markers[m + 2]), 3.2f, markerPaint)
            m += 3
        }

        if (hasPose) {
            val px = sx(currentX)
            val py = sy(currentZ)
            canvas.drawCircle(px, py, 6f, headingPaint)
            val len = 18f
            val n = kotlin.math.hypot(headingX.toDouble(), headingZ.toDouble()).toFloat()
            if (n > 1e-3f) {
                canvas.drawLine(px, py, px + headingX / n * len, py + headingZ / n * len, trackPaint)
            }
        }

        val metres = (max(spanX, spanZ)).toInt().coerceAtLeast(1)
        canvas.drawText("~${metres} m", 10f, h - 10f, textPaint)
    }
}
