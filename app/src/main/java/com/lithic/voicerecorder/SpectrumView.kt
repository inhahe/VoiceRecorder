package com.lithic.voicerecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * A small live frequency-spectrum meter drawn as vertical bars: of what is being recorded, on the
 * main screen, and of what is being played, in the player bar.
 *
 * While [active] and visible it reads the band magnitudes from [source] on every frame, raises the
 * drawn bars to them at once and eases them down, so the meter falls smoothly between the
 * analyzer's updates. It redraws only when a bar has moved by half a pixel or more, and when no
 * new audio arrives and the bars have settled (paused), or the meter is scrolled out of sight, it
 * looks again ten times a second instead of on every frame.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val visibleRect = Rect()

    private val targets = FloatArray(SpectrumAnalyzer.BANDS)
    private val display = FloatArray(SpectrumAnalyzer.BANDS)
    private val drawn = FloatArray(SpectrumAnalyzer.BANDS) // display as of the last draw

    private var active = false
    private var shown = false

    private val frame = Choreographer.FrameCallback { onFrame(it) }
    private var frameScheduled = false
    private var lastFrameNanos = 0L

    private val accentColor = ContextCompat.getColor(context, R.color.accent)
    private val topColor = ContextCompat.getColor(context, R.color.primary)

    /**
     * Fills the array it is given with the 0..1 band magnitudes, lowest frequency first, and says
     * whether any new audio was analysed since the previous call (false while paused or idle).
     * Called on every frame while audio is coming in: as often as the screen can show a change,
     * rather than on a timer of its own that would gather the analyses between two ticks into one
     * step.
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
        if (shown) {
            scheduleFrame()
        } else {
            cancelFrame()
            // Back on screen, it shows the sound then, not bars from when it was hidden.
            targets.fill(0f)
            display.fill(0f)
        }
    }

    override fun onDetachedFromWindow() {
        cancelFrame()
        super.onDetachedFromWindow()
    }

    /** Asks for [onFrame] at the next frame, or [delayMillis] later when nothing is changing. */
    private fun scheduleFrame(delayMillis: Long = 0) {
        if (frameScheduled || !active || !shown || !isAttachedToWindow) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallbackDelayed(frame, delayMillis)
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
        val fresh = source?.invoke(targets) == true
        val moving = BarMotion.step(display, targets, seconds)
        val onScreen = getLocalVisibleRect(visibleRect) // false when scrolled out of sight
        if (onScreen && changedByHalfAPixel()) invalidate()
        scheduleFrame(if (onScreen && (fresh || moving)) 0 else IDLE_POLL_MILLIS)
    }

    /** Whether any bar has moved by half a pixel or more since it was last drawn. */
    private fun changedByHalfAPixel(): Boolean {
        val range = height * (1f - MIN_BAR)
        for (i in display.indices) if (abs(display[i] - drawn[i]) * range >= 0.5f) return true
        return false
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
        val minBar = h * MIN_BAR
        display.copyInto(drawn)
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

        /** How often to look for audio again once nothing is changing: paused, settled or out of sight. */
        private const val IDLE_POLL_MILLIS = 100L

        /** The height of a silent bar, as a fraction of the view's. */
        private const val MIN_BAR = 0.02f
    }
}
