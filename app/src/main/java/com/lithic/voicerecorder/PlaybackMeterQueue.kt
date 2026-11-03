package com.lithic.voicerecorder

/**
 * The spectrum of what is being heard during playback. Each block of PCM on its way to the output
 * is handed over ([add]) with the time it starts at; the meter is read with the time the output
 * has reached ([take]), and only the blocks heard by then are analysed. So the bars move with what
 * is heard, a pause holds them (the output's time stands still), and no clock of its own has to be
 * kept in step with the output's. Times are the output's own, in µs: a change of rate partway
 * through a file doesn't change what they mean.
 *
 * Added to from the player's thread, read on the main thread; the analyzer is touched only under
 * the lock, by [take] and [clear].
 */
class PlaybackMeterQueue(private val analyzer: SpectrumAnalyzer = SpectrumAnalyzer()) {

    private class Block(val startUs: Long, val endUs: Long, val data: ByteArray, val channels: Int, val rate: Int)

    private val lock = Any()
    private val queue = ArrayDeque<Block>()

    /**
     * A block of interleaved 16-bit PCM, [channels] channels at [rate], that starts at [startUs].
     * Kept as it is, not copied: the caller hands it over.
     */
    fun add(startUs: Long, pcm: ByteArray, channels: Int, rate: Int) {
        if (pcm.isEmpty() || channels <= 0 || rate <= 0) return
        val frames = pcm.size / (2 * channels)
        val block = Block(startUs, startUs + frames * 1_000_000L / rate, pcm, channels, rate)
        synchronized(lock) {
            queue.addLast(block)
            while (queue.size > MAX_QUEUED) queue.removeFirst()
        }
    }

    /** The output starts afresh (a seek): what was queued won't be heard, and the bars start from nothing. */
    fun clear() = synchronized(lock) {
        queue.clear()
        analyzer.reset()
    }

    /**
     * Analyses the blocks the output has begun to play by [heardUs], and writes the bars into
     * [out]; whether there was anything new (see [SpectrumAnalyzer.takeBandsInto]). Blocks played
     * long before (the meter wasn't being read: the screen was away) are dropped unanalysed, and the
     * bars start afresh rather than from where they were.
     */
    fun take(heardUs: Long, out: FloatArray): Boolean = synchronized(lock) {
        while (queue.isNotEmpty() && queue.first().startUs < heardUs) {
            val block = queue.removeFirst()
            if (block.endUs < heardUs - STALE_US) {
                analyzer.reset()
                continue
            }
            analyzer.push(block.data, block.data.size, block.channels, block.rate)
        }
        analyzer.takeBandsInto(out)
    }

    companion object {
        /** Blocks kept at most, for a screen that stops reading while the player runs ahead. */
        private const val MAX_QUEUED = 256

        /** A block that ended longer ago than this when the meter is read is old sound: not shown. */
        private const val STALE_US = 250_000L
    }
}
