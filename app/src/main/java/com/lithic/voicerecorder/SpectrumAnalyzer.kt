package com.lithic.voicerecorder

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

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
 * window is a fixed length of time, and [takeBands] returns the peak since its previous call, so a
 * click is shown at its full height however many analyses one buffer or one display frame spans.
 */
class SpectrumAnalyzer(private val bandCount: Int = 40) {

    /** The rate the buffers below are sized for; 0 until the first [push]. */
    private var sampleRate = 0

    /** Frames between analyses. */
    private var hop = 0

    /** Frames each analysis looks at: [WINDOW_SECONDS] at [sampleRate]. */
    private var windowLength = 0

    /** [windowLength] rounded up to a power of two; the FFT input past the window is zero. */
    private var fftSize = 0

    private var window = FloatArray(0)
    private var ring = FloatArray(0)
    private var ringPos = 0
    private var filled = 0
    private var sinceLastFft = 0
    private var re = FloatArray(0)
    private var im = FloatArray(0)
    private var bandEdges = IntArray(0)
    private val smoothed = FloatArray(bandCount)

    /** Analyses run since the last [reset]; for the tests, which can't see them otherwise. */
    internal var analyses = 0L
        private set

    private val lock = Any()

    /** The highest each band reached since the last [takeBands]. Guarded by [lock]. */
    private val peaks = FloatArray(bandCount)

    /** True when [peaks] has been taken and the next analysis starts it afresh. Guarded by [lock]. */
    private var peaksTaken = true

    /**
     * Feed one interleaved 16-bit little-endian PCM buffer: [len] valid bytes of [channels]
     * channels at [sampleRate] Hz. A change of rate starts the meter afresh.
     */
    fun push(data: ByteArray, len: Int, channels: Int, sampleRate: Int) {
        if (sampleRate != this.sampleRate) configure(sampleRate)
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
            ring[ringPos] = (acc.toFloat() / channels) / 32768f
            ringPos = (ringPos + 1) % windowLength
            if (filled < windowLength) filled++
            if (++sinceLastFft >= hop && filled >= windowLength) {
                sinceLastFft = 0
                analyse()
            }
            i += frameBytes
        }
    }

    private fun analyse() {
        // The window, oldest sample first, then zeros up to the FFT size.
        for (k in 0 until windowLength) re[k] = ring[(ringPos + k) % windowLength] * window[k]
        re.fill(0f, windowLength, fftSize)
        im.fill(0f)
        Fft.transform(re, im, fftSize)

        // A full-scale sine through the Hann window peaks at windowLength/4, so this puts it at
        // -6 dB; the scale is the meter's calibration and only needs to be the same at every rate.
        val scale = windowLength / 2f
        for (b in 0 until bandCount) {
            val start = bandEdges[b]
            val end = max(start + 1, bandEdges[b + 1])
            var peak = 0f
            for (bin in start until end) {
                val mag = hypot(re[bin], im[bin]) / scale
                if (mag > peak) peak = mag
            }
            // Magnitude → dB, mapped from [-DB_FLOOR, 0] dB to [0, 1].
            val db = 20f * log10(peak + 1e-6f)
            val norm = ((db + DB_FLOOR) / DB_FLOOR).coerceIn(0f, 1f)

            // Fast attack, slow decay for a lively-but-readable meter. Never below the current
            // reading: a steady tone that reads a hair lower than last time must not drop the bar
            // by a whole decay step. A NaN reading (impossible from 16-bit input, but cheap to rule
            // out) is skipped, not adopted.
            val held = smoothed[b] * DECAY
            smoothed[b] = if (norm.isNaN()) held else max(norm, held)
        }
        analyses++
        synchronized(lock) {
            for (b in 0 until bandCount) peaks[b] = if (peaksTaken) smoothed[b] else max(peaks[b], smoothed[b])
            peaksTaken = false
        }
    }

    /**
     * The bars to draw, 0..1, lowest frequency first: the highest each band reached since the
     * previous call, or the same as then if nothing has been analysed since. Meant for one reader,
     * which would otherwise take the other's peaks.
     */
    fun takeBands(): FloatArray = synchronized(lock) {
        peaksTaken = true
        peaks.copyOf()
    }

    /** Clear the meter. Call it from the thread that pushes, or once that thread has stopped. */
    fun reset() {
        ringPos = 0
        filled = 0
        sinceLastFft = 0
        analyses = 0
        smoothed.fill(0f)
        synchronized(lock) {
            peaks.fill(0f)
            peaksTaken = true
        }
    }

    private fun configure(rate: Int) {
        require(rate >= UPDATES_PER_SECOND) { "Sample rate $rate Hz" }
        sampleRate = rate
        hop = rate / UPDATES_PER_SECOND
        windowLength = windowLengthFor(rate)
        // Every sample must count: Hann windows that overlap by half or more weigh each sample at
        // least 0.5 in one of them. Overlapping less, a click between two windows all but vanishes.
        check(windowLength >= 2 * hop) { "A $windowLength-frame window for a $hop-frame hop" }
        fftSize = fftSizeFor(rate)
        window = FloatArray(windowLength) { i -> (0.5 - 0.5 * cos(2.0 * Math.PI * i / (windowLength - 1))).toFloat() }
        ring = FloatArray(windowLength)
        re = FloatArray(fftSize)
        im = FloatArray(fftSize)
        bandEdges = bandEdgesFor(bandCount, fftSize)
        reset()
    }

    companion object {
        /** Analyses per second of audio. */
        internal const val UPDATES_PER_SECOND = 50

        /**
         * The audio each analysis looks at: 2048 frames at 48 kHz. Fixed in time rather than in
         * frames, so a sound that stops lets go of the bars as quickly at 8 kHz as at 96 kHz, and
         * at least two hops long (see [configure]).
         */
        internal const val WINDOW_SECONDS = 2048.0 / 48000

        internal fun windowLengthFor(rate: Int): Int = (rate * WINDOW_SECONDS).roundToInt()

        internal fun fftSizeFor(rate: Int): Int {
            var n = 1
            while (n < windowLengthFor(rate)) n = n shl 1
            return n
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
