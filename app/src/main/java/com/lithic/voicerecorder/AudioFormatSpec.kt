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
    val compressed: Boolean
) {
    WAV("wav", "WAV (PCM, lossless)", "wav", false),
    M4A("m4a", "M4A (AAC)", "m4a", true),
    OPUS("opus", "Opus (Ogg)", "opus", true);

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
        val SAMPLE_RATES = listOf(8000, 16000, 22050, 44100, 48000)

        /** Bitrate choices offered for compressed formats. */
        val BITRATES_KBPS = listOf(16, 24, 32, 48, 64, 96, 128, 192, 256)

        fun nearestSampleRate(value: Int): Int =
            SAMPLE_RATES.minByOrNull { kotlin.math.abs(it - value) } ?: 44100

        fun nearestBitrate(value: Int): Int =
            BITRATES_KBPS.minByOrNull { kotlin.math.abs(it - value) } ?: 128
    }
}
