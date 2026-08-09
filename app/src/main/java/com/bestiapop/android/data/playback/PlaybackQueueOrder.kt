package com.bestiapop.android.data.playback

/**
 * Pure queue order helpers: wrap a collection so the tapped item is index 0,
 * and trim already-played history before persisting.
 */
object PlaybackQueueOrder {
    const val MAX_QUEUE_HISTORY = 20

    data class TrimmedQueue<T>(val items: List<T>, val currentIndex: Int)

    fun <T> rotateToStart(items: List<T>, startIndex: Int): List<T> {
        if (items.isEmpty()) return items
        val i = startIndex.coerceIn(0, items.lastIndex)
        if (i == 0) return items
        return items.drop(i) + items.take(i)
    }

    fun <T> trimHistory(
        items: List<T>,
        currentIndex: Int,
        maxHistory: Int = MAX_QUEUE_HISTORY
    ): TrimmedQueue<T> {
        if (items.isEmpty()) return TrimmedQueue(emptyList(), 0)
        val idx = currentIndex.coerceIn(0, items.lastIndex)
        val keepFrom = (idx - maxHistory.coerceAtLeast(0)).coerceAtLeast(0)
        if (keepFrom == 0) return TrimmedQueue(items, idx)
        return TrimmedQueue(items.drop(keepFrom), idx - keepFrom)
    }
}
