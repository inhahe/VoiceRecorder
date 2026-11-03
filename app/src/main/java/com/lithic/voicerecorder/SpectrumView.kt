package com.lithic.voicerecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat

/**
 * A small live frequency-spectrum meter drawn as vertical bars, shown only while recording.
 *
 * While [active] and on screen it reads the band magnitudes from [source] on every frame, raises
 * the drawn bars to them at once and eases them down, so the meter falls smoothly between the
 * analyzer's updates. It redraws only when a bar moves: a paused or settled meter costs a read per
 * frame and no drawing.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val targets = FloatArray(SpectrumAnalyzer.BANDS)
    private val display = FloatArray(SpectrumAnalyzer.BANDS)

    private var active = false
    private var shown = false

    private val frame = Choreographer.FrameCallback { onFrame(it) }
    private var frameScheduled = false
    private var lastFrameNanos = 0L

    private val accentColor = ContextCompat.getColor(context, R.color.accent)
    private val topColor = ContextCompat.getColor(context, R.color.primary)

    /**
     * Fills the array it is given with the 0..1 band magnitudes, lowest frequency first, and says
     * whether it did (false while there is nothing to read). Called on every frame while [active]:
     * as often as the screen can show a change, rather than on a timer of its own that would gather
     * the analyses between two ticks into one step.
     */
    var source: ((FloatArray) -> Boolean)? = null

    /** Show/animate the meter, or hide and reset it. */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (active) {
            visibility = VISIBLE
            scheduleFrame()
        } else {
            cancelFrame()
            targets.fill(0f)
            display.fill(0f)
            visibility = GONE
        }
    }

    /** Runs the frame loop only while the meter can be seen: not in the background, not hidden. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        if (shown) scheduleFrame() else cancelFrame()
    }

    override fun onDetachedFromWindow() {
        cancelFrame()
        super.onDetachedFromWindow()
    }

    private fun scheduleFrame() {
        if (frameScheduled || !active || !shown || !isAttachedToWindow) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun cancelFrame() {
        if (frameScheduled) Choreographer.getInstance().removeFrameCallback(frame)
        frameScheduled = false
        lastFrameNanos = 0L // a gap isn't a frame: the next one starts from here
    }

    private fun onFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!active) return
        val seconds = if (lastFrameNanos == 0L) 0f
        else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, MAX_STEP_SECONDS)
        lastFrameNanos = frameTimeNanos
        source?.invoke(targets)
        if (BarMotion.step(display, targets, seconds)) invalidate()
        scheduleFrame()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barPaint.shader = LinearGradient(0f, h.toFloat(), 0f, 0f, accentColor, topColor, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = display.size
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w / n * 0.25f
        val barWidth = w / n - gap
        val radius = barWidth * 0.4f
        val minBar = h * 0.02f
        for (i in 0 until n) {
            val barH = minBar + display[i] * (h - minBar)
            val left = i * (barWidth + gap) + gap / 2f
            rect.set(left, h - barH, left + barWidth, h)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }
    }

    companion object {
        /** The longest step one frame may take, so a stall doesn't drop the bars in one jump. */
        private const val MAX_STEP_SECONDS = 0.1f
    }
}
