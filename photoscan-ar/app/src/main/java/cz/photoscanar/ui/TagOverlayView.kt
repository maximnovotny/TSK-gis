package cz.photoscanar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import cz.photoscanar.model.TagObservation
import cz.photoscanar.ui.gl.PreviewMapper

/**
 * Screen-space markers over the detected AprilTags.
 *
 * The outline is drawn where the tag was found in the analysis frame, mapped into
 * view coordinates with the same rotate-and-crop transform the preview uses. It
 * is an overlay on the live image, not a 3D placement: the app does not solve the
 * tag's pose, and it never pretends to know its real-world coordinates.
 */
class TagOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF35D07F"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0E1116"); style = Paint.Style.FILL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 34f; isFakeBoldText = true
    }

    private var detections: List<TagObservation> = emptyList()
    private var mapper: PreviewMapper? = null
    private val path = Path()
    private val tmp = FloatArray(2)

    fun setMapper(m: PreviewMapper) { mapper = m }

    fun setDetections(list: List<TagObservation>) {
        detections = list
        invalidate()
    }

    fun clear() {
        if (detections.isEmpty()) return
        detections = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val m = mapper ?: return
        if (detections.isEmpty()) return
        for (d in detections) {
            path.reset()
            for (i in 0 until 4) {
                m.imageToView(d.cornersX[i], d.cornersY[i], d.sourceWidth, d.sourceHeight, tmp)
                if (i == 0) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1])
            }
            path.close()
            canvas.drawPath(path, outline)
            m.imageToView(d.centerX, d.centerY, d.sourceWidth, d.sourceHeight, tmp)
            val text = "#${d.tagId}"
            val tw = label.measureText(text)
            canvas.drawRoundRect(
                tmp[0] - tw / 2 - 10f, tmp[1] - 26f, tmp[0] + tw / 2 + 10f, tmp[1] + 16f,
                8f, 8f, labelBg
            )
            canvas.drawText(text, tmp[0] - tw / 2, tmp[1] + 8f, label)
        }
    }
}
