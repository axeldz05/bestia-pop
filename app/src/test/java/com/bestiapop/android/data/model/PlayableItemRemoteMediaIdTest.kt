package com.bestiapop.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayableItemRemoteMediaIdTest {

    @Test
    fun mediaId_usesStableQueryHash() {
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
    fun mediaId_afterResolve_staysStable_andVideoIdRemainsResolvedData() {
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
        assertEquals(unresolved.mediaId, resolved.mediaId)
        assertNotEquals("remote:dQw4w9wgXcQ", resolved.mediaId)
        assertEquals("dQw4w9wgXcQ", resolved.resolved?.videoId)
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
    fun freshQueueEntries_distinguishRepeatedLocalAndRemoteInstances() {
        val local = Song(
            id = 1L,
            uriString = "content://song/1",
            title = "Local"
        ).toPlayable()
        val remote = PlayableItem.remoteFrom(
            artist = "Same",
            title = "Song",
            youtubeQueryOrId = "Same Song"
        )

        val queue = listOf(local, local, remote, remote).withFreshQueueEntryIds()
        val firstLocal = queue[0] as PlayableItem.Local
        val secondLocal = queue[1] as PlayableItem.Local
        val firstRemote = queue[2] as PlayableItem.Remote
        val secondRemote = queue[3] as PlayableItem.Remote

        assertEquals(4, queue.map { it.queueEntryId }.toSet().size)
        assertNotEquals(firstLocal.queueEntryId, secondLocal.queueEntryId)
        assertNotEquals(firstRemote.queueEntryId, secondRemote.queueEntryId)
        assertEquals(1, queue.indexOfQueueEntry(secondLocal))
        assertEquals(3, queue.indexOfRemoteSlot(secondRemote))
    }

    @Test
    fun queueEntryId_survivesCopyAndReorder_forLocalAndRemote() {
        val local = Song(
            id = 1L,
            uriString = "content://song/1",
            title = "Local"
        ).toPlayable()
        val remote = PlayableItem.remoteFrom(
            artist = "Artist",
            title = "Remote"
        )
        val updatedLocal = local.copy(song = local.song.copy(title = "Updated"))
        val resolvedRemote = remote.copy(
            resolved = ResolvedStream(
                audioUrl = "https://cdn.example/audio",
                userAgent = "ua",
                videoId = "video-id",
                resolvedAtEpochMs = 2L
            )
        )

        assertEquals(local.queueEntryId, updatedLocal.queueEntryId)
        assertEquals(remote.queueEntryId, resolvedRemote.queueEntryId)
        assertEquals(
            listOf(remote.queueEntryId, local.queueEntryId),
            listOf(resolvedRemote, updatedLocal).map { it.queueEntryId }
        )
    }

    @Test
    fun remoteOnlyFreshIdAlias_refreshesAllQueueOccurrences() {
        val local = Song(
            uriString = "content://song/local",
            title = "Local"
        ).toPlayable()
        val remote = PlayableItem.remoteFrom(artist = "Artist", title = "Remote")

        val refreshed = listOf(local, remote).withFreshRemoteQueueEntryIds()

        assertNotEquals(local.queueEntryId, refreshed[0].queueEntryId)
        assertNotEquals(remote.queueEntryId, refreshed[1].queueEntryId)
    }
}
