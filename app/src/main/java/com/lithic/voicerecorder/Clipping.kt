package com.lithic.voicerecorder

/**
 * The clipping [Gain.apply] found in one buffer, in samples of each of the two kinds, which are told
 * apart because they need different fixes:
 *
 * - [atInput]: the sample arrived at full scale, so it was clipped before the app saw it — the sound
 *   was too loud for the mic, its converter or the source feeding it. No gain setting can undo that,
 *   and it happens at 0 dB as readily as at any other gain.
 * - [byGain]: the app's own gain pushed the sample past full scale. A lower gain fixes it.
 */
data class ClipCount(val atInput: Int, val byGain: Int) {
    val any: Boolean get() = atInput > 0 || byGain > 0

    companion object {
        val NONE = ClipCount(0, 0)
    }
}

/**
 * How one recording has clipped so far: how many samples of each kind (see [ClipCount]), and when
 * each kind last did.
 *
 * Immutable, so the capture thread publishes a new one through a single volatile field and a reader
 * on another thread always sees one consistent record, never a total from one buffer and a time from
 * the next.
 */
data class ClipRecord(
    val inputSamples: Long = 0L,
    val gainSamples: Long = 0L,
    /** `SystemClock.elapsedRealtime()` of the latest buffer that clipped at the input; meaningless while [inputSamples] is 0. */
    val lastInputMs: Long = 0L,
    /** The same for the gain; meaningless while [gainSamples] is 0. */
    val lastGainMs: Long = 0L,
) {
    /** This record with [count], found in a buffer captured at [nowMs] (`elapsedRealtime()`), added. */
    fun add(count: ClipCount, nowMs: Long): ClipRecord = ClipRecord(
        inputSamples = inputSamples + count.atInput,
        gainSamples = gainSamples + count.byGain,
        lastInputMs = if (count.atInput > 0) nowMs else lastInputMs,
        lastGainMs = if (count.byGain > 0) nowMs else lastGainMs,
    )

    /** Whether the input clipped less than [windowMs] before [nowMs]: the input row's CLIP light. */
    fun inputClippedWithin(nowMs: Long, windowMs: Long): Boolean =
        inputSamples > 0 && nowMs - lastInputMs < windowMs

    /** Whether the gain clipped less than [windowMs] before [nowMs]: the gain row's CLIP light. */
    fun gainClippedWithin(nowMs: Long, windowMs: Long): Boolean =
        gainSamples > 0 && nowMs - lastGainMs < windowMs

    companion object {
        val NONE = ClipRecord()
    }
}
