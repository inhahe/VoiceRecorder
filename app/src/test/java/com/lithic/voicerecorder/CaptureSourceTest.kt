package com.lithic.voicerecorder

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM checks for the pure half of [CaptureSource]: the mapping to a `MediaRecorder.AudioSource`,
 * including the fallback that matters most — asking for raw capture on a device that has no
 * unprocessed path must land on the lowest-processing source available, not on plain `MIC`.
 */
class CaptureSourceTest {

    @Test fun `processed always means the tuned voice chain`() {
        assertEquals(
            MediaRecorder.AudioSource.MIC,
            CaptureSource.androidSource(CaptureSource.PROCESSED, unprocessedSupported = true)
        )
        assertEquals(
            MediaRecorder.AudioSource.MIC,
            CaptureSource.androidSource(CaptureSource.PROCESSED, unprocessedSupported = false)
        )
    }

    @Test fun `raw uses UNPROCESSED where the device supports it`() {
        assertEquals(
            MediaRecorder.AudioSource.UNPROCESSED,
            CaptureSource.androidSource(CaptureSource.RAW, unprocessedSupported = true)
        )
    }

    @Test fun `raw falls back to the lowest-processing source, not to MIC`() {
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            CaptureSource.androidSource(CaptureSource.RAW, unprocessedSupported = false)
        )
    }

    @Test fun `ids round-trip, and anything unknown means the safe default`() {
        CaptureSource.entries.forEach { assertEquals(it, CaptureSource.fromId(it.id)) }
        assertEquals(CaptureSource.PROCESSED, CaptureSource.fromId(null))
        assertEquals(CaptureSource.PROCESSED, CaptureSource.fromId("nonsense"))
    }
}
