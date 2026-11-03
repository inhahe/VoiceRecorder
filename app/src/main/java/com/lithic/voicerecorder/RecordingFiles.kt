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

    /** What a recording is called between the two steps of a change of case. Not audio, so not listed; not hidden, so not lost. */
    private const val STEP_SUFFIX = ".renaming"

    /**
     * Renames [file] to [target] (from [renameTarget]); true once the folder lists it under the
     * new name. A change of case alone goes through a temporary name: on Android's shared storage,
     * which ignores case, renaming straight to the same name in another case succeeds and changes
     * nothing. Should the app die between the two steps, [finishRenames] completes it.
     */
    fun rename(file: File, target: File): Boolean {
        val dir = file.parentFile ?: return false
        if (target.name.equals(file.name, ignoreCase = true)) {
            val step = File(dir, target.name + STEP_SUFFIX)
            if (step.exists() || !file.renameTo(step)) return false
            if (!step.renameTo(target)) {
                step.renameTo(file) // back as it was
                return false
            }
        } else if (!file.renameTo(target)) {
            return false
        }
        return dir.list()?.contains(target.name) == true
    }

    /** Completes a change of case cut short between its two steps (see [rename]): run before listing [dir]. */
    fun finishRenames(dir: File) {
        for (step in dir.listFiles() ?: return) {
            if (!step.name.endsWith(STEP_SUFFIX)) continue
            val target = File(dir, step.name.removeSuffix(STEP_SUFFIX))
            if (!target.exists()) step.renameTo(target)
        }
    }

    /**
     * Where deleted recordings wait, inside the folder they were in: hidden, so neither this list
     * nor media apps show them, and named for this app, since a `.trash` there may be another's.
     */
    const val TRASH_FOLDER = ".voicerecorder-trash"

    /** A deleted recording's name in the trash: the moment it was deleted (ms, 13 digits), then its name. */
    private val TRASHED_NAME = Regex("""(\d{13})-(.+)""")

    /** How long a deleted recording is kept before it goes for good. */
    const val TRASH_KEEP_MS = 30L * 24 * 60 * 60 * 1000

    /** A deleted recording: [name] is what it was called, [deletedAt] when (ms since 1970). */
    data class Trashed(val file: File, val name: String, val deletedAt: Long, val size: Long)

    /**
     * Moves [file] into its folder's trash, as `<now>-<name>`, so it can be restored; what it is
     * there, or null if it couldn't be moved. A move within the folder's volume: nothing is copied.
     * [now] is the time in ms (13 digits), as the trash's names hold it.
     */
    fun trash(file: File, now: Long): Trashed? {
        val dir = File(file.parentFile ?: return null, TRASH_FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val size = file.length()
        var at = now
        while (File(dir, "$at-${file.name}").exists()) at++
        val target = File(dir, "$at-${file.name}")
        return if (file.renameTo(target)) Trashed(target, file.name, at, size) else null
    }

    /**
     * What [folder]'s trash holds, the most recently deleted first: only recordings this app put
     * there, named as [trash] names them, since only those are its own to list, restore and delete.
     */
    fun trashed(folder: File): List<Trashed> =
        (File(folder, TRASH_FOLDER).listFiles() ?: emptyArray())
            .mapNotNull { f ->
                val match = TRASHED_NAME.matchEntire(f.name) ?: return@mapNotNull null
                val name = match.groupValues[2]
                if (File(name).extension.lowercase(Locale.ROOT) !in AUDIO_EXTENSIONS || !f.isFile) return@mapNotNull null
                Trashed(f, name, match.groupValues[1].toLong(), f.length())
            }
            .sortedByDescending { it.deletedAt }

    /**
     * Puts [item] back in [folder] under its old name, or with `_2`, `_3`, … if that name has been
     * taken since: never over another file. The restored file, or null if it couldn't be moved.
     */
    fun restore(item: Trashed, folder: File): File? {
        val target = StorageUtil.uniqueFile(folder, item.name.substringBeforeLast('.'), item.name.substringAfterLast('.'))
        return if (item.file.renameTo(target)) target else null
    }

    /** Deletes for good what [folder]'s trash has held for longer than [TRASH_KEEP_MS]. */
    fun purgeTrash(folder: File, now: Long) {
        for (item in trashed(folder)) if (now - item.deletedAt > TRASH_KEEP_MS) item.file.delete()
    }

    /**
     * The length of a WAV in milliseconds, from its header ([WavLayout]: a take cut off before it
     * finished counts to the end of the file); null if it isn't a PCM WAV this can read.
     */
    fun wavDurationMs(file: File): Long? = WavLayout.read(file)?.durationMs

    /**
     * A length as it is shown: m:ss, or h:mm:ss from an hour. [padMinutes] gives mm:ss below an
     * hour, as the recording timer shows it, where the digits shouldn't jump at ten minutes.
     */
    fun clock(ms: Long, padMinutes: Boolean = false): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            padMinutes -> String.format(Locale.US, "%02d:%02d", m, s)
            else -> String.format(Locale.US, "%d:%02d", m, s)
        }
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
