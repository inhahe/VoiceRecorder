package com.lithic.voicerecorder

import kotlin.math.abs
import kotlin.math.pow

/**
 * How a meter's drawn bars follow its readings: up at once, eased down, at the same pace whatever
 * the screen's frame rate.
 */
internal object BarMotion {
    /** The fraction of the way to its reading a falling bar covers in a 60th of a second. */
    const val EASE = 0.35f

    /**
     * Moves [display] toward [targets] by [seconds]' worth of falling, entry by entry; true if any
     * bar moved, so a meter whose bars have settled can stop drawing. A rise is shown at once, as
     * meters do: eased, a short sound would be drawn at a fraction of its height. A bar within
     * 0.001 of its reading snaps to it, so it settles rather than creeping for ever.
     */
    fun step(display: FloatArray, targets: FloatArray, seconds: Float): Boolean {
        val ease = 1f - (1f - EASE).pow(seconds * 60f)
        var moved = false
        for (i in display.indices) {
            val t = targets[i]
            val d = display[i]
            val eased = if (t > d) t else d + (t - d) * ease
            val next = if (abs(t - eased) < 0.001f) t else eased
            if (next != d) moved = true
            display[i] = next
        }
        return moved
    }
}
