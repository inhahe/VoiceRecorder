package com.lithic.voicerecorder

import android.annotation.SuppressLint
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
    private val analyzer: SpectrumAnalyzer?
) : Recorder {

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
            MediaRecorder.AudioSource.MIC,
            spec.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw IllegalStateException("AudioRecord failed to initialise")
        }
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
            try {
                while (recording) {
                    if (paused) {
                        // AudioRecord is stopped while paused; wait without spinning the CPU.
                        Thread.sleep(20)
                        continue
                    }
                    val read = ar.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        enc.encode(buffer, read)
                        analyzer?.push(buffer, read, spec.channels)
                    } else if (read < 0 && !paused) {
                        throw IllegalStateException("AudioRecord.read error $read")
                    }
                }
                enc.finish()
            } catch (t: Throwable) {
                failure = t
                try { enc.abort() } catch (_: Throwable) {}
            }
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
        // If the worker finished normally it already called finish(); make sure nothing is left.
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
