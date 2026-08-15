package com.bestiapop.android.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedLyricsTest {

    @Test
    fun parse_lrcAndUntimed_roundTrip() {
        val raw = """
            [00:12.40]Hello
            plain line
            [01:03.5]World
        """.trimIndent()
        val lines = SyncedLyrics.parse(raw)
        assertEquals(3, lines.size)
        assertEquals(12400L, lines[0].timeMs)
        assertEquals("Hello", lines[0].text)
        assertNull(lines[1].timeMs)
        assertEquals("plain line", lines[1].text)
        assertEquals(63500L, lines[2].timeMs)
        assertEquals("World", lines[2].text)

        val formatted = SyncedLyrics.format(lines)
        val again = SyncedLyrics.parse(formatted)
        assertEquals(listOf(12400L, null, 63500L), again.map { it.timeMs })
        assertEquals(listOf("Hello", "plain line", "World"), again.map { it.text })
    }

    @Test
    fun parse_centisecondsVsMilliseconds() {
        assertEquals(1500L, SyncedLyrics.parse("[00:01.5]a").single().timeMs)
        assertEquals(1230L, SyncedLyrics.parse("[00:01.23]a").single().timeMs)
        assertEquals(1123L, SyncedLyrics.parse("[00:01.123]a").single().timeMs)
    }

    @Test
    fun formatTimestamp_andParseTimestamp() {
        assertEquals("01:02.03", SyncedLyrics.formatTimestamp(62_030))
        assertEquals(62_030L, SyncedLyrics.parseTimestamp("1:02.03"))
        assertEquals(62_000L, SyncedLyrics.parseTimestamp("01:02"))
        assertNull(SyncedLyrics.parseTimestamp("1:99"))
        assertNull(SyncedLyrics.parseTimestamp("abc"))
    }

    @Test
    fun stamp_setsTime() {
        val stamped = SyncedLyrics.stamp(SyncedLyricLine(text = "Hi"), 4_500)
        assertEquals(4_500L, stamped.timeMs)
        assertEquals("Hi", stamped.text)
    }

    @Test
    fun currentLineIndex_lastTimedAtOrBeforePosition() {
        val lines = listOf(
            SyncedLyricLine(1_000, "a"),
            SyncedLyricLine(null, "skip"),
            SyncedLyricLine(3_000, "b"),
            SyncedLyricLine(5_000, "c")
        )
        assertEquals(-1, SyncedLyrics.currentLineIndex(lines, 0))
        assertEquals(0, SyncedLyrics.currentLineIndex(lines, 1_000))
        assertEquals(0, SyncedLyrics.currentLineIndex(lines, 2_999))
        assertEquals(2, SyncedLyrics.currentLineIndex(lines, 3_000))
        assertEquals(3, SyncedLyrics.currentLineIndex(lines, 9_000))
        assertTrue(SyncedLyrics.hasTimestamps(lines))
        assertFalse(SyncedLyrics.hasTimestamps(listOf(SyncedLyricLine(text = "only"))))
    }

    @Test
    fun parse_blank_isEmpty() {
        assertTrue(SyncedLyrics.parse("").isEmpty())
        assertTrue(SyncedLyrics.parse("  \n  ").isEmpty())
    }

    @Test
    fun plainText_stripsTimestamps() {
        val lines = SyncedLyrics.parse("[00:12.40]Hello\nWorld")
        assertEquals("Hello\nWorld", SyncedLyrics.plainText(lines))
        assertTrue(SyncedLyrics.looksLikeLrc("[00:12.40]Hello"))
        assertFalse(SyncedLyrics.looksLikeLrc("Hello\nWorld"))
    }

    @Test
    fun realignByText_sameCount_keepsTimesByIndex() {
        val old = listOf(
            SyncedLyricLine(1_000, "Hello"),
            SyncedLyricLine(2_000, "World")
        )
        val aligned = SyncedLyrics.realignByText(old, listOf("Hello!", "World?"))
        assertEquals(listOf(1_000L, 2_000L), aligned.map { it.timeMs })
        assertEquals(listOf("Hello!", "World?"), aligned.map { it.text })
    }

    @Test
    fun realignByText_insertAtStart_doesNotRecycleFirstStamp() {
        val old = listOf(
            SyncedLyricLine(1_000, "Hello"),
            SyncedLyricLine(2_000, "World")
        )
        val aligned = SyncedLyrics.realignByText(old, listOf("Intro", "Hello", "World"))
        assertEquals(listOf(null, 1_000L, 2_000L), aligned.map { it.timeMs })
        assertEquals(listOf("Intro", "Hello", "World"), aligned.map { it.text })
    }

    @Test
    fun realignByText_removedLine_matchesRemainingByText() {
        val old = listOf(
            SyncedLyricLine(1_000, "A"),
            SyncedLyricLine(2_000, "B"),
            SyncedLyricLine(3_000, "C")
        )
        val aligned = SyncedLyrics.realignByText(old, listOf("A", "C"))
        assertEquals(listOf(1_000L, 3_000L), aligned.map { it.timeMs })
    }
}
