package com.lithic.voicerecorder

import java.io.File

/**
 * What plays one file: [MediaPlayerOutput] on a phone, a fake in the tests. Every callback arrives
 * on the main thread, after the call that caused it has returned.
 */
interface AudioOutput {
    /**
     * Starts opening [file]. Then exactly one of [onReady], with its length in ms (0 when the file
     * doesn't say), or [onError], with why it can't be played; later, [onEnd] when it plays to its
     * end, or [onError] if playing fails.
     */
    fun open(file: File, onReady: (durationMs: Long) -> Unit, onEnd: () -> Unit, onError: (String) -> Unit)

    fun start()
    fun pause()
    fun seekTo(ms: Long)

    /** Where it is, in ms from the start. */
    val positionMs: Long

    /** Lets go of the file and everything else; nothing is called back after. */
    fun release()
}

/**
 * The player under the recordings list: one recording at a time, played, paused, sought, ended
 * and played again from the start. It owns the output and drops it whenever it moves on, so a
 * late callback from an output already let go of changes nothing.
 *
 * @param newOutput makes the output for each recording opened.
 * @param requestFocus asks to play aloud now (audio focus); false (a call under way, say) and it doesn't start.
 * @param abandonFocus says it has stopped playing aloud.
 * @param onChange called after anything shown changed: the recording, playing or not, the length, an error.
 */
class PlayerController(
    private val newOutput: () -> AudioOutput,
    private val requestFocus: () -> Boolean = { true },
    private val abandonFocus: () -> Unit = {},
    private val onChange: () -> Unit = {},
) {
    /** The recording loaded (or being opened), or null. Kept after an error, so Play tries again. */
    var file: File? = null
        private set

    var isPlaying = false
        private set

    /** Being opened: the play button waits for it. */
    var isOpening = false
        private set

    /** Its length in ms; 0 while unknown. */
    var durationMs = 0L
        private set

    /** Why the recording couldn't be played, or null. */
    var error: String? = null
        private set

    private var output: AudioOutput? = null

    /** Played to its end: Play starts again from the beginning, and the position shows the end. */
    private var ended = false

    /** Where it is, in ms. */
    val positionMs: Long
        get() = when {
            ended -> durationMs
            isOpening -> 0L // MediaPlayer can't be asked while it prepares
            else -> output?.positionMs ?: 0L
        }

    /** Opens [target] and plays it, letting go of whatever was loaded. */
    fun play(target: File) {
        close()
        file = target
        error = null
        isOpening = true
        val out = newOutput()
        output = out
        out.open(
            target,
            onReady = { length ->
                if (output === out) {
                    isOpening = false
                    durationMs = length.coerceAtLeast(0)
                    start(out)
                }
            },
            onEnd = {
                if (output === out) {
                    isPlaying = false
                    ended = true
                    abandonFocus()
                    onChange()
                }
            },
            onError = { why ->
                if (output === out) {
                    close()
                    error = why
                    onChange()
                }
            },
        )
        onChange()
    }

    /** The play button: pauses, resumes, plays again after the end, or reopens after an error. */
    fun toggle() {
        if (isPlaying) pause() else resume()
    }

    fun resume() {
        val target = file ?: return
        val out = output
        when {
            out == null -> play(target) // after an error, or before anything was opened
            isOpening -> Unit // starts by itself once open
            else -> {
                if (ended) {
                    out.seekTo(0)
                    ended = false
                }
                start(out)
            }
        }
    }

    private fun start(out: AudioOutput) {
        if (!requestFocus()) {
            onChange()
            return
        }
        out.start()
        isPlaying = true
        onChange()
    }

    fun pause() {
        if (!isPlaying) return
        output?.pause()
        isPlaying = false
        abandonFocus()
        onChange()
    }

    /** Moves to [ms]; playing or paused stays as it was. */
    fun seekTo(ms: Long) {
        val out = output ?: return
        if (isOpening) return
        val to = if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)
        out.seekTo(to)
        ended = false
    }

    /** Back to the start, paused. */
    fun stop() {
        pause()
        seekTo(0)
        onChange()
    }

    /** [from] was renamed to [to]: if it is the one loaded, it plays on under its new name. */
    fun renamed(from: File, to: File) {
        if (file == from) {
            file = to
            onChange()
        }
    }

    /** Lets go of [target] if it is the one loaded, as before it is deleted. */
    fun release(target: File) {
        if (file == target) {
            close()
            file = null
            error = null
            onChange()
        }
    }

    /** Lets go of everything: the screen is going. */
    fun releaseAll() {
        close()
        file = null
    }

    private fun close() {
        val out = output ?: return
        output = null
        if (isPlaying) abandonFocus()
        out.release()
        isPlaying = false
        isOpening = false
        ended = false
        durationMs = 0
    }
}
