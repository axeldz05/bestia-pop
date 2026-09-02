package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.AudioFileMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EmbeddedTagFillTest {

    @Test
    fun blackHoleUnknown_fillsFromFileAndUnifiesFlitterArtist() {
        val sibling = Song(
            id = 1L,
            uriString = "/a.mp3",
            title = "Alive",
            artist = "Namitape",
            album = "Flitter",
            genre = "Electronica"
        )
        val unknown = Song(
            id = 2L,
            uriString = "/b.mp3",
            title = "Black Hole",
            artist = "Unknown Artist",
            album = "Unknown Album",
            genre = "Music",
            artworkUri = "https://cdn.example/wrong.jpg",
            lyrics = "I'd rather be a light, not a black hole"
        )
        val meta = AudioFileMetadata(
            title = "ブラックホール / Black Hole",
            artist = "namitape; Kaai Yuki",
            album = "Flitter",
            genre = "Electronica; Vocaloid",
            durationMs = 214_204L,
            artworkUri = "file:///embedded.png",
            trackNumber = 2,
            year = 2023,
            lyrics = "目にブラックホールがあります"
        )
        val filled = fillSongGapsFromFileTags(unknown, meta, listOf(sibling))
        assertEquals("Namitape", filled.artist)
        assertEquals("Flitter", filled.album)
        assertEquals("ブラックホール / Black Hole", filled.title)
        assertEquals("Electronica; Vocaloid", filled.genre)
        assertEquals(2023, filled.year)
        assertEquals(2, filled.trackNumber)
        assertEquals("file:///embedded.png", filled.artworkUri)
        assertEquals("目にブラックホールがあります", filled.lyrics)
        assertFalse(needsGapIdentify(filled))
    }

    @Test
    fun alreadyIdentified_isNotOverwritten() {
        val known = Song(
            id = 3L,
            uriString = "/c.mp3",
            title = "Alive",
            artist = "Namitape",
            album = "Flitter",
            genre = "Electronica",
            year = 2023,
            lyrics = "kept"
        )
        val meta = AudioFileMetadata(
            title = "Other Title",
            artist = "Other Artist",
            album = "Other Album",
            genre = "Pop",
            durationMs = 1000L,
            artworkUri = "file:///x.png",
            year = 1999,
            lyrics = "nope"
        )
        val filled = fillSongGapsFromFileTags(known, meta, listOf(known))
        assertEquals("Namitape", filled.artist)
        assertEquals("Flitter", filled.album)
        assertEquals("Alive", filled.title)
        assertEquals("Electronica", filled.genre)
        assertEquals(2023, filled.year)
        assertEquals("kept", filled.lyrics)
    }

    @Test
    fun unknownDashDownload_splitsUsingKnownLibraryArtist() {
        val sibling = Song(
            id = 10L,
            uriString = "/known.mp3",
            title = "Light My Fire",
            artist = "The Doors",
            album = "The Doors"
        )
        val unknown = Song(
            id = 11L,
            uriString = "/storage/emulated/0/Music/BestiaPop/The_Doors_Roadhouse_Blues.m4a",
            title = "The Doors Roadhouse Blues",
            artist = "Unknown Artist",
            album = "Unknown Album",
            genre = "Music"
        )
        val meta = AudioFileMetadata(
            title = "The Doors Roadhouse Blues",
            artist = "Unknown Artist",
            album = "Unknown Album",
            genre = "Music",
            durationMs = 240_000L,
            artworkUri = null
        )
        val filled = fillSongGapsFromFileTags(unknown, meta, listOf(sibling))
        assertEquals("The Doors", filled.artist)
        assertEquals("Roadhouse Blues", filled.title)
        assertEquals("Unknown Album", filled.album)
    }
}
