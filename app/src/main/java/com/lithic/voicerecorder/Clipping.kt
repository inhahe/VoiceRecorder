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
 * One kind of clipping over a recording: how many samples, and the `SystemClock.elapsedRealtime()`
 * of the latest buffer with any ([lastMs] is meaningless while [samples] is 0).
 */
data class ClipTally(val samples: Long = 0L, val lastMs: Long = 0L) {
    /** This tally with [count] more samples, found in a buffer captured at [nowMs]. */
    fun add(count: Int, nowMs: Long): ClipTally = if (count == 0) this else ClipTally(samples + count, nowMs)

    /** Whether this kind clipped less than [windowMs] before [nowMs]: whether its CLIP light is lit. */
    fun within(nowMs: Long, windowMs: Long): Boolean = samples > 0 && nowMs - lastMs < windowMs
}

/** Where a recording clipped, which decides what the message after it says to do. */
enum class ClippedAt { INPUT, GAIN, BOTH }

/**
 * How one recording has clipped so far, a [ClipTally] for each kind (see [ClipCount]).
 *
 * Immutable, so the capture thread publishes a new one through a single volatile field and a reader
 * on another thread always sees one consistent record, never a total from one buffer and a time from
 * the next.
 */
data class ClipRecord(val input: ClipTally = ClipTally(), val gain: ClipTally = ClipTally()) {
    /** This record with [count], found in a buffer captured at [nowMs] (`elapsedRealtime()`), added. */
    fun add(count: ClipCount, nowMs: Long): ClipRecord =
        ClipRecord(input.add(count.atInput, nowMs), gain.add(count.byGain, nowMs))

    /** Where it clipped over the whole recording, or null if it never did. */
    val clippedAt: ClippedAt?
        get() = when {
            input.samples > 0 && gain.samples > 0 -> ClippedAt.BOTH
            input.samples > 0 -> ClippedAt.INPUT
            gain.samples > 0 -> ClippedAt.GAIN
            else -> null
        }

    companion object {
        val NONE = ClipRecord()
    }
}
