package cz.tsk.artrack

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.min

/**
 * The record button: a white ring with a red core that morphs between a circle
 * (idle) and a rounded square (recording), the way the iOS camera does it.
 */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = RED
    }
    private val coreRect = RectF()

    /** 0 = idle circle, 1 = recording square. */
    private var morph = 0f
        set(value) {
            field = value
            invalidate()
        }

    private var pressScale = 1f
        set(value) {
            field = value
            invalidate()
        }

    private var animator: ValueAnimator? = null

    var isRecording: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animator?.cancel()
            animator = ValueAnimator.ofFloat(morph, if (value) 1f else 0f).apply {
                duration = 260L
                interpolator = EASE_OUT
                addUpdateListener { morph = it.animatedValue as Float }
                start()
            }
            contentDescription = if (value) "Zastavit záznam" else "Spustit záznam"
        }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Spustit záznam"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resolveSize((DEFAULT_DP * resources.displayMetrics.density).toInt(), widthMeasureSpec)
        setMeasuredDimension(size, resolveSize(size, heightMeasureSpec))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animatePress(0.9f)
            MotionEvent.ACTION_UP -> {
                animatePress(1f)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            MotionEvent.ACTION_CANCEL -> animatePress(1f)
        }
        return super.onTouchEvent(event)
    }

    private fun animatePress(target: Float) {
        ValueAnimator.ofFloat(pressScale, target).apply {
            duration = 120L
            interpolator = EASE_OUT
            addUpdateListener { pressScale = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val density = resources.displayMetrics.density
        val stroke = 3f * density
        ringPaint.strokeWidth = stroke

        val outer = min(width, height) / 2f - stroke / 2f - 1f
        canvas.drawCircle(cx, cy, outer, ringPaint)

        // The core sits inside the ring with an iOS-sized gap.
        val gap = 5f * density
        val coreMax = (outer - stroke / 2f - gap) * pressScale
        val half = coreMax * (1f - 0.42f * morph)        // the stop square is clearly smaller
        coreRect.set(cx - half, cy - half, cx + half, cy + half)
        val radius = half * (1f - 0.5f * morph)          // circle -> rounded square
        canvas.drawRoundRect(coreRect, radius, radius, corePaint)
    }

    private companion object {
        const val DEFAULT_DP = 76f
        const val RED = 0xFFFF3B30.toInt()
        val EASE_OUT = PathInterpolator(0.33f, 1f, 0.68f, 1f)
    }
}
