package com.lithic.voicerecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * A sink that consumes raw 16-bit interleaved PCM and finalises a file in some container/codec.
 *
 * The single [AudioRecord] capture loop in [PcmRecorder] owns the PCM stream and pushes it to one of
 * these; that way every format shares the same capture path and can be tapped by the analyzer.
 */
interface PcmEncoder {
    /** Open the output file / codec. Throws on failure. */
    fun start()

    /** Encode/append one PCM buffer ([len] valid bytes). Called only from the capture thread. */
    fun encode(data: ByteArray, len: Int)

    /** Flush and finalise the output file. */
    fun finish()

    /** Best-effort abort that deletes any partial output. */
    fun abort()
}

object PcmEncoderFactory {
    fun create(spec: AudioFormatSpec, outputFile: File): PcmEncoder = when (spec.format) {
        AudioFormat.WAV -> WavPcmEncoder(spec, outputFile)
        AudioFormat.M4A -> MediaCodecPcmEncoder(
            spec, outputFile,
            mime = MediaFormat.MIMETYPE_AUDIO_AAC,
            muxerOutputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        AudioFormat.OPUS -> MediaCodecPcmEncoder(
            spec, outputFile,
            mime = MediaFormat.MIMETYPE_AUDIO_OPUS,
            // OGG muxing (required for Opus) is API 29+; the format itself is already gated there.
            muxerOutputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
        )
    }
}

/**
 * Lossless 16-bit PCM to .wav. Writes a 44-byte placeholder RIFF/WAVE header, streams samples, and
 * patches the size fields on [finish].
 */
class WavPcmEncoder(
    private val spec: AudioFormatSpec,
    private val outputFile: File
) : PcmEncoder {

    private val bitsPerSample = 16
    private var raf: RandomAccessFile? = null

    override fun start() {
        val f = RandomAccessFile(outputFile, "rw")
        f.setLength(0)
        f.write(ByteArray(44)) // placeholder header, patched on finish()
        raf = f
    }

    override fun encode(data: ByteArray, len: Int) {
        raf?.write(data, 0, len)
    }

    override fun finish() {
        val f = raf ?: return
        try {
            val totalAudioLen = f.length() - 44
            val totalDataLen = totalAudioLen + 36
            val channels = spec.channels
            val byteRate = spec.sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8

            f.seek(0)
            f.write('R'.code); f.write('I'.code); f.write('F'.code); f.write('F'.code)
            f.writeLE32(totalDataLen.toInt())
            f.write('W'.code); f.write('A'.code); f.write('V'.code); f.write('E'.code)

            f.write('f'.code); f.write('m'.code); f.write('t'.code); f.write(' '.code)
            f.writeLE32(16)
            f.writeLE16(1)
            f.writeLE16(channels)
            f.writeLE32(spec.sampleRate)
            f.writeLE32(byteRate)
            f.writeLE16(blockAlign)
            f.writeLE16(bitsPerSample)

            f.write('d'.code); f.write('a'.code); f.write('t'.code); f.write('a'.code)
            f.writeLE32(totalAudioLen.toInt())
        } finally {
            f.close()
            raf = null
        }
    }

    override fun abort() {
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null
        outputFile.delete()
    }

    private fun RandomAccessFile.writeLE16(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
    }

    private fun RandomAccessFile.writeLE32(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
        write((value shr 24) and 0xff)
    }
}

/**
 * Encodes PCM to a compressed codec via [MediaCodec] and muxes it into a container with
 * [MediaMuxer]. Used for AAC-in-MP4 (.m4a) and Opus-in-OGG (.opus).
 *
 * This replaces the old MediaRecorder path so that a single AudioRecord capture loop feeds both the
 * encoder and the live spectrum analyzer.
 */
class MediaCodecPcmEncoder(
    private val spec: AudioFormatSpec,
    private val outputFile: File,
    private val mime: String,
    private val muxerOutputFormat: Int
) : PcmEncoder {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    private val bytesPerFrame = 2 * spec.channels
    private var totalFramesFed = 0L
    private var sawInputEos = false
    private var sawOutputEos = false

    override fun start() {
        val format = MediaFormat.createAudioFormat(mime, spec.sampleRate, spec.channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }
        val c = MediaCodec.createEncoderByType(mime)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c
        muxer = MediaMuxer(outputFile.absolutePath, muxerOutputFormat)
    }

    override fun encode(data: ByteArray, len: Int) {
        val c = codec ?: return
        var offset = 0
        while (offset < len) {
            drainOutput(endOfStream = false)
            val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex < 0) continue // no input buffer free yet; drain and retry
            val inBuf: ByteBuffer = c.getInputBuffer(inIndex) ?: continue
            inBuf.clear()
            val chunk = minOf(inBuf.remaining(), len - offset)
            inBuf.put(data, offset, chunk)
            val ptsUs = totalFramesFed * 1_000_000L / spec.sampleRate
            c.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
            totalFramesFed += chunk / bytesPerFrame
            offset += chunk
        }
    }

    override fun finish() {
        val c = codec
        val m = muxer
        if (c == null || m == null) {
            abort()
            return
        }
        try {
            signalEndOfStream()
            while (!sawOutputEos) drainOutput(endOfStream = true)
        } finally {
            try { c.stop() } catch (_: Exception) {}
            c.release()
            try { if (muxerStarted) m.stop() } catch (_: Exception) {}
            try { m.release() } catch (_: Exception) {}
            codec = null
            muxer = null
        }
    }

    override fun abort() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        codec = null
        muxer = null
        outputFile.delete()
    }

    private fun signalEndOfStream() {
        val c = codec ?: return
        if (sawInputEos) return
        while (true) {
            val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex < 0) {
                drainOutput(endOfStream = true)
                continue
            }
            val ptsUs = totalFramesFed * 1_000_000L / spec.sampleRate
            c.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            sawInputEos = true
            return
        }
    }

    private fun drainOutput(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        while (true) {
            val outIndex = c.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return // nothing pending; caller keeps feeding
                    // else keep polling until EOS arrives
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Output format changed twice" }
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val outBuf = c.getOutputBuffer(outIndex)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Codec config is carried in the track format for the muxer; skip here.
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted && outBuf != null) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        m.writeSampleData(trackIndex, outBuf, bufferInfo)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                        return
                    }
                }
            }
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_INPUT_SIZE = 1 shl 16
    }
}
