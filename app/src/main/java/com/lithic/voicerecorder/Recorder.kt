package com.lithic.voicerecorder

import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import java.io.File

/** Something that can capture microphone audio to a file. */
interface Recorder {
    /**
     * Digital input gain in dB (see [Gain]). Safe to change while recording — the new value takes
     * effect on the next captured buffer.
     */
    var gainDb: Int

    /**
     * Which capture device to record from, or null to let Android choose. Safe to change while
     * recording; the platform re-routes the running stream.
     *
     * This is a *hint* (`AudioRecord.setPreferredDevice`) — see [routedInputLabel] for what capture
     * actually ended up on. Null is only for "Automatic": a chosen device that has gone missing is
     * handled by pausing ([RecordingService]), never by passing null here.
     */
    var preferredInput: AudioDeviceInfo?

    /** Label of the device audio is really coming from, or null when not recording. */
    val routedInputLabel: String?

    /**
     * How this recording has clipped so far, at the input and by the gain. Safe to read from any
     * thread. The UI uses it to light its clip warnings, and to say after saving that it clipped.
     */
    val clips: ClipRecord

    /**
     * Called on the capture thread when capture ends by itself because of an error, once whatever
     * it had captured has been finalised. The owner should then call [stop], which reports the
     * error.
     */
    var onCaptureFailed: (() -> Unit)?

    /** Begin capture. Throws on failure (caller should surface the error and clean up). */
    fun start()

    /** Pause capture: stop feeding the encoder without finalising. No-op if already paused. */
    fun pause()

    /** Resume capture after [pause]. No-op if not paused. */
    fun resume()

    /**
     * Stop capture and flush/finalise the output file. Safe to call once after start(). Throws if
     * capture failed. Even then, the part recorded before the failure is left on disk, unless the
     * failure came before anything was captured.
     */
    fun stop()

    /**
     * Discards the take: stops capture and deletes the output. Used when a recording fails to
     * start. A capture that fails partway is not discarded; [stop] keeps what it got.
     */
    fun cancel()
}

object RecorderFactory {
    /**
     * All formats now share the single [PcmRecorder] capture path; the format only decides which
     * [PcmEncoder] sink is used. An optional [analyzer] is fed the same PCM for the live spectrum.
     */
    fun create(
        spec: AudioFormatSpec,
        outputFile: File,
        analyzer: SpectrumAnalyzer? = null,
        gainDb: Int = 0,
        input: AudioDeviceInfo? = null,
        audioSource: Int = MediaRecorder.AudioSource.MIC
    ): Recorder = PcmRecorder(spec, outputFile, analyzer, gainDb, input, audioSource)
}
