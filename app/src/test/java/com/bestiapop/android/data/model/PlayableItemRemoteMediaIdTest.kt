package com.bestiapop.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayableItemRemoteMediaIdTest {

    @Test
    fun mediaId_unresolved_usesQueryHash_notVideoPrefix() {
        val remote = PlayableItem.remoteFrom(
            artist = "Artist",
            title = "Song",
            youtubeQueryOrId = "Artist Song"
        )
        assertTrue(remote.mediaId.startsWith("remote:"))
        assertFalse(remote.mediaId.startsWith("remote:dQw4w9wgXcQ"))
        val expected =
            "remote:${"Artist Song".lowercase().hashCode().toUInt().toString(16)}"
        assertEquals(expected, remote.mediaId)
    }

    @Test
    fun mediaId_withResolvedVideoId_usesVideoId() {
        val unresolved = PlayableItem.remoteFrom(
            artist = "Artist",
            title = "Song",
            youtubeQueryOrId = "Artist Song"
        )
        val resolved = unresolved.copy(
            resolved = ResolvedStream(
                audioUrl = "https://cdn.example/a.m4a",
                userAgent = "ua",
                videoId = "dQw4w9wgXcQ",
                resolvedAtEpochMs = 1L
            )
        )
        assertEquals("remote:dQw4w9wgXcQ", resolved.mediaId)
        assertNotEquals(unresolved.mediaId, resolved.mediaId)
    }

    @Test
    fun indexOfRemoteSlot_matchesQueueEntryBeforeOrAfterResolve() {
        val original = PlayableItem.remoteFrom(
            artist = "A",
            title = "T",
            youtubeQueryOrId = "A T"
        )
        val other = PlayableItem.remoteFrom(
            artist = "B",
            title = "U",
            youtubeQueryOrId = "B U"
        )
        val resolved = original.copy(
            resolved = ResolvedStream(
                audioUrl = "https://cdn.example/a.m4a",
                userAgent = "ua",
                videoId = "abc123XYZ01",
                resolvedAtEpochMs = 1L
            )
        )
        val queue: List<PlayableItem> = listOf(other, original)

        assertEquals(1, queue.indexOfRemoteSlot(original))
        // After the slot was already updated to the resolved copy:
        val updated = listOf(other, resolved)
        assertEquals(1, updated.indexOfRemoteSlot(original))
        assertEquals(-1, listOf(other).indexOfRemoteSlot(original))
    }

    @Test
    fun indexOfRemoteSlot_duplicateQuery_matchesTheResolvedQueueEntry() {
        val first = PlayableItem.remoteFrom(
            artist = "Same",
            title = "Song",
            youtubeQueryOrId = "Same Song"
        )
        val second = PlayableItem.remoteFrom(
            artist = "Same",
            title = "Song",
            youtubeQueryOrId = "Same Song"
        )
        assertEquals(first.mediaId, second.mediaId)
        assertNotEquals(first.queueEntryId, second.queueEntryId)

        val resolvedSecond = second.copy(
            resolved = ResolvedStream(
                audioUrl = "https://cdn.example/second.m4a",
                userAgent = "ua",
                videoId = "second12345",
                resolvedAtEpochMs = 1L
            )
        )

        assertEquals(1, listOf(first, second).indexOfRemoteSlot(second))
        assertEquals(second.queueEntryId, resolvedSecond.queueEntryId)
    }

    @Test
    fun freshQueueEntries_distinguishRepeatedRemoteInstance() {
        val remote = PlayableItem.remoteFrom(
            artist = "Same",
            title = "Song",
            youtubeQueryOrId = "Same Song"
        )

        val queue = listOf(remote, remote).withFreshRemoteQueueEntryIds()
        val first = queue[0] as PlayableItem.Remote
        val second = queue[1] as PlayableItem.Remote

        assertNotEquals(first.queueEntryId, second.queueEntryId)
        assertEquals(1, queue.indexOfRemoteSlot(second))
    }
}
