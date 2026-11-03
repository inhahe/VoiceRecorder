package com.lithic.voicerecorder

import org.junit.Assert.assertArrayEquals
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

    /**
     * Android's player refuses a WAV whose sizes are still unknown, so a cut-off take is played
     * through a [WavView]: it must read exactly as the same audio finished properly would.
     */
    @Test
    fun a_cut_off_wav_reads_through_its_view_as_if_finished() {
        val cut = wav("cut.wav", 44100, 1.5, finish = false)
        RandomAccessFile(cut, "rw").use { it.setLength(it.length() + 1) } // a frame cut short
        val finished = wav("done.wav", 44100, 1.5, finish = true)
        val layout = WavLayout.read(cut)!!
        assertEquals(false, layout.sizesRight)
        val read = WavView(cut, layout).use { view ->
            val out = ByteArray(view.length.toInt())
            var at = 0
            while (at < out.size) at += view.readAt(at.toLong(), out, at, minOf(4096, out.size - at)) // as the player reads, in pieces
            assertEquals(-1, view.readAt(view.length, ByteArray(16), 0, 16))
            out
        }
        assertArrayEquals(finished.readBytes(), read)
    }

    @Test
    fun a_finished_wav_needs_no_view() {
        assertEquals(true, WavLayout.read(wav("done.wav", 16000, 1.0, finish = true))!!.sizesRight)
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
