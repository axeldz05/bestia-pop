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
            testSong(2, "The Beatles", lastPlayedAt = 0L),
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
}
