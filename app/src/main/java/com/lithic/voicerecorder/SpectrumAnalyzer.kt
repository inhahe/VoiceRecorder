package com.lithic.voicerecorder

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Turns a live 16-bit PCM stream into a small set of normalised magnitude bands for the recording
 * spectrum display.
 *
 * PCM buffers are pushed in from the capture thread via [push]; samples slide into a fixed-size
 * window, and every 1/[UPDATES_PER_SECOND] s of audio a Hann-windowed FFT is run, its magnitude
 * spectrum bucketed into logarithmically spaced bands, and the (peak-decayed, 0..1-normalised)
 * result stored in [bands]. The UI thread reads [bands] without locking — the reference is swapped
 * atomically on each update. Everything else, [reset] included, belongs to the thread that pushes.
 */
class SpectrumAnalyzer(private val bandCount: Int = 40) {

    private val fftSize = FFT_SIZE

    // Hann window to reduce spectral leakage.
    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
    }

    private val ring = FloatArray(fftSize)
    private var ringPos = 0
    private var filled = 0

    /** Frames between analyses: 1/[UPDATES_PER_SECOND] s at the rate [reset] was given. */
    private var hop = 44100 / UPDATES_PER_SECOND
    private var sinceLastFft = 0

    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)

    // Log-spaced band edges over bins [1, fftSize/2].
    private val bandEdges = IntArray(bandCount + 1).also { edges ->
        val minBin = 1.0
        val maxBin = (fftSize / 2).toDouble()
        val logMin = ln(minBin)
        val logMax = ln(maxBin)
        for (b in 0..bandCount) {
            val t = b.toDouble() / bandCount
            edges[b] = Math.exp(logMin + t * (logMax - logMin)).toInt().coerceIn(1, fftSize / 2)
        }
    }

    @Volatile
    var bands: FloatArray = FloatArray(bandCount)
        private set

    private val smoothed = FloatArray(bandCount)

    /**
     * Feed one interleaved 16-bit PCM buffer (little-endian). [len] is the valid byte count.
     *
     * The spectrum is analysed every [hop] frames, inside the buffer when it is longer than that,
     * so the buffer size (which `AudioRecord` picks per device) doesn't change how often the bars
     * update or how fast they fall, and no stretch of a long buffer goes unanalysed.
     */
    fun push(data: ByteArray, len: Int, channels: Int) {
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
            ringPos = (ringPos + 1) % fftSize
            if (filled < fftSize) filled++
            if (++sinceLastFft >= hop && filled >= fftSize) {
                sinceLastFft = 0
                computeBands()
            }
            i += frameBytes
        }
    }

    private fun computeBands() {
        // Copy the ring (oldest→newest) into re[], apply the window; im[] = 0.
        for (k in 0 until fftSize) {
            val idx = (ringPos + k) % fftSize
            re[k] = ring[idx] * window[k]
            im[k] = 0f
        }
        Fft.transform(re, im, fftSize)

        val out = FloatArray(bandCount)
        for (b in 0 until bandCount) {
            val start = bandEdges[b]
            val end = max(start + 1, bandEdges[b + 1])
            var peak = 0f
            for (bin in start until end) {
                val mag = hypot(re[bin], im[bin]) / (fftSize / 2)
                if (mag > peak) peak = mag
            }
            // Magnitude → dB, mapped from [-DB_FLOOR, 0] dB to [0, 1].
            val db = 20f * log10(peak + 1e-6f)
            val norm = ((db + DB_FLOOR) / DB_FLOOR).coerceIn(0f, 1f)

            // Fast attack, slow decay for a lively-but-readable meter. Never below the current
            // reading: a steady tone that reads a hair lower than last frame (its phase moving
            // against the FFT bins) must not drop the bar by a whole decay step. A NaN reading
            // (impossible from 16-bit input, but cheap to rule out) is skipped, not adopted.
            val held = smoothed[b] * DECAY
            smoothed[b] = if (norm.isNaN()) held else max(norm, held)
            out[b] = smoothed[b]
        }
        bands = out
    }

    /**
     * Clear the meter. Call it from the thread that pushes (or once that thread has stopped), and
     * pass the recording's [sampleRate] whenever it changes so analyses stay at
     * [UPDATES_PER_SECOND] per second of audio.
     */
    fun reset(sampleRate: Int = 0) {
        if (sampleRate > 0) hop = max(1, sampleRate / UPDATES_PER_SECOND)
        ringPos = 0
        filled = 0
        sinceLastFft = 0
        java.util.Arrays.fill(smoothed, 0f)
        bands = FloatArray(bandCount)
    }

    companion object {
        /** Samples per analysis window. */
        internal const val FFT_SIZE = 1024

        /**
         * Analyses per second of audio. At 44.1 kHz that is a hop of 882 samples, shorter than
         * the window, so the windows overlap and every sample reaches the meter.
         */
        internal const val UPDATES_PER_SECOND = 50

        /** Dynamic range shown, in dB below full-scale. */
        private const val DB_FLOOR = 60f

        /**
         * A band that stops rising falls to 1% in this long: the speed the meter had on a typical
         * phone when it decayed once per (80 ms) capture buffer.
         */
        private const val FALL_SECONDS = 1.9f

        /** Per-analysis decay that makes [FALL_SECONDS] hold. */
        private val DECAY = 0.01f.pow(1f / (FALL_SECONDS * UPDATES_PER_SECOND))
    }
}
