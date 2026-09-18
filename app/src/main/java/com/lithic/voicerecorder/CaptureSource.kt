package com.lithic.voicerecorder

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder

/**
 * Whether to let the platform's voice processing (noise suppression, auto-gain, echo cancellation)
 * touch the captured audio.
 *
 * The choice is really a choice of `MediaRecorder.AudioSource`, which is fixed when `AudioRecord` is
 * constructed — so unlike gain and input device this one cannot change mid-recording.
 *
 * - [PROCESSED] → `MIC`, the device's tuned voice chain. Right for speech into the phone's own mic.
 * - [RAW] → `UNPROCESSED` where the device declares support for it, else `VOICE_RECOGNITION`.
 *   Right for a line-level or USB microphone, where the phone's speech tuning is fighting a signal
 *   that is already clean and mostly just pumps the level around.
 *
 * The `VOICE_RECOGNITION` fallback matters: `UNPROCESSED` is optional, and on a device that doesn't
 * implement it Android silently substitutes... nothing useful. `VOICE_RECOGNITION` is the documented
 * "as little processing as the device can manage" source and is the honest second choice; the
 * Settings screen says so rather than pretending the request was honoured.
 */
enum class CaptureSource(val id: String) {
    PROCESSED("processed"),
    RAW("raw");

    companion object {
        fun fromId(id: String?): CaptureSource = entries.firstOrNull { it.id == id } ?: PROCESSED

        /**
         * The `MediaRecorder.AudioSource` constant to construct `AudioRecord` with. Pure so the
         * fallback rule is unit-testable.
         */
        fun androidSource(source: CaptureSource, unprocessedSupported: Boolean): Int = when {
            source == PROCESSED -> MediaRecorder.AudioSource.MIC
            unprocessedSupported -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        /** True when the device advertises a genuine unprocessed capture path. */
        fun unprocessedSupported(context: Context): Boolean {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        }
    }
}
