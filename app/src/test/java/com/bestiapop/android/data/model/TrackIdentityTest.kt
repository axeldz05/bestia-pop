package com.bestiapop.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackIdentityTest {

    @Test
    fun mergePreferring_keepsFilledFieldsAndFillsGaps() {
        val base = TrackIdentity(
            title = "Creep",
            artist = "",
            album = "",
            artworkUri = null,
            durationMs = 0L,
            trackNumber = 0
        )
        val other = TrackIdentity(
            title = "Other",
            artist = "Radiohead",
            album = "Pablo Honey",
            artworkUri = "https://art.example/a.jpg",
            durationMs = 238_000L,
            trackNumber = 4
        )
        val merged = base.mergePreferring(other)
        assertEquals("Creep", merged.title)
        assertEquals("Radiohead", merged.artist)
        assertEquals("Pablo Honey", merged.album)
        assertEquals("https://art.example/a.jpg", merged.artworkUri)
        assertEquals(238_000L, merged.durationMs)
        assertEquals(4, merged.trackNumber)
    }

    @Test
    fun mergePreferring_doesNotOverwritePositiveTrackNumber() {
        val base = TrackIdentity(title = "A", artist = "B", trackNumber = 2)
        val other = TrackIdentity(title = "A", artist = "B", trackNumber = 9)
        assertEquals(2, base.mergePreferring(other).trackNumber)
    }

    @Test
    fun songToIdentity_copiesSharedFieldsOnly() {
        val song = Song(
            id = 7L,
            uriString = "file:///a.m4a",
            title = "Digital Love",
            artist = "Daft Punk",
            album = "Discovery",
            genre = "Electronic",
            durationMs = 300_000L,
            year = 2001,
            trackNumber = 2003,
            artworkUri = "file:///art.jpg",
            lyrics = "lyrics",
            folderPath = "/music",
            dateAdded = 1L
        )
        val identity = song.toIdentity()
        assertEquals("Digital Love", identity.title)
        assertEquals("Daft Punk", identity.artist)
        assertEquals("Discovery", identity.album)
        assertEquals("file:///art.jpg", identity.artworkUri)
        assertEquals(300_000L, identity.durationMs)
        assertEquals(2003, identity.trackNumber)
        assertNull(identity.artworkUri?.takeIf { it.isBlank() })
    }
}
