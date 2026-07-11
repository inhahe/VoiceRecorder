package com.lithic.voicerecorder

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent user preferences backed by SharedPreferences.
 *
 * Holds the configurable *defaults* that seed each new recording (format, sample rate, bitrate,
 * channels), the save directory, the filename pattern, and the first-run flag.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("voice_recorder", Context.MODE_PRIVATE)

    var firstRunComplete: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_RUN_DONE, value).apply()

    var defaultFormat: AudioFormat
        get() = AudioFormat.fromId(prefs.getString(KEY_FORMAT, AudioFormat.M4A.id))
        set(value) = prefs.edit().putString(KEY_FORMAT, value.id).apply()

    var defaultSampleRate: Int
        get() = prefs.getInt(KEY_SAMPLE_RATE, 44100)
        set(value) = prefs.edit().putInt(KEY_SAMPLE_RATE, value).apply()

    var defaultBitrateKbps: Int
        get() = prefs.getInt(KEY_BITRATE, 128)
        set(value) = prefs.edit().putInt(KEY_BITRATE, value).apply()

    /** 1 = mono, 2 = stereo. */
    var channels: Int
        get() = prefs.getInt(KEY_CHANNELS, 1).coerceIn(1, 2)
        set(value) = prefs.edit().putInt(KEY_CHANNELS, value.coerceIn(1, 2)).apply()

    /** Absolute path of the directory new recordings are saved into (may be null before first-run setup). */
    var saveDirectory: String?
        get() = prefs.getString(KEY_SAVE_DIR, null)
        set(value) = prefs.edit().putString(KEY_SAVE_DIR, value).apply()

    /**
     * strftime-ish filename pattern (without extension). `%` tokens are resolved via SimpleDateFormat.
     * Default e.g. "Recording_20260711_143002".
     */
    var filenamePattern: String
        get() = prefs.getString(KEY_FILENAME_PATTERN, DEFAULT_FILENAME_PATTERN) ?: DEFAULT_FILENAME_PATTERN
        set(value) = prefs.edit().putString(KEY_FILENAME_PATTERN, value).apply()

    /** Resolve the current default spec from the stored defaults. */
    fun defaultSpec(): AudioFormatSpec = AudioFormatSpec(
        format = defaultFormat.let { if (it.isSupportedOnThisDevice) it else AudioFormat.M4A },
        sampleRate = defaultSampleRate,
        bitrateKbps = defaultBitrateKbps,
        channels = channels
    )

    /** Build a suggested filename (without extension) for a new recording. */
    fun suggestedBaseName(now: Date = Date()): String =
        SimpleDateFormat(filenamePattern, Locale.US).format(now)

    /** The effective save directory as a File, falling back to the default location. */
    fun saveDirectoryFile(): File {
        val path = saveDirectory
        return if (path != null) File(path) else defaultSaveDirectory()
    }

    companion object {
        private const val KEY_FIRST_RUN_DONE = "first_run_done"
        private const val KEY_FORMAT = "default_format"
        private const val KEY_SAMPLE_RATE = "default_sample_rate"
        private const val KEY_BITRATE = "default_bitrate"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_SAVE_DIR = "save_dir"
        private const val KEY_FILENAME_PATTERN = "filename_pattern"

        const val DEFAULT_FILENAME_PATTERN = "'Recording_'yyyyMMdd_HHmmss"

        /** A reasonable default target: <shared storage>/VoiceRecordings. */
        fun defaultSaveDirectory(): File =
            File(Environment.getExternalStorageDirectory(), "VoiceRecordings")
    }
}
