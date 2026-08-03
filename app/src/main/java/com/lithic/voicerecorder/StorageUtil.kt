package com.lithic.voicerecorder

import android.os.StatFs
import java.io.File
import java.util.Locale

object StorageUtil {

    /** Free bytes available on the filesystem backing [dir]. Returns 0 if it can't be read. */
    fun freeBytes(dir: File): Long = try {
        val probe = firstExistingAncestor(dir)
        if (probe != null) StatFs(probe.absolutePath).availableBytes else 0L
    } catch (_: Exception) {
        0L
    }

    /**
     * How many seconds of audio fit in the free space of [dir] for the given [spec].
     * Returns [Long.MAX_VALUE]-ish large value clamped for display sanity.
     */
    fun availableSeconds(dir: File, spec: AudioFormatSpec): Long {
        val bps = spec.bytesPerSecond().coerceAtLeast(1L)
        return freeBytes(dir) / bps
    }

    /** Human string like "12.4 hours" or "37 min" describing recordable time in [dir] for [spec]. */
    fun availableTimeText(dir: File, spec: AudioFormatSpec): String {
        val seconds = availableSeconds(dir, spec)
        return formatDuration(seconds)
    }

    fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600.0
        return when {
            totalSeconds <= 0 -> "0 min"
            hours >= 1.0 -> String.format(Locale.US, "%.1f hours", hours)
            else -> String.format(Locale.US, "%d min", totalSeconds / 60)
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    /**
     * First non-colliding file name for `base.ext`, appending `_2`, `_3`, … until [exists] says no.
     * Pure (no filesystem) so it can be unit-tested; [uniqueFile] is the on-disk wrapper.
     */
    fun nextAvailableName(base: String, ext: String, exists: (String) -> Boolean): String {
        var candidate = "$base.$ext"
        var i = 2
        while (exists(candidate)) {
            candidate = "${base}_$i.$ext"
            i++
        }
        return candidate
    }

    /** The file `base.ext` in [dir], suffixed with `_2`, `_3`, … if that name is already taken. */
    fun uniqueFile(dir: File, base: String, ext: String): File =
        File(dir, nextAvailableName(base, ext) { File(dir, it).exists() })

    private fun firstExistingAncestor(dir: File): File? {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return d
    }
}
