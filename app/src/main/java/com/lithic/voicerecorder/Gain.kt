package com.lithic.voicerecorder

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Digital input gain for the capture path.
 *
 * Android exposes no mic *pre-amp* control, so this is applied in software: every 16-bit sample is
 * scaled on its way out of [AudioRecord][android.media.AudioRecord] and before it reaches either the
 * encoder or the spectrum analyzer, so the file, the meter and the clip lights always agree.
 *
 * Because it happens after the ADC, boosting can't recover detail the converter never captured and
 * *will* clip if the source is already hot; nor can any gain undo clipping in the audio as it
 * arrives. Hence [apply] reporting both kinds (see [ClipCount]) so the UI can warn, and say which.
 * Negative gain is still useful: it makes a too-loud recording usable without re-recording.
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

    /** Linear multiplier for [db]. `linear(0) == 1f` exactly, so at 0 dB [apply] writes nothing. */
    fun linear(db: Int): Float = if (db == 0) 1f else 10f.pow(db / 20f)

    /** "+6 dB" / "0 dB" / "-3 dB" — always signed so the control reads unambiguously. */
    fun label(db: Int): String = if (db > 0) "+$db dB" else "$db dB"

    /**
     * Scales [length] bytes of 16-bit little-endian PCM in [buffer] by [gain], in place, clamping to
     * the 16-bit range, and returns the clipping it found: the samples that arrived at full scale, at
     * any gain including unity, and the samples only [gain] pushed past it. A sample that arrived at
     * full scale and that the gain clamps as well is counted once, as the input's.
     *
     * At unity gain the buffer is only read. A trailing odd byte, which can only happen on a
     * truncated read, is left alone.
     */
    fun apply(buffer: ByteArray, length: Int, gain: Float): ClipCount {
        val unity = gain == 1f
        var atInput = 0
        var byGain = 0
        var i = 0
        val end = length - 1
        while (i < end) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff))
            val arrivedClipped = atFullScale(sample)
            if (arrivedClipped) atInput++
            if (!unity) {
                val scaled = (sample * gain).roundToInt()
                val clamped = scaled.coerceIn(SAMPLE_MIN, SAMPLE_MAX)
                if (clamped != scaled && !arrivedClipped) byGain++
                buffer[i] = (clamped and 0xff).toByte()
                buffer[i + 1] = ((clamped shr 8) and 0xff).toByte()
            }
            i += 2
        }
        return if (atInput == 0 && byGain == 0) ClipCount.NONE else ClipCount(atInput, byGain)
    }

    /**
     * Whether [sample] is at full scale, i.e. was clipped before it reached us. −32767 counts as well
     * as −32768: some converters clip symmetrically, at ±32767. A sound that peaks there exactly
     * without clipping is too rare to matter.
     */
    private fun atFullScale(sample: Int): Boolean = sample >= SAMPLE_MAX || sample <= -SAMPLE_MAX
}
