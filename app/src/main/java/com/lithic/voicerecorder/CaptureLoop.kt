package com.lithic.voicerecorder

import kotlin.math.max
import kotlin.math.min

/**
 * The capture thread's loop, apart from `AudioRecord` and what is done with each buffer, so its
 * handling of pauses can be tested on the JVM.
 *
 * @param read fills the buffer from the microphone; the byte count, or negative on an error.
 * @param deliver hands on the first `n` bytes of a buffer just read.
 * @param clearMeter clears the spectrum meter. Called here, on the thread that feeds the meter,
 *   once a pause is seen: cleared from the thread that pauses, a buffer still being read when the
 *   pause came would light the meter again for the whole pause.
 * @param idle waits a moment while paused (the microphone is stopped then).
 */
internal class CaptureLoop(
    private val buffer: ByteArray,
    private val read: (ByteArray) -> Int,
    private val deliver: (ByteArray, Int) -> Unit,
    private val clearMeter: () -> Unit,
    private val isRecording: () -> Boolean,
    private val isPaused: () -> Boolean,
    private val idle: () -> Unit = { Thread.sleep(20) },
) {
    /** Bytes delivered so far. */
    var captured = 0L
        private set

    /** Runs until recording ends. Throws when the microphone reports an error other than a pause. */
    fun run() {
        while (isRecording()) {
            if (isPaused()) {
                clearMeter()
                while (isPaused() && isRecording()) idle()
                continue
            }
            val n = read(buffer)
            if (n > 0) {
                deliver(buffer, n)
                captured += n
            } else if (n < 0 && !isPaused()) {
                throw IllegalStateException("AudioRecord.read error $n")
            }
        }
    }

    companion object {
        /**
         * Bytes to ask `AudioRecord` for at a time: one spectrum hop (1/[SpectrumAnalyzer.UPDATES_PER_SECOND]
         * s) of [channels]-channel 16-bit audio at [sampleRate], whole frames, and no more than
         * [bufferBytes], the buffer `AudioRecord` was given. Reading a whole buffer at a time instead
         * (160 ms on some phones) hands the meter a burst of analyses at once, so it moves in steps;
         * a hop at a time, it moves as the audio arrives. `AudioRecord` keeps its whole buffer either
         * way, so a late read loses nothing.
         */
        fun readBytes(sampleRate: Int, channels: Int, bufferBytes: Int): Int {
            val frameBytes = 2 * channels
            val hop = SpectrumAnalyzer.hopFor(sampleRate) * frameBytes
            return max(frameBytes, min(hop, bufferBytes / frameBytes * frameBytes))
        }
    }
}
