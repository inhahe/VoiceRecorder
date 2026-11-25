package com.lithic.voicerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM checks for [ClipRecord]: the running clip totals of a recording, which decide the message after
 * it ([ClipRecord.clippedAt]), and the hold that keeps each of the two CLIP lights lit for a while
 * after its kind clipped.
 */
class ClippingTest {

    private val hold = 1200L

    @Test fun `a recording that never clipped lights neither light, and says nothing`() {
        assertFalse(ClipRecord.NONE.input.within(5000, hold))
        assertFalse(ClipRecord.NONE.gain.within(5000, hold))
        assertNull(ClipRecord.NONE.clippedAt)
    }

    @Test fun `totals add up, and each kind keeps the time it last clipped`() {
        val r = ClipRecord.NONE
            .add(ClipCount(atInput = 3, byGain = 0), nowMs = 1000)
            .add(ClipCount(atInput = 0, byGain = 2), nowMs = 2000)
            .add(ClipCount(atInput = 1, byGain = 1), nowMs = 3000)
            .add(ClipCount(atInput = 0, byGain = 4), nowMs = 4000)
        assertEquals(ClipRecord(input = ClipTally(4, 3000), gain = ClipTally(7, 4000)), r)
    }

    @Test fun `input clipping lights only the input light, until the hold runs out`() {
        val r = ClipRecord.NONE.add(ClipCount(atInput = 1, byGain = 0), nowMs = 1000)
        assertTrue(r.input.within(1000, hold))
        assertTrue(r.input.within(2199, hold))
        assertFalse(r.input.within(2200, hold))
        assertFalse(r.gain.within(1000, hold))
    }

    @Test fun `gain clipping lights only the gain light, until the hold runs out`() {
        val r = ClipRecord.NONE.add(ClipCount(atInput = 0, byGain = 1), nowMs = 1000)
        assertTrue(r.gain.within(2199, hold))
        assertFalse(r.gain.within(2200, hold))
        assertFalse(r.input.within(1000, hold))
    }

    @Test fun `a light that has gone out comes back on with the next clip of its kind`() {
        val r = ClipRecord.NONE
            .add(ClipCount(atInput = 1, byGain = 1), nowMs = 1000)
            .add(ClipCount(atInput = 0, byGain = 1), nowMs = 5000)
        assertTrue(r.gain.within(5100, hold))
        assertFalse(r.input.within(5100, hold))
    }

    @Test fun `the message names where it clipped, however long ago`() {
        val input = ClipRecord.NONE.add(ClipCount(atInput = 1, byGain = 0), nowMs = 1000)
        val gain = ClipRecord.NONE.add(ClipCount(atInput = 0, byGain = 1), nowMs = 1000)
        assertEquals(ClippedAt.INPUT, input.clippedAt)
        assertEquals(ClippedAt.GAIN, gain.clippedAt)
        assertEquals(ClippedAt.BOTH, input.add(ClipCount(atInput = 0, byGain = 1), nowMs = 90_000).clippedAt)
    }
}
