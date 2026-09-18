package com.lithic.voicerecorder

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Digital input gain for the capture path.
 *
 * Android exposes no mic *pre-amp* control, so this is applied in software: every 16-bit sample is
 * scaled on its way out of [AudioRecord][android.media.AudioRecord] and before it reaches either the
 * encoder or the spectrum analyzer, so the file, the meter and the clip indicator always agree.
 *
 * Because it happens after the ADC, boosting can't recover detail the converter never captured and
 * *will* clip if the source is already hot — hence [apply] reporting clipped samples so the UI can
 * warn. Negative gain is still useful: it makes a too-loud recording usable without re-recording.
 *
 * All of this is pure integer/float maths with no Android dependencies, so it is unit-tested
 * directly in `GainTest`.
 */
object Gain {

    /** Slider range, in dB. 0 = untouched. */
    const val MIN_DB = -20
    const val MAX_DB = 24

    /** Sample values are clipped to the 16-bit signed range. */
    private const val SAMPLE_MAX = 32767
    private const val SAMPLE_MIN = -32768

    /** Linear multiplier for [db]. `linear(0) == 1f` exactly, so the no-gain fast path is exact. */
    fun linear(db: Int): Float = if (db == 0) 1f else 10f.pow(db / 20f)

    /** "+6 dB" / "0 dB" / "-3 dB" — always signed so the control reads unambiguously. */
    fun label(db: Int): String = if (db > 0) "+$db dB" else "$db dB"

    /**
     * Scales [length] bytes of 16-bit little-endian PCM in [buffer] by [gain], in place, clamping to
     * the 16-bit range. Returns how many samples clipped (0 when none did).
     *
     * A trailing odd byte, which can only happen on a truncated read, is left alone.
     */
    fun apply(buffer: ByteArray, length: Int, gain: Float): Int {
        if (gain == 1f) return 0
        var clipped = 0
        var i = 0
        val end = length - 1
        while (i < end) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff))
            var scaled = (sample * gain).roundToInt()
            if (scaled > SAMPLE_MAX) {
                scaled = SAMPLE_MAX
                clipped++
            } else if (scaled < SAMPLE_MIN) {
                scaled = SAMPLE_MIN
                clipped++
            }
            buffer[i] = (scaled and 0xff).toByte()
            buffer[i + 1] = ((scaled shr 8) and 0xff).toByte()
            i += 2
        }
        return clipped
    }
}
