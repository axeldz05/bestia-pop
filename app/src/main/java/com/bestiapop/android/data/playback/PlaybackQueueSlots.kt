package com.bestiapop.android.data.playback

import com.bestiapop.android.data.model.PlayableItem
import java.util.ArrayDeque

/**
 * Queue order ready to be converted to the persisted queue model.
 *
 * [items] still contains runtime-only fields; persistence must keep using its explicit codec, which
 * omits queue entry ids and resolved CDN data.
 */
data class PlaybackQueueSnapshotProjection(
    val items: List<PlayableItem>,
    val currentIndex: Int,
    val shufflePlayOrder: List<Int>? = null
)

/**
 * Pure operations that reconcile physical queue order by occurrence rather than by track identity.
 */
object PlaybackQueueSlots {

    /** Process-only pre-shuffle order. These ids must never be persisted. */
    fun capturePreShuffleOrder(items: List<PlayableItem>): List<String> =
        items.map { it.queueEntryId }

    /**
     * Projects the captured order onto the live queue.
     *
     * Removed entries stay removed, resolved copies retain their live data, and entries added while
     * shuffled are appended in their current relative order.
     */
    fun restorePreShuffleOrder(
        liveQueue: List<PlayableItem>,
        preShuffleOrder: List<String>
    ): List<PlayableItem> {
        if (liveQueue.isEmpty() || preShuffleOrder.isEmpty()) return liveQueue

        val liveIndicesBySlot = HashMap<String, ArrayDeque<Int>>(liveQueue.size)
        liveQueue.forEachIndexed { index, item ->
            liveIndicesBySlot.getOrPut(item.queueEntryId, ::ArrayDeque).addLast(index)
        }
        val consumed = BooleanArray(liveQueue.size)
        val restored = ArrayList<PlayableItem>(liveQueue.size)
        for (queueEntryId in preShuffleOrder) {
            val index = liveIndicesBySlot[queueEntryId]?.pollFirst() ?: continue
            consumed[index] = true
            restored += liveQueue[index]
        }
        liveQueue.forEachIndexed { index, item ->
            if (!consumed[index]) restored += item
        }
        return restored
    }

    /**
     * Trims in physical play order, then represents the survivors in pre-shuffle order plus a
     * physical-to-original permutation. Slot ids are used only while calculating this projection.
     *
     * If no pre-shuffle order is supplied, or queue slot ids are invalid, the safe result is a
     * linear snapshot of the trimmed physical queue.
     */
    fun projectSnapshot(
        queue: List<PlayableItem>,
        currentIndex: Int,
        preShuffleOrder: List<String>? = null,
        maxHistory: Int = PlaybackQueueOrder.MAX_QUEUE_HISTORY
    ): PlaybackQueueSnapshotProjection {
        val trimmed = PlaybackQueueOrder.trimHistory(
            items = queue,
            currentIndex = currentIndex,
            maxHistory = maxHistory
        )
        val linear = PlaybackQueueSnapshotProjection(
            items = trimmed.items,
            currentIndex = trimmed.currentIndex
        )
        val capturedOrder = preShuffleOrder ?: return linear
        if (trimmed.items.isEmpty()) return linear

        val original = restorePreShuffleOrder(trimmed.items, capturedOrder)
        val originalIndexBySlot = original.uniqueSlotIndexOrNull() ?: return linear
        val playOrder = trimmed.items.map { item ->
            originalIndexBySlot[item.queueEntryId] ?: return linear
        }
        val validPlayOrder = PlaybackQueueOrder.validPlayOrderOrNull(
            playOrder = playOrder,
            size = original.size
        ) ?: return linear
        val currentSlot = trimmed.items[trimmed.currentIndex].queueEntryId
        val currentInOriginal = originalIndexBySlot[currentSlot] ?: return linear

        return PlaybackQueueSnapshotProjection(
            items = original,
            currentIndex = currentInOriginal,
            shufflePlayOrder = validPlayOrder
        )
    }

    private fun List<PlayableItem>.uniqueSlotIndexOrNull(): Map<String, Int>? {
        val result = HashMap<String, Int>(size)
        forEachIndexed { index, item ->
            if (result.put(item.queueEntryId, index) != null) return null
        }
        return result
    }
}
