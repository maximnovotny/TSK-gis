package cz.tsk.artrack

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

/** An iOS-style segmented control: a track with a selected pill that slides. */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TRACK
        style = Paint.Style.FILL
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PILL
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BORDER
        style = Paint.Style.STROKE
        strokeWidth = 0.66f * density
    }
    private val trackRect = RectF()
    private val pillRect = RectF()

    var items: List<String> = emptyList()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var selected: Int = 0
        private set

    /** Called when the user picks a segment (not when it is set programmatically). */
    var onSelected: ((Int) -> Unit)? = null

    private var pillPosition = 0f
    private var animator: ValueAnimator? = null

    fun setSelectedSilently(index: Int) {
        if (items.isEmpty()) return
        selected = index.coerceIn(0, items.size - 1)
        animator?.cancel()
        pillPosition = selected.toFloat()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = resolveSize((34f * density).toInt(), heightMeasureSpec)
        val widest = items.maxOfOrNull { labelPaint.measureText(it) } ?: 0f
        val width = ((widest + 26f * density) * items.size).toInt()
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty() || !isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_MOVE
        ) {
            val segment = (event.x / (width.toFloat() / items.size)).toInt()
                .coerceIn(0, items.size - 1)
            if (segment != selected) {
                selected = segment
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                animateTo(segment.toFloat())
                onSelected?.invoke(segment)
            }
            return true
        }
        return event.actionMasked == MotionEvent.ACTION_UP
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(pillPosition, target).apply {
            duration = 240L
            interpolator = EASE_OUT
            addUpdateListener {
                pillPosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val h = height.toFloat()
        val inner = borderPaint.strokeWidth / 2f
        trackRect.set(inner, inner, width - inner, h - inner)
        canvas.drawRoundRect(trackRect, h / 2f, h / 2f, trackPaint)
        canvas.drawRoundRect(trackRect, h / 2f, h / 2f, borderPaint)

        val segmentWidth = width.toFloat() / items.size
        val inset = 2f * density
        pillRect.set(
            pillPosition * segmentWidth + inset,
            inset,
            (pillPosition + 1f) * segmentWidth - inset,
            h - inset
        )
        val pillRadius = (h - 2 * inset) / 2f
        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillPaint)

        val metrics = labelPaint.fontMetrics
        val baseline = h / 2f - (metrics.ascent + metrics.descent) / 2f
        for ((index, item) in items.withIndex()) {
            labelPaint.color = if (index == selected) Color.WHITE else LABEL_SECONDARY
            canvas.drawText(item, (index + 0.5f) * segmentWidth, baseline, labelPaint)
        }
    }

    private companion object {
        const val TRACK = 0xB21C1C1E.toInt()       // same glass as the panels
        const val BORDER = 0x33FFFFFF
        const val PILL = 0xFF636366.toInt()        // iOS selected segment, dark
        const val LABEL_SECONDARY = 0xCCEBEBF5.toInt()
        val EASE_OUT = PathInterpolator(0.33f, 1f, 0.68f, 1f)
    }
}
