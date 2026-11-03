package com.lithic.voicerecorder

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Every encoding the player's sink may be handed, as the 16-bit samples the spectrum takes. */
class Pcm16Test {

    /** Samples as 16-bit little-endian bytes. */
    private fun le16(vararg samples: Int): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it.toShort()) } }.array()

    private fun bytes(vararg b: Int) = ByteBuffer.wrap(ByteArray(b.size) { b[it].toByte() })

    @Test
    fun sixteen_bit_little_endian_is_as_it_is() {
        assertArrayEquals(le16(0x1234, -2), Pcm16.from(ByteBuffer.wrap(le16(0x1234, -2)), C.ENCODING_PCM_16BIT))
    }

    @Test
    fun big_endian_samples_are_turned_round() {
        assertArrayEquals(le16(0x1234, -2), Pcm16.from(bytes(0x12, 0x34, 0xFF, 0xFE), C.ENCODING_PCM_16BIT_BIG_ENDIAN))
        assertArrayEquals(le16(0x1234, -2), Pcm16.from(bytes(0x12, 0x34, 0x56, 0xFF, 0xFE, 0x00), C.ENCODING_PCM_24BIT_BIG_ENDIAN))
        assertArrayEquals(le16(0x1234), Pcm16.from(bytes(0x12, 0x34, 0x56, 0x78), C.ENCODING_PCM_32BIT_BIG_ENDIAN))
    }

    /** Wider samples keep their top 16 bits, sign and all. */
    @Test
    fun wider_samples_keep_their_top_sixteen_bits() {
        assertArrayEquals(le16(0x1234, -2), Pcm16.from(bytes(0x56, 0x34, 0x12, 0x00, 0xFE, 0xFF), C.ENCODING_PCM_24BIT))
        assertArrayEquals(le16(0x1234, -32768), Pcm16.from(bytes(0x78, 0x56, 0x34, 0x12, 0x00, 0x00, 0x00, 0x80), C.ENCODING_PCM_32BIT))
    }

    @Test
    fun eight_bit_samples_are_centred_on_128() {
        assertArrayEquals(le16(0, -32768, 127 shl 8), Pcm16.from(bytes(128, 0, 255), C.ENCODING_PCM_8BIT))
    }

    /** Float: full scale to full scale, beyond it clipped, and a sample that isn't a number silent. */
    @Test
    fun float_samples_are_scaled_and_clipped() {
        val floats = ByteBuffer.allocate(5 * 4).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0.5f, -1f, 3f, -3f, Float.NaN).forEach { floats.putFloat(it) }
        floats.flip()
        assertArrayEquals(le16(16383, -32767, 32767, -32767, 0), Pcm16.from(floats, C.ENCODING_PCM_FLOAT))
    }

    /** What it reads is the buffer's from its position, which it leaves where it was, for the output. */
    @Test
    fun the_buffer_is_read_from_its_position_and_left_as_it_was() {
        val buffer = ByteBuffer.wrap(le16(7, 0x1234, -2))
        buffer.position(2)
        assertArrayEquals(le16(0x1234, -2), Pcm16.from(buffer, C.ENCODING_PCM_16BIT))
        assertEquals(2, buffer.position())
        assertEquals(6, buffer.limit())
    }

    /** An encoding it doesn't read (encoded audio passed through, say) is nothing, not noise. */
    @Test
    fun an_encoding_it_does_not_read_is_nothing() {
        assertNull(Pcm16.from(ByteBuffer.wrap(le16(1, 2)), C.ENCODING_AC3))
        assertNull(Pcm16.from(ByteBuffer.wrap(le16(1, 2)), C.ENCODING_INVALID))
    }
}
