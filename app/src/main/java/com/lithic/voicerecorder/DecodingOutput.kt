package com.lithic.voicerecorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [AudioOutput] that decodes the recording itself, with `MediaExtractor` and `MediaCodec`, and
 * plays the PCM through an `AudioTrack`: Android's `MediaPlayer` plays a file but never hands over
 * its audio, and the spectrum needs it ([takeSpectrum]).
 *
 * The decoding runs on a thread of its own, writing to the track without blocking, so a pause, a
 * seek or a release is seen within a few milliseconds. Each block written is queued for the meter
 * by the track frame it starts at, and the meter releases it once the track's playback head has
 * reached it ([PlaybackMeterQueue]). The file is opened by descriptor, which keeps it open: a
 * rename doesn't interrupt playing. A WAV cut off mid-take, whose sizes Android's extractor
 * refuses, is read through a [WavView] with them filled in.
 *
 * Callbacks arrive on the main thread, and none after [release].
 */
class DecodingOutput(private val context: Context) : AudioOutput {

    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private val meter = PlaybackMeterQueue()

    @Volatile
    private var released = false

    // Shared with the decoding thread, under [lock].
    private var playing = false
    private var seekToUs = -1L
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 1

    /** Where the track's first frame is in the recording, in µs: the last seek's target. */
    private var baseUs = 0L

    /** The track's playback head when it last started afresh: frames before it belong to another stretch. */
    private var headAtStart = 0L

    override fun open(file: File, onReady: (durationMs: Long) -> Unit, onEnd: () -> Unit, onError: (String) -> Unit) {
        Thread({ decode(file, onReady, onEnd, onError) }, "playback-decoder").apply {
            isDaemon = true
            start()
        }
    }

    override fun start() {
        synchronized(lock) {
            playing = true
            track?.play()
            lock.notifyAll()
        }
    }

    override fun pause() {
        synchronized(lock) {
            playing = false
            track?.pause()
        }
    }

    override fun seekTo(ms: Long) {
        synchronized(lock) {
            seekToUs = ms * 1000
            lock.notifyAll()
        }
    }

    override val positionMs: Long
        get() = synchronized(lock) {
            val t = track ?: return baseUs / 1000
            (baseUs + played(t) * 1_000_000L / trackRate) / 1000
        }

    override fun takeSpectrum(out: FloatArray): Boolean {
        val played = synchronized(lock) { track?.let { played(it) } } ?: return false
        return meter.take(played, out)
    }

    override fun release() {
        synchronized(lock) {
            released = true
            track?.pause() // silent at once; the decoding thread lets go of everything as it ends
            track?.flush()
            lock.notifyAll()
        }
        main.removeCallbacksAndMessages(null)
    }

