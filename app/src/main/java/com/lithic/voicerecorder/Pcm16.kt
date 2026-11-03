package com.lithic.voicerecorder

import androidx.media3.common.C
import java.nio.ByteBuffer

/**
 * Decoded PCM in any of the encodings Media3 hands its audio sink, as the interleaved 16-bit
 * little-endian samples the spectrum takes ([SpectrumAnalyzer.push]). Pure, so tested on the JVM.
 */
internal object Pcm16 {

    /**
     * The samples in [buffer] from its position to its limit, which are left as they are, read as
     * [encoding] (a `C.ENCODING_PCM_*`); null for an encoding this doesn't read, rather than its
     * bytes taken as 16-bit, which would show (and, played, be heard as) noise. A float sample out
     * of range is clipped, and one that isn't a number is silence.
     */
    fun from(buffer: ByteBuffer, encoding: Int): ByteArray? {
        val start = buffer.position()
        val n = buffer.remaining()
        fun at(i: Int): Int = buffer.get(start + i).toInt() and 0xFF

        /** [count] samples of [width] bytes, each turned into its top 16 bits by [sample]. */
        fun convert(width: Int, sample: (Int) -> Int): ByteArray {
            val count = n / width
            val out = ByteArray(count * 2)
            for (s in 0 until count) {
                val v = sample(s * width)
                out[2 * s] = v.toByte()
                out[2 * s + 1] = (v shr 8).toByte()
            }
            return out
        }

        return when (encoding) {
            C.ENCODING_PCM_16BIT -> convert(2) { i -> at(i) or (at(i + 1) shl 8) }
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> convert(2) { i -> at(i + 1) or (at(i) shl 8) }
            C.ENCODING_PCM_8BIT -> convert(1) { i -> (at(i) - 128) shl 8 } // unsigned, centred on 128
            C.ENCODING_PCM_24BIT -> convert(3) { i -> at(i + 1) or (at(i + 2) shl 8) }
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> convert(3) { i -> at(i + 1) or (at(i) shl 8) }
            C.ENCODING_PCM_32BIT -> convert(4) { i -> at(i + 2) or (at(i + 3) shl 8) }
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> convert(4) { i -> at(i + 1) or (at(i) shl 8) }
            C.ENCODING_PCM_FLOAT -> convert(4) { i ->
                val f = java.lang.Float.intBitsToFloat(at(i) or (at(i + 1) shl 8) or (at(i + 2) shl 16) or (at(i + 3) shl 24))
                (f.coerceIn(-1f, 1f) * 32767f).toInt() // toInt() makes a NaN 0
            }
            else -> null
        }
    }
}
