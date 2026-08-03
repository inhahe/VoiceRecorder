package com.lithic.voicerecorder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM checks for the pure filename helpers behind the "reuse the last file name" flow:
 * [Settings.mergeRecent] (the MRU list) and [StorageUtil.nextAvailableName] (the `_2`, `_3`, …
 * suffixing that keeps repeated takes as separate files).
 */
class FilenameHistoryTest {

    @Test fun `newest name goes to the front`() {
        val list = Settings.mergeRecent(listOf("B", "C"), "A")
        assertEquals(listOf("A", "B", "C"), list)
    }

    @Test fun `reusing a name moves it up instead of duplicating`() {
        val list = Settings.mergeRecent(listOf("B", "C"), "C")
        assertEquals(listOf("C", "B"), list)
    }

    @Test fun `dedupe ignores case`() {
        val list = Settings.mergeRecent(listOf("Interview"), "interview")
        assertEquals(listOf("interview"), list)
    }

    @Test fun `blank names are not remembered`() {
        assertEquals(listOf("A"), Settings.mergeRecent(listOf("A"), "   "))
    }

    @Test fun `names are trimmed`() {
        assertEquals(listOf("Take", "A"), Settings.mergeRecent(listOf("A"), "  Take  "))
    }

    @Test fun `list is capped at max`() {
        var list = emptyList<String>()
        repeat(Settings.MAX_RECENT_NAMES + 5) { list = Settings.mergeRecent(list, "n$it") }
        assertEquals(Settings.MAX_RECENT_NAMES, list.size)
        assertEquals("n${Settings.MAX_RECENT_NAMES + 4}", list.first())
    }

    @Test fun `free name is used as-is`() {
        assertEquals("Take.m4a", StorageUtil.nextAvailableName("Take", "m4a") { false })
    }

    @Test fun `taken names get numeric suffixes`() {
        val taken = mutableSetOf("Take.m4a", "Take_2.m4a")
        assertEquals("Take_3.m4a", StorageUtil.nextAvailableName("Take", "m4a") { it in taken })
    }

    @Test fun `suffixing is per-extension`() {
        val taken = setOf("Take.m4a")
        assertEquals("Take.wav", StorageUtil.nextAvailableName("Take", "wav") { it in taken })
    }
}
