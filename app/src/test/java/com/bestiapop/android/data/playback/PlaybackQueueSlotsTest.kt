package com.bestiapop.android.data.playback

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.data.model.withFreshQueueEntryIds
import com.bestiapop.android.data.preferences.QueueSnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackQueueSlotsTest {

    @Test
    fun restorePreShuffleOrder_resolveEnqueueRemoveAndDuplicates_preservesLiveOccurrences() {
        val repeatedLocal = local(1, "Repeated")
        val repeatedRemote = remote("Repeated")
        val original = listOf(
            repeatedLocal,
            repeatedRemote,
            repeatedLocal,
            repeatedRemote,
            local(2, "Removed")
        ).withFreshQueueEntryIds()
        val preShuffleOrder = PlaybackQueueSlots.capturePreShuffleOrder(original)

        val resolvedSecondRemote = (original[3] as PlayableItem.Remote).resolvedAs(
            videoId = "video-second",
            audioUrl = "https://cdn.example/second"
        )
        val resolvedFirstRemote = (original[1] as PlayableItem.Remote).resolvedAs(
            videoId = "video-first",
            audioUrl = "https://cdn.example/first"
        )
        val enqueued = listOf(repeatedRemote, repeatedLocal).withFreshQueueEntryIds()
        val liveQueue = listOf(
            resolvedSecondRemote,
            original[2],
            resolvedFirstRemote,
            original[0],
            enqueued[0],
            enqueued[1]
        )

        val restored = PlaybackQueueSlots.restorePreShuffleOrder(
            liveQueue = liveQueue,
            preShuffleOrder = preShuffleOrder
        )

        assertEquals(
            listOf(
                original[0],
                original[1],
                original[2],
                original[3],
                enqueued[0],
                enqueued[1]
            ).slotIds(),
            restored.slotIds()
        )
        assertEquals(
            "https://cdn.example/first",
            (restored[1] as PlayableItem.Remote).resolved?.audioUrl
        )
        assertEquals(
            "https://cdn.example/second",
            (restored[3] as PlayableItem.Remote).resolved?.audioUrl
        )
        assertEquals(6, restored.slotIds().toSet().size)
    }

    @Test
    fun projectSnapshot_shuffleResolveEnqueueRemoveAndTrim_roundTripsPhysicalOrderBySlot() {
        val repeatedLocal = local(1, "Repeated")
        val repeatedRemote = remote("Repeated")
        val original = listOf(
            repeatedLocal,
            repeatedRemote,
            repeatedLocal,
            repeatedRemote,
            local(2, "Removed")
        ).withFreshQueueEntryIds()
        val preShuffleOrder = PlaybackQueueSlots.capturePreShuffleOrder(original)
        val enqueued = listOf(repeatedRemote, repeatedLocal).withFreshQueueEntryIds()
        val physicalQueue = listOf(
            (original[3] as PlayableItem.Remote).resolvedAs(
                videoId = "video-second",
                audioUrl = "https://cdn.example/second"
            ),
            original[2],
            (original[1] as PlayableItem.Remote).resolvedAs(
                videoId = "video-first",
                audioUrl = "https://cdn.example/first"
            ),
            original[0],
            enqueued[0],
            enqueued[1]
        )

        val projection = PlaybackQueueSlots.projectSnapshot(
            queue = physicalQueue,
            currentIndex = 3,
            preShuffleOrder = preShuffleOrder,
            maxHistory = 2
        )

        val trimmedPhysical = physicalQueue.drop(1)
        assertEquals(
            listOf(original[0], original[1], original[2], enqueued[0], enqueued[1]).slotIds(),
            projection.items.slotIds()
        )
        assertEquals(0, projection.currentIndex)
        assertEquals(listOf(2, 1, 0, 3, 4), projection.shufflePlayOrder)
        assertEquals(
            trimmedPhysical.slotIds(),
            PlaybackQueueOrder.applyPlayOrder(
                projection.items,
                projection.shufflePlayOrder
            ).slotIds()
        )
        assertEquals(
            "video-first",
            (projection.items[1] as PlayableItem.Remote).resolved?.videoId
        )

        val encoded = QueueSnapshotCodec.encode(
            QueueSnapshotCodec.fromPlayable(
                items = projection.items,
                currentIndex = projection.currentIndex,
                positionMs = 12_000L,
                shufflePlayOrder = projection.shufflePlayOrder
            )
        )
        (original + enqueued).forEach { assertFalse(encoded.contains(it.queueEntryId)) }
        assertFalse(encoded.contains("cdn.example"))
    }

    @Test
    fun projectSnapshot_sameMediaIds_mapsEachOccurrenceByQueueEntryId() {
        val remote = remote("Same")
        val original = listOf(remote, remote).withFreshQueueEntryIds()
        val resolvedSecond = (original[1] as PlayableItem.Remote).resolvedAs(
            videoId = "same-video",
            audioUrl = "https://cdn.example/same"
        )

        val projection = PlaybackQueueSlots.projectSnapshot(
            queue = listOf(resolvedSecond, original[0]),
            currentIndex = 0,
            preShuffleOrder = PlaybackQueueSlots.capturePreShuffleOrder(original)
        )

        assertEquals(original.slotIds(), projection.items.slotIds())
        assertEquals(listOf(1, 0), projection.shufflePlayOrder)
        assertEquals(1, projection.currentIndex)
        assertEquals(original[0].mediaId, resolvedSecond.mediaId)
    }

    @Test
    fun projectSnapshot_invalidDuplicateSlotIds_fallsBackToLinearTrimmedQueue() {
        val first = local(1, "First")
        val duplicateSlot = local(2, "Second").copy(queueEntryId = first.queueEntryId)
        val queue = listOf(first, duplicateSlot, local(3, "Third"))

        val projection = PlaybackQueueSlots.projectSnapshot(
            queue = queue,
            currentIndex = 2,
            preShuffleOrder = listOf(first.queueEntryId),
            maxHistory = 2
        )

        assertEquals(queue.slotIds(), projection.items.slotIds())
        assertEquals(2, projection.currentIndex)
        assertNull(projection.shufflePlayOrder)
    }

    private fun local(id: Long, title: String): PlayableItem.Local =
        Song(
            id = id,
            uriString = "content://song/$id",
            title = title,
            artist = "Artist"
        ).toPlayable()

    private fun remote(title: String): PlayableItem.Remote =
        PlayableItem.remoteFrom(
            artist = "Artist",
            title = title,
            youtubeQueryOrId = "Artist $title"
        )

    private fun PlayableItem.Remote.resolvedAs(
        videoId: String,
        audioUrl: String
    ): PlayableItem.Remote = copy(
        resolved = ResolvedStream(
            audioUrl = audioUrl,
            userAgent = "ua",
            videoId = videoId,
            resolvedAtEpochMs = 1L
        )
    )

    private fun List<PlayableItem>.slotIds(): List<String> = map { it.queueEntryId }
}
