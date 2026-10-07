package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.radio.LocalMetadataRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BuildSearchPlaybackQueueUseCaseTest {
    private fun createSong(
        id: Long,
        title: String,
        artist: String,
        genre: String = "Rock",
        year: Int = 2000,
    ) = Song(
        id = id,
        uriString = "file:///song/$id",
        title = title,
        artist = artist,
        album = "Album $id",
        genre = genre,
        year = year,
        durationMs = 180_000L,
    )

    @Test
    fun placesSeedFirstFollowedBySimilarSongsAndRemainingCollection() {
        val seed = createSong(1, "Seed Track", "Artist A", genre = "Rock", year = 2000)
        val similarArtist = createSong(2, "Track 2", "Artist A", genre = "Pop", year = 2005)
        val similarGenre = createSong(3, "Track 3", "Artist B", genre = "Rock", year = 2000)
        val unrelated1 = createSong(4, "Track 4", "Artist C", genre = "Classical", year = 1800)
        val unrelated2 = createSong(5, "Track 5", "Artist D", genre = "Electronic", year = 1990)

        val fullCollection = listOf(unrelated1, similarArtist, unrelated2, seed, similarGenre)

        val radio = LocalMetadataRadio(random = Random(42))
        val useCase = BuildSearchPlaybackQueueUseCase(localMetadataRadio = radio)

        val queue = useCase.execute(seedSong = seed, fullCollection = fullCollection, limitSimilar = 2)

        // Seed must be first
        assertEquals(seed.id, queue.first().id)
        // Must contain all 5 tracks without loss or duplicates
        assertEquals(5, queue.size)
        assertEquals(5, queue.map { it.id }.distinct().size)

        // Index 1 and 2 should be similar tracks
        val topSimilarIds = queue.subList(1, 3).map { it.id }.toSet()
        assertTrue(topSimilarIds.contains(similarArtist.id) || topSimilarIds.contains(similarGenre.id))

        // Remaining tracks must be the unrelated ones
        val remainingIds = queue.subList(3, 5).map { it.id }
        assertTrue(remainingIds.contains(unrelated1.id))
        assertTrue(remainingIds.contains(unrelated2.id))
    }

    @Test
    fun emptyCollectionReturnsOnlySeed() {
        val seed = createSong(1, "Alone", "Artist")
        val useCase = BuildSearchPlaybackQueueUseCase()

        val queue = useCase.execute(seedSong = seed, fullCollection = emptyList())

        assertEquals(listOf(seed), queue)
    }
}