    /** Frames the track has played since it last started afresh. Under [lock]. */
    private fun played(t: AudioTrack): Long = (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - headAtStart

    private fun post(block: () -> Unit) {
        main.post { if (!released) block() }
    }

    private fun decode(file: File, onReady: (Long) -> Unit, onEnd: () -> Unit, onError: (String) -> Unit) {
        val extractor = MediaExtractor()
        var input: FileInputStream? = null
        var view: WavView? = null
        var codec: MediaCodec? = null
        try {
            val wav = if (file.extension.equals("wav", ignoreCase = true)) WavLayout.read(file) else null
            if (wav != null && !wav.sizesRight) {
                val v = WavView(file, wav).also { view = it }
                extractor.setDataSource(object : MediaDataSource() {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = v.readAt(position, buffer, offset, size)
                    override fun getSize() = v.length
                    override fun close() {}
                })
            } else {
                input = FileInputStream(file).also { extractor.setDataSource(it.fd) }
            }
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException(context.getString(R.string.player_no_audio))
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, null, null, 0)
                start()
            }
            post { onReady(durationUs / 1000) }
            loop(extractor, codec, onEnd)
        } catch (e: Exception) {
            if (!released) post { onError(context.getString(R.string.player_cant_open, file.name, e.message ?: e.javaClass.simpleName)) }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
            input?.close()
            view?.close()
            synchronized(lock) {
                track?.release()
                track = null
            }
        }
    }

    /** Decodes and plays until released: through seeks, and waiting at the end for a seek back. */
    private fun loop(extractor: MediaExtractor, codec: MediaCodec, onEnd: () -> Unit) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var ended = false
        var pcm = ByteArray(0)
        var pcmStart = 0 // decoded PCM not yet written: pcm[pcmStart until pcmEnd]
        var pcmEnd = 0
        var written = 0L // frames written to the track since it last started afresh
        var skipUntilUs = -1L // after a seek, what decodes before the target is dropped
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var channels = 1
        var rate = 0

        while (!released) {
            val seek = synchronized(lock) { seekToUs.also { seekToUs = -1 } }
            if (seek >= 0) {
                extractor.seekTo(seek, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                codec.flush()
                inputDone = false
                outputDone = false
                ended = false
                pcmStart = 0
                pcmEnd = 0
                written = 0
                skipUntilUs = seek
                meter.clear()
                synchronized(lock) {
                    track?.let { t ->
                        t.pause()
                        t.flush()
                        headAtStart = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        if (playing) t.play()
                    }
                    baseUs = seek
                }
            }

            if (pcmStart < pcmEnd) {
                // Write what is decoded; a full (or paused) track takes nothing, so wait a little.
                val t = synchronized(lock) { track } ?: break
                val n = t.write(pcm, pcmStart, pcmEnd - pcmStart, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) throw IOException("AudioTrack.write: $n")
                if (n > 0) {
                    meter.add(written, pcm, pcmStart, n, channels, rate)
                    written += n / (2 * channels)
                    pcmStart += n
                } else {
                    waitBriefly()
                }
                continue
            }

            if (outputDone) {
                // Everything decoded is in the track: the end comes once it has all been played.
                if (!ended) {
                    val played = synchronized(lock) { track?.let { played(it) } } ?: written
                    if (played >= written) {
                        ended = true
                        post(onEnd)
                    }
                }
                waitBriefly()
                continue
            }

            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(0)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = codec.outputFormat
                    rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    openTrack(rate, channels)
                }
                outIndex >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (rate == 0 && info.size > 0) {
                        // A decoder that hands over audio before saying its format: ask it.
                        val out = codec.outputFormat
                        rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        openTrack(rate, channels)
                    }
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 && rate > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = toPcm16(buffer, encoding, info.size)
                        if (pcm.size < bytes.size) pcm = ByteArray(bytes.size)
                        System.arraycopy(bytes, 0, pcm, 0, bytes.size)
                        pcmStart = 0
                        pcmEnd = bytes.size
                        if (skipUntilUs >= 0) {
                            // A seek lands on the sync point before the target: drop the frames before it.
                            val frameBytes = 2 * channels
                            val frames = pcmEnd / frameBytes
                            val skip = (((skipUntilUs - info.presentationTimeUs) * rate / 1_000_000L).coerceIn(0, frames.toLong())).toInt()
                            pcmStart = skip * frameBytes
                            if (skip < frames) skipUntilUs = -1
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    private fun openTrack(rate: Int, channels: Int) {
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw IOException(context.getString(R.string.player_channels, channels))
        }
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = max(min * 2, rate * channels * 2 / 5) // a fifth of a second, at least
        val t = AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        synchronized(lock) {
            track?.release()
            track = t
            trackRate = rate
            trackChannels = channels
            headAtStart = 0
            if (playing) t.play()
        }
    }

    /** Waits up to a few milliseconds, or until a start, seek or release wakes it. */
    private fun waitBriefly() = synchronized(lock) {
        if (!released && seekToUs < 0) lock.wait(WAIT_MS)
    }

    companion object {
        private const val WAIT_MS = 8L

        /**
         * What the player plays as, and the audio focus it asks for: the two must agree, since
         * the system decides from them how to treat the sound (speech isn't ducked under others).
         */
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        /** A decoder's output as 16-bit little-endian PCM, which the track and the meter take. */
        internal fun toPcm16(buffer: java.nio.ByteBuffer, encoding: Int, size: Int): ByteArray = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteArray(size / 2).also { out ->
                    for (i in 0 until size / 4) {
                        val v = (floats.get(i).coerceIn(-1f, 1f) * 32767f).roundToInt()
                        out[2 * i] = v.toByte()
                        out[2 * i + 1] = (v shr 8).toByte()
                    }
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> ByteArray(size * 2).also { out ->
                for (i in 0 until size) {
                    val v = ((buffer.get().toInt() and 0xFF) - 128) shl 8
                    out[2 * i] = v.toByte()
                    out[2 * i + 1] = (v shr 8).toByte()
                }
            }
            else -> ByteArray(size).also { buffer.get(it) }
        }
    }
}
