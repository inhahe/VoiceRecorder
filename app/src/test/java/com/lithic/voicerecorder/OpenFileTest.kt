package com.lithic.voicerecorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

/** The file the player reads a recording through. */
class OpenFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Media3 interrupts its loading thread to cancel a load (a seek beyond what is buffered): the
     * read it was making still works, and so does every read after it.
     */
    @Test
    fun an_interrupted_read_leaves_the_file_open() {
        val f = tmp.newFile("take.wav").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        OpenFile(RandomAccessFile(f, "r")).use { open ->
            val buffer = ByteArray(4)
            Thread.currentThread().interrupt()
            try {
                assertEquals(4, open.read(500, buffer, 0, 4))
            } finally {
                Thread.interrupted() // the flag cleared, for the next test on this thread
            }
            assertArrayEquals(byteArrayOf(500.toByte(), 501.toByte(), 502.toByte(), 503.toByte()), buffer)
            assertEquals(4, open.read(0, buffer, 0, 4)) // the next load, after the cancelled one
            assertArrayEquals(byteArrayOf(0, 1, 2, 3), buffer)
            assertEquals(1000L, open.size)
            assertEquals(-1, open.read(1000, buffer, 0, 4))
        }
    }
}
