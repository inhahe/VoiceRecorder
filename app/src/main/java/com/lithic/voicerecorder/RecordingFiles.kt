package com.lithic.voicerecorder

import java.io.File
import java.util.Locale

/**
 * The recordings in a folder, the rules for renaming one, and the length of a WAV read from its
 * header. Plain java.io, so all of it is tested on the JVM.
 */
object RecordingFiles {

    /** Extensions the recordings list shows: this app's formats, and the common others Android plays. */
    val AUDIO_EXTENSIONS = setOf("wav", "m4a", "opus", "ogg", "mp3", "aac", "flac", "3gp", "amr", "mp4")

    /** Characters no file name may hold (FAT and exFAT cards refuse them, and `/` is the separator). */
    val ILLEGAL_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** The audio files directly in [dir], newest first (then by name); empty when it can't be read. */
    fun list(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name.lowercase(Locale.ROOT) })

    /** What renaming a recording would do. */
    sealed interface Rename {
        /** Renaming goes ahead, to [target]. */
        data class To(val target: File) : Rename

        /** The name is the one it has: nothing to do. */
        data object Unchanged : Rename

        /** It can't be renamed so, for [why]. */
        data class Refused(val why: Problem) : Rename
    }

    enum class Problem { EMPTY, ILLEGAL, EXISTS }

    /**
     * Where renaming [file] to [newBase] would put it, its extension kept, or why it can't go
     * there: no name, a character no file name may hold, or a file already of that name (never
     * overwritten). A change of case alone is a rename, though on a card that ignores case the
     * new name "exists" already: it is this file.
     */
    fun renameTarget(file: File, newBase: String): Rename {
        val base = newBase.trim()
        if (base.isEmpty()) return Rename.Refused(Problem.EMPTY)
        if (base.any { it in ILLEGAL_FILENAME_CHARS } || base == "." || base == "..") return Rename.Refused(Problem.ILLEGAL)
        val name = if (file.extension.isEmpty()) base else "$base.${file.extension}"
        if (name == file.name) return Rename.Unchanged
        val target = File(file.parentFile, name)
        if (!name.equals(file.name, ignoreCase = true) && target.exists()) return Rename.Refused(Problem.EXISTS)
        return Rename.To(target)
    }

    /**
     * The length of a WAV in milliseconds, from its header ([WavLayout]: a take cut off before it
     * finished counts to the end of the file); null if it isn't a PCM WAV this can read.
     */
    fun wavDurationMs(file: File): Long? = WavLayout.read(file)?.durationMs

    /** A length as the list and the player show it: m:ss, or h:mm:ss from an hour. */
    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** The MIME type a share names for [file], so the receiving app knows it is audio of that kind. */
    fun mimeType(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "wav" -> "audio/wav"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus", "ogg" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "3gp" -> "audio/3gpp"
        "amr" -> "audio/amr"
        else -> "audio/*"
    }
}
