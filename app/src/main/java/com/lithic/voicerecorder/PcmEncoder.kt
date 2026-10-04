package com.lithic.voicerecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    /** Flush and finalise the output file. A second call does nothing. */
    fun finish()

    /**
     * Best-effort abort that deletes any partial output. Only for a take with nothing worth
     * keeping; see [endTake].
     */
    fun abort()
}

/**
 * Ends a take on [encoder]. Whatever was captured is finalised even when capture broke partway, so
 * an error costs the rest of the recording rather than all of it. Only a take that failed before
 * capturing anything is discarded, because there is nothing in it to keep.
 *
 * Returns the error to report: the capture [failure] if there was one, otherwise any failure to
 * finalise. A failure to finalise never deletes the file: a part-written file can still be
 * recovered, and a deleted one can't.
 */
fun endTake(encoder: PcmEncoder, failure: Throwable?, capturedBytes: Long): Throwable? {
    try {
        if (failure == null || capturedBytes > 0) encoder.finish() else encoder.abort()
    } catch (t: Throwable) {
        return failure ?: t
    }
    return failure
}

object PcmEncoderFactory {
    fun create(spec: AudioFormatSpec, outputFile: File): PcmEncoder = when (spec.format) {
        AudioFormat.WAV -> WavPcmEncoder(spec, outputFile)
        AudioFormat.M4A -> MediaCodecPcmEncoder(
            spec, outputFile,
            mime = requireNotNull(AudioFormat.M4A.mime),
            muxerOutputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        AudioFormat.OPUS -> MediaCodecPcmEncoder(
            spec, outputFile,
            mime = requireNotNull(AudioFormat.OPUS.mime),
            // OGG muxing (required for Opus) is API 29+; the format itself is already gated there.
            muxerOutputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
        )
    }
}

/**
 * Lossless 16-bit PCM to .wav. Writes a complete RIFF/WAVE header up front with both sizes marked
 * unknown, streams samples after it, and fills in the real sizes on [finish].
 *
 * The up-front header is what keeps a take that never reaches [finish] readable: if the app is
 * killed or the phone dies mid-recording, ffmpeg and libsndfile read the unknown sizes as "to the
 * end of the file". The 44 zero bytes this used to start with aren't recognised as WAV at all.
 */
class WavPcmEncoder(
    private val spec: AudioFormatSpec,
    private val outputFile: File
) : PcmEncoder {

    private var raf: RandomAccessFile? = null

    override fun start() {
        val f = RandomAccessFile(outputFile, "rw")
        f.setLength(0)
        f.write(header(dataBytes = null))
        raf = f
    }

    override fun encode(data: ByteArray, len: Int) {
        raf?.write(data, 0, len)
    }

    override fun finish() {
        val f = raf ?: return
        try {
            val dataBytes = f.length() - HEADER_BYTES
            f.seek(0)
            f.write(header(dataBytes))
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

    /**
     * The 44-byte RIFF/WAVE header for [dataBytes] of PCM. While that isn't known yet (null), both
     * size fields are [UNKNOWN_SIZE]. The header is built in memory and written in one call, so it
     * is never left half-updated.
     */
    private fun header(dataBytes: Long?): ByteArray {
        val blockAlign = spec.channels * BITS_PER_SAMPLE / 8
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(if (dataBytes == null) UNKNOWN_SIZE else (dataBytes + 36).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // fmt chunk size
            putShort(1) // PCM
            putShort(spec.channels.toShort())
            putInt(spec.sampleRate)
            putInt(spec.sampleRate * blockAlign) // byte rate
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes?.toInt() ?: UNKNOWN_SIZE)
        }.array()
    }

    companion object {
        const val HEADER_BYTES = 44
        private const val BITS_PER_SAMPLE = 16

        /**
         * Both size fields until [finish] knows the real ones: 0xFFFFFFFF, which ffmpeg and
         * libsndfile read as "to the end of the file". A size of 0 would not do: libsndfile reads
         * that as an empty file.
         */
        private const val UNKNOWN_SIZE = -1
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
        val c = createEncoderFor(format)
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
            // Already finished, or never fully started. Never delete from here. This used to call
            // abort(), so finishing twice threw away the file the first call had just saved.
            try { c?.release() } catch (_: Exception) {}
            codec = null
            return
        }
        try {
            signalEndOfStream()
            while (!sawOutputEos) drainOutput(endOfStream = true)
        } finally {
            // The muxer is stopped whatever happened above, even after a codec failure: stop()
            // writes the container's index (the MP4 moov), and without it nothing captured so far
            // would play.
            try { c.stop() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
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

    /**
     * Resolves the encoder through [MediaCodecList.findEncoderForFormat] — the same call
     * [SampleRateSupport] probes with — so the rate list the UI offers and the codec that actually
     * gets configured cannot disagree. Falls back to `createEncoderByType` if the lookup yields
     * nothing, matching the "permissive on failure" stance of the probe.
     */
    private fun createEncoderFor(format: MediaFormat): MediaCodec {
        val name = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        } catch (_: Throwable) {
            null
        }
        return if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(mime)
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
