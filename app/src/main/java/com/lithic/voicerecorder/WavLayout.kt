package com.lithic.voicerecorder

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Where a PCM WAV's audio is and how long it plays, read from its header. A data size left
 * unknown (0xFFFFFFFF: what this app writes until a take finishes, so one cut off by the app being
 * killed still has it), or one larger than the file, counts as "to the end of the file", in whole
 * frames, which is what ffmpeg and libsndfile do with such a file.
 *
 * @property dataStart where the samples begin.
 * @property dataBytes how many bytes of samples are there to play.
 * @property sizesRight the header's two sizes already say exactly that: Android plays it as it is.
 */
data class WavLayout(
    val byteRate: Long,
    val blockAlign: Int,
    val dataStart: Long,
    val dataBytes: Long,
    val sizesRight: Boolean,
) {
    val durationMs: Long get() = dataBytes * 1000 / byteRate

    /** Where the playable data ends: the whole file, less a frame cut short and anything after the data. */
    val end: Long get() = dataStart + dataBytes

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
                val riffSize = le32(header, 4)
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
                            val bytes = claimed / blockAlign * blockAlign
                            // Both sizes known, the data's within the file and in whole frames.
                            val right = size == bytes && riffSize != UNKNOWN_SIZE
                            return WavLayout(byteRate, blockAlign, start, bytes, right)
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

/**
 * A WAV as it reads had its take been finished: the header's sizes filled in from the [layout]
 * and the file ending after its last whole frame. Android's player refuses a WAV whose sizes are
 * still unknown; given this instead, it plays and seeks it. The file itself isn't changed. Reads
 * are serialised, as the player makes them from its own threads.
 */
class WavView(file: File, private val layout: WavLayout) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val header = ByteArray(layout.dataStart.toInt())

    init {
        try {
            raf.readFully(header)
            put32(header, 4, layout.end - 8) // RIFF size: everything after it, up to the data's end
            put32(header, header.size - 4, layout.dataBytes) // the data chunk's own size
        } catch (e: IOException) {
            raf.close()
            throw e
        }
    }

    /** How long the view is: where the last whole frame ends. */
    val length: Long get() = layout.end

    /** Reads up to [size] bytes at [position] into [buffer] from [offset]; -1 at the end. */
    @Synchronized
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        val n = minOf(size.toLong(), length - position).toInt()
        var done = 0
        if (position < header.size) {
            done = minOf(n.toLong(), header.size - position).toInt()
            System.arraycopy(header, position.toInt(), buffer, offset, done)
        }
        if (done < n) {
            raf.seek(position + done)
            raf.readFully(buffer, offset + done, n - done)
        }
        return n
    }

    @Synchronized
    override fun close() = raf.close()

    private fun put32(b: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) b[at + i] = (value shr (8 * i)).toByte()
    }
}
