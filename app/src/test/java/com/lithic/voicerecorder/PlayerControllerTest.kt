package com.lithic.voicerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The recordings screen's player, over a fake output whose callbacks the test fires by hand. */
class PlayerControllerTest {

    private class FakeOutput : AudioOutput {
        var file: File? = null
        var ready: ((Long) -> Unit)? = null
        var end: (() -> Unit)? = null
        var fail: ((String) -> Unit)? = null
        var playing = false
        var released = false
        override var positionMs = 0L

        override fun open(file: File, onReady: (durationMs: Long) -> Unit, onEnd: () -> Unit, onError: (String) -> Unit) {
            this.file = file
            ready = onReady
            end = onEnd
            fail = onError
        }

        override fun start() {
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun seekTo(ms: Long) {
            positionMs = ms
        }

        override fun release() {
            released = true
            playing = false
        }
    }

    private val outputs = mutableListOf<FakeOutput>()
    private var focus = true
    private var focusHeld = 0
    private val player = PlayerController(
        newOutput = { FakeOutput().also { outputs += it } },
        requestFocus = { focus.also { if (it) focusHeld++ } },
        abandonFocus = { focusHeld-- },
    )
    private val a = File("a.wav")
    private val b = File("b.wav")

    private fun opened(file: File, length: Long = 10_000): FakeOutput {
        player.play(file)
        return outputs.last().also { it.ready!!(length) }
    }

    @Test
    fun a_recording_plays_once_open() {
        player.play(a)
        assertTrue(player.isOpening)
        assertFalse(player.isPlaying)
        outputs.last().ready!!(10_000)
        assertTrue(player.isPlaying)
        assertTrue(outputs.last().playing)
        assertEquals(10_000, player.durationMs)
        assertEquals(1, focusHeld)
    }

    /** Home pressed, or a second tap, while it opens: it doesn't start a moment later with no screen to stop it. */
    @Test
    fun a_pause_while_it_opens_keeps_it_from_starting() {
        player.play(a)
        player.pause()
        outputs.last().ready!!(10_000)
        assertFalse(player.isPlaying)
        assertFalse(outputs.last().playing)
        assertEquals(0, focusHeld)
        player.toggle() // now open and paused: plays
        assertTrue(outputs.last().playing)
    }

    @Test
    fun the_play_button_pressed_twice_while_it_opens_leaves_it_paused() {
        player.play(a)
        assertTrue(player.isPlayingOrStarting)
        player.toggle()
        assertFalse(player.isPlayingOrStarting)
        player.toggle()
        assertTrue(player.isPlayingOrStarting)
        outputs.last().ready!!(10_000)
        assertTrue(outputs.last().playing)
    }

    @Test
    fun the_play_button_pauses_and_resumes_where_it_was() {
        val out = opened(a)
        out.positionMs = 4_000
        player.toggle()
        assertFalse(out.playing)
        assertEquals(0, focusHeld)
        player.toggle()
        assertTrue(out.playing)
        assertEquals(4_000, player.positionMs)
    }

    @Test
    fun at_the_end_it_shows_the_end_and_plays_again_from_the_start() {
        val out = opened(a)
        out.positionMs = 9_990
        out.playing = false
        out.end!!()
        assertFalse(player.isPlaying)
        assertEquals(10_000, player.positionMs)
        assertEquals(0, focusHeld)
        player.toggle()
        assertEquals(0, out.positionMs)
        assertTrue(out.playing)
    }

    /** A later choice wins: the first output is let go, and its late "ready" changes nothing. */
    @Test
    fun choosing_another_recording_lets_go_of_the_first() {
        player.play(a)
        val first = outputs.last()
        player.play(b)
        assertTrue(first.released)
        first.ready!!(10_000) // late, from an output already let go of
        assertFalse(first.playing)
        assertTrue(player.isOpening)
        assertEquals(b, player.file)
    }

    @Test
    fun a_recording_that_wont_play_says_why_and_play_tries_again() {
        player.play(a)
        val failed = outputs.last()
        failed.fail!!("damaged")
        assertEquals("damaged", player.error)
        assertTrue(failed.released)
        assertFalse(player.isPlaying)
        assertEquals(a, player.file) // still named, so the play button opens it again
        player.toggle()
        assertEquals(2, outputs.size)
        assertNull(player.error)
    }

    @Test
    fun without_audio_focus_it_doesnt_start() {
        focus = false
        val out = opened(a)
        assertFalse(out.playing)
        assertFalse(player.isPlaying)
    }

    @Test
    fun a_renamed_recording_plays_on_under_its_new_name() {
        val out = opened(a)
        player.renamed(a, b)
        assertEquals(b, player.file)
        assertTrue(out.playing)
        assertFalse(out.released)
    }

    /** Before a delete: nothing plays on from a file that is gone. */
    @Test
    fun letting_go_of_the_recording_loaded_closes_it() {
        val out = opened(a)
        player.release(b) // another one: nothing happens
        assertTrue(out.playing)
        player.release(a)
        assertTrue(out.released)
        assertNull(player.file)
        assertEquals(0, focusHeld)
    }

    @Test
    fun a_seek_stays_within_the_recording() {
        val out = opened(a)
        player.seekTo(25_000)
        assertEquals(10_000, out.positionMs)
        player.seekTo(-5)
        assertEquals(0, out.positionMs)
    }

    @Test
    fun stop_goes_back_to_the_start_paused() {
        val out = opened(a)
        out.positionMs = 6_000
        player.stop()
        assertFalse(out.playing)
        assertEquals(0, out.positionMs)
        assertEquals(0, focusHeld)
    }
}
