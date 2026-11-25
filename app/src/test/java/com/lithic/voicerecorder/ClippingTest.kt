package com.lithic.voicerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM checks for [ClipRecord]: the running clip totals of a recording, which the post-save warning
 * reads, and the hold that keeps each of the two CLIP lights lit for a while after its kind clipped.
 */
class ClippingTest {

    private val hold = 1200L

    @Test fun `a recording that never clipped lights neither light`() {
        assertFalse(ClipRecord.NONE.inputClippedWithin(5000, hold))
        assertFalse(ClipRecord.NONE.gainClippedWithin(5000, hold))
    }

    @Test fun `totals add up, and each kind keeps the time it last clipped`() {
        val r = ClipRecord.NONE
            .add(ClipCount(atInput = 3, byGain = 0), nowMs = 1000)
            .add(ClipCount(atInput = 0, byGain = 2), nowMs = 2000)
            .add(ClipCount(atInput = 1, byGain = 1), nowMs = 3000)
            .add(ClipCount(atInput = 0, byGain = 4), nowMs = 4000)
        assertEquals(ClipRecord(inputSamples = 4, gainSamples = 7, lastInputMs = 3000, lastGainMs = 4000), r)
    }

    @Test fun `input clipping lights only the input light, until the hold runs out`() {
        val r = ClipRecord.NONE.add(ClipCount(atInput = 1, byGain = 0), nowMs = 1000)
        assertTrue(r.inputClippedWithin(1000, hold))
        assertTrue(r.inputClippedWithin(2199, hold))
        assertFalse(r.inputClippedWithin(2200, hold))
        assertFalse(r.gainClippedWithin(1000, hold))
    }

    @Test fun `gain clipping lights only the gain light, until the hold runs out`() {
        val r = ClipRecord.NONE.add(ClipCount(atInput = 0, byGain = 1), nowMs = 1000)
        assertTrue(r.gainClippedWithin(2199, hold))
        assertFalse(r.gainClippedWithin(2200, hold))
        assertFalse(r.inputClippedWithin(1000, hold))
    }

    @Test fun `a light that has gone out comes back on with the next clip of its kind`() {
        val r = ClipRecord.NONE
            .add(ClipCount(atInput = 1, byGain = 1), nowMs = 1000)
            .add(ClipCount(atInput = 0, byGain = 1), nowMs = 5000)
        assertTrue(r.gainClippedWithin(5100, hold))
        assertFalse(r.inputClippedWithin(5100, hold))
    }
}
