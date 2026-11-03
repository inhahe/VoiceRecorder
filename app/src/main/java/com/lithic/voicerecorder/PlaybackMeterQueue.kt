package com.lithic.voicerecorder

/**
 * The spectrum of what is being heard during playback. The decoder hands over each block of PCM
 * it writes to the output ([add]), numbered by its first frame since the output last started
 * afresh; the meter is read with how many frames the output has played ([take]), and only the
 * blocks played by then are analysed. So the bars move with what is heard, a pause holds them
 * (the output plays nothing), and no clock has to be kept in step with the output's.
 *
 * Added to from the decoder's thread, read on the main thread; the analyzer is touched only under
 * the lock, by [take] and [clear].
 */
class PlaybackMeterQueue(private val analyzer: SpectrumAnalyzer = SpectrumAnalyzer()) {

    private class Block(val frame: Long, val frames: Int, val data: ByteArray, val channels: Int, val rate: Int)

    private val lock = Any()
    private val queue = ArrayDeque<Block>()

    /** A block of interleaved 16-bit PCM written to the output, starting at frame [firstFrame]. Copied. */
    fun add(firstFrame: Long, data: ByteArray, offset: Int, len: Int, channels: Int, rate: Int) {
        if (len <= 0) return
        val copy = data.copyOfRange(offset, offset + len)
        synchronized(lock) {
            queue.addLast(Block(firstFrame, len / (2 * channels), copy, channels, rate))
            while (queue.size > MAX_QUEUED) queue.removeFirst()
        }
    }

    /** The output starts afresh (a seek): what was queued won't be heard, and the bars start from nothing. */
    fun clear() = synchronized(lock) {
        queue.clear()
        analyzer.reset()
    }

    /**
     * Analyses the blocks the output has begun to play by [playedFrames], and writes the bars into
     * [out]; whether there was anything new (see [SpectrumAnalyzer.takeBandsInto]). Blocks played
     * long before (the meter wasn't being read: the screen was away) are dropped unanalysed, and the
     * bars start afresh rather than from where they were.
     */
    fun take(playedFrames: Long, out: FloatArray): Boolean = synchronized(lock) {
        while (queue.isNotEmpty() && queue.first().frame < playedFrames) {
            val block = queue.removeFirst()
            val staleBefore = playedFrames - (block.rate * STALE_SECONDS).toLong()
            if (block.frame + block.frames < staleBefore) {
                analyzer.reset()
                continue
            }
            analyzer.push(block.data, block.data.size, block.channels, block.rate)
        }
        analyzer.takeBandsInto(out)
    }

    companion object {
        /** Blocks kept at most, for a screen that stops reading while the decoder runs ahead. */
        private const val MAX_QUEUED = 256

        /** A block played longer ago than this when the meter is read is old sound: not shown. */
        private const val STALE_SECONDS = 0.25
    }
}
