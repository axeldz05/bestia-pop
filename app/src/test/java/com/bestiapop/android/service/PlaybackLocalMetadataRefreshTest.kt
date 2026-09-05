package com.bestiapop.android.service

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.compareSongsWithinAlbum
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

    @Test
    fun compareSongsWithinAlbum_sortsByTrackNumberAndDisc() {
        val track1 = song(id = 1L, uri = "file:///1", title = "Track 1").copy(trackNumber = 1)
        val track2 = song(id = 2L, uri = "file:///2", title = "Track 2").copy(trackNumber = 2)
        val disc2Track1 = song(id = 3L, uri = "file:///3", title = "D2 T1").copy(trackNumber = 2001)
        val noTrack = song(id = 4L, uri = "file:///4", title = "No Track").copy(trackNumber = 0)

        val list = listOf(noTrack, disc2Track1, track2, track1)
        val sorted = list.sortedWith(::compareSongsWithinAlbum)

        assertEquals(listOf(track1, track2, disc2Track1, noTrack), sorted)
    }

    @Test
    fun compareSongsWithinAlbum_breaksTiesByTitle() {
        val songA = song(id = 1L, uri = "file:///1", title = "Alpha").copy(trackNumber = 1)
        val songB = song(id = 2L, uri = "file:///2", title = "Beta").copy(trackNumber = 1)

        val sorted = listOf(songB, songA).sortedWith(::compareSongsWithinAlbum)
        assertEquals(listOf(songA, songB), sorted)
    }

    @Test
    fun reorderAlbumQueueByTrackNumber_reordersUnsortedAlbumQueue() {
        val s1 = song(id = 1L, uri = "file:///1", title = "Track 1").copy(trackNumber = 1)
        val s2 = song(id = 2L, uri = "file:///2", title = "Track 2").copy(trackNumber = 2)
        val item1 = PlayableItem.Local(s1, "slot-1")
        val item2 = PlayableItem.Local(s2, "slot-2")

        val reordered = reorderAlbumQueueByTrackNumber(listOf(item2, item1), isShuffle = false)
        assertEquals(listOf(item1, item2), reordered)
    }

    @Test
    fun reorderAlbumQueueByTrackNumber_returnsNullWhenAlreadySorted() {
        val s1 = song(id = 1L, uri = "file:///1", title = "Track 1").copy(trackNumber = 1)
        val s2 = song(id = 2L, uri = "file:///2", title = "Track 2").copy(trackNumber = 2)
        val item1 = PlayableItem.Local(s1, "slot-1")
        val item2 = PlayableItem.Local(s2, "slot-2")

        val reordered = reorderAlbumQueueByTrackNumber(listOf(item1, item2), isShuffle = false)
        assertEquals(null, reordered)
    }

    @Test
    fun reorderAlbumQueueByTrackNumber_returnsNullWhenShuffledOrMixed() {
        val s1 = song(id = 1L, uri = "file:///1", title = "Track 1").copy(trackNumber = 1)
        val s2 = song(id = 2L, uri = "file:///2", title = "Track 2").copy(trackNumber = 2, album = "Different")
        val item1 = PlayableItem.Local(s1, "slot-1")
        val item2 = PlayableItem.Local(s2, "slot-2")

        // Shuffle active
        assertEquals(null, reorderAlbumQueueByTrackNumber(listOf(item2, item1), isShuffle = true))
        // Mixed albums
        assertEquals(null, reorderAlbumQueueByTrackNumber(listOf(item2, item1), isShuffle = false))
    }

    private fun song(id: Long, uri: String, title: String): Song = Song(
        id = id,
        uriString = uri,
        title = title,
        artist = "Artist",
        album = "Album"
    )
}
