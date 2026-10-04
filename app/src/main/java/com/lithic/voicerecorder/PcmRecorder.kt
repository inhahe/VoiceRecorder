package com.lithic.voicerecorder

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import kotlin.concurrent.thread

/**
 * The single capture engine behind every format.
 *
 * A dedicated [AudioRecord] reads 16-bit PCM from the mic on a worker thread and tees each buffer to
 * two consumers: the format-specific [PcmEncoder] that writes the file, and (optionally) a
 * [SpectrumAnalyzer] that powers the live recording spectrum. Owning the PCM ourselves — rather than
 * handing the mic to MediaRecorder — is what lets the analyzer work for compressed formats too.
 */
class PcmRecorder(
    private val spec: AudioFormatSpec,
    private val outputFile: File,
    private val analyzer: SpectrumAnalyzer?,
    initialGainDb: Int = 0,
    initialInput: AudioDeviceInfo? = null,
    /** `MediaRecorder.AudioSource` constant; fixed for the life of the AudioRecord. */
    private val audioSource: Int = MediaRecorder.AudioSource.MIC
) : Recorder {

    /** Cached linear multiplier so the capture loop never does a pow() per buffer. */
    @Volatile private var gainLinear: Float = Gain.linear(initialGainDb)

    @Volatile private var _gainDb: Int = initialGainDb
    override var gainDb: Int
        get() = _gainDb
        set(value) {
            val clamped = value.coerceIn(Gain.MIN_DB, Gain.MAX_DB)
            _gainDb = clamped
            gainLinear = Gain.linear(clamped)
        }

    @Volatile private var _preferredInput: AudioDeviceInfo? = initialInput
    override var preferredInput: AudioDeviceInfo?
        get() = _preferredInput
        set(value) {
            _preferredInput = value
            // AudioRecord accepts this on a running stream, so a change mid-recording re-routes
            // capture without interrupting the file (null = clear the preference).
            try { audioRecord?.setPreferredDevice(value) } catch (_: Throwable) {}
        }

    override val routedInputLabel: String?
        get() = audioRecord?.routedDevice?.let { InputDevices.optionFor(it).label }

    @Volatile override var lastClipElapsedMs: Long = 0L
        private set

    @Volatile override var clippedSamples: Long = 0L
        private set

    @Volatile override var onCaptureFailed: (() -> Unit)? = null

    @Volatile private var recording = false
    @Volatile private var paused = false
    private var worker: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var encoder: PcmEncoder? = null
    @Volatile private var failure: Throwable? = null

    private val channelMask = if (spec.channels >= 2) {
        AndroidAudioFormat.CHANNEL_IN_STEREO
    } else {
        AndroidAudioFormat.CHANNEL_IN_MONO
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is validated before recording starts.
    override fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(
            spec.sampleRate, channelMask, AndroidAudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("Sample rate ${spec.sampleRate} not supported for PCM capture")
        }
        val bufferSize = minBuffer * 2

        val ar = AudioRecord(
            audioSource,
            spec.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw IllegalStateException("AudioRecord failed to initialise")
        }
        // Route to the chosen mic (USB interface, headset, …) before capture starts. It's only a
        // hint — the UI reports routedDevice so the user can see what actually happened.
        _preferredInput?.let { ar.setPreferredDevice(it) }
        audioRecord = ar

        val enc = PcmEncoderFactory.create(spec, outputFile)
        try {
            enc.start()
        } catch (t: Throwable) {
            ar.release()
            audioRecord = null
            throw t
        }
        encoder = enc

        analyzer?.reset()
        recording = true
        ar.startRecording()

        worker = thread(name = "pcm-capture") {
            val buffer = ByteArray(bufferSize)
            var captured = 0L
            try {
                while (recording) {
                    if (paused) {
                        // AudioRecord is stopped while paused; wait without spinning the CPU.
                        Thread.sleep(20)
                        continue
                    }
                    val read = ar.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // Gain first, so the file, the meter and the clip warning all agree.
                        val clipped = Gain.apply(buffer, read, gainLinear)
                        if (clipped > 0) {
                            clippedSamples += clipped
                            lastClipElapsedMs = SystemClock.elapsedRealtime()
                        }
                        enc.encode(buffer, read)
                        captured += read
                        analyzer?.push(buffer, read, spec.channels)
                    } else if (read < 0 && !paused) {
                        throw IllegalStateException("AudioRecord.read error $read")
                    }
                }
            } catch (t: Throwable) {
                failure = t
            }
            // Finalise what was captured, error or not. Only a take that failed before capturing
            // anything is thrown away. (resume() can also have set `failure`, from the main thread.)
            failure = endTake(enc, failure, captured)
            // Tell the owner straight away, rather than leave the screen saying "Recording…" over a
            // stream that has stopped.
            if (failure != null) onCaptureFailed?.invoke()
        }
    }

    override fun pause() {
        if (!recording || paused) return
        paused = true
        // Stop the mic stream so its buffer doesn't fill up while we're not draining it.
        try { audioRecord?.stop() } catch (_: Exception) {}
        analyzer?.reset()
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO was validated in start().
    override fun resume() {
        if (!recording || !paused) return
        try {
            audioRecord?.startRecording()
        } catch (t: Throwable) {
            failure = t
            recording = false
            return
        }
        paused = false
    }

    override fun stop() {
        recording = false
        paused = false
        worker?.join()
        worker = null
        releaseAudioRecord()
        encoder = null
        analyzer?.reset()
        failure?.let { throw it }
    }

    override fun cancel() {
        recording = false
        paused = false
        worker?.join()
        worker = null
        releaseAudioRecord()
        // The worker has already finished the file on its way out; a cancel discards it anyway.
        try { encoder?.abort() } catch (_: Throwable) {}
        encoder = null
        analyzer?.reset()
        outputFile.delete()
    }

    private fun releaseAudioRecord() {
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
    }
}
