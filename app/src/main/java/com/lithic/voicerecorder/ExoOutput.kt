package com.lithic.voicerecorder

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.io.Closeable
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * [AudioOutput] through Media3's ExoPlayer, which reads every format the app records (and most
 * others), and copes with the phone's audio output: its latency, how much it must hold before it
 * starts, a change of format or of device. On their way to the output, the decoded buffers pass
 * through a [MeterSink], which queues each for the spectrum by its time, and notes the time the
 * output has reached; the meter is read with that ([takeSpectrum]), so the bars move with what is
 * heard.
 *
 * The file is opened once, at [open], and read through that open file ([OpenFileDataSource]): a
 * rename while it plays doesn't interrupt it, nor does a seek after one. Used on the main thread,
 * where the callbacks arrive too; none after [release].
 */
@OptIn(UnstableApi::class)
class ExoOutput(private val context: Context) : AudioOutput {

    private val main = Handler(Looper.getMainLooper())
    private val meter = PlaybackMeterQueue()
    private val sink = MeterSink(DefaultAudioSink.Builder(context).build(), meter)
    private var player: ExoPlayer? = null
    private var handle: OpenFile? = null
    private var released = false

    override fun open(file: File, onReady: (durationMs: Long) -> Unit, onEnd: () -> Unit, onError: (String) -> Unit) {
        val opened = try {
            OpenFile(RandomAccessFile(file, "r"))
        } catch (e: IOException) {
            main.post { if (!released) onError(context.getString(R.string.player_cant_open, file.name, e.message ?: e.javaClass.simpleName)) }
            return
        }
        handle = opened
        val uri = Uri.fromFile(file)
        val source = ProgressiveMediaSource.Factory(
            { OpenFileDataSource(opened, uri) },
            // Seeking in a file with no index of its own (ADTS AAC, some MP3s) by its bitrate.
            DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
        ).createMediaSource(MediaItem.fromUri(uri))
        // The audio renderer alone: there is nothing else to show.
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, sink))
        }
        val p = ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(PLAYER_ATTRIBUTES, false) // the focus is the screen's to ask for (PlayerController)
            .build()
        player = p
        p.addListener(object : Player.Listener {
            private var readied = false

            private fun ready() {
                if (readied) return
                readied = true
                if (!p.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
                    onError(context.getString(R.string.player_cant_open, file.name, context.getString(R.string.player_no_audio)))
                } else {
                    onReady(p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0L)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (released) return
                when (state) {
                    Player.STATE_READY -> ready()
                    Player.STATE_ENDED -> {
                        ready() // a file with nothing in it ends without being ready first
                        if (released) return
                        // Paused at the end, so a seek after it (the bar, Back to the start) doesn't
                        // play on by itself: Play does that.
                        p.pause()
                        onEnd()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!released) onError(describe(file, error))
            }
        })
        p.setMediaSource(source)
        p.prepare()
    }

    override fun start() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(ms: Long) {
        player?.seekTo(ms)
    }

    override val positionMs: Long
        get() = player?.currentPosition ?: 0L

    override fun takeSpectrum(out: FloatArray): Boolean = meter.take(out)

    override fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        try {
            handle?.close()
        } catch (_: IOException) {
        }
        handle = null
        meter.clear()
    }

    /** Why [file] can't be played, as the bar says it. */
    private fun describe(file: File, error: PlaybackException): String = when (error.errorCode) {
        in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> context.getString(R.string.player_cant_play, file.name)
        else -> context.getString(R.string.player_cant_open, file.name, error.cause?.message ?: error.message ?: error.errorCodeName)
    }

    /**
     * The output's own sink, with the spectrum's taps on its way: each decoded buffer is queued
     * once, by its presentation time, as it is first handed over; and the time the output has
     * reached is told to the queue each time the player asks it (about every 10 ms while it
     * plays). The two are the sink's own timebase, so they agree whatever the player adds to the
     * media's times. Called on the player's thread.
     */
    private class MeterSink(sink: AudioSink, private val meter: PlaybackMeterQueue) : ForwardingAudioSink(sink) {
        private var encoding = C.ENCODING_INVALID
        private var channels = 0
        private var rate = 0

        /** The buffer being handed over, already queued: the player hands one over until it is all taken. */
        private var queued: ByteBuffer? = null

        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            encoding = inputFormat.pcmEncoding
            channels = inputFormat.channelCount
            rate = inputFormat.sampleRate
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            if (buffer !== queued) {
                queued = buffer
                Pcm16.from(buffer, encoding)?.let { meter.add(presentationTimeUs, it, channels, rate) }
            }
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount).also { taken -> if (taken) queued = null }
        }

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
            super.getCurrentPositionUs(sourceEnded).also {
                meter.heard(if (it == AudioSink.CURRENT_POSITION_NOT_SET) PlaybackMeterQueue.UNKNOWN else it)
            }

        override fun flush() {
            startAfresh()
            super.flush()
        }

        override fun reset() {
            startAfresh()
            super.reset()
        }

        private fun startAfresh() {
            queued = null
            meter.clear()
        }
    }

    /**
     * Reads the recording through the [file] opened once, so it is the same file however often the
     * player opens it again (each seek does), even renamed meanwhile.
     */
    private class OpenFileDataSource(private val file: OpenFile, private val uri: Uri) : BaseDataSource(false) {
        private var position = 0L
        private var remaining = 0L
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            val size = try {
                file.size
            } catch (e: IOException) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
            if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            position = dataSpec.position
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
            opened = true
            transferStarted(dataSpec)
            return remaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val n = try {
                file.read(position, buffer, offset, min(length.toLong(), remaining).toInt())
            } catch (e: IOException) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
            if (n < 0) return C.RESULT_END_OF_INPUT // shorter now than when it was opened
            position += n
            remaining -= n
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri = uri

        override fun close() {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        /**
         * What the player plays as, and the audio focus it asks for: the two must agree, since
         * the system decides from them how to treat the sound (speech isn't ducked under others).
         */
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        /** [ATTRIBUTES], as Media3 takes them. */
        private val PLAYER_ATTRIBUTES = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
    }
}

/**
 * A file opened once and read by position, from whichever thread asks: what the player reads a
 * recording through. The reads go through [RandomAccessFile], under a lock for its file pointer,
 * not through a `FileChannel`: Media3 interrupts its loading thread to cancel a load (a seek
 * beyond what is buffered does), and an interrupt closes a channel for good, failing every read
 * after it. Media3's own file source reads through a [RandomAccessFile] too.
 */
internal class OpenFile(private val file: RandomAccessFile) : Closeable {

    /** How long the file is now. */
    val size: Long
        get() = synchronized(file) { file.length() }

    /** Reads up to [length] bytes at [position] into [buffer] from [offset]: how many, or -1 at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = synchronized(file) {
        file.seek(position)
        file.read(buffer, offset, length)
    }

    /** Not under the lock: a read stuck on a drive that went mustn't hold up letting go. */
    override fun close() = file.close()
}
