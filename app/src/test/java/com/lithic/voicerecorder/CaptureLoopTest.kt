package com.lithic.voicerecorder

import org.junit.Assert.assertEquals
import org.junit.Test

/** [CaptureLoop]: what reaches the encoder and the meter around a pause. */
class CaptureLoopTest {

    private class Mic {
        var recording = true
        var paused = false
        val events = mutableListOf<String>()
    }

    private fun loop(mic: Mic, read: (Int) -> Int, idle: (Int) -> Unit): CaptureLoop {
        var reads = 0
        var idles = 0
        return CaptureLoop(
            buffer = ByteArray(8),
            read = { reads++; mic.events.add("read"); read(reads) },
            deliver = { _, n -> mic.events.add("deliver $n") },
            clearMeter = { mic.events.add("clear") },
            isRecording = { mic.recording },
            isPaused = { mic.paused },
            idle = { idles++; idle(idles) },
        )
    }

    /**
     * A pause that comes while a buffer is being read: that buffer is still delivered (it is in
     * the file), and the meter is cleared after it, so it doesn't stay lit through the pause.
     */
    @Test
    fun aPauseDuringAReadClearsTheMeterAfterThatBuffer() {
        val mic = Mic()
        val loop = loop(mic,
            read = { n -> if (n == 2) mic.paused = true; 8 },
            idle = { mic.recording = false })
        loop.run()
        assertEquals(listOf("read", "deliver 8", "read", "deliver 8", "clear"), mic.events)
        assertEquals(16L, loop.captured)
    }

    /** Once per pause, not on every wait; nothing is read while paused; reading resumes after. */
    @Test
    fun aPauseClearsOnceAndReadingResumesAfterIt() {
        val mic = Mic()
        mic.paused = true
        val loop = loop(mic,
            read = { n -> if (n == 1) mic.recording = false; 4 },
            idle = { n -> if (n == 3) mic.paused = false })
        loop.run()
        assertEquals(listOf("clear", "read", "deliver 4"), mic.events)
        assertEquals(4L, loop.captured)
    }

    /** A read that returns nothing is neither audio nor an error: the loop just reads again. */
    @Test
    fun aReadOfNothingIsSkipped() {
        val mic = Mic()
        val loop = loop(mic,
            read = { n -> if (n == 2) mic.recording = false; if (n == 1) 0 else 6 },
            idle = {})
        loop.run()
        assertEquals(listOf("read", "read", "deliver 6"), mic.events)
        assertEquals(6L, loop.captured)
    }

    /** A read error ends the loop, unless it is the microphone being stopped for a pause. */
    @Test
    fun aReadErrorIsAFailureUnlessPaused() {
        val mic = Mic()
        val stopped = loop(mic,
            read = { n -> if (n == 1) { mic.paused = true; -3 } else 0 },
            idle = { mic.recording = false })
        stopped.run() // no throw: the error came from the pause stopping the mic

        val failing = loop(Mic(), read = { -3 }, idle = {})
        val error = runCatching { failing.run() }.exceptionOrNull()
        assertEquals("AudioRecord.read error -3", error?.message)
    }
}
