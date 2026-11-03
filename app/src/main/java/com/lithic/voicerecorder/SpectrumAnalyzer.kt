package com.lithic.voicerecorder

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a live 16-bit PCM stream into a small set of normalised magnitude bands for the recording
 * spectrum display.
 *
 * The capture thread feeds it through [push]. Every 1/[UPDATES_PER_SECOND] s of audio, the last
 * [WINDOW_SECONDS] are Hann-windowed and transformed, the magnitude spectrum is bucketed into
 * logarithmically spaced bands, and each band is mapped to 0..1 and peak-decayed. The display reads
 * the result with [takeBands], which may be called from any thread; everything else, [reset]
 * included, belongs to the thread that pushes.
 *
 * What the bars show depends neither on how `AudioRecord` cuts the audio into buffers (its size is
 * the device's choice) nor on the sample rate: analyses happen at fixed points in the audio, the
 * window is a fixed length of time, and [takeBands] shows the latest analysis together with any
 * rise since the previous read, so a click reaches its full height however many analyses one
 * buffer holds, while a falling bar is never held back.
 */
class SpectrumAnalyzer(private val bandCount: Int = 40) {

    /** Everything that depends on the sample rate, built once per rate and replaced whole. */
    private class Geometry(val sampleRate: Int, bandCount: Int) {
        /** Frames between analyses. */
        val hop = sampleRate / UPDATES_PER_SECOND

        /** Frames each analysis looks at: [WINDOW_SECONDS] at [sampleRate]. */
        val windowLength = windowLengthFor(sampleRate)

        /** [windowLength] rounded up to a power of two; the FFT input past the window is zero. */
        val fftSize = nextPowerOfTwo(windowLength)

        init {
            require(sampleRate >= UPDATES_PER_SECOND) { "Sample rate $sampleRate Hz" }
            // Every sample must count: Hann windows that overlap by half or more weigh each sample
            // at least 0.5 in one of them. Overlapping less, a click between two windows all but
            // vanishes.
            check(windowLength >= 2 * hop) { "A $windowLength-frame window for a $hop-frame hop" }
        }

        val window = FloatArray(windowLength) { i -> (0.5 - 0.5 * cos(2.0 * Math.PI * i / (windowLength - 1))).toFloat() }
        val bandEdges = bandEdgesFor(bandCount, fftSize)
        val ring = FloatArray(windowLength)
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
    }

    /**
     * One band's rises since the last read, kept only while each could still be the highest of the
     * last [RISE_HOLD] analyses: oldest first, values falling. A sliding-window maximum, so a lower
     * rise behind a higher one isn't lost when the higher one ages out before the read.
     */
    private class Rises {
        private val value = FloatArray(RISE_HOLD + 1)
        private val at = LongArray(RISE_HOLD + 1)
        private var first = 0
        private var count = 0

        /** The band rose to [v] in analysis [analysis]. */
        fun add(v: Float, analysis: Long) {
            expire(analysis)
            while (count > 0 && value[slot(count - 1)] <= v) count--
            // Room: what is left is younger than RISE_HOLD analyses, one rise per analysis at most.
            val s = slot(count)
            value[s] = v
            at[s] = analysis
            count++
        }

        /** The highest rise of the last [RISE_HOLD] analyses as of analysis [now], or 0; then none are left. */
        fun take(now: Long): Float {
            expire(now)
            val highest = if (count > 0) value[first] else 0f
            count = 0
            return highest
        }

        fun clear() {
            count = 0
        }

        private fun expire(now: Long) {
            while (count > 0 && now - at[first] >= RISE_HOLD) {
                first = (first + 1) % value.size
                count--
            }
        }

        private fun slot(k: Int) = (first + k) % value.size
    }

    private var geometry: Geometry? = null
    private var ringPos = 0
    private var sinceLastFft = 0

    /** The latest analysis's readings, before smoothing; the analysing thread's own. */
    private val readings = FloatArray(bandCount)

    private val lock = Any()

    /** The bars as of the latest analysis. Guarded by [lock]. */
    private val smoothed = FloatArray(bandCount)

    /** Each band's rises since the last [takeBands]. Guarded by [lock]. */
    private val rises = Array(bandCount) { Rises() }

    /** Analyses run since the last [reset]. Guarded by [lock] (the tests read it on the one thread). */
    internal var analyses = 0L
        private set

    /**
     * Feed one interleaved 16-bit little-endian PCM buffer: [len] valid bytes of [channels]
     * channels at [sampleRate] Hz. A change of rate starts the meter afresh.
     */
    fun push(data: ByteArray, len: Int, channels: Int, sampleRate: Int) {
        val g = geometry?.takeIf { it.sampleRate == sampleRate }
            ?: Geometry(sampleRate, bandCount).also {
                geometry = it
                reset()
            }
        val frameBytes = 2 * channels
        var i = 0
        while (i + frameBytes <= len) {
            // Down-mix all channels to mono, normalise to [-1, 1].
            var acc = 0
            for (c in 0 until channels) {
                val lo = data[i + c * 2].toInt() and 0xff
                val hi = data[i + c * 2 + 1].toInt() // sign-extended
                acc += (hi shl 8) or lo
            }
            g.ring[ringPos] = (acc.toFloat() / channels) / 32768f
            ringPos = (ringPos + 1) % g.windowLength
            if (++sinceLastFft >= g.hop) {
                sinceLastFft = 0
                analyse(g)
            }
            i += frameBytes
        }
    }

    private fun analyse(g: Geometry) {
        // The window, oldest sample first (from ringPos to the end of the ring, then from its
        // start), then zeros up to the FFT size.
        val older = g.windowLength - ringPos
        for (k in 0 until older) g.re[k] = g.ring[ringPos + k] * g.window[k]
        for (k in older until g.windowLength) g.re[k] = g.ring[k - older] * g.window[k]
        g.re.fill(0f, g.windowLength, g.fftSize)
        g.im.fill(0f)
        Fft.transform(g.re, g.im, g.fftSize)

        // A full-scale sine through the Hann window peaks at windowLength/4, so this puts it at
        // -6 dB; the scale is the meter's calibration and only needs to be the same at every rate.
        val scale = g.windowLength / 2f
        for (b in 0 until bandCount) {
            val start = g.bandEdges[b]
            val end = max(start + 1, g.bandEdges[b + 1])
            var peakPower = 0f // the loudest bin, compared by power: one square root per band
            for (bin in start until end) {
                val power = g.re[bin] * g.re[bin] + g.im[bin] * g.im[bin]
                if (power > peakPower) peakPower = power
            }
            // Magnitude → dB, mapped from [-DB_FLOOR, 0] dB to [0, 1].
            val db = 20f * log10(sqrt(peakPower) / scale + 1e-6f)
            readings[b] = ((db + DB_FLOOR) / DB_FLOOR).coerceIn(0f, 1f)
        }
        // A few dozen multiplications: cheap enough to hold the reader off for.
        synchronized(lock) {
            analyses++
            for (b in 0 until bandCount) {
                // Fast attack, slow decay for a lively-but-readable meter. Never below the current
                // reading: a steady tone that reads a hair lower than last time must not drop the
                // bar by a whole decay step. A NaN reading (impossible from 16-bit input, but cheap
                // to rule out) is skipped, not adopted.
                val before = smoothed[b]
                val held = before * DECAY
                smoothed[b] = if (readings[b].isNaN()) held else max(readings[b], held)
                if (smoothed[b] > before) rises[b].add(smoothed[b], analyses)
            }
        }
    }

    /**
     * The bars to draw, 0..1, lowest frequency first: the latest analysis, but no lower than the
     * highest each band rose to since the previous call. A rise is what a reader polling between
     * bursts of analyses would miss (a click in a long buffer has decayed by the time it can read);
     * a fall it can't miss, so a falling bar shows where it is now. Only rises of the last
     * [RISE_HOLD] analyses count: one older was made while nobody was reading (the screen was off),
     * and showing it now would flash a sound long past. Meant for one reader, which would
     * otherwise take the other's rises.
     */
    fun takeBands(): FloatArray = synchronized(lock) {
        FloatArray(bandCount) { b -> max(smoothed[b], rises[b].take(analyses)) }
    }

    /** Clear the meter. Call it from the thread that pushes, or once that thread has stopped. */
    fun reset() {
        // The window starts as silence rather than empty, so the first analysis comes a hop in, on
        // the same grid as the rest: waiting for a full window instead, sound in its first part
        // would only ever be seen near that one window's edge.
        geometry?.ring?.fill(0f)
        ringPos = 0
        sinceLastFft = 0
        synchronized(lock) {
            analyses = 0
            smoothed.fill(0f)
            for (r in rises) r.clear()
        }
    }

    companion object {
        /** Analyses per second of audio. */
        internal const val UPDATES_PER_SECOND = 50

        /**
         * The audio each analysis looks at: 2048 frames at 48 kHz. Fixed in time rather than in
         * frames, so a sound that stops lets go of the bars as quickly at 8 kHz as at 96 kHz, and
         * at least two hops long (see [Geometry]).
         */
        internal const val WINDOW_SECONDS = 2048.0 / 48000

        /** How long, in analyses, a rise waits to be read before it is dropped: half a second. */
        private const val RISE_HOLD = UPDATES_PER_SECOND / 2

        internal fun windowLengthFor(rate: Int): Int = (rate * WINDOW_SECONDS).roundToInt()

        private fun nextPowerOfTwo(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }

        /** Log-spaced band edges over bins [1, fftSize/2]. */
        private fun bandEdgesFor(bandCount: Int, fftSize: Int) = IntArray(bandCount + 1) { b ->
            val logMax = ln((fftSize / 2).toDouble())
            Math.exp(b.toDouble() / bandCount * logMax).toInt().coerceIn(1, fftSize / 2)
        }

        /** Dynamic range shown, in dB below full-scale. */
        private const val DB_FLOOR = 60f

        /** A band that stops rising falls to 1% in this long. */
        private const val FALL_SECONDS = 1.9f

        /** Per-analysis decay that makes [FALL_SECONDS] hold. */
        private val DECAY = 0.01f.pow(1f / (FALL_SECONDS * UPDATES_PER_SECOND))
    }
}
