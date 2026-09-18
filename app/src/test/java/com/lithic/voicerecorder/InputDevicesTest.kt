package com.lithic.voicerecorder

import android.media.AudioDeviceInfo
import com.lithic.voicerecorder.InputDevices.InputOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM checks for the pure half of [InputDevices]: labelling, the stable key that survives a
 * reconnect, and the matching rules the persisted selection is resolved through.
 *
 * `AudioDeviceInfo.TYPE_*` are compile-time `static final int` constants, so they inline and are
 * safe to use without the Android framework.
 */
class InputDevicesTest {

    private fun usb(id: Int, name: String = "USB Audio CODEC") =
        InputOption(id, AudioDeviceInfo.TYPE_USB_DEVICE, name)

    private fun builtin(id: Int = 1, name: String = "SM-G991U") =
        InputOption(id, AudioDeviceInfo.TYPE_BUILTIN_MIC, name)

    @Test fun `the built-in mic is named for what it is, not the phone model`() {
        assertEquals("Phone microphone", builtin().label)
    }

    @Test fun `a USB device is labelled with its product name`() {
        assertEquals("USB Audio CODEC (USB)", usb(7).label)
    }

    @Test fun `a nameless USB device still gets a usable label`() {
        assertEquals("USB microphone", usb(7, "").label)
    }

    @Test fun `bluetooth and wired headsets have their own labels`() {
        assertEquals(
            "WH-1000XM4 (Bluetooth)",
            InputOption(3, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "WH-1000XM4").label
        )
        assertEquals(
            "Headset microphone",
            InputOption(4, AudioDeviceInfo.TYPE_WIRED_HEADSET, "Headset").label
        )
    }

    @Test fun `the key ignores the device id, which changes on every reconnect`() {
        assertEquals(usb(7).key, usb(41).key)
    }

    @Test fun `a key round-trips back to the same label without the device attached`() {
        assertEquals(usb(7).label, InputDevices.labelFromKey(usb(7).key))
        assertEquals(builtin().label, InputDevices.labelFromKey(builtin().key))
    }

    @Test fun `an unparseable key is echoed rather than crashing`() {
        assertEquals("garbage", InputDevices.labelFromKey("garbage"))
    }

    @Test fun `match finds the exact device`() {
        val options = listOf(builtin(), usb(7))
        assertEquals(usb(7), InputDevices.match(options, usb(7).key))
    }

    @Test fun `match survives a reconnect under a different id`() {
        val reconnected = usb(41)
        assertEquals(reconnected, InputDevices.match(listOf(builtin(), reconnected), usb(7).key))
    }

    @Test fun `match falls back to another device of the same type`() {
        // "Record from USB" should still mean USB if the interface reports a different name.
        val other = usb(9, "Scarlett Solo USB")
        assertEquals(other, InputDevices.match(listOf(builtin(), other), usb(7).key))
    }

    @Test fun `match returns null for automatic and for an absent type`() {
        assertNull(InputDevices.match(listOf(builtin()), InputDevices.AUTOMATIC))
        assertNull(InputDevices.match(listOf(builtin()), usb(7).key))
    }

    @Test fun `summary describes automatic, a present device and an absent one`() {
        val options = listOf(builtin(), usb(7))
        assertEquals(
            InputDevices.AUTOMATIC_LABEL,
            InputDevices.summary(options, InputDevices.AUTOMATIC)
        )
        assertEquals("USB Audio CODEC (USB)", InputDevices.summary(options, usb(7).key))

        val absent = InputDevices.summary(listOf(builtin()), usb(7).key)
        assertTrue(absent, absent.startsWith("USB Audio CODEC (USB)"))
        assertTrue(absent, absent.endsWith(InputDevices.DISCONNECTED_SUFFIX))
    }

    @Test fun `duplicate capsules of the built-in mic collapse to one entry`() {
        // Phones commonly report bottom/top/back capsules as separate TYPE_BUILTIN_MIC devices.
        val deduped = InputDevices.dedupe(listOf(builtin(1), builtin(2), builtin(3), usb(7)))
        assertEquals(listOf(builtin(1), usb(7)), deduped)
    }

    @Test fun `dedupe keeps distinct devices of the same type`() {
        val a = usb(7, "USB Audio CODEC")
        val b = usb(8, "Scarlett Solo USB")
        assertEquals(listOf(a, b), InputDevices.dedupe(listOf(a, b)))
    }

    @Test fun `dedupe merges the capabilities of collapsed capsules`() {
        // Losing a capsule mustn't shrink what the surviving row claims the mic can do.
        val a = builtin(1).copy(sampleRates = listOf(16000, 48000), channelCounts = listOf(1))
        val b = builtin(2).copy(sampleRates = listOf(44100, 48000), channelCounts = listOf(1, 2))
        val merged = InputDevices.dedupe(listOf(a, b)).single()
        assertEquals(listOf(16000, 44100, 48000), merged.sampleRates)
        assertEquals(listOf(1, 2), merged.channelCounts)
    }

