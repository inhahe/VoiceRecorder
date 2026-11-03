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

    /** [frames] of a 1 kHz tone, 16-bit mono, half scale, at [at] Hz. */
    private fun tone(frames: Int, at: Int = rate): ByteArray {
        val out = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val v = (16384 * sin(2 * PI * 1000 * i / at)).toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun shown() = bars.maxOrNull() ?: 0f

    /** The player's times start where it pleases (Media3 adds an offset): only the difference counts. */
    private val start = 1_000_000_000_000L

    @Test
    fun a_block_is_shown_once_the_output_has_played_it() {
        queue.add(start, tone(4800), 1, rate)
        queue.take(heardUs = start, out = bars)
        assertTrue(shown() == 0f) // decoded, not yet heard
        queue.take(heardUs = start + 100_000, out = bars)
        assertTrue(shown() > 0.5f)
    }

    /** A seek: what was decoded before it is never heard. */
    @Test
    fun clearing_drops_what_was_queued() {
        queue.add(start, tone(4800), 1, rate)
        queue.clear()
        queue.take(heardUs = start + 100_000, out = bars)
        assertTrue(shown() == 0f)
    }

    /** The screen was away while it played: on coming back the bars show the sound now, not then. */
    @Test
    fun what_was_played_long_ago_is_not_shown() {
        queue.add(start, tone(4800), 1, rate)
        queue.take(heardUs = start, out = bars)
        queue.take(heardUs = start + 10_000_000, out = bars)
        assertTrue(shown() == 0f)
    }

    /**
     * A block lasts as long as its own rate says: read shortly after it ends (the screen was slow),
     * a 22.05 kHz block is still recent sound, and shown.
     */
    @Test
    fun a_block_lasts_as_long_as_its_own_rate_says() {
        queue.add(start, tone(2205, at = 22_050), 1, 22_050) // 0.1 s
        queue.take(heardUs = start, out = bars)
        queue.take(heardUs = start + 340_000, out = bars) // 0.24 s after it ended: not yet stale
        assertTrue(shown() > 0.5f)
    }
}
