package com.lithic.voicerecorder

import android.os.Build

/**
 * The container/codec the user can record into.
 *
 * WMA is intentionally absent: Android's platform encoders (MediaRecorder / MediaCodec) do not
 * ship a Windows Media Audio encoder, so it cannot be produced on-device without bundling a
 * third-party native encoder. See known-issues.md.
 */
enum class AudioFormat(
    val id: String,
    val displayName: String,
    val extension: String,
    /** True when the codec is lossy and therefore honours a target bitrate. */
    val compressed: Boolean,
    /**
     * The `MediaCodec` encoder mime type, or null for formats written without a codec (WAV).
     * Null is what makes "no encoder can veto a sample rate" explicit in [SampleRateSupport].
     */
    val mime: String?
) {
    WAV("wav", "WAV (PCM, lossless)", "wav", false, null),
    M4A("m4a", "M4A (AAC)", "m4a", true, android.media.MediaFormat.MIMETYPE_AUDIO_AAC),
    OPUS("opus", "Opus (Ogg)", "opus", true, android.media.MediaFormat.MIMETYPE_AUDIO_OPUS);

    /** Opus recording via MediaRecorder is only available on Android 10 (API 29) and newer. */
    val isSupportedOnThisDevice: Boolean
        get() = this != OPUS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    companion object {
        fun fromId(id: String?): AudioFormat =
            entries.firstOrNull { it.id == id } ?: WAV

        /** Formats that can actually be recorded on the current device. */
        fun supported(): List<AudioFormat> = entries.filter { it.isSupportedOnThisDevice }
    }
}

/**
 * A fully-resolved recording configuration.
 *
 * @param bitrateKbps only meaningful for compressed formats; ignored for WAV.
 */
data class AudioFormatSpec(
    val format: AudioFormat,
    val sampleRate: Int,
    val bitrateKbps: Int,
    val channels: Int
) {
    /**
     * Estimated encoded size for one second of audio, in bytes. Used to compute how many hours of
     * recording will fit in the free space of the target directory.
     */
    fun bytesPerSecond(): Long = if (format.compressed) {
        // Lossy: size is driven by the target bitrate, container overhead is negligible here.
        bitrateKbps.toLong() * 1000L / 8L
    } else {
        // WAV/PCM 16-bit: sampleRate * channels * 2 bytes.
        sampleRate.toLong() * channels.toLong() * 2L
    }

    companion object {
        /**
         * *Candidate* rates. This is not what any given recording can use: [SampleRateSupport]
         * narrows it per format and per device, because the encoder and the capture path each get a
         * veto (Opus is a 48 kHz-family codec; AAC encoders typically stop at 48 kHz; WAV is capped
         * only by what `AudioRecord` will accept, which on decent USB hardware reaches 96 kHz).
         */
        val SAMPLE_RATES = listOf(8000, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000)

        /** Bitrate choices offered for compressed formats. */
        val BITRATES_KBPS = listOf(16, 24, 32, 48, 64, 96, 128, 192, 256)

        fun nearestSampleRate(value: Int): Int = snap(value, SAMPLE_RATES)

        /**
         * The entry of [supported] closest to [value], preferring an exact match. Used when a
         * format change makes the currently-chosen rate unavailable — e.g. picking Opus while
         * 96 kHz is selected has to land somewhere, and silently recording at the wrong rate or
         * failing at `start()` are both worse than moving to the nearest thing that works.
         */
        fun snap(value: Int, supported: List<Int>): Int {
            if (supported.isEmpty()) return value
            if (value in supported) return value
            return supported.minByOrNull { kotlin.math.abs(it - value) } ?: value
        }

        fun nearestBitrate(value: Int): Int =
            BITRATES_KBPS.minByOrNull { kotlin.math.abs(it - value) } ?: 128

        /**
         * "48 kHz", "44.1 kHz", "22.05 kHz", "11.025 kHz" — one implementation for every screen.
         *
         * Three decimals, not two: device-reported rates include 11025, which `%.2f` rounded to a
         * wrong-and-odd-looking "11.03 kHz". Trailing zeros are trimmed, so the common rates stay
         * short.
         */
        fun hzLabel(hz: Int): String = if (hz % 1000 == 0) {
            "${hz / 1000} kHz"
        } else {
            val k = String.format(java.util.Locale.US, "%.3f", hz / 1000.0)
                .trimEnd('0').trimEnd('.')
            "$k kHz"
        }

        /** "48 kHz" / "44.1 / 48 kHz" — for describing what a device supports. */
        fun hzListLabel(rates: List<Int>): String = when {
            rates.isEmpty() -> ""
            rates.size == 1 -> hzLabel(rates[0])
            else -> rates.sorted().joinToString(" / ") {
                if (it % 1000 == 0) "${it / 1000}" else hzLabel(it).removeSuffix(" kHz")
            } + " kHz"
        }
    }
}
