package com.bestiapop.android.data.model

import com.bestiapop.android.ui.state.toPlayableItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayableItemCandidateMappingTest {

    @Test
    fun fromLibraryOrRemote_withLocal_returnsLocalPlayable() {
        val song = Song(
            id = 42L,
            uriString = "content://music/42",
            title = "Bohemian Rhapsody",
            artist = "Queen"
        )
        val identity = TrackIdentity(title = "Bohemian Rhapsody", artist = "Queen")

        val result = PlayableItem.fromLibraryOrRemote(
            local = song,
            identity = identity,
            youtubeQueryOrId = "Queen - Bohemian Rhapsody Official Video"
        )

        assertTrue(result is PlayableItem.Local)
        assertEquals(42L, (result as PlayableItem.Local).song.id)
    }

    @Test
    fun fromLibraryOrRemote_withoutLocal_returnsRemoteWithQuery() {
        val identity = TrackIdentity(title = "Stairway to Heaven", artist = "Led Zeppelin")

        val result = PlayableItem.fromLibraryOrRemote(
            local = null,
            identity = identity,
            youtubeQueryOrId = "https://youtube.com/watch?v=specific_id"
        )

        assertTrue(result is PlayableItem.Remote)
        val remote = result as PlayableItem.Remote
        assertEquals("https://youtube.com/watch?v=specific_id", remote.youtubeQueryOrId)
        assertEquals("Stairway to Heaven", remote.title)
        assertEquals("Led Zeppelin", remote.artist)
    }

    @Test
    fun toPlayableItems_mapsCandidatesCorrectlyPreservingQueries() {
        val localSong = Song(
            id = 101L,
            uriString = "content://music/101",
            title = "Track One",
            artist = "Artist A"
        )
        val index = mapOf(
            com.bestiapop.android.domain.util.TrackMatchKeys.matchKey("Artist A", "Track One") to localSong
        )

        val candidateLocal = CatalogTrackCandidate(
            identity = TrackIdentity(title = "Track One", artist = "Artist A"),
            candidates = listOf(
                OnlineCatalogTrack(
                    id = "yt_1",
                    title = "Track One",
                    artist = "Artist A",
                    audioUrl = "https://youtube.com/watch?v=yt_1"
                )
            )
        )

        val candidateRemote = CatalogTrackCandidate(
            identity = TrackIdentity(title = "Track Two", artist = "Artist B"),
            candidates = listOf(
                OnlineCatalogTrack(
                    id = "yt_2",
                    title = "Track Two",
                    artist = "Artist B",
                    audioUrl = "https://youtube.com/watch?v=yt_2"
                )
            )
        )

        val items = listOf(candidateLocal, candidateRemote).toPlayableItems(index)

        assertEquals(2, items.size)
        assertTrue("First item should be local", items[0] is PlayableItem.Local)
        assertEquals(101L, (items[0] as PlayableItem.Local).song.id)

        assertTrue("Second item should be remote", items[1] is PlayableItem.Remote)
        val remote = items[1] as PlayableItem.Remote
        assertEquals("https://youtube.com/watch?v=yt_2", remote.youtubeQueryOrId)
    }
}
