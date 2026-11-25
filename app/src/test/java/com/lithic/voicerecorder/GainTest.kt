package com.lithic.voicerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM checks for [Gain]: the software input gain applied to captured PCM. Covers the dB/linear
 * mapping, in-place scaling of 16-bit little-endian samples, and the clip counting, at the input and
 * by the gain, that the UI's two CLIP lights and post-save warning depend on.
 */
class GainTest {

    /** Build a little-endian 16-bit PCM buffer from sample values. */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    /** Read a little-endian 16-bit PCM buffer back into sample values. */
    private fun samples(buf: ByteArray): List<Int> =
        (0 until buf.size / 2).map { (buf[it * 2 + 1].toInt() shl 8) or (buf[it * 2].toInt() and 0xff) }

    @Test fun `0 dB is exactly unity`() {
        assertEquals(1f, Gain.linear(0), 0f)
    }

    @Test fun `6 dB roughly doubles, -6 dB roughly halves`() {
        assertTrue(abs(Gain.linear(6) - 2f) < 0.01f)
        assertTrue(abs(Gain.linear(-6) - 0.5f) < 0.01f)
    }

    @Test fun `unity gain leaves the buffer untouched`() {
        val buf = pcm(0, 1234, -1234, 32766, -32766)
        val before = buf.copyOf()
        assertEquals(ClipCount.NONE, Gain.apply(buf, buf.size, 1f))
        assertTrue(before.contentEquals(buf))
    }

    // Audio that arrives clipped is the input's to report even at 0 dB, where nothing is scaled.
    @Test fun `unity gain still counts samples that arrived at full scale, touching none`() {
        val buf = pcm(0, 32767, -32768, -32767, 32766)
        val before = buf.copyOf()
        assertEquals(ClipCount(atInput = 3, byGain = 0), Gain.apply(buf, buf.size, 1f))
        assertTrue(before.contentEquals(buf))
    }

    @Test fun `attenuation can't undo clipping at the input, so it is still counted`() {
        val buf = pcm(32767, -32768, 1000)
        assertEquals(ClipCount(atInput = 2, byGain = 0), Gain.apply(buf, buf.size, 0.5f))
        assertEquals(listOf(16384, -16384, 500), samples(buf))
    }

    @Test fun `a sample both clipped on arrival and clamped by the gain counts once, as the input's`() {
        val buf = pcm(32767, -32768, 20000)
        assertEquals(ClipCount(atInput = 2, byGain = 1), Gain.apply(buf, buf.size, 2f))
        assertEquals(listOf(32767, -32768, 32767), samples(buf))
    }

    @Test fun `gain scales samples in place, preserving sign`() {
        val buf = pcm(100, -100, 0)
        Gain.apply(buf, buf.size, 2f)
        assertEquals(listOf(200, -200, 0), samples(buf))
    }

    @Test fun `attenuation works too`() {
        val buf = pcm(1000, -1000)
        Gain.apply(buf, buf.size, 0.5f)
        assertEquals(listOf(500, -500), samples(buf))
    }

    @Test fun `clipping clamps to the 16-bit range and is counted as the gain's`() {
        val buf = pcm(20000, -20000, 100)
        assertEquals(ClipCount(atInput = 0, byGain = 2), Gain.apply(buf, buf.size, 4f))
        assertEquals(listOf(32767, -32768, 400), samples(buf))
    }

    @Test fun `nothing outside the reported length is touched or counted`() {
        val buf = pcm(100, 32767)
        // Only the first sample was actually read; the second is left over from an earlier read.
        assertEquals(ClipCount.NONE, Gain.apply(buf, 2, 2f))
        assertEquals(listOf(200, 32767), samples(buf))
    }

    @Test fun `a trailing odd byte is left alone`() {
        val buf = pcm(100) + byteArrayOf(7)
        Gain.apply(buf, buf.size, 2f)
        assertEquals(7, buf[2].toInt())
    }

    @Test fun `labels are always signed`() {
        assertEquals("+6 dB", Gain.label(6))
        assertEquals("0 dB", Gain.label(0))
        assertEquals("-6 dB", Gain.label(-6))
    }
}
