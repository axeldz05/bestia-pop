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

    @Test
    fun songWithIdentity_updatesSharedFieldsOnly() {
        val song = Song(
            id = 3L,
            uriString = "file:///x.m4a",
            title = "Old",
            artist = "Old Artist",
            album = "Old Album",
            genre = "Rock",
            durationMs = 1L,
            year = 1999,
            trackNumber = 1,
            artworkUri = null,
            lyrics = "keep",
            folderPath = "/kept",
            dateAdded = 9L
        )
        val updated = song.withIdentity(
            TrackIdentity(
                title = "New",
                artist = "New Artist",
                album = "New Album",
                artworkUri = "file:///art.jpg",
                durationMs = 120_000L,
                trackNumber = 5
            )
        )
        assertEquals("New", updated.title)
        assertEquals("New Artist", updated.artist)
        assertEquals("New Album", updated.album)
        assertEquals("file:///art.jpg", updated.artworkUri)
        assertEquals(120_000L, updated.durationMs)
        assertEquals(5, updated.trackNumber)
        assertEquals("Rock", updated.genre)
        assertEquals(1999, updated.year)
        assertEquals("keep", updated.lyrics)
        assertEquals("/kept", updated.folderPath)
        assertEquals("file:///x.m4a", updated.uriString)
        assertEquals(3L, updated.id)
    }

    @Test
    fun mergePreferring_candidateOverEntity_fillsGapsFromSong() {
        val candidate = TrackIdentity(
            title = "Creep",
            artist = "Radiohead",
            album = "",
            artworkUri = "https://art.example/c.jpg",
            durationMs = 238_000L,
            trackNumber = 0
        )
        val entity = TrackIdentity(
            title = "File Name",
            artist = "Unknown",
            album = "Pablo Honey",
            artworkUri = null,
            durationMs = 0L,
            trackNumber = 2
        )
        val merged = candidate.mergePreferring(entity)
        assertEquals("Creep", merged.title)
        assertEquals("Radiohead", merged.artist)
        assertEquals("Pablo Honey", merged.album)
        assertEquals("https://art.example/c.jpg", merged.artworkUri)
        assertEquals(238_000L, merged.durationMs)
        assertEquals(2, merged.trackNumber)
    }

    @Test
    fun toListenBrainzCatalogTrack_prefersMbidElseArtistTitle() {
        val identity = TrackIdentity(title = "Missing", artist = "Other", album = "Rel")
        val withMbid = identity.toListenBrainzCatalogTrack("mbid-xyz")
        assertEquals("mbid-xyz", withMbid.id)
        assertEquals("ListenBrainz", withMbid.provider)
        assertEquals("Missing", withMbid.title)

        val without = identity.toListenBrainzCatalogTrack(null)
        assertEquals("Other Missing", without.id)
        assertEquals("ListenBrainz", without.provider)
    }
}
