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

    private var geometry: Geometry? = null
    private var ringPos = 0
    private var sinceLastFft = 0
    private val smoothed = FloatArray(bandCount)
    private val rose = BooleanArray(bandCount)

    /** Analyses run since the last [reset]; for the tests, which can't see them otherwise. */
    internal var analyses = 0L
        private set

    private val lock = Any()

    /** The bands after the latest analysis. Guarded by [lock]. */
    private val latest = FloatArray(bandCount)

    /** The highest each band rose to since the last [takeBands], and in which analysis. Guarded by [lock]. */
    private val risePeak = FloatArray(bandCount)
    private val riseAt = LongArray(bandCount)

    /** [analyses] as of the latest analysis, for [takeBands]. Guarded by [lock]. */
    private var published = 0L

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
        // The window, oldest sample first, then zeros up to the FFT size.
        for (k in 0 until g.windowLength) g.re[k] = g.ring[(ringPos + k) % g.windowLength] * g.window[k]
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
            val norm = ((db + DB_FLOOR) / DB_FLOOR).coerceIn(0f, 1f)

            // Fast attack, slow decay for a lively-but-readable meter. Never below the current
            // reading: a steady tone that reads a hair lower than last time must not drop the bar
            // by a whole decay step. A NaN reading (impossible from 16-bit input, but cheap to rule
            // out) is skipped, not adopted.
            val before = smoothed[b]
            val held = before * DECAY
            smoothed[b] = if (norm.isNaN()) held else max(norm, held)
            rose[b] = smoothed[b] > before
        }
        analyses++
        synchronized(lock) {
            for (b in 0 until bandCount) {
                latest[b] = smoothed[b]
                // A higher rise replaces the one held, and so does any rise once that one is stale.
                if (rose[b] && (smoothed[b] >= risePeak[b] || analyses - riseAt[b] >= RISE_HOLD)) {
                    risePeak[b] = smoothed[b]
                    riseAt[b] = analyses
                }
            }
            published = analyses
        }
    }

    /**
     * The bars to draw, 0..1, lowest frequency first: the latest analysis, but no lower than the
     * highest each band rose to since the previous call. A rise is what a reader polling between
     * bursts of analyses would miss (a click in a long buffer has decayed by the time it can read);
     * a fall it can't miss, so a falling bar shows where it is now. A rise older than [RISE_HOLD]
     * analyses is dropped: nobody was reading then (the screen was off), and showing it now would
     * flash a sound long past. Meant for one reader, which would otherwise take the other's rises.
     */
    fun takeBands(): FloatArray = synchronized(lock) {
        FloatArray(bandCount) { b ->
            val rise = if (published - riseAt[b] < RISE_HOLD) risePeak[b] else 0f
            risePeak[b] = 0f
            max(latest[b], rise)
        }
    }

    /** Clear the meter. Call it from the thread that pushes, or once that thread has stopped. */
    fun reset() {
        // The window starts as silence rather than empty, so the first analysis comes a hop in, on
        // the same grid as the rest: waiting for a full window instead, sound in its first part
        // would only ever be seen near that one window's edge.
        geometry?.ring?.fill(0f)
        ringPos = 0
        sinceLastFft = 0
        analyses = 0
        smoothed.fill(0f)
        synchronized(lock) {
            latest.fill(0f)
            risePeak.fill(0f)
            riseAt.fill(0L)
            published = 0L
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

        internal fun fftSizeFor(rate: Int): Int = nextPowerOfTwo(windowLengthFor(rate))

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
