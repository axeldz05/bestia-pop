package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.data.model.TrackIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerSimilarRadioTest {

    private fun song(id: Long, title: String, artist: String) = Song(
        id = id,
        uriString = "file:///song/$id",
        title = title,
        artist = artist,
        album = "Album",
        genre = "Rock",
        year = 2000,
        durationMs = 180_000L
    )

    private fun hint(title: String, artist: String, album: String? = null) =
        TrackIdentity(title = title, artist = artist, album = album.orEmpty())

    @Test
    fun suggestReturnsRemotesFromRadioAndRelated() = runBlocking {
        val radio = DeezerSimilarRadio(
            resolveArtistId = { 42L },
            fetchArtistRadio = {
                listOf(
                    hint("Radio One", "Artist A"),
                    hint("Radio Two", "Neighbor")
                )
            },
            fetchRelatedArtistIds = { _, _ -> listOf(99L) },
            fetchArtistTop = { id, _ ->
                if (id == 99L) listOf(hint("Top Hit", "Related Act")) else emptyList()
            },
            fetchItunesArtistSongs = { _, _ -> emptyList() }
        )
        val seedSong = song(1, "Seed", "Artist A")
        val result = radio.suggest(
            seed = seedSong.toPlayable(),
            library = listOf(seedSong),
            excludeKeys = emptySet(),
            limit = 10
        )

        assertTrue(result.all { it is PlayableItem.Remote })
        assertTrue(result.any { it.title == "Radio One" })
        assertTrue(result.any { it.title == "Top Hit" })
        val remote = result.filterIsInstance<PlayableItem.Remote>().first { it.title == "Radio One" }
        assertEquals("Artist A Radio One", remote.youtubeQueryOrId)
    }

    @Test
    fun skipsLibraryMatchesAndSeed() = runBlocking {
        val radio = DeezerSimilarRadio(
            resolveArtistId = { 1L },
            fetchArtistRadio = {
                listOf(
                    hint("Seed", "Artist A"),
                    hint("Known", "Artist A"),
                    hint("Fresh", "Artist B")
                )
            },
            fetchRelatedArtistIds = { _, _ -> emptyList() },
            fetchArtistTop = { _, _ -> emptyList() },
            fetchItunesArtistSongs = { _, _ -> emptyList() }
        )
        val known = song(2, "Known", "Artist A")
        val seed = song(1, "Seed", "Artist A")
        val result = radio.suggest(
            seed = seed.toPlayable(),
            library = listOf(seed, known),
            excludeKeys = emptySet(),
            limit = 10
        )

        assertEquals(1, result.size)
        assertEquals("Fresh", result.single().title)
        assertFalse(result.any { it.title == "Known" })
        assertFalse(result.any { it.title == "Seed" })
    }

    @Test
    fun itunesFillsWhenDeezerShort() = runBlocking {
        val radio = DeezerSimilarRadio(
            resolveArtistId = { 1L },
            fetchArtistRadio = { listOf(hint("Only One", "Artist A")) },
            fetchRelatedArtistIds = { _, _ -> emptyList() },
            fetchArtistTop = { _, _ -> emptyList() },
            fetchItunesArtistSongs = { _, _ ->
                listOf(
                    hint("Only One", "Artist A"),
                    hint("Seed", "Artist A"),
                    hint("iTunes Extra", "Artist A")
                )
            }
        )
        val result = radio.suggest(
            seed = song(1, "Seed", "Artist A").toPlayable(),
            library = emptyList(),
            excludeKeys = emptySet(),
            limit = 5
        )

        assertTrue(result.any { it.title == "Only One" })
        assertTrue(result.any { it.title == "iTunes Extra" })
        assertFalse(result.any { it.title == "Seed" })
    }

    @Test
    fun emptyWhenArtistUnresolved() = runBlocking {
        val radio = DeezerSimilarRadio(
            resolveArtistId = { null },
            fetchArtistRadio = { error("should not call") },
            fetchRelatedArtistIds = { _, _ -> error("should not call") },
            fetchArtistTop = { _, _ -> error("should not call") }
        )
        val result = radio.suggest(
            seed = song(1, "Seed", "Artist A").toPlayable(),
            library = emptyList(),
            excludeKeys = emptySet(),
            limit = 5
        )
        assertTrue(result.isEmpty())
    }
}
