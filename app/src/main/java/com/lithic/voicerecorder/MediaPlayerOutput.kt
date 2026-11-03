package com.lithic.voicerecorder

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * [AudioOutput] through Android's [MediaPlayer], which plays every format this app records.
 * Made and used on the main thread, so its callbacks arrive there.
 *
 * The file is opened by descriptor, which MediaPlayer keeps a duplicate of (or, for a cut-off WAV,
 * through a [WavView], which holds it open): renaming the file while it plays doesn't interrupt it.
 */
class MediaPlayerOutput(private val context: Context) : AudioOutput {

    private val player = MediaPlayer()
    private val main = Handler(Looper.getMainLooper())
    private var released = false

    /** The corrected view a cut-off WAV is read through, closed with the player. */
    private var view: WavView? = null

    override fun open(file: File, onReady: (durationMs: Long) -> Unit, onEnd: () -> Unit, onError: (String) -> Unit) {
        player.setAudioAttributes(ATTRIBUTES)
        try {
            val wav = if (file.extension.equals("wav", ignoreCase = true)) WavLayout.read(file) else null
            if (wav != null && !wav.sizesRight) {
                // A take cut off before it finished: Android refuses its unknown sizes, so it is
                // read through a header with them filled in.
                val view = WavView(file, wav).also { this.view = it }
                player.setDataSource(object : MediaDataSource() {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) =
                        view.readAt(position, buffer, offset, size)

                    override fun getSize() = view.length

                    override fun close() = view.close()
                })
            } else {
                FileInputStream(file).use { player.setDataSource(it.fd) }
            }
        } catch (e: Exception) {
            // An IOException, or IllegalArgumentException and the like for a file MediaPlayer refuses outright.
            main.post { if (!released) onError(context.getString(R.string.player_cant_open, file.name, e.message ?: "")) }
            return
        }
        player.setOnPreparedListener { onReady(it.duration.toLong().coerceAtLeast(0)) }
        player.setOnCompletionListener { onEnd() }
        player.setOnErrorListener { _, _, _ ->
            onError(context.getString(R.string.player_cant_play, file.name))
            true // handled: no completion callback after it
        }
        player.prepareAsync()
    }

    override fun start() = player.start()

    override fun pause() = player.pause()

    // SEEK_CLOSEST: to the moment asked for, not the nearest sync point, which in AAC can be far.
    override fun seekTo(ms: Long) = player.seekTo(ms, MediaPlayer.SEEK_CLOSEST)

    override val positionMs: Long
        get() = player.currentPosition.toLong()

    override fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        player.release()
        view?.close()
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
    }
}
