package com.lithic.voicerecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * A small live frequency-spectrum meter drawn as vertical bars, shown only while recording.
 *
 * The recorder pushes fresh band magnitudes via [submitBands]; the view eases the drawn bars toward
 * those targets and keeps re-invalidating itself while [active], so the meter stays smooth even
 * between the (coarser) magnitude updates.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradient: LinearGradient? = null
    private val rect = RectF()

    private var targets = FloatArray(0)
    private var display = FloatArray(0)

    private var active = false

    private val accentColor = ContextCompat.getColor(context, R.color.accent)
    private val topColor = ContextCompat.getColor(context, R.color.primary)

    /** Provide the latest 0..1 band magnitudes (copied defensively). */
    fun submitBands(bands: FloatArray) {
        if (targets.size != bands.size) {
            targets = FloatArray(bands.size)
            display = FloatArray(bands.size)
        }
        System.arraycopy(bands, 0, targets, 0, bands.size)
        if (active) postInvalidateOnAnimation()
    }

    /** Show/animate the meter, or hide and reset it. */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (active) {
            visibility = VISIBLE
            postInvalidateOnAnimation()
        } else {
            java.util.Arrays.fill(targets, 0f)
            java.util.Arrays.fill(display, 0f)
            visibility = GONE
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = LinearGradient(
            0f, h.toFloat(), 0f, 0f,
            accentColor, topColor, Shader.TileMode.CLAMP
        )
        barPaint.shader = gradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = display.size
        if (n == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w / n * 0.25f
        val barWidth = w / n - gap
        val radius = barWidth * 0.4f
        val minBar = h * 0.02f

        var animating = false
        for (i in 0 until n) {
            // Ease drawn value toward the target; snap when close.
            val t = targets[i]
            val d = display[i]
            val next = d + (t - d) * EASE
            display[i] = if (kotlin.math.abs(t - next) < 0.001f) t else next
            if (kotlin.math.abs(t - display[i]) > 0.001f) animating = true

            val barH = minBar + display[i] * (h - minBar)
            val left = i * (barWidth + gap) + gap / 2f
            rect.set(left, h - barH, left + barWidth, h)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }

        if (active && animating) postInvalidateOnAnimation()
    }

    companion object {
        private const val EASE = 0.35f
    }
}
