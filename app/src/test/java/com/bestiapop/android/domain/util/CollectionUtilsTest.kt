package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionUtilsTest {

    private fun testSong(
        id: Long,
        artist: String,
        lastPlayedAt: Long = 0L
    ) = Song(
        id = id,
        uriString = "file:///song$id.mp3",
        title = "Track $id",
        artist = artist,
        album = "Album",
        durationMs = 180_000L,
        lastPlayedAt = lastPlayedAt
    )

    @Test
    fun interleaveEquitable_alternatesItemsCorrectly() {
        val listA = listOf("A1", "A2", "A3")
        val listB = listOf("B1", "B2", "B3")

        val result = CollectionUtils.interleaveEquitable(listA, listB, limit = 10)
        assertEquals(listOf("A1", "B1", "A2", "B2", "A3", "B3"), result)
    }

    @Test
    fun interleaveEquitable_drainsRemainingWhenUnbalanced() {
        val listA = listOf("A1")
        val listB = listOf("B1", "B2", "B3", "B4")

        val result = CollectionUtils.interleaveEquitable(listA, listB, limit = 4)
        assertEquals(listOf("A1", "B1", "B2", "B3"), result)
    }

    @Test
    fun interleaveEquitable_respectsLimitAndEmptyLists() {
        val empty: List<String> = emptyList()
        val list = listOf("X1", "X2")

        assertTrue(CollectionUtils.interleaveEquitable(empty, empty, limit = 5).isEmpty())
        assertEquals(listOf("X1"), CollectionUtils.interleaveEquitable(list, empty, limit = 1))
        assertTrue(CollectionUtils.interleaveEquitable(list, list, limit = 0).isEmpty())
    }

    @Test
    fun calculateTopLocalArtists_ranksByPlayScoreAndFiltersPlaceholders() {
        val songs = listOf(
            testSong(1, "The Beatles", lastPlayedAt = 1000L),
            testSong(2, "the beatles", lastPlayedAt = 0L), // case variation should combine!
            testSong(3, "Queen", lastPlayedAt = 0L),
            testSong(4, "Unknown Artist", lastPlayedAt = 5000L),
            testSong(5, "", lastPlayedAt = 5000L),
            testSong(6, "Pink Floyd", lastPlayedAt = 2000L)
        )
        val playStats = mapOf(
            1L to 1000L,
            6L to 2000L
        )

        val top = CollectionUtils.calculateTopLocalArtists(songs, playStats)

        // The Beatles: 10 (lastPlayed>0) + 1 = 11
        // Pink Floyd: 10 (lastPlayed>0) = 10
        // Queen: 1 = 1
        // "Unknown Artist" and blank should be filtered out
        assertEquals(listOf("The Beatles", "Pink Floyd", "Queen"), top)
    }

    @Test
    fun scoreLocalArtists_preservesArtworkAndScoresAccurately() {
        val songs = listOf(
            Song(
                id = 1L,
                uriString = "file:///song1.mp3",
                title = "Track 1",
                artist = "Daft Punk",
                album = "Discovery",
                durationMs = 200_000L,
                artworkUri = "content://art1",
                lastPlayedAt = 500L
            ),
            Song(
                id = 2L,
                uriString = "file:///song2.mp3",
                title = "Track 2",
                artist = "daft punk",
                album = "Homework",
                durationMs = 220_000L,
                artworkUri = null,
                lastPlayedAt = 0L
            )
        )
        val playStats = mapOf(1L to 500L)

        val scores = CollectionUtils.scoreLocalArtists(
            librarySongs = songs,
            playStats = playStats,
            playedWeight = 5L,
            unplayedWeight = 1L
        )

        val key = TrackMatchKeys.normalize("Daft Punk")
        val daftPunk = scores[key]
        org.junit.Assert.assertNotNull(daftPunk)
        assertEquals(6L, daftPunk!!.score) // 5L + 1L
        assertEquals("content://art1", daftPunk.artworkUri)
    }

    @Test
    fun scoreLocalAlbums_scoresAndPreservesArtwork() {
        val songs = listOf(
            Song(
                id = 1L,
                uriString = "file:///song1.mp3",
                title = "One More Time",
                artist = "Daft Punk",
                album = "Discovery",
                durationMs = 200_000L,
                artworkUri = "content://discovery_art",
                lastPlayedAt = 500L
            ),
            Song(
                id = 2L,
                uriString = "file:///song2.mp3",
                title = "Aerodynamic",
                artist = "Daft Punk",
                album = "Discovery",
                durationMs = 220_000L,
                artworkUri = null,
                lastPlayedAt = 0L
            ),
            Song(
                id = 3L,
                uriString = "file:///song3.mp3",
                title = "Around The World",
                artist = "Daft Punk",
                album = "Unknown Album", // Generic album filtered
                durationMs = 240_000L,
                lastPlayedAt = 500L
            )
        )
        val playStats = mapOf(1L to 500L)
        val albumScores = CollectionUtils.scoreLocalAlbums(songs, playStats, playedWeight = 5L, unplayedWeight = 1L)

        assertEquals(1, albumScores.size)
        val discovery = albumScores.values.first()
        assertEquals("Discovery", discovery.title)
        assertEquals("Daft Punk", discovery.artist)
        assertEquals(6L, discovery.score)
        assertEquals("content://discovery_art", discovery.artworkUri)
    }

    @Test
    fun scoreLocalTracks_scoresAccurately() {
        val songs = listOf(
            Song(
                id = 1L,
                uriString = "file:///song1.mp3",
                title = "One More Time",
                artist = "Daft Punk",
                album = "Discovery",
                durationMs = 200_000L,
                lastPlayedAt = 500L
            ),
            Song(
                id = 2L,
                uriString = "file:///song2.mp3",
                title = "Aerodynamic",
                artist = "Daft Punk",
                album = "Discovery",
                durationMs = 220_000L,
                lastPlayedAt = 0L
            )
        )
        val playStats = mapOf(1L to 500L)
        val trackScores = CollectionUtils.scoreLocalTracks(songs, playStats, playedWeight = 10L, unplayedWeight = 1L)

        assertEquals(2, trackScores.size)
        val omtKey = TrackMatchKeys.composeKey(TrackMatchKeys.normalize("Daft Punk"), TrackMatchKeys.normalize("One More Time"))
        assertEquals(10L, trackScores[omtKey]?.score)
    }

    @Test
    fun calculateSongPlayScore_prefersPlayStatsThenLastPlayedAt() {
        val songPlayedInStats = testSong(1L, "Artist", lastPlayedAt = 0L)
        val songPlayedInTags = testSong(2L, "Artist", lastPlayedAt = 1500L)
        val songUnplayed = testSong(3L, "Artist", lastPlayedAt = 0L)

        val playStats = mapOf(1L to 3000L)

        assertEquals(10L, CollectionUtils.calculateSongPlayScore(songPlayedInStats, playStats, playedWeight = 10L))
        assertEquals(10L, CollectionUtils.calculateSongPlayScore(songPlayedInTags, playStats, playedWeight = 10L))
        assertEquals(1L, CollectionUtils.calculateSongPlayScore(songUnplayed, playStats, playedWeight = 10L))
    }

    @Test
    fun recommendLocalAlbums_ranksAndLimits() {
        val songs = listOf(
            Song(id = 1L, uriString = "file:///s1.mp3", title = "T1", artist = "Daft Punk", album = "Discovery", durationMs = 200_000L, lastPlayedAt = 500L),
            Song(id = 2L, uriString = "file:///s2.mp3", title = "T2", artist = "The Beatles", album = "Abbey Road", durationMs = 180_000L, lastPlayedAt = 0L)
        )
        val playStats = mapOf(1L to 500L)
        val recs = CollectionUtils.recommendLocalAlbums(songs, playStats, limit = 1)
        assertEquals(1, recs.size)
        assertEquals("Discovery", recs.first().title)
    }

    @Test
    fun recommendLocalTracks_ranksAndLimits() {
        val songs = listOf(
            Song(id = 1L, uriString = "file:///s1.mp3", title = "Played Track", artist = "Daft Punk", album = "Discovery", durationMs = 200_000L, lastPlayedAt = 500L),
            Song(id = 2L, uriString = "file:///s2.mp3", title = "Unplayed Track", artist = "Daft Punk", album = "Discovery", durationMs = 220_000L, lastPlayedAt = 0L)
        )
        val playStats = mapOf(1L to 500L)
        val recs = CollectionUtils.recommendLocalTracks(songs, playStats, limit = 1)
        assertEquals(1, recs.size)
        assertEquals("Played Track", recs.first().title)
    }
}
