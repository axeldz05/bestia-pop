package com.bestiapop.android.data.playback

import kotlin.random.Random

/**
 * Pure queue order helpers: wrap a collection so the tapped item is index 0,
 * trim already-played history before persisting, and shuffle as a timeline
 * index permutation (play order) without rewriting the source list.
 */
object PlaybackQueueOrder {
    const val MAX_QUEUE_HISTORY = 20

    data class TrimmedQueue<T>(
        val items: List<T>,
        val currentIndex: Int,
        val shufflePlayOrder: List<Int>? = null
    )

    fun <T> rotateToStart(items: List<T>, startIndex: Int): List<T> {
        if (items.isEmpty()) return items
        val i = startIndex.coerceIn(0, items.lastIndex)
        if (i == 0) return items
        return items.drop(i) + items.take(i)
    }

    fun <T> trimHistory(
        items: List<T>,
        currentIndex: Int,
        maxHistory: Int = MAX_QUEUE_HISTORY,
        shufflePlayOrder: List<Int>? = null
    ): TrimmedQueue<T> {
        if (items.isEmpty()) return TrimmedQueue(emptyList(), 0, null)
        val idx = currentIndex.coerceIn(0, items.lastIndex)
        val keepFrom = (idx - maxHistory.coerceAtLeast(0)).coerceAtLeast(0)
        val trimmedItems = if (keepFrom == 0) items else items.drop(keepFrom)
        val newIndex = if (keepFrom == 0) idx else idx - keepFrom
        return TrimmedQueue(
            items = trimmedItems,
            currentIndex = newIndex,
            shufflePlayOrder = dropPrefixFromPlayOrder(
                shufflePlayOrder,
                keepFrom,
                trimmedItems.size
            )
        )
    }

    fun isValidPlayOrder(playOrder: List<Int>?, size: Int): Boolean {
        if (playOrder == null) return false
        if (size <= 0) return playOrder.isEmpty()
        if (playOrder.size != size) return false
        val seen = BooleanArray(size)
        for (idx in playOrder) {
            if (idx !in 0 until size || seen[idx]) return false
            seen[idx] = true
        }
        return true
    }

    fun validPlayOrderOrNull(playOrder: List<Int>?, size: Int): List<Int>? =
        playOrder?.takeIf { isValidPlayOrder(it, size) }

    /** Current timeline index first; remaining indices shuffled. */
    fun shufflePlayOrder(
        size: Int,
        currentIndex: Int,
        random: Random = Random.Default
    ): List<Int> {
        if (size <= 0) return emptyList()
        val current = currentIndex.coerceIn(0, size - 1)
        if (size == 1) return listOf(0)
        val rest = (0 until size).filter { it != current }.shuffled(random)
        return listOf(current) + rest
    }

    /** Full reshuffle; retries so [avoidStartingWith] is not first when size > 1. */
    fun reshufflePlayOrder(
        size: Int,
        avoidStartingWith: Int? = null,
        random: Random = Random.Default
    ): List<Int> {
        if (size <= 0) return emptyList()
        if (size == 1) return listOf(0)
        repeat(5) {
            val attempt = (0 until size).shuffled(random)
            if (avoidStartingWith == null || attempt.first() != avoidStartingWith) return attempt
        }
        return (0 until size).shuffled(random)
    }

    fun <T> applyPlayOrder(items: List<T>, playOrder: List<Int>?): List<T> {
        val order = validPlayOrderOrNull(playOrder, items.size) ?: return items
        return order.map { items[it] }
    }

    fun toTimelineIndex(playOrder: List<Int>?, displayIndex: Int, size: Int): Int {
        val order = validPlayOrderOrNull(playOrder, size) ?: return displayIndex
        if (displayIndex !in order.indices) return displayIndex
        return order[displayIndex]
    }

    fun toDisplayIndex(playOrder: List<Int>?, timelineIndex: Int, size: Int): Int {
        val order = validPlayOrderOrNull(playOrder, size) ?: return timelineIndex
        val i = order.indexOf(timelineIndex)
        return if (i >= 0) i else timelineIndex
    }

    /**
     * After inserting [count] items at timeline [insertAt], remap indices and splice
     * the new ids immediately after [currentTimelineIndex] in play order.
     */
    fun insertAfterCurrent(
        playOrder: List<Int>,
        currentTimelineIndex: Int,
        insertAt: Int,
        count: Int
    ): List<Int> {
        if (count <= 0) return playOrder
        val remapped = remapAfterTimelineInsert(playOrder, insertAt, count)
        val currentInNew =
            if (currentTimelineIndex >= insertAt) currentTimelineIndex + count else currentTimelineIndex
        val pos = remapped.indexOf(currentInNew)
        val newIndices = List(count) { insertAt + it }
        if (pos < 0) return remapped + newIndices
        return remapped.take(pos + 1) + newIndices + remapped.drop(pos + 1)
    }

    /** Append [count] new timeline indices starting at [firstNewIndex] (typically old size). */
    fun appendToPlayOrder(playOrder: List<Int>, firstNewIndex: Int, count: Int): List<Int> {
        if (count <= 0) return playOrder
        val remapped = remapAfterTimelineInsert(playOrder, firstNewIndex, count)
        return remapped + List(count) { firstNewIndex + it }
    }

    fun removeFromPlayOrder(playOrder: List<Int>, removedTimelineIndex: Int): List<Int> =
        playOrder.mapNotNull { idx ->
            when {
                idx == removedTimelineIndex -> null
                idx > removedTimelineIndex -> idx - 1
                else -> idx
            }
        }

    fun moveInPlayOrder(playOrder: List<Int>, fromDisplay: Int, toDisplay: Int): List<Int> {
        if (fromDisplay !in playOrder.indices || toDisplay !in playOrder.indices) return playOrder
        if (fromDisplay == toDisplay) return playOrder
        val list = playOrder.toMutableList()
        val item = list.removeAt(fromDisplay)
        list.add(toDisplay, item)
        return list
    }

    /**
     * Remap a permutation after some original timeline indices disappear.
     * [oldToNew] maps surviving old index → compact new index.
     */
    fun remapPlayOrder(
        playOrder: List<Int>?,
        oldToNew: Map<Int, Int>,
        newSize: Int
    ): List<Int>? {
        if (playOrder == null) return null
        val remapped = playOrder.mapNotNull { oldToNew[it] }
        return validPlayOrderOrNull(remapped, newSize)
    }

    fun dropPrefixFromPlayOrder(
        playOrder: List<Int>?,
        keepFrom: Int,
        newSize: Int
    ): List<Int>? {
        if (playOrder == null) return null
        if (keepFrom <= 0) return validPlayOrderOrNull(playOrder, newSize)
        val remapped = playOrder.mapNotNull { idx ->
            if (idx < keepFrom) null else idx - keepFrom
        }
        return validPlayOrderOrNull(remapped, newSize)
    }

    private fun remapAfterTimelineInsert(
        playOrder: List<Int>,
        insertAt: Int,
        count: Int
    ): List<Int> {
        if (count <= 0) return playOrder
        return playOrder.map { idx -> if (idx >= insertAt) idx + count else idx }
    }
}
