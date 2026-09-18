package com.lithic.voicerecorder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM checks for the sample-rate labelling shared by the home screen, the settings screen and the
 * pre-record dialog's device-rate warning, plus the bytes/second estimate behind the storage hint.
 */
class AudioFormatSpecTest {

    @Test fun `whole-kHz rates lose the decimal point entirely`() {
        assertEquals("48 kHz", AudioFormatSpec.hzLabel(48000))
        assertEquals("8 kHz", AudioFormatSpec.hzLabel(8000))
    }

    @Test fun `fractional rates keep only the digits they need`() {
        assertEquals("44.1 kHz", AudioFormatSpec.hzLabel(44100))
        assertEquals("22.05 kHz", AudioFormatSpec.hzLabel(22050))
        assertEquals("88.2 kHz", AudioFormatSpec.hzLabel(88200))
    }

    @Test fun `11025 keeps its third decimal instead of rounding to 11 03`() {
        // Devices report 11025 in getSampleRates(), and two decimals turned it into "11.03 kHz".
        assertEquals("11.025 kHz", AudioFormatSpec.hzLabel(11025))
        assertEquals("8 / 11.025 / 48 kHz", AudioFormatSpec.hzListLabel(listOf(48000, 8000, 11025)))
    }

    @Test fun `a device's supported rates read as one unit`() {
        assertEquals("48 kHz", AudioFormatSpec.hzListLabel(listOf(48000)))
        assertEquals("44.1 / 48 kHz", AudioFormatSpec.hzListLabel(listOf(48000, 44100)))
        assertEquals("", AudioFormatSpec.hzListLabel(emptyList()))
    }

    @Test fun `WAV bytes per second is exact PCM`() {
        val wav = AudioFormatSpec(AudioFormat.WAV, 44100, 128, 2)
        assertEquals(44100L * 2 * 2, wav.bytesPerSecond())
    }

    @Test fun `compressed bytes per second follows the bitrate, not the sample rate`() {
        val m4a = AudioFormatSpec(AudioFormat.M4A, 44100, 128, 1)
        assertEquals(16000L, m4a.bytesPerSecond())
        assertEquals(16000L, m4a.copy(sampleRate = 48000).bytesPerSecond())
    }

    @Test fun `nearest snaps an arbitrary value onto the offered choices`() {
        assertEquals(44100, AudioFormatSpec.nearestSampleRate(44000))
        assertEquals(128, AudioFormatSpec.nearestBitrate(130))
    }

    @Test fun `high rates are candidates, not rejected out of hand`() {
        // 96 kHz is legitimate for WAV on capable hardware; SampleRateSupport, not this list, is
        // what decides per format. A regression here would silently re-cap the app at 48 kHz.
        assertEquals(96000, AudioFormatSpec.nearestSampleRate(96000))
        assertEquals(88200, AudioFormatSpec.nearestSampleRate(88200))
    }

    @Test fun `snap keeps a rate the format supports`() {
        assertEquals(48000, AudioFormatSpec.snap(48000, listOf(8000, 12000, 16000, 24000, 48000)))
    }

    @Test fun `snap moves an unsupported rate to the closest one that works`() {
        // The Opus case: 44.1 kHz isn't in the 48 kHz family, so it lands on 48 kHz.
        val opusRates = listOf(8000, 12000, 16000, 24000, 48000)
        assertEquals(48000, AudioFormatSpec.snap(44100, opusRates))
        assertEquals(48000, AudioFormatSpec.snap(96000, opusRates))
        assertEquals(24000, AudioFormatSpec.snap(22050, opusRates))
    }

    @Test fun `snap leaves the value alone when nothing is known to be supported`() {
        // An empty list means the probes failed, not that the device records nothing — so refusing
        // to move is the safe reading.
        assertEquals(44100, AudioFormatSpec.snap(44100, emptyList()))
    }

    @Test fun `only WAV has no encoder mime`() {
        // Null mime is exactly what tells SampleRateSupport "no codec can veto a rate here".
        assertEquals(null, AudioFormat.WAV.mime)
        assertEquals("audio/mp4a-latm", AudioFormat.M4A.mime)
        assertEquals("audio/opus", AudioFormat.OPUS.mime)
    }
}
