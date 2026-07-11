package com.lithic.voicerecorder

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max

/**
 * Turns a live 16-bit PCM stream into a small set of normalised magnitude bands for the recording
 * spectrum display.
 *
 * PCM buffers are pushed in from the capture thread via [push]; each push slides samples into a
 * fixed-size window, runs a Hann-windowed FFT, buckets the magnitude spectrum into logarithmically
 * spaced bands and stores the (peak-decayed, 0..1-normalised) result in [bands]. The UI thread reads
 * [bands] without locking — the reference is swapped atomically on each update.
 */
class SpectrumAnalyzer(private val bandCount: Int = 40) {

    private val fftSize = 1024

    // Hann window to reduce spectral leakage.
    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
    }

    private val ring = FloatArray(fftSize)
    private var ringPos = 0
    private var filled = 0

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

    /** Feed one interleaved 16-bit PCM buffer (little-endian). [len] is the valid byte count. */
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
            i += frameBytes
        }
        if (filled >= fftSize) computeBands()
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

            // Fast attack, slow decay for a lively-but-readable meter.
            smoothed[b] = if (norm > smoothed[b]) norm else smoothed[b] * DECAY
            out[b] = smoothed[b]
        }
        bands = out
    }

    /** Clear the meter (call when recording stops). */
    fun reset() {
        ringPos = 0
        filled = 0
        java.util.Arrays.fill(smoothed, 0f)
        bands = FloatArray(bandCount)
    }

    companion object {
        /** Dynamic range shown, in dB below full-scale. */
        private const val DB_FLOOR = 60f
        private const val DECAY = 0.82f
    }
}
