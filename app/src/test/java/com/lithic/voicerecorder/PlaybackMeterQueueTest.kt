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

    /** Reads [queue] with the output at [us]. */
    private fun at(us: Long) {
        queue.heard(us)
        queue.take(bars)
    }

    @Test
    fun a_block_is_shown_once_the_output_has_played_it() {
        queue.add(start, tone(4800), 1, rate)
        at(start)
        assertTrue(shown() == 0f) // decoded, not yet heard
        at(start + 100_000)
        assertTrue(shown() > 0.5f)
    }

    /** A seek: what was decoded before it is never heard, and the time is unknown until the output says. */
    @Test
    fun clearing_drops_what_was_queued() {
        queue.add(start, tone(4800), 1, rate)
        queue.heard(start + 100_000)
        queue.clear()
        assertTrue(!queue.take(bars))
        assertTrue(shown() == 0f)
    }

    /** The screen was away while it played: on coming back the bars show the sound now, not then. */
    @Test
    fun what_was_played_long_ago_is_not_shown() {
        queue.add(start, tone(4800), 1, rate)
        at(start)
        at(start + 10_000_000)
        assertTrue(shown() == 0f)
    }

    /**
     * A long block (the player hands over seconds of WAV at once) is analysed as it is heard, not
     * all at once: read every 0.2 s through 3 s, the first read has analysed a fifteenth of it.
     */
    @Test
    fun a_long_block_is_shown_as_it_is_heard() = assertHeardAsItPlays(rate)

    /** The same at another rate: how much of a block has been heard goes by its own rate. */
    @Test
    fun a_block_is_heard_at_its_own_rate() = assertHeardAsItPlays(22_050)

    private fun assertHeardAsItPlays(at: Int) {
        val analyzer = SpectrumAnalyzer()
        val queue = PlaybackMeterQueue(analyzer)
        queue.add(start, tone(at * 3, at), 1, at)
        val counts = (1..15).map { i ->
            queue.heard(start + i * 200_000L)
            queue.take(bars)
            analyzer.analyses
        }
        val whole = counts.last()
        assertTrue("analyses $counts", whole > 30)
        assertTrue("analyses $counts", kotlin.math.abs(counts.first() - whole / 15) <= 2)
        assertTrue("analyses $counts", kotlin.math.abs(counts[7] - whole * 8 / 15) <= 2)
    }
}
