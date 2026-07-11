package com.lithic.voicerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Deterministic DSP checks for [SpectrumAnalyzer] / [Fft] that run on the JVM (no Android or mic
 * needed). They prove the analyzer actually reacts to signal — silence stays flat, a tone lights up
 * a band, and the lit band moves up as the tone frequency rises.
 */
class SpectrumAnalyzerTest {

    private val sampleRate = 44100

    /** Build interleaved mono 16-bit LE PCM for a sine of [freq] Hz. */
    private fun sinePcm(freq: Double, samples: Int, amp: Double = 0.8): ByteArray {
        val out = ByteArray(samples * 2)
        for (n in 0 until samples) {
            val v = (sin(2.0 * Math.PI * freq * n / sampleRate) * amp * 32767).toInt()
            out[n * 2] = (v and 0xff).toByte()
            out[n * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun bandsFor(freq: Double): FloatArray {
        val analyzer = SpectrumAnalyzer()
        val pcm = sinePcm(freq, 8192)
        analyzer.push(pcm, pcm.size, channels = 1)
        return analyzer.bands
    }

    private fun argmax(a: FloatArray): Int {
        var idx = 0
        for (i in a.indices) if (a[i] > a[idx]) idx = i
        return idx
    }

    @Test
    fun silenceStaysFlat() {
        val analyzer = SpectrumAnalyzer()
        val silence = ByteArray(8192 * 2) // all zeros
        analyzer.push(silence, silence.size, channels = 1)
        val peak = analyzer.bands.maxOrNull() ?: 0f
        assertTrue("silent input should give ~0 bands, got $peak", peak < 0.05f)
    }

    @Test
    fun toneLightsUpABand() {
        val bands = bandsFor(3000.0)
        val peak = bands.maxOrNull() ?: 0f
        assertTrue("a strong tone should drive a band high, got $peak", peak > 0.6f)
        val idx = argmax(bands)
        assertTrue("peak band should be in the interior, got $idx", idx in 1 until bands.size - 1)
    }

    @Test
    fun peakBandRisesWithFrequency() {
        val low = argmax(bandsFor(300.0))
        val mid = argmax(bandsFor(3000.0))
        val high = argmax(bandsFor(12000.0))
        assertTrue("expected low < mid ($low < $mid)", low < mid)
        assertTrue("expected mid < high ($mid < $high)", mid < high)
    }

    @Test
    fun fftMatchesNaiveDftMagnitude() {
        // Cross-check the FFT against a direct DFT on a small random-ish signal.
        val n = 64
        val re = FloatArray(n) { (sin(0.3 * it) + 0.5 * sin(1.1 * it + 0.7)).toFloat() }
        val im = FloatArray(n)
        val srcRe = re.copyOf()
        Fft.transform(re, im, n)
        for (k in 0 until n) {
            var dr = 0.0
            var di = 0.0
            for (t in 0 until n) {
                val ang = -2.0 * Math.PI * k * t / n
                dr += srcRe[t] * Math.cos(ang)
                di += srcRe[t] * Math.sin(ang)
            }
            assertEquals("re[$k]", dr, re[k].toDouble(), 1e-2)
            assertEquals("im[$k]", di, im[k].toDouble(), 1e-2)
        }
    }
}
