package com.bestiapop.android.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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

    @Test
    fun shufflePlayOrder_currentFirst_restPermutation() {
        val order = PlaybackQueueOrder.shufflePlayOrder(5, currentIndex = 2, random = Random(1))
        assertEquals(5, order.size)
        assertEquals(2, order.first())
        assertEquals(setOf(0, 1, 2, 3, 4), order.toSet())
    }

    @Test
    fun reshufflePlayOrder_avoidsStartingWith() {
        repeat(8) { seed ->
            val order = PlaybackQueueOrder.reshufflePlayOrder(
                size = 4,
                avoidStartingWith = 1,
                random = Random(seed)
            )
            assertTrue(order.first() != 1)
            assertEquals(setOf(0, 1, 2, 3), order.toSet())
        }
    }

    @Test
    fun insertAfterCurrent_playNext_splicesAfterPlaying() {
        // Timeline [A B C D] play order B,D,A,C = [1,3,0,2]; current B(1); insert E at 2
        val moved = PlaybackQueueOrder.insertAfterCurrent(
            playOrder = listOf(1, 3, 0, 2),
            currentTimelineIndex = 1,
            insertAt = 2,
            count = 1
        )
        assertEquals(listOf(1, 2, 4, 0, 3), moved)
    }

    @Test
    fun appendToPlayOrder_addsAtEndWithoutShiftingExisting() {
        val moved = PlaybackQueueOrder.appendToPlayOrder(listOf(1, 0, 2), firstNewIndex = 3, count = 2)
        assertEquals(listOf(1, 0, 2, 3, 4), moved)
    }

    @Test
    fun removeFromPlayOrder_compactsHigherIndices() {
        val moved = PlaybackQueueOrder.removeFromPlayOrder(listOf(1, 3, 0, 2), removedTimelineIndex = 3)
        assertEquals(listOf(1, 0, 2), moved)
    }

    @Test
    fun moveInPlayOrder_reordersDisplayOnly() {
        val moved = PlaybackQueueOrder.moveInPlayOrder(listOf(2, 0, 3, 1), fromDisplay = 1, toDisplay = 3)
        assertEquals(listOf(2, 3, 1, 0), moved)
    }

    @Test
    fun remapPlayOrder_dropsDeletedAndCompacts() {
        // old [A B C D] order C,A,D,B = [2,0,3,1]; B(1) deleted → new A,C,D
        val remapped = PlaybackQueueOrder.remapPlayOrder(
            playOrder = listOf(2, 0, 3, 1),
            oldToNew = mapOf(0 to 0, 2 to 1, 3 to 2),
            newSize = 3
        )
        assertEquals(listOf(1, 0, 2), remapped)
    }

    @Test
    fun remapPlayOrder_invalidAfterDrop_returnsNull() {
        assertNull(
            PlaybackQueueOrder.remapPlayOrder(
                playOrder = listOf(0, 1, 1),
                oldToNew = mapOf(0 to 0, 1 to 1),
                newSize = 2
            )
        )
    }

    @Test
    fun trimHistory_remapsPlayOrderWithDroppedPrefix() {
        val items = (0..29).toList()
        val playOrder = (0..29).toList().reversed()
        val trimmed = PlaybackQueueOrder.trimHistory(
            items,
            currentIndex = 25,
            maxHistory = 5,
            shufflePlayOrder = playOrder
        )
        assertEquals((20..29).toList(), trimmed.items)
        assertEquals(5, trimmed.currentIndex)
        assertTrue(PlaybackQueueOrder.isValidPlayOrder(trimmed.shufflePlayOrder, 10))
        assertEquals(
            playOrder.filter { it >= 20 }.map { it - 20 },
            trimmed.shufflePlayOrder
        )
    }

    @Test
    fun applyPlayOrder_andIndexConversion() {
        val items = listOf("A", "B", "C", "D")
        val order = listOf(2, 0, 3, 1)
        assertEquals(listOf("C", "A", "D", "B"), PlaybackQueueOrder.applyPlayOrder(items, order))
        assertEquals(3, PlaybackQueueOrder.toTimelineIndex(order, displayIndex = 2, size = 4))
        assertEquals(0, PlaybackQueueOrder.toDisplayIndex(order, timelineIndex = 2, size = 4))
        assertFalse(PlaybackQueueOrder.isValidPlayOrder(listOf(0, 0, 1), 3))
    }
}
