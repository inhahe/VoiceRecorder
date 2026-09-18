package com.lithic.voicerecorder

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Which of [AudioFormatSpec.SAMPLE_RATES] can actually be recorded, for a given format on *this*
 * device.
 *
 * Two independent vetoes, and the offered list is their intersection:
 *
 * 1. **Capture.** `AudioRecord.getMinBufferSize` rejects a rate/channel combination the framework
 *    won't open at all.
 * 2. **Encoder.** For compressed formats, `MediaCodecList.findEncoderForFormat` answers whether any
 *    regular encoder handles that exact mime/rate/channel format, and the chosen encoder is then
 *    **configured for real** to check it honours the rate rather than silently substituting one.
 *    Opus is a 48 kHz-family codec and AAC encoders mostly stop at 48 kHz, so this is what keeps the
 *    high rates off those lists — and, going the other way, what stops the app offering 44.1 kHz
 *    Opus, which it used to.
 *
 * WAV has no encoder, so it is limited only by capture — which is why 96 kHz can legitimately appear
 * there and nowhere else.
 *
 * Probing is *permissive when it cannot get an answer* — a query that throws leaves the rate in
 * place, since hiding a rate the device could have used is worse than one that fails loudly at
 * `start()`. It is **strict when it does get one**: a codec that answers with a different rate than
 * it was asked for is rejected, because that path ends in a silently mis-rated file rather than an
 * error (see [encoderSupports]).
 *
 * The trial costs a codec configure per candidate rate, which is why results are cached; a format's
 * list is computed once per process.
 */
object SampleRateSupport {

    private val cache = HashMap<String, List<Int>>()

    /** Rates that can be recorded for [format] at [channels] channels, in ascending order. */
    @Synchronized
    fun supported(format: AudioFormat, channels: Int): List<Int> {
        val key = "${format.id}:$channels"
        cache[key]?.let { return it }

        val mask = if (channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val rates = AudioFormatSpec.SAMPLE_RATES.filter { rate ->
            captureSupports(rate, mask) && encoderSupports(format.mime, rate, channels)
        }
        // Never hand an empty list to a picker: an empty result means the probes failed, not that
        // the device can record nothing.
        val result = rates.ifEmpty { AudioFormatSpec.SAMPLE_RATES }
        cache[key] = result
        return result
    }

    private fun captureSupports(rate: Int, channelMask: Int): Boolean = try {
        val size = AudioRecord.getMinBufferSize(rate, channelMask, AndroidAudioFormat.ENCODING_PCM_16BIT)
        size != AudioRecord.ERROR && size != AudioRecord.ERROR_BAD_VALUE && size > 0
    } catch (_: Throwable) {
        true
    }

    /**
     * Asked per-rate rather than by OR-ing every encoder's capabilities, because the answer has to
     * match the codec that will actually be configured — [MediaCodecPcmEncoder] resolves its encoder
     * through the same [MediaCodecList] call, so the two cannot disagree about *which* codec.
     *
     * Declared capabilities are then **verified by actually configuring that codec**, because they
     * lie. Observed on a Galaxy S22 (SM-S908U): `c2.sec.aac.encoder` advertises `sample-rate
     * 8000-96000`, `findEncoderForFormat` duly returns it for 96 kHz AAC, and `configure()`
     * succeeds — but the codec quietly runs at 44.1 kHz (`C2SecAacEnc: setAudioParams: 44100 Hz`).
     * The 96 kHz PCM we feed is then interpreted as 44.1 kHz, so the recording comes out pitched
     * down and 2.18x too long. Trusting the declaration produced a silently corrupt file, which is
     * the one outcome worse than refusing the rate.
     */
    private fun encoderSupports(mime: String?, rate: Int, channels: Int): Boolean {
        if (mime == null) return true // WAV: no codec in the path
        val format = try {
            encoderFormat(mime, rate, channels)
        } catch (_: Throwable) {
            return true
        }
        val name = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        } catch (_: Throwable) {
            return true // probe unavailable: keep the rate, per the permissive stance
        } ?: return false // no encoder claims it — a definite answer, and a cheap one

        return actualRateOf(name, format)?.let { it == rate } ?: true
    }

    /**
     * The rate [name] *really* runs at for [format], or null if the trial couldn't be completed.
     *
     * Null is deliberately distinct from "mismatch": a codec that is busy, or a device that refuses
     * a second encoder instance while recording, must not be read as "this rate is unsupported".
     * Only a successfully-read, differing rate rejects.
     */
    private fun actualRateOf(name: String, format: MediaFormat): Int? {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createByCodecName(name)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            // The output format carries what the codec resolved at configure time, which is where
            // the clamp shows up.
            codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } catch (_: Throwable) {
            null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Must match what [MediaCodecPcmEncoder] configures closely enough that the trial is
     * representative — an encoder can reject a rate only in combination with a profile or bitrate.
     */
    private fun encoderFormat(mime: String, rate: Int, channels: Int): MediaFormat =
        MediaFormat.createAudioFormat(mime, rate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, PROBE_BITRATE)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
            }
        }

    /** Only needs to be a value every encoder accepts; the probe asks about rate, not bitrate. */
    private const val PROBE_BITRATE = 128_000
}
