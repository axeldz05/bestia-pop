package com.bestiapop.android.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilenameMetadataHintsTest {

    @Test
    fun parse_splitsFirstUnderscore() {
        val hints = parseFilenameMetadataHints("Radiohead_Creep")
        assertEquals("Radiohead", hints.artist)
        assertEquals("Creep", hints.title)
    }

    @Test
    fun parse_underscoresBecomeSpacesInParts() {
        val hints = parseFilenameMetadataHints("The_Beatles_Hey_Jude")
        assertEquals("The", hints.artist)
        assertEquals("Beatles Hey Jude", hints.title)
    }

    @Test
    fun parse_noUnderscore_titleOnly() {
        val hints = parseFilenameMetadataHints("SomeTrack")
        assertNull(hints.artist)
        assertEquals("SomeTrack", hints.title)
    }

    @Test
    fun applyFilenameHints_fillsUnknownArtist() {
        val tagged = AudioFileMetadata(
            title = "Radiohead_Creep",
            artist = "Unknown Artist",
            album = "Unknown Album",
            genre = "Music",
            durationMs = 1000L,
            artworkUri = null
        )
        val result = AudioFileMetadata.applyFilenameHints(tagged, "Radiohead_Creep")
        assertEquals("Radiohead", result.artist)
        assertEquals("Creep", result.title)
        assertEquals("Unknown Album", result.album)
    }

    @Test
    fun applyFilenameHints_keepsKnownTags() {
        val tagged = AudioFileMetadata(
            title = "Creep",
            artist = "Radiohead",
            album = "Pablo Honey",
            genre = "Rock",
            durationMs = 1000L,
            artworkUri = null
        )
        val result = AudioFileMetadata.applyFilenameHints(tagged, "Radiohead_Creep")
        assertEquals("Radiohead", result.artist)
        assertEquals("Creep", result.title)
        assertEquals("Pablo Honey", result.album)
    }
}
