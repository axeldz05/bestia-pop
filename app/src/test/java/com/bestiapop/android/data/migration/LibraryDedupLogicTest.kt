package com.bestiapop.android.data.migration

import com.bestiapop.android.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDedupLogicTest {

    private fun song(
        id: Long,
        title: String,
        artist: String,
        uri: String,
        folder: String = "",
        durationMs: Long = 1000L
    ) = SongEntity(
        id = id,
        uriString = uri,
        title = title,
        artist = artist,
        album = "Album",
        genre = "Music",
        durationMs = durationMs,
        year = 0,
        trackNumber = 0,
        artworkUri = null,
        lyrics = null,
        folderPath = folder,
        dateAdded = id
    )

    @Test
    fun selectKeeper_prefersAppOwnedOverMediaStore() {
        val media = song(
            1,
            "Hole",
            "65daysofstatic",
            "content://media/external/audio/media/1",
            "/storage/emulated/0/Music/BestiaPop/hole.mp3",
            durationMs = 5000
        )
        val file = song(
            2,
            "Hole",
            "65daysofstatic",
            "file:/storage/emulated/0/Music/BestiaPop/hole.mp3",
            "/storage/emulated/0/Music/BestiaPop",
            durationMs = 1000
        )
        assertEquals(2L, LibraryDedupLogic.selectKeeper(listOf(media, file)).id)
    }

    @Test
    fun selectKeeper_amongFilesPrefersLongerDurationThenLowerId() {
        val a = song(10, "AOD", "65daysofstatic", "/storage/emulated/0/Music/BestiaPop/a.mp3", durationMs = 100)
        val b = song(11, "AOD", "65daysofstatic", "/storage/emulated/0/Music/BestiaPop/b.mp3", durationMs = 200)
        assertEquals(11L, LibraryDedupLogic.selectKeeper(listOf(a, b)).id)
    }

    @Test
    fun groupDuplicates_usesNormalizedMatchKey() {
        val groups = LibraryDedupLogic.groupDuplicates(
            listOf(
                song(1, "No Station!", "65daysofstatic", "file:/x/a.mp3", "/x"),
                song(2, "No Station", "65daysofstatic", "content://media/1", "/x/a.mp3"),
                song(3, "Other", "Artist", "/y/z.mp3")
            )
        )
        assertEquals(1, groups.size)
        assertEquals(1, groups.first().losers.size)
        assertTrue(groups.first().keeper.id in setOf(1L, 2L))
    }

    @Test
    fun normalizedKeeperUri_convertsFileUri() {
        val keeper = song(
            1,
            "T",
            "A",
            "file:/storage/emulated/0/Music/BestiaPop/t.mp3",
            "/storage/emulated/0/Music/BestiaPop"
        )
        assertEquals(
            "/storage/emulated/0/Music/BestiaPop/t.mp3",
            LibraryDedupLogic.normalizedKeeperUri(keeper)
        )
    }

    @Test
    fun shouldSkipMediaStoreBestiaPop_pathCheck() {
        // Mirrors scanMediaStore filter using the same helper
        assertTrue(
            com.bestiapop.android.data.util.SongPathNormalizer.isUnderBestiaPop(
                "/storage/emulated/0/Music/BestiaPop/track.mp3"
            )
        )
    }
}
