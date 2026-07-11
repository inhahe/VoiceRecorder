package com.lithic.voicerecorder

import java.io.File

/** Something that can capture microphone audio to a file. */
interface Recorder {
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
        analyzer: SpectrumAnalyzer? = null
    ): Recorder = PcmRecorder(spec, outputFile, analyzer)
}