    @Test fun `an unspecified capability list stays unspecified after merging`() {
        // Empty means "didn't say", so merging must not narrow it to the other device's list.
        val known = builtin(1).copy(sampleRates = listOf(48000))
        val unknown = builtin(2).copy(sampleRates = emptyList())
        assertTrue(InputDevices.dedupe(listOf(known, unknown)).single().sampleRates.isEmpty())
        assertTrue(InputDevices.dedupe(listOf(unknown, known)).single().sampleRates.isEmpty())
    }

    // --- effective device (advisory) ------------------------------------------

    @Test fun `an explicit choice is the effective device`() {
        val options = listOf(builtin(), usb(7))
        assertEquals(usb(7), InputDevices.effective(options, usb(7).key))
        assertEquals(builtin(), InputDevices.effective(options, builtin().key))
    }

    @Test fun `under automatic an attached external mic outranks the built-in one`() {
        // Android routes MIC capture to USB when it's present, so that's what to warn about.
        assertEquals(
            usb(7),
            InputDevices.effective(listOf(builtin(), usb(7)), InputDevices.AUTOMATIC)
        )
    }

    @Test fun `under automatic with nothing attached it's the phone mic`() {
        assertEquals(builtin(), InputDevices.effective(listOf(builtin()), InputDevices.AUTOMATIC))
        assertNull(InputDevices.effective(emptyList(), InputDevices.AUTOMATIC))
    }

    // --- sample-rate advice ---------------------------------------------------

    private val offered = listOf(8000, 16000, 22050, 44100, 48000)

    @Test fun `no advice when the device supports the requested rate`() {
        val device = usb(7).copy(sampleRates = listOf(44100, 48000))
        assertNull(InputDevices.rateAdvice(device, 44100, offered))
    }

    @Test fun `no advice when no device is chosen or capabilities are unspecified`() {
        assertNull(InputDevices.rateAdvice(null, 44100, offered))
        assertNull(InputDevices.rateAdvice(usb(7), 44100, offered))
    }

    @Test fun `a 48k-only USB interface suggests 48k for a 44_1k request`() {
        val device = usb(7).copy(sampleRates = listOf(48000))
        val advice = InputDevices.rateAdvice(device, 44100, offered)!!
        assertEquals(listOf(48000), advice.supported)
        assertEquals(48000, advice.suggested)
    }

    @Test fun `the suggestion is the nearest supported rate we can actually offer`() {
        val device = usb(7).copy(sampleRates = listOf(32000, 48000, 96000))
        // 96 kHz is supported but we don't offer it; 48 kHz is nearer to 44.1 than 32 kHz.
        assertEquals(48000, InputDevices.rateAdvice(device, 44100, offered)!!.suggested)
        // From 22.05 kHz the nearest offerable native rate is 32 kHz... which we don't offer either.
        assertEquals(48000, InputDevices.rateAdvice(device, 8000, offered)!!.suggested)
    }

    @Test fun `a mismatch with nothing we can offer still warns, without a suggestion`() {
        val device = usb(7).copy(sampleRates = listOf(96000, 192000))
        val advice = InputDevices.rateAdvice(device, 44100, offered)!!
        assertEquals(listOf(96000, 192000), advice.supported)
        assertNull(advice.suggested)
    }

    // --- downsampling note (the quiet, non-actionable line) -------------------

    @Test fun `recording below the device ceiling reports what it comes down from`() {
        // The phone mic declares everything up to 48 kHz; 8 kHz is supported but resampled.
        val device = builtin().copy(sampleRates = listOf(8000, 16000, 44100, 48000))
        assertEquals(48000, InputDevices.downsampleFrom(device, 8000))
        assertEquals(48000, InputDevices.downsampleFrom(device, 44100))
    }

    @Test fun `no note at the device's own ceiling`() {
        val device = builtin().copy(sampleRates = listOf(8000, 16000, 44100, 48000))
        assertNull(InputDevices.downsampleFrom(device, 48000))
    }

    @Test fun `no note without a device or without declared capabilities`() {
        assertNull(InputDevices.downsampleFrom(null, 44100))
        assertNull(InputDevices.downsampleFrom(usb(7), 44100))
    }

    @Test fun `the note and the warning are never both shown`() {
        // Every case the red warning covers is a rate the device does *not* declare, and the note
        // requires one it does — so the two are mutually exclusive without the caller sequencing
        // them. Checked across a device's whole range, upsampling included.
        val device = usb(7).copy(sampleRates = listOf(16000, 48000))
        for (rate in AudioFormatSpec.SAMPLE_RATES) {
            val warns = InputDevices.rateAdvice(device, rate, offered) != null
            val notes = InputDevices.downsampleFrom(device, rate) != null
            assertTrue("both fired at $rate", !(warns && notes))
        }
        // ...and the interesting cases really do land on one each, rather than neither.
        assertNull(InputDevices.rateAdvice(device, 16000, offered))
        assertEquals(48000, InputDevices.downsampleFrom(device, 16000))
        assertTrue(InputDevices.rateAdvice(device, 44100, offered) != null)
        assertNull(InputDevices.downsampleFrom(device, 44100))
    }
}
