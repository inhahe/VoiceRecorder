package com.lithic.voicerecorder

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONException
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

    /**
     * Digital input gain in dB applied while capturing (see [Gain]). Persisted so the level you
     * settled on is still there next time; the home-screen slider writes it live.
     */
    var gainDb: Int
        get() = prefs.getInt(KEY_GAIN_DB, 0).coerceIn(Gain.MIN_DB, Gain.MAX_DB)
        set(value) = prefs.edit().putInt(KEY_GAIN_DB, value.coerceIn(Gain.MIN_DB, Gain.MAX_DB)).apply()

    /**
     * Which microphone to record from, as an [InputDevices.InputOption.key] — a *type + product
     * name* pair rather than an `AudioDeviceInfo` id, because ids are reassigned on every unplug.
     * [InputDevices.AUTOMATIC] (the default) lets Android choose.
     */
    var inputDeviceKey: String
        get() = prefs.getString(KEY_INPUT_DEVICE, InputDevices.AUTOMATIC) ?: InputDevices.AUTOMATIC
        set(value) = prefs.edit().putString(KEY_INPUT_DEVICE, value).apply()

    /**
     * Whether the platform's voice processing is applied to captured audio (see [CaptureSource]).
     * Fixed for the duration of a recording, so it only takes effect from the next one.
     */
    var captureSource: CaptureSource
        get() = CaptureSource.fromId(prefs.getString(KEY_CAPTURE_SOURCE, null))
        set(value) = prefs.edit().putString(KEY_CAPTURE_SOURCE, value.id).apply()

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

    /**
     * When true, a new recording starts with the file name used for the previous recording instead
     * of a fresh timestamp — handy for doing several takes of the same thing (the `_2`, `_3`, …
     * suffix keeps each take a separate file).
     */
    var reuseLastFilename: Boolean
        get() = prefs.getBoolean(KEY_REUSE_LAST_NAME, true)
        set(value) = prefs.edit().putBoolean(KEY_REUSE_LAST_NAME, value).apply()

    /** Most-recently-used base names (no extension), newest first. Offered in the pre-record dialog. */
    var recentBaseNames: List<String>
        get() {
            val raw = prefs.getString(KEY_RECENT_NAMES, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotEmpty() } }
            } catch (_: JSONException) {
                emptyList()
            }
        }
        set(value) {
            val arr = JSONArray()
            value.take(MAX_RECENT_NAMES).forEach { arr.put(it) }
            prefs.edit().putString(KEY_RECENT_NAMES, arr.toString()).apply()
        }

    /** Record [name] as the most recently used base name. */
    fun rememberBaseName(name: String) {
        recentBaseNames = mergeRecent(recentBaseNames, name)
    }

    fun clearRecentBaseNames() = prefs.edit().remove(KEY_RECENT_NAMES).apply()

    /**
     * Resolve the current default spec from the stored defaults.
     *
     * Both the format and the rate are *validated against this device*, not taken on trust: a stored
     * format the device can't record falls back to M4A, and the rate is snapped to one the resulting
     * format can actually encode here. Settings written by an older build — or on another device,
     * via a backup restore — therefore can't produce a spec that fails at `start()`.
     */
    fun defaultSpec(): AudioFormatSpec {
        val format = defaultFormat.let { if (it.isSupportedOnThisDevice) it else AudioFormat.M4A }
        val ch = channels
        return AudioFormatSpec(
            format = format,
            sampleRate = AudioFormatSpec.snap(defaultSampleRate, SampleRateSupport.supported(format, ch)),
            bitrateKbps = defaultBitrateKbps,
            channels = ch
        )
    }

    /** Build a filename (without extension) from the timestamp pattern. */
    fun suggestedBaseName(now: Date = Date()): String =
        SimpleDateFormat(filenamePattern, Locale.US).format(now)

    /**
     * The name the pre-record dialog should start with: the previous recording's name when
     * [reuseLastFilename] is on and there is one, otherwise a fresh timestamped name.
     */
    fun startingBaseName(now: Date = Date()): String =
        (if (reuseLastFilename) recentBaseNames.firstOrNull() else null) ?: suggestedBaseName(now)

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
        private const val KEY_GAIN_DB = "gain_db"
        private const val KEY_INPUT_DEVICE = "input_device_key"
        private const val KEY_CAPTURE_SOURCE = "capture_source"
        private const val KEY_REUSE_LAST_NAME = "reuse_last_filename"
        private const val KEY_RECENT_NAMES = "recent_base_names"

        const val DEFAULT_FILENAME_PATTERN = "'Recording_'yyyyMMdd_HHmmss"

        /** How many previously-used file names are remembered for the dropdown. */
        const val MAX_RECENT_NAMES = 12

        /**
         * Put [name] at the front of [existing], removing any earlier (case-insensitive) copy and
         * capping the list at [max]. Blank names are ignored. Pure, so it's unit-testable.
         */
        fun mergeRecent(existing: List<String>, name: String, max: Int = MAX_RECENT_NAMES): List<String> {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return existing
            val rest = existing.filterNot { it.equals(trimmed, ignoreCase = true) || it.isBlank() }
            return (listOf(trimmed) + rest).take(max)
        }

        /** A reasonable default target: <shared storage>/VoiceRecordings. */
        fun defaultSaveDirectory(): File =
            File(Environment.getExternalStorageDirectory(), "VoiceRecordings")
    }
}
