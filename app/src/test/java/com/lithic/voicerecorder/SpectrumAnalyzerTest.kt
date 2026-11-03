package com.lithic.voicerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Deterministic DSP checks for [SpectrumAnalyzer] / [Fft] that run on the JVM (no Android or mic
 * needed). They prove the analyzer actually reacts to signal — silence stays flat, a tone lights up
 * a band, and the lit band moves up as the tone frequency rises — and that what the bars show
 * depends neither on the buffer size nor on the sample rate.
 */
class SpectrumAnalyzerTest {

    private val sampleRate = 44100

    /** Build interleaved mono 16-bit LE PCM for a sine of [freq] Hz, silent outside [from] until [until]. */
    private fun sinePcm(
        freq: Double, samples: Int, amp: Double = 0.8, rate: Int = sampleRate,
        from: Int = 0, until: Int = samples
    ): ByteArray {
        val out = ByteArray(samples * 2)
        for (n in from until until) {
            val v = (sin(2.0 * Math.PI * freq * n / rate) * amp * 32767).toInt()
            out[n * 2] = (v and 0xff).toByte()
            out[n * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun bandsFor(freq: Double): FloatArray {
        val analyzer = SpectrumAnalyzer()
        val pcm = sinePcm(freq, 8192)
        analyzer.push(pcm, pcm.size, channels = 1, sampleRate = sampleRate)
        return analyzer.takeBands()
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
        analyzer.push(silence, silence.size, channels = 1, sampleRate = sampleRate)
        val peak = analyzer.takeBands().maxOrNull() ?: 0f
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

    /** Pushes [pcm] in pieces of [chunkFrames] frames at [rate], calling [after] after each piece. */
    private fun pushInChunks(
        analyzer: SpectrumAnalyzer, pcm: ByteArray, chunkFrames: Int, rate: Int = sampleRate, after: () -> Unit = {}
    ) {
        val chunkBytes = chunkFrames * 2
        var offset = 0
        while (offset < pcm.size) {
            val part = pcm.copyOfRange(offset, minOf(offset + chunkBytes, pcm.size))
            analyzer.push(part, part.size, channels = 1, sampleRate = rate)
            offset += part.size
            after()
        }
    }

    /**
     * A steady tone must read steadily: one that reads a hair lower than last time must not drop
     * the bar by a whole decay step. The tone advances a fraction of a cycle per hop, so successive
     * analyses see it at different phases, and the pieces are shorter than a hop, so each reading
     * comes from one analysis.
     */
    @Test
    fun steadyToneHoldsItsLevel() {
        val analyzer = SpectrumAnalyzer()
        var band = -1
        var seen = 0L
        val readings = mutableListOf<Float>()
        pushInChunks(analyzer, sinePcm(3010.0, sampleRate), chunkFrames = 512) {
            val bands = analyzer.takeBands()
            if (analyzer.analyses == seen) return@pushInChunks
            seen = analyzer.analyses
            // Skip the first few analyses, while the bar is still climbing to the tone.
            if (seen < 3) return@pushInChunks
            if (band < 0) band = argmax(bands)
            readings.add(bands[band])
        }
        val min = readings.minOrNull() ?: 0f
        val max = readings.maxOrNull() ?: 0f
        assertTrue("no readings: the analyzer never ran", readings.size > 10 && max > 0.5f)
        // A decay step is about 5%; the tone's own wobble is far below 1%.
        assertTrue("a steady tone's bar dipped from $max to $min", min > max * 0.99f)
    }

    /** How fast the bars fall doesn't depend on how the audio is cut up. */
    @Test
    fun barsFallAtTheSameSpeedWhateverTheBufferSize() {
        val hop = sampleRate / SpectrumAnalyzer.UPDATES_PER_SECOND
        fun afterSilence(chunkFrames: Int): Float {
            val analyzer = SpectrumAnalyzer()
            pushInChunks(analyzer, sinePcm(3000.0, sampleRate / 2), chunkFrames)
            pushInChunks(analyzer, ByteArray(sampleRate / 2 * 2), chunkFrames) // half a second of silence
            // Then exactly one more analysis, read on its own.
            analyzer.takeBands()
            val before = analyzer.analyses
            pushInChunks(analyzer, ByteArray(hop * 2), chunkFrames = hop)
            assertEquals("analyses in one hop", before + 1, analyzer.analyses)
            return analyzer.takeBands().maxOrNull() ?: 0f
        }
        val reference = afterSilence(256)
        assertTrue("the bar should be falling, not gone or still full: $reference", reference in 0.05f..0.7f)
        for (chunk in listOf(7, 1024, 3584, 7168)) {
            assertEquals("bars after silence with $chunk-frame buffers", reference.toDouble(), afterSilence(chunk).toDouble(), 1e-3)
        }
    }

    /**
     * A short sound reaches its full height on screen however many analyses one buffer spans:
     * the screen takes the peak since its last read, not just the latest analysis, which in a long
     * buffer has already decayed.
     */
    @Test
    fun aShortSoundShowsAtFullHeightWhateverTheBufferSize() {
        // Half a second of silence with 5 ms of tone early in its second 7168-frame stretch.
        val start = 7168 + 2000
        val pcm = sinePcm(3000.0, sampleRate / 2, from = start, until = start + sampleRate / 200)
        fun shown(chunkFrames: Int): Float {
            val analyzer = SpectrumAnalyzer()
            var highest = 0f
            // Read once per buffer, as often as the screen could possibly see a change.
            pushInChunks(analyzer, pcm, chunkFrames) { highest = max(highest, analyzer.takeBands().maxOrNull() ?: 0f) }
            return highest
        }
        val reference = shown(64)
        assertTrue("the sound should light the meter: $reference", reference > 0.5f)
        for (chunk in listOf(1024, 7168)) {
            assertEquals("the sound's height with $chunk-frame buffers", reference.toDouble(), shown(chunk).toDouble(), 1e-3)
        }
    }

    /** Nor does the sample rate change how often the bars are analysed. */
    @Test
    fun analysesComeAtAFixedRatePerSecondOfAudio() {
        for (rate in AudioFormatSpec.SAMPLE_RATES) {
            val analyzer = SpectrumAnalyzer()
            pushInChunks(analyzer, ByteArray(rate * 2 * 2), chunkFrames = 300, rate = rate) // two seconds
            // Less the time the window takes to fill.
            val expected = (2.0 - SpectrumAnalyzer.windowLengthFor(rate).toDouble() / rate) * SpectrumAnalyzer.UPDATES_PER_SECOND
            assertEquals("$rate Hz", expected, analyzer.analyses.toDouble(), 2.0)
        }
    }

    /**
     * Or how long the bars stay up once a sound stops: the window is a fixed length of time, so a
     * tone that ends lets go of them as quickly at 8 kHz as at 96 kHz.
     */
    @Test
    fun barsLetGoAsQuicklyAtEveryRate() {
        fun msToHalf(rate: Int): Int {
            val analyzer = SpectrumAnalyzer()
            pushInChunks(analyzer, sinePcm(1000.0, rate, rate = rate), chunkFrames = rate / 100, rate = rate)
            val steady = analyzer.takeBands().maxOrNull() ?: 0f
            val ms = rate / 1000
            for (elapsed in 1..2000) {
                analyzer.push(ByteArray(ms * 2), ms * 2, channels = 1, sampleRate = rate)
                if ((analyzer.takeBands().maxOrNull() ?: 0f) < steady / 2) return elapsed
            }
            return -1
        }
        val reference = msToHalf(48000)
        assertTrue("the bars should fall: $reference ms", reference > 0)
        for (rate in listOf(8000, 16000, 44100, 96000)) {
            val took = msToHalf(rate)
            assertTrue("$rate Hz took $took ms to fall to half, 48 kHz $reference ms", abs(took - reference) <= 25)
        }
    }

    /** At every rate offered, consecutive windows overlap by half or more, so no sound slips between them. */
    @Test
    fun windowsOverlapAtEveryRate() {
        for (rate in AudioFormatSpec.SAMPLE_RATES) {
            val window = SpectrumAnalyzer.windowLengthFor(rate)
            val hop = rate / SpectrumAnalyzer.UPDATES_PER_SECOND
            assertTrue("$rate Hz: a $window-frame window for a $hop-frame hop", window >= 2 * hop)
            val fft = SpectrumAnalyzer.fftSizeFor(rate)
            assertTrue("$rate Hz: FFT of $fft for a $window-frame window", fft >= window && fft and (fft - 1) == 0)
            // And the analyzer accepts the rate (it checks the same itself).
            SpectrumAnalyzer().push(ByteArray(2), 2, channels = 1, sampleRate = rate)
        }
    }
}
