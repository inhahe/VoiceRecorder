package com.lithic.voicerecorder

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * How long a PCM WAV plays, read from its header, for the recordings list. A data size left
 * unknown (0xFFFFFFFF: what this app writes until a take finishes, so one cut off by the app being
 * killed still has it), or one larger than the file, counts as "to the end of the file", in whole
 * frames, which is what ffmpeg, libsndfile and the player (Media3) do with such a file.
 *
 * @property byteRate the bytes of samples a second.
 * @property dataBytes how many bytes of samples are there to play, in whole frames.
 */
data class WavLayout(
    val byteRate: Long,
    val dataBytes: Long,
) {
    val durationMs: Long get() = dataBytes * 1000 / byteRate

    companion object {
        private const val UNKNOWN_SIZE = 0xFFFFFFFFL

        /** The layout of [file], or null if it isn't a PCM WAV this reads. */
        fun read(file: File): WavLayout? {
            val f = try {
                RandomAccessFile(file, "r")
            } catch (e: IOException) {
                return null
            }
            try {
                val length = f.length()
                if (length < 12) return null
                val header = ByteArray(12)
                f.readFully(header)
                if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null
                var byteRate = 0L // from the fmt chunk, which comes before the data
                var blockAlign = 0
                var at = 12L
                val chunk = ByteArray(8)
                while (at + 8 <= length) {
                    f.seek(at)
                    f.readFully(chunk)
                    val id = String(chunk, 0, 4, Charsets.US_ASCII)
                    val size = le32(chunk, 4)
                    when {
                        id == "data" -> {
                            if (byteRate <= 0 || blockAlign <= 0) return null
                            val start = at + 8
                            val claimed = if (size == UNKNOWN_SIZE || start + size > length) length - start else size
                            return WavLayout(byteRate, claimed / blockAlign * blockAlign)
                        }
                        size == UNKNOWN_SIZE -> return null // only the data chunk can run to the end
                        id == "fmt " && size >= 16 -> {
                            val fmt = ByteArray(16)
                            f.readFully(fmt)
                            byteRate = le32(fmt, 8)
                            blockAlign = (fmt[12].toInt() and 0xFF) or ((fmt[13].toInt() and 0xFF) shl 8)
                        }
                    }
                    at += 8 + size + (size and 1) // chunks are padded to an even length
                }
                return null
            } catch (e: IOException) {
                return null
            } finally {
                f.close()
            }
        }

        internal fun le32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
    }
}
