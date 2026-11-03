package com.lithic.voicerecorder

import java.io.File
import java.io.IOException
import java.nio.file.Files
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

    /** A recording as it was when listed: its size and time are read once, then sorted on. */
    data class Listed(val file: File, val size: Long, val modified: Long)

    /**
     * The audio files directly in [dir], newest first (then by name); empty when it can't be read.
     * Hidden files are left out: MediaStore's trash and unfinished writes are `.trashed-…` and
     * `.pending-…` files beside the recordings. Each file is looked at once, so a file changing
     * while the list is sorted can't make the order contradict itself.
     */
    fun list(dir: File): List<Listed> =
        (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") && it.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS && it.isFile }
            .map { Listed(it, it.length(), it.lastModified()) }
            .sortedWith(compareByDescending<Listed> { it.modified }.thenBy { it.file.name.lowercase(Locale.ROOT) })

    /** What renaming a recording would do. */
    sealed interface Rename {
        /** Renaming goes ahead, to [target]. */
        data class To(val target: File) : Rename

        /** The name is the one it has: nothing to do. */
        data object Unchanged : Rename

        /** It can't be renamed so, for [why]. */
        data class Refused(val why: Problem) : Rename
    }

    enum class Problem { EMPTY, ILLEGAL, HIDDEN, EXISTS }

    /**
     * Where renaming [file] to [newBase] would put it, its extension kept, or why it can't go
     * there: no name, a character no file name may hold, a leading dot (the file would be hidden,
     * and leave the list), or another file already of that name (never overwritten). A change of
     * case alone is a rename: on a card that ignores case the new name "exists" already, but as
     * this same file; in a folder that minds case it may be another file, which is refused.
     */
    fun renameTarget(file: File, newBase: String): Rename {
        val base = newBase.trim()
        if (base.isEmpty()) return Rename.Refused(Problem.EMPTY)
        if (base.any { it in ILLEGAL_FILENAME_CHARS } || base == "." || base == "..") return Rename.Refused(Problem.ILLEGAL)
        if (base.startsWith(".")) return Rename.Refused(Problem.HIDDEN)
        val name = if (file.extension.isEmpty()) base else "$base.${file.extension}"
        if (name == file.name) return Rename.Unchanged
        val target = File(file.parentFile, name)
        if (target.exists() && !sameFile(file, target)) return Rename.Refused(Problem.EXISTS)
        return Rename.To(target)
    }

    private fun sameFile(a: File, b: File): Boolean = try {
        Files.isSameFile(a.toPath(), b.toPath())
    } catch (e: IOException) {
        false
    }

    /**
     * Renames [file] to [target] (from [renameTarget]); true once the folder lists it under the
     * new name. A change of case alone goes through a temporary name: on Android's shared storage,
     * which ignores case, renaming straight to the same name in another case succeeds and changes
     * nothing.
     */
    fun rename(file: File, target: File): Boolean {
        val dir = file.parentFile ?: return false
        if (target.name.equals(file.name, ignoreCase = true)) {
            val step = File(dir, ".renaming-" + System.nanoTime() + "-" + target.name)
            if (!file.renameTo(step)) return false
            if (!step.renameTo(target)) {
                step.renameTo(file) // back as it was
                return false
            }
        } else if (!file.renameTo(target)) {
            return false
        }
        return dir.list()?.contains(target.name) == true
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
