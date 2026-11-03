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
 * While [active] it reads the band magnitudes from [source] on every frame, raises the drawn bars
 * to them at once and eases them down, so the meter falls smoothly between the analyzer's updates.
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

    /**
     * Where the 0..1 band magnitudes come from, read on every frame while [active]: as often as the
     * screen can show a change, rather than on a timer of its own that would gather the analyses
     * between two ticks into one step. Null, or an empty array, while there is nothing to read.
     */
    var source: (() -> FloatArray?)? = null

    private fun pull() {
        val bands = source?.invoke() ?: return
        if (bands.isEmpty()) return
        if (targets.size != bands.size) {
            targets = FloatArray(bands.size)
            display = FloatArray(bands.size)
        }
        System.arraycopy(bands, 0, targets, 0, bands.size)
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
        if (active) pull()
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
            // Up at once, eased down, as meters do: easing a rise too would draw a click that lasts
            // one poll at a fraction of its height. Snap when close.
            val t = targets[i]
            val d = display[i]
            val next = if (t > d) t else d + (t - d) * EASE
            display[i] = if (kotlin.math.abs(t - next) < 0.001f) t else next
            if (kotlin.math.abs(t - display[i]) > 0.001f) animating = true

            val barH = minBar + display[i] * (h - minBar)
            val left = i * (barWidth + gap) + gap / 2f
            rect.set(left, h - barH, left + barWidth, h)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }

        if (active) postInvalidateOnAnimation() // the next frame reads the source again
    }

    companion object {
        private const val EASE = 0.35f
    }
}
