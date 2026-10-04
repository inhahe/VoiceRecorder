package com.lithic.voicerecorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JVM checks for what a recording leaves on disk when it doesn't end cleanly:
 *
 * - a capture that fails partway keeps a finished file of what it got ([endTake]), rather than
 *   deleting the lot, which is what used to happen;
 * - a WAV is readable even if it never reaches finish() at all (the app killed mid-take).
 *
 * [WavPcmEncoder] is plain java.io, so it runs here for real. The MediaCodec encoder can't.
 */
class PartialRecordingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val spec = AudioFormatSpec(AudioFormat.WAV, 44100, 128, 1)

    /** 0.1 s of mono 16-bit PCM, patterned so a mangled byte would show. */
    private val chunk = ByteArray(8820) { (it % 251).toByte() }

    private val file get() = File(tmp.root, "take.wav")

    private fun startedWav() = WavPcmEncoder(spec, file).also { it.start() }

    /** The header fields a player relies on, read straight off the disk. */
    private data class Header(val riffSize: Long, val dataSize: Long, val channels: Int, val rate: Int)

    private fun header(): Header {
        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        val b = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        return Header(
            riffSize = b.getInt(4).toLong() and 0xFFFFFFFFL,
            dataSize = b.getInt(40).toLong() and 0xFFFFFFFFL,
            channels = b.getShort(22).toInt(),
            rate = b.getInt(24),
        )
    }

    @Test fun `a capture that fails partway keeps a finished file of what it got`() {
        val enc = startedWav()
        repeat(3) { enc.encode(chunk, chunk.size) }
        val readError = IllegalStateException("AudioRecord.read error -6")

        val reported = endTake(enc, readError, capturedBytes = 3L * chunk.size)

        assertSame("the capture error is what gets reported", readError, reported)
        assertTrue("the partial file must survive the error", file.exists())
        assertEquals(Header(36L + 3 * chunk.size, 3L * chunk.size, 1, 44100), header())
        val audio = file.readBytes().copyOfRange(44, file.length().toInt())
        assertArrayEquals(chunk + chunk + chunk, audio)
    }

    @Test fun `a take that fails before capturing anything is discarded`() {
        val enc = startedWav()
        val reported = endTake(enc, IllegalStateException("AudioRecord.read error -3"), capturedBytes = 0)
        assertFalse("nothing was captured, so there's nothing to keep", file.exists())
        assertEquals("AudioRecord.read error -3", reported?.message)
    }

    @Test fun `a clean stop finishes the file and reports nothing`() {
        val enc = startedWav()
        enc.encode(chunk, chunk.size)
        assertNull(endTake(enc, null, capturedBytes = chunk.size.toLong()))
        assertEquals(Header(36L + chunk.size, chunk.size.toLong(), 1, 44100), header())
    }

    @Test fun `a failure to finish never deletes the file`() {
        // A part-written file can still be recovered with tools; a deleted one can't.
        val enc = FakeEncoder(finishError = IllegalStateException("muxer stop failed"))
        val reported = endTake(enc, null, capturedBytes = 100)
        assertFalse(enc.aborted)
        assertEquals("muxer stop failed", reported?.message)
    }

    @Test fun `when finishing fails too, the capture error is still the one reported`() {
        // The root cause is what the user needs to see, not the knock-on failure.
        val enc = FakeEncoder(finishError = IllegalStateException("muxer stop failed"))
        val readError = IllegalStateException("AudioRecord.read error -6")
        assertSame(readError, endTake(enc, readError, capturedBytes = 100))
        assertFalse(enc.aborted)
    }

    @Test fun `finishing a WAV twice keeps the file`() {
        val enc = startedWav()
        enc.encode(chunk, chunk.size)
        enc.finish()
        enc.finish()
        assertEquals(chunk.size.toLong(), header().dataSize)
    }

    @Test fun `a WAV cut off before finish still has a readable header`() {
        // What the app being killed mid-take leaves behind: finish() never runs. Both sizes read
        // as "unknown" (0xFFFFFFFF), which ffmpeg and libsndfile take as "to the end of the file".
        val enc = startedWav()
        repeat(2) { enc.encode(chunk, chunk.size) }
        try {
            assertEquals(Header(0xFFFFFFFFL, 0xFFFFFFFFL, 1, 44100), header())
            assertEquals(44L + 2 * chunk.size, file.length())
        } finally {
            enc.abort() // release the handle so the temporary folder can be cleaned up
        }
    }

    @Test fun `the header describes stereo correctly`() {
        val stereo = WavPcmEncoder(spec.copy(channels = 2, sampleRate = 48000), file)
        stereo.start()
        stereo.encode(chunk, chunk.size)
        stereo.finish()
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(48000, b.getInt(24))
        assertEquals(48000 * 4, b.getInt(28)) // byte rate
        assertEquals(4, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt()) // bits per sample
    }

    private class FakeEncoder(private val finishError: Throwable? = null) : PcmEncoder {
        var aborted = false
        override fun start() {}
        override fun encode(data: ByteArray, len: Int) {}
        override fun finish() { finishError?.let { throw it } }
        override fun abort() { aborted = true }
    }
}
