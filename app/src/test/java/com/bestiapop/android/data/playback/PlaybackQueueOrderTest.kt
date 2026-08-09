package com.bestiapop.android.data.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueOrderTest {

    @Test
    fun rotateToStart_zero_keepsOrder() {
        val items = listOf("A", "B", "C", "D")
        assertEquals(items, PlaybackQueueOrder.rotateToStart(items, 0))
    }

    @Test
    fun rotateToStart_middle_wrapsPrefixAfterSuffix() {
        val items = listOf("A", "B", "C", "D", "E", "F")
        assertEquals(
            listOf("D", "E", "F", "A", "B", "C"),
            PlaybackQueueOrder.rotateToStart(items, 3)
        )
    }

    @Test
    fun rotateToStart_last_putsOnlyThatItemFirst() {
        val items = listOf("A", "B", "C")
        assertEquals(listOf("C", "A", "B"), PlaybackQueueOrder.rotateToStart(items, 2))
    }

    @Test
    fun rotateToStart_empty_returnsEmpty() {
        assertEquals(emptyList<String>(), PlaybackQueueOrder.rotateToStart(emptyList<String>(), 0))
    }

    @Test
    fun rotateToStart_coercesOutOfRange() {
        val items = listOf("A", "B")
        assertEquals(listOf("B", "A"), PlaybackQueueOrder.rotateToStart(items, 99))
    }

    @Test
    fun trimHistory_currentAtStart_keepsAll() {
        val items = (0..9).toList()
        val trimmed = PlaybackQueueOrder.trimHistory(items, currentIndex = 0, maxHistory = 20)
        assertEquals(items, trimmed.items)
        assertEquals(0, trimmed.currentIndex)
    }

    @Test
    fun trimHistory_dropsOlderThanMax() {
        val items = (0..29).toList()
        val trimmed = PlaybackQueueOrder.trimHistory(items, currentIndex = 25, maxHistory = 5)
        assertEquals((20..29).toList(), trimmed.items)
        assertEquals(5, trimmed.currentIndex)
        assertEquals(25, trimmed.items[trimmed.currentIndex])
    }

    @Test
    fun trimHistory_zeroHistory_keepsCurrentAndUpcoming() {
        val items = listOf("A", "B", "C", "D")
        val trimmed = PlaybackQueueOrder.trimHistory(items, currentIndex = 2, maxHistory = 0)
        assertEquals(listOf("C", "D"), trimmed.items)
        assertEquals(0, trimmed.currentIndex)
    }

    @Test
    fun trimHistory_empty_returnsEmpty() {
        val trimmed = PlaybackQueueOrder.trimHistory<Int>(emptyList(), 0, 20)
        assertEquals(emptyList<Int>(), trimmed.items)
        assertEquals(0, trimmed.currentIndex)
    }
}
