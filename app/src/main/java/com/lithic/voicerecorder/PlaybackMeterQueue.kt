package com.lithic.voicerecorder

/**
 * The spectrum of what is being heard during playback. Each block of PCM on its way to the output
 * is handed over ([add]) with the time it starts at, and the output tells the time it has reached
 * ([heard]); reading the meter ([take]) analyses what has been heard by then, and no further, even
 * partway through a block (the player hands over seconds of WAV at once). So the bars move with
 * what is heard, a pause holds them (the output's time stands still), and no clock of its own has
 * to be kept in step with the output's. Times are the output's own, in µs: a change of rate
 * partway through a file doesn't change what they mean.
 *
 * Added to and told the time from the player's thread, read on the main thread, all under one
 * lock, so a seek ([clear]) can't fall between reading the time and using it.
 */
class PlaybackMeterQueue(private val analyzer: SpectrumAnalyzer = SpectrumAnalyzer()) {

    private class Block(val startUs: Long, val data: ByteArray, val channels: Int, val rate: Int) {
        val frames = data.size / (2 * channels)
        val endUs = startUs + frames * 1_000_000L / rate

        /** Frames already analysed, or skipped as too old to show. */
        var done = 0

        /** Its frames heard by [us]. */
        fun framesBy(us: Long): Int = ((us - startUs) * rate / 1_000_000L).coerceIn(0, frames.toLong()).toInt()
    }

    private val lock = Any()
    private val queue = ArrayDeque<Block>()
    private var heardUs = UNKNOWN

    /**
     * A block of interleaved 16-bit PCM, [channels] channels at [rate], that starts at [startUs].
     * Kept as it is, not copied: the caller hands it over.
     */
    fun add(startUs: Long, pcm: ByteArray, channels: Int, rate: Int) {
        if (channels <= 0 || rate <= 0 || pcm.size < 2 * channels) return
        val block = Block(startUs, pcm, channels, rate)
        synchronized(lock) {
            queue.addLast(block)
            // A screen that stops reading while the player runs ahead keeps at most this much.
            while (queue.size > 1 && block.endUs - queue.first().startUs > MAX_QUEUED_US) queue.removeFirst()
        }
    }

    /** The output has reached [us] (in the blocks' time), or doesn't know where it is: [UNKNOWN]. */
    fun heard(us: Long) = synchronized(lock) {
        heardUs = us
    }

    /** The output starts afresh (a seek): what was queued won't be heard, the bars start from nothing, and the time is unknown until told. */
    fun clear() = synchronized(lock) {
        queue.clear()
        heardUs = UNKNOWN
        analyzer.reset()
    }

    /**
     * Analyses what has been heard since the last read, and writes the bars into [out]; whether
     * there was anything new (see [SpectrumAnalyzer.takeBandsInto]). Sound heard longer ago than
     * a moment (the meter wasn't being read: the screen was away) is skipped unanalysed, and the
     * bars start afresh rather than from where they were.
     */
    fun take(out: FloatArray): Boolean = synchronized(lock) {
        val heard = heardUs
        if (heard == UNKNOWN) return false
        while (queue.isNotEmpty()) {
            val block = queue.first()
            if (block.startUs >= heard) break
            val old = block.framesBy(heard - STALE_US)
            if (old > block.done) {
                block.done = old
                analyzer.reset()
            }
            val now = block.framesBy(heard)
            if (now > block.done) {
                val frameBytes = 2 * block.channels
                analyzer.push(block.data, block.done * frameBytes, (now - block.done) * frameBytes, block.channels, block.rate)
                block.done = now
            }
            if (block.done < block.frames) break // the rest is still to be heard
            queue.removeFirst()
        }
        analyzer.takeBandsInto(out)
    }

    companion object {
        /** Not told where the output is. */
        const val UNKNOWN = Long.MIN_VALUE

        /** Sound queued at most, for a screen that stops reading while the player runs ahead. */
        private const val MAX_QUEUED_US = 30_000_000L

        /** Sound heard longer ago than this when the meter is read is old: not shown. */
        private const val STALE_US = 250_000L
    }
}
