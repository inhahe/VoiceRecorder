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

    /** Pushes [pcm] in pieces of [chunkFrames] frames, calling [after] after each piece. */
    private fun pushInChunks(analyzer: SpectrumAnalyzer, pcm: ByteArray, chunkFrames: Int, after: () -> Unit = {}) {
        val chunkBytes = chunkFrames * 2
        var offset = 0
        while (offset < pcm.size) {
            val part = pcm.copyOfRange(offset, minOf(offset + chunkBytes, pcm.size))
            analyzer.push(part, part.size, channels = 1)
            offset += part.size
            after()
        }
    }

    /**
     * A steady tone must read steadily. Analysed every hop, a tone that falls between FFT bins reads
     * a little higher or lower from one analysis to the next as its phase moves. One that reads a
     * hair lower must not drop the bar by a whole decay step. The pieces here are smaller than the
     * hop, so successive readings come from successive analyses.
     */
    @Test
    fun steadyToneHoldsItsLevel() {
        val analyzer = SpectrumAnalyzer().apply { reset(sampleRate) }
        var band = -1
        var analyses = 0
        var last = analyzer.bands
        val readings = mutableListOf<Float>()
        pushInChunks(analyzer, sinePcm(3000.0, sampleRate), chunkFrames = 512) {
            if (analyzer.bands !== last) analyses++
            last = analyzer.bands
            // Skip the first few analyses, while the bar is still climbing to the tone.
            if (analyses < 3) return@pushInChunks
            if (band < 0) band = argmax(analyzer.bands)
            readings.add(analyzer.bands[band])
        }
        val min = readings.minOrNull() ?: 0f
        val max = readings.maxOrNull() ?: 0f
        assertTrue("no readings: the analyzer never ran", readings.size > 10 && max > 0.5f)
        assertTrue("a steady tone's bar dipped from $max to $min", min > max * 0.95f)
    }

    /**
     * How fast the bars fall doesn't depend on how the audio is cut up. `AudioRecord` hands the
     * capture loop buffers of a size it picks per device, and one analysis per buffer made the meter
     * sluggish on one phone and twitchy on another.
     */
    @Test
    fun barsFallAtTheSameSpeedWhateverTheBufferSize() {
        fun afterSilence(chunkFrames: Int): Float {
            val analyzer = SpectrumAnalyzer().apply { reset(sampleRate) }
            pushInChunks(analyzer, sinePcm(3000.0, sampleRate / 2), chunkFrames)
            pushInChunks(analyzer, ByteArray(sampleRate / 2 * 2), chunkFrames) // half a second of silence
            return analyzer.bands.maxOrNull() ?: 0f
        }
        val reference = afterSilence(256)
        assertTrue("the bar should be falling, not gone or still full: $reference", reference in 0.05f..0.7f)
        for (chunk in listOf(7, 1024, 3584, 7168)) {
            assertEquals("bars after silence with $chunk-frame buffers", reference.toDouble(), afterSilence(chunk).toDouble(), 1e-3)
        }
    }

    /** Nor does the sample rate: the analyses come at a fixed rate per second of audio. */
    @Test
    fun analysesComeAtAFixedRatePerSecondOfAudio() {
        for (rate in listOf(8000, 16000, 44100, 48000)) {
            val analyzer = SpectrumAnalyzer().apply { reset(rate) }
            var updates = 0
            var last = analyzer.bands
            // Two seconds, one frame at a time, so every analysis is seen.
            pushInChunks(analyzer, ByteArray(rate * 2 * 2), chunkFrames = 1) {
                if (analyzer.bands !== last) updates++
                last = analyzer.bands
            }
            // Less the time the window takes to fill.
            val expected = (2.0 - SpectrumAnalyzer.FFT_SIZE.toDouble() / rate) * SpectrumAnalyzer.UPDATES_PER_SECOND
            assertEquals("$rate Hz", expected, updates.toDouble(), 2.0)
        }
    }
}
