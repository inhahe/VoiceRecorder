package com.lithic.voicerecorder

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
}
