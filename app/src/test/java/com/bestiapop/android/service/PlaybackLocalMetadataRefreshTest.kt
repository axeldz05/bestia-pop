package com.bestiapop.android.service

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackLocalMetadataRefreshTest {

    @Test
    fun refresh_preservesFirstIdOrUriMatchAndQueueEntryId() {
        val queued = PlayableItem.Local(
            song = song(id = 7L, uri = "file:///same", title = "Old"),
            queueEntryId = "slot-7"
        )
        val uriMatchFirst = song(id = 2L, uri = "file:///same", title = "URI first")
        val idMatchLater = song(id = 7L, uri = "file:///other", title = "ID later")

        val refreshed = refreshLocalQueueMetadata(
            queue = listOf(queued),
            songs = listOf(uriMatchFirst, idMatchLater)
        ).single() as PlayableItem.Local

        assertEquals("URI first", refreshed.song.title)
        assertEquals("slot-7", refreshed.queueEntryId)
    }

    @Test
    fun refresh_fallsBackToUriForRowsWithoutStableId() {
        val queued = PlayableItem.Local(
            song = song(id = 0L, uri = "content://media/track", title = "Old"),
            queueEntryId = "slot-uri"
        )
        val current = song(id = 42L, uri = "content://media/track", title = "Current")

        val refreshed = refreshLocalQueueMetadata(listOf(queued), listOf(current))
            .single() as PlayableItem.Local

        assertEquals(current, refreshed.song)
    }

    @Test
    fun refresh_keepsHydratedLyricsWhenLibraryRowIsSlim() {
        val queued = PlayableItem.Local(
            song = song(id = 7L, uri = "file:///same", title = "Old").copy(lyrics = "[00:01]kept"),
            queueEntryId = "slot-7"
        )
        val slim = song(id = 7L, uri = "file:///same", title = "New")
        val refreshed = refreshLocalQueueMetadata(listOf(queued), listOf(slim))
            .single() as PlayableItem.Local
        assertEquals("New", refreshed.song.title)
        assertEquals("[00:01]kept", refreshed.song.lyrics)
    }

    @Test
    fun keepLyricsIfIncomingSlim_copiesOnlyWhenIncomingHasNone() {
        val hydrated = song(id = 1L, uri = "file:///a", title = "A").copy(lyrics = "[00:01]kept")
        val slim = song(id = 1L, uri = "file:///a", title = "B")
        val full = slim.copy(lyrics = "[00:02]fresh")
        assertEquals("[00:01]kept", hydrated.keepLyricsIfIncomingSlim(slim).lyrics)
        assertEquals("B", hydrated.keepLyricsIfIncomingSlim(slim).title)
        assertEquals("[00:02]fresh", hydrated.keepLyricsIfIncomingSlim(full).lyrics)
    }

    @Test
    fun refresh_keepsUnmatchedQueueItem() {
        val queued = PlayableItem.Local(
            song = song(id = 1L, uri = "file:///missing", title = "Missing"),
            queueEntryId = "slot-missing"
        )

        assertSame(queued, refreshLocalQueueMetadata(listOf(queued), emptyList()).single())
    }

    private fun song(id: Long, uri: String, title: String): Song = Song(
        id = id,
        uriString = uri,
        title = title,
        artist = "Artist",
        album = "Album"
    )
}
