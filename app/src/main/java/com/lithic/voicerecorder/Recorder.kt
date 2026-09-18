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
     * actually ended up on.
     */
    var preferredInput: AudioDeviceInfo?

    /** Label of the device audio is really coming from, or null when not recording. */
    val routedInputLabel: String?

    /**
     * [android.os.SystemClock.elapsedRealtime] of the most recent clipped sample, or 0 if nothing
     * has clipped. The UI uses it to flash a clip warning.
     */
    val lastClipElapsedMs: Long

    /** Total samples that clipped during this recording. */
    val clippedSamples: Long

    /** Begin capture. Throws on failure (caller should surface the error and clean up). */
    fun start()

    /** Pause capture: stop feeding the encoder without finalising. No-op if already paused. */
    fun pause()

    /** Resume capture after [pause]. No-op if not paused. */
    fun resume()

    /** Stop capture and flush/finalise the output file. Safe to call once after start(). */
    fun stop()

    /** Best-effort abort that also deletes any partial output. Used on error paths. */
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
