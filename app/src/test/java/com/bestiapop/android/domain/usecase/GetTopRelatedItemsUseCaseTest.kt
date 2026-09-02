package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetTopRelatedItemsUseCaseTest {

    private val useCase = GetTopRelatedItemsUseCase()

    @Test
    fun execute_withoutListenBrainz_aggregatesLocalLibrary() = runBlocking {
        val songs = listOf(
            Song(
                id = 1L,
                uriString = "/music/1.mp3",
                title = "Army of Me",
                artist = "Björk",
                album = "Post",
                lastPlayedAt = 1000L
            ),
            Song(
                id = 2L,
                uriString = "/music/2.mp3",
                title = "Hyperballad",
                artist = "Björk",
                album = "Post",
                lastPlayedAt = 2000L
            ),
            Song(
                id = 3L,
                uriString = "/music/3.mp3",
                title = "Karma Police",
                artist = "Radiohead",
                album = "OK Computer",
                lastPlayedAt = 0L
            )
        )
        val playStats = mapOf(1L to 5L, 2L to 10L, 3L to 1L)

        val feed = useCase.execute(
            librarySongs = songs,
            playStats = playStats,
            username = null,
            token = null
        )

        // Artists
        assertEquals(2, feed.topArtists.size)
        assertEquals("Björk", feed.topArtists[0].name)
        assertEquals("Local", feed.topArtists[0].source)
        assertTrue(feed.topArtists[0].playCount > feed.topArtists[1].playCount)

        // Albums
        assertEquals(2, feed.topAlbums.size)
        assertEquals("Post", feed.topAlbums[0].title)
        assertEquals("Björk", feed.topAlbums[0].artist)
        assertEquals("Local", feed.topAlbums[0].source)

        // Tracks
        assertEquals(3, feed.topTracks.size)
        assertEquals("Local", feed.topTracks[0].source)
    }

    @Test
    fun clearUserStatsCache_runsSafely() {
        com.bestiapop.android.data.network.ListenBrainzClient.clearUserStatsCache()
    }
}
