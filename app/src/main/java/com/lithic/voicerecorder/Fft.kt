package com.lithic.voicerecorder

/**
 * Minimal in-place iterative radix-2 Cooley–Tukey FFT operating on separate real/imaginary arrays.
 *
 * No external dependencies — used by [SpectrumAnalyzer] to turn PCM windows into magnitude spectra.
 * [n] must be a power of two and match the length of [re] / [im].
 */
object Fft {

    /** In-place forward transform. [re] and [im] must both have length [n] (a power of two). */
    fun transform(re: FloatArray, im: FloatArray, n: Int) {
        require(n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        // Butterflies.
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wpr = Math.cos(ang).toFloat()
            val wpi = Math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wr = 1.0f
                var wi = 0.0f
                val half = len shr 1
                for (k in 0 until half) {
                    val iEven = i + k
                    val iOdd = i + k + half
                    val tr = wr * re[iOdd] - wi * im[iOdd]
                    val ti = wr * im[iOdd] + wi * re[iOdd]
                    re[iOdd] = re[iEven] - tr
                    im[iOdd] = im[iEven] - ti
                    re[iEven] += tr
                    im[iEven] += ti
                    val nwr = wr * wpr - wi * wpi
                    wi = wr * wpi + wi * wpr
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
