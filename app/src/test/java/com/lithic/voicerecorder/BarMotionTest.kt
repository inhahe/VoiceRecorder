package com.lithic.voicerecorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spectrum meter's bars following their readings. */
class BarMotionTest {

    @Test
    fun aRiseShowsAtOnce() {
        val display = floatArrayOf(0f, 0.2f)
        assertTrue(BarMotion.step(display, floatArrayOf(0.8f, 0.9f), seconds = 0f))
        assertArrayEquals(floatArrayOf(0.8f, 0.9f), display, 0f)
    }

    /** A fall covers [BarMotion.EASE] of the way in a 60th of a second, however many frames that is. */
    @Test
    fun aFallTakesAsLongAtAnyFrameRate() {
        val at60 = floatArrayOf(1f)
        BarMotion.step(at60, floatArrayOf(0f), seconds = 1f / 60)
        assertEquals(1f - BarMotion.EASE, at60[0], 1e-4f)

        val at120 = floatArrayOf(1f)
        repeat(2) { BarMotion.step(at120, floatArrayOf(0f), seconds = 1f / 120) }
        assertEquals(at60[0], at120[0], 1e-4f)
    }

    /** Settled bars report no movement, so the meter stops drawing until a reading changes. */
    @Test
    fun settledBarsDontMove() {
        val display = floatArrayOf(0.5f, 0f)
        val targets = floatArrayOf(0f, 0f)
        var frames = 0
        while (frames <= 100 && BarMotion.step(display, targets, seconds = 1f / 60)) frames++
        assertArrayEquals(floatArrayOf(0f, 0f), display, 0f)
        assertTrue("took $frames frames to settle", frames in 1..30)
        assertFalse(BarMotion.step(display, targets, seconds = 1f / 60))
    }
}
