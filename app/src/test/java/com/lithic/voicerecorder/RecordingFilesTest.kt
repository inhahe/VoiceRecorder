package com.lithic.voicerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/** The recordings screen's rules about files: what is listed, how renames go, how long a WAV is. */
class RecordingFilesTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, modified: Long = 1_000_000L, bytes: Int = 3): File =
        tmp.newFile(name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }

    @Test
    fun audio_files_are_listed_newest_first() {
        file("old.wav", modified = 1_000_000)
        file("new.m4a", modified = 3_000_000)
        file("mid.opus", modified = 2_000_000)
        file("notes.txt", modified = 4_000_000)
        tmp.newFolder("folder.wav")
        assertEquals(listOf("new.m4a", "mid.opus", "old.wav"), RecordingFiles.list(tmp.root).map { it.file.name })
        assertEquals(3_000_000L, RecordingFiles.list(tmp.root).first().modified)
    }

    /** MediaStore's trash and unfinished writes sit beside the recordings as hidden files. */
    @Test
    fun hidden_files_are_not_listed() {
        file(".trashed-1733000000-take.wav")
        file(".pending-1733000000-other.m4a")
        file("take2.wav")
        assertEquals(listOf("take2.wav"), RecordingFiles.list(tmp.root).map { it.file.name })
    }

    @Test
    fun extensions_are_matched_ignoring_case() {
        file("LOUD.WAV")
        assertEquals(listOf("LOUD.WAV"), RecordingFiles.list(tmp.root).map { it.file.name })
    }

    @Test
    fun a_folder_that_cant_be_read_lists_nothing() {
        assertEquals(emptyList<File>(), RecordingFiles.list(File(tmp.root, "missing")))
    }

    @Test
    fun a_rename_keeps_the_extension() {
        val take = file("Take.wav")
        assertEquals(RecordingFiles.Rename.To(File(tmp.root, "Interview.wav")), RecordingFiles.renameTarget(take, "  Interview "))
    }

    @Test
    fun a_rename_never_overwrites() {
        val take = file("Take.wav")
        file("Interview.wav")
        assertEquals(RecordingFiles.Rename.Refused(RecordingFiles.Problem.EXISTS), RecordingFiles.renameTarget(take, "Interview"))
    }

    @Test
    fun a_change_of_case_alone_is_a_rename() {
        val take = file("take.wav")
        val check = RecordingFiles.renameTarget(take, "Take")
        assertEquals(RecordingFiles.Rename.To(File(tmp.root, "Take.wav")), check)
        assertTrue(RecordingFiles.rename(take, (check as RecordingFiles.Rename.To).target))
        assertEquals(listOf("Take.wav"), tmp.root.list()!!.toList()) // listed under the new case, nothing left behind
    }

    /** The app killed between a change of case's two steps: the next listing finishes it. */
    @Test
    fun a_change_of_case_cut_short_is_finished_before_listing() {
        file("Take.wav.renaming")
        RecordingFiles.finishRenames(tmp.root)
        assertEquals(listOf("Take.wav"), tmp.root.list()!!.toList())
    }

    /** A moment in 2026, as the trash's names hold it (13 digits of ms). */
    private val now = 1_790_000_000_000L

    @Test
    fun a_deleted_recording_waits_in_the_trash_and_comes_back() {
        val take = file("Take.wav")
        val trashed = RecordingFiles.trash(take, now)!!
        assertEquals(emptyList<String>(), RecordingFiles.list(tmp.root).map { it.file.name }) // no longer listed
        val item = RecordingFiles.trashed(tmp.root).single()
        assertEquals("Take.wav", item.name)
        assertEquals(now, item.deletedAt)
        assertEquals(trashed, item) // what trash() says it put there is what the trash lists

        assertEquals(File(tmp.root, "Take.wav"), RecordingFiles.restore(item))
        assertEquals(listOf("Take.wav"), RecordingFiles.list(tmp.root).map { it.file.name })
        assertEquals(emptyList<RecordingFiles.Trashed>(), RecordingFiles.trashed(tmp.root))
    }

    /** A new recording took the name meanwhile: the old one comes back beside it, never over it. */
    @Test
    fun a_restored_recording_never_overwrites() {
        RecordingFiles.trash(file("Take.wav"), now)
        file("Take.wav")
        val back = RecordingFiles.restore(RecordingFiles.trashed(tmp.root).single())
        assertEquals(File(tmp.root, "Take_2.wav"), back)
    }

    @Test
    fun the_same_name_deleted_twice_keeps_both() {
        RecordingFiles.trash(file("Take.wav"), now)
        RecordingFiles.trash(file("Take.wav"), now)
        assertEquals(2, RecordingFiles.trashed(tmp.root).size)
    }

    /**
     * The trash counts as its own only recordings named as it names them; anything else put there
     * (a note, a recording without a time before its name) is neither listed nor ever deleted.
     */
    @Test
    fun the_trash_leaves_alone_what_it_did_not_put_there() {
        val trash = File(tmp.root, RecordingFiles.TRASH_FOLDER).apply { mkdirs() }
        val foreign = listOf("20240115-notes.txt", "1700000000000-notes.txt", "take.wav", "x-take.wav")
            .map { File(trash, it).apply { writeBytes(byteArrayOf(1)) } }
        RecordingFiles.trash(file("Take.wav"), now)
        assertEquals(listOf("Take.wav"), RecordingFiles.trashed(tmp.root).map { it.name })
        RecordingFiles.purgeTrash(tmp.root, now = now + 365L * 24 * 60 * 60 * 1000)
        assertEquals(emptyList<RecordingFiles.Trashed>(), RecordingFiles.trashed(tmp.root))
        for (f in foreign) assertEquals(f.name, true, f.exists())
    }

    /** Deleted while the phone's clock was wrong (before 2001: fewer digits), it is still listed and still goes. */
    @Test
    fun a_recording_deleted_with_the_clock_wrong_is_still_its_own() {
        val trashed = RecordingFiles.trash(file("Take.wav"), now = 946_684_800_000L)!! // 1 January 2000
        assertEquals(listOf(trashed), RecordingFiles.trashed(tmp.root))
        RecordingFiles.purgeTrash(tmp.root, now)
        assertEquals(emptyList<RecordingFiles.Trashed>(), RecordingFiles.trashed(tmp.root))
    }

    /** Restored, it goes back to the folder it was deleted from, and the trashes of several folders list together. */
    @Test
    fun a_recording_comes_back_to_its_own_folder() {
        val other = tmp.newFolder("other")
        RecordingFiles.trash(file("Here.wav"), now)
        RecordingFiles.trash(File(other, "There.wav").apply { writeBytes(byteArrayOf(1)) }, now + 1)
        val items = RecordingFiles.trashedIn(listOf(tmp.root, other, tmp.root))
        assertEquals(listOf("There.wav", "Here.wav"), items.map { it.name })
        assertEquals(File(other, "There.wav"), RecordingFiles.restore(items.first()))
    }

    @Test
    fun the_trash_lets_go_after_thirty_days() {
        val day = 24L * 60 * 60 * 1000
        RecordingFiles.trash(file("Old.wav"), now)
        RecordingFiles.trash(file("New.wav"), now + 25 * day)
        RecordingFiles.purgeTrash(tmp.root, now = now + 31 * day)
        assertEquals(listOf("New.wav"), RecordingFiles.trashed(tmp.root).map { it.name })
    }

    @Test
    fun the_recording_timer_pads_its_minutes() {
        assertEquals("00:05", RecordingFiles.clock(5_000, padMinutes = true))
        assertEquals("1:00:05", RecordingFiles.clock(3_605_000, padMinutes = true))
    }

    @Test
    fun a_rename_is_listed_under_its_new_name() {
        val take = file("Take.wav")
        assertTrue(RecordingFiles.rename(take, File(tmp.root, "Interview.wav")))
        assertEquals(listOf("Interview.wav"), tmp.root.list()!!.toList())
    }

    @Test
    fun a_name_with_a_leading_dot_is_refused() {
        val take = file("Take.wav")
        assertEquals(RecordingFiles.Rename.Refused(RecordingFiles.Problem.HIDDEN), RecordingFiles.renameTarget(take, ".secret"))
    }

    @Test
    fun the_same_name_is_no_change() {
        val take = file("Take.wav")
        assertEquals(RecordingFiles.Rename.Unchanged, RecordingFiles.renameTarget(take, "Take"))
    }

    @Test
    fun a_name_must_be_there_and_legal() {
        val take = file("Take.wav")
        assertEquals(RecordingFiles.Rename.Refused(RecordingFiles.Problem.EMPTY), RecordingFiles.renameTarget(take, "   "))
        assertEquals(RecordingFiles.Rename.Refused(RecordingFiles.Problem.ILLEGAL), RecordingFiles.renameTarget(take, "a/b"))
        assertEquals(RecordingFiles.Rename.Refused(RecordingFiles.Problem.ILLEGAL), RecordingFiles.renameTarget(take, "what?"))
        assertEquals(RecordingFiles.Rename.Refused(RecordingFiles.Problem.ILLEGAL), RecordingFiles.renameTarget(take, ".."))
    }

    /** One second of mono 16-bit silence at [rate], through the app's own WAV writer. */
    private fun wav(name: String, rate: Int, seconds: Double, finish: Boolean): File {
        val out = File(tmp.root, name)
        val encoder = WavPcmEncoder(AudioFormatSpec(AudioFormat.WAV, rate, 0, 1), out)
        encoder.start()
        val bytes = (rate * seconds).toInt() * 2
        encoder.encode(ByteArray(bytes), bytes)
        if (finish) encoder.finish()
        return out
    }

    @Test
    fun a_finished_wav_is_measured_from_its_header() {
        assertEquals(2500L, RecordingFiles.wavDurationMs(wav("done.wav", 48000, 2.5, finish = true)))
    }

    /** What the app being killed mid-take leaves: both sizes still 0xFFFFFFFF. */
    @Test
    fun a_wav_cut_off_mid_take_is_measured_to_the_end_of_the_file() {
        assertEquals(1500L, RecordingFiles.wavDurationMs(wav("cut.wav", 44100, 1.5, finish = false)))
    }

    @Test
    fun a_wav_claiming_more_than_is_there_is_measured_to_the_end() {
        val f = wav("short.wav", 16000, 1.0, finish = true)
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 16000) } // half the audio gone
        assertEquals(500L, RecordingFiles.wavDurationMs(f))
    }

    @Test
    fun what_isnt_a_wav_has_no_wav_length() {
        assertNull(RecordingFiles.wavDurationMs(file("junk.wav", bytes = 100)))
        assertNull(RecordingFiles.wavDurationMs(File(tmp.root, "missing.wav")))
    }

    @Test
    fun lengths_read_as_a_clock() {
        assertEquals("0:00", RecordingFiles.clock(0))
        assertEquals("0:59", RecordingFiles.clock(59_999))
        assertEquals("1:05", RecordingFiles.clock(65_000))
        assertEquals("1:00:00", RecordingFiles.clock(3_600_000))
        assertEquals("0:00", RecordingFiles.clock(-5))
    }
}
