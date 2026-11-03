package com.lithic.voicerecorder

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** The playback spectrum: it shows what the output has played, not what was decoded ahead of it. */
class PlaybackMeterQueueTest {

    private val rate = 48_000
    private val queue = PlaybackMeterQueue()
    private val bars = FloatArray(SpectrumAnalyzer.BANDS)

    /** [frames] of a 1 kHz tone, 16-bit mono, half scale. */
    private fun tone(frames: Int): ByteArray {
        val out = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val v = (16384 * sin(2 * PI * 1000 * i / rate)).toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun shown() = bars.maxOrNull() ?: 0f

    @Test
    fun a_block_is_shown_once_the_output_has_played_it() {
        val block = tone(4800)
        queue.add(0, block, 0, block.size, 1, rate)
        queue.take(playedFrames = 0, out = bars)
        assertTrue(shown() == 0f) // decoded, not yet heard
        queue.take(playedFrames = 4800, out = bars)
        assertTrue(shown() > 0.5f)
    }

    /** A seek: what was decoded before it is never heard. */
    @Test
    fun clearing_drops_what_was_queued() {
        val block = tone(4800)
        queue.add(0, block, 0, block.size, 1, rate)
        queue.clear()
        queue.take(playedFrames = 4800, out = bars)
        assertTrue(shown() == 0f)
    }

    /** The screen was away while it played: on coming back the bars show the sound now, not then. */
    @Test
    fun what_was_played_long_ago_is_not_shown() {
        val block = tone(4800)
        queue.add(0, block, 0, block.size, 1, rate)
        queue.take(playedFrames = 0, out = bars)
        queue.take(playedFrames = rate * 10L, out = bars)
        assertTrue(shown() == 0f)
    }
}
