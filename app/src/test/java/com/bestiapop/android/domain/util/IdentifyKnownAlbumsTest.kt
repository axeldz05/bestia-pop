package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyKnownAlbumsTest {

    private fun song(
        id: Long,
        title: String,
        artist: String = "Ciro y Los Persas",
        album: String = "Espejos",
        durationMs: Long = 200_000L,
        trackNumber: Int = 0,
        folderPath: String = ""
    ) = Song(
        id = id,
        uriString = "file:///tmp/$id.mp3",
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        trackNumber = trackNumber,
        folderPath = folderPath
    )

    private fun espejosLibrary() = knownAlbumsFromLibrary(
        listOf(
            song(1, "Antes y Después", trackNumber = 1, durationMs = 210_000L),
            song(2, "Míralo", trackNumber = 2, durationMs = 190_000L),
            song(3, "Astros", trackNumber = 3, durationMs = 230_000L)
        )
    )

    private fun espejosAlbum(
        tracks: List<KnownAlbumTrack> = listOf(
            KnownAlbumTrack("Antes y Después", durationMs = 210_000L, trackNumber = 1),
            KnownAlbumTrack("Míralo", durationMs = 190_000L, trackNumber = 2),
            KnownAlbumTrack("Astros", durationMs = 230_000L, trackNumber = 3)
        )
    ) = KnownAlbumTracks(
        key = albumGroupKey("Ciro y Los Persas", "Espejos"),
        artist = "Ciro y Los Persas",
        album = "Espejos",
        artworkUri = "file:///espejos.jpg",
        tracks = tracks
    )

    @Test
    fun indexesNonGenericLibraryAlbums() {
        val albums = knownAlbumsFromLibrary(
            listOf(
                song(1, "Antes y Después"),
                song(2, "Míralo"),
                song(10, "Rip", artist = "Unknown Artist", album = "Unknown Album"),
                song(11, "Video", artist = "Someone", album = "YouTube Music")
            )
        )
        assertEquals(1, albums.size)
        assertEquals("Espejos", albums.single().album)
        assertEquals(2, albums.single().tracks.size)
    }

    @Test
    fun uniqueTitleMatchesEspejos() {
        val album = espejosLibrary().single()
        val match = matchSongToKnownAlbum(
            KnownAlbumQuery(songId = 99, title = "Antes y Después", durationMs = 211_000L),
            album
        )
        assertEquals("Antes y Después", match?.track?.title)
        assertTrue(match!!.score >= IdentifyRanking.MEDIUM_SCORE)
    }

    @Test
    fun globalDoesNotAssignWhenTitleExistsOnTwoAlbums() {
        val albums = knownAlbumsFromLibrary(
            listOf(
                song(1, "Hysteria", artist = "Muse", album = "Absolution"),
                song(2, "Hysteria", artist = "Def Leppard", album = "High 'n' Dry")
            )
        )
        val assigned = assignUniqueKnownAlbumMatches(
            queries = listOf(KnownAlbumQuery(songId = 50, title = "Hysteria", durationMs = 220_000L)),
            albums = albums,
            scoped = false
        )
        assertTrue(assigned.isEmpty())
    }

    @Test
    fun twoFilesSameTrack_betterDurationWins() {
        val album = espejosAlbum()
        val assigned = assignUniqueKnownAlbumMatches(
            queries = listOf(
                KnownAlbumQuery(songId = 10, title = "Míralo", durationMs = 204_000L),
                KnownAlbumQuery(songId = 11, title = "Míralo", durationMs = 191_000L)
            ),
            albums = listOf(album),
            scoped = true
        )
        assertEquals(setOf(11L), assigned.keys)
        assertEquals("Míralo", assigned.getValue(11L).track.title)
    }

    @Test
    fun scopedMatchesCatalogTracklistWhenLibraryIsIncomplete() {
        val libraryOnly = knownAlbumsFromLibrary(
            listOf(song(1, "Antes y Después", durationMs = 210_000L, trackNumber = 1))
        ).single()
        val catalog = listOf(
            KnownAlbumTrack("Antes y Después", durationMs = 210_000L, trackNumber = 1),
            KnownAlbumTrack("Míralo", durationMs = 190_000L, trackNumber = 2),
            KnownAlbumTrack("Astros", durationMs = 230_000L, trackNumber = 3)
        )
        val merged = mergeKnownAlbumTracks(
            artist = "Ciro y Los Persas",
            album = "Espejos",
            library = libraryOnly,
            catalog = catalog
        )!!
        assertEquals(3, merged.tracks.size)
        val assigned = assignUniqueKnownAlbumMatches(
            queries = listOf(
                KnownAlbumQuery(songId = 20, title = "Míralo", durationMs = 189_000L),
                KnownAlbumQuery(songId = 21, title = "Astros", durationMs = 231_000L)
            ),
            albums = listOf(merged),
            scoped = true
        )
        assertEquals(setOf(20L, 21L), assigned.keys)
        assertEquals("Míralo", assigned.getValue(20L).track.title)
        assertEquals("Astros", assigned.getValue(21L).track.title)
    }

    @Test
    fun placeholderAndGenericAlbumDoNotIndex() {
        val albums = knownAlbumsFromLibrary(
            listOf(
                song(1, "A", artist = "Unknown Artist", album = "Unknown Album"),
                song(2, "B", artist = "Unknown Artist", album = "Unknown Album")
            )
        )
        assertTrue(albums.isEmpty())
    }

    @Test
    fun junkTitlesDoNotMatch() {
        val album = espejosAlbum()
        assertNull(
            matchSongToKnownAlbum(KnownAlbumQuery(songId = 3, title = "01"), album)
        )
        assertNull(
            matchSongToKnownAlbum(KnownAlbumQuery(songId = 4, title = "Track 5"), album)
        )
        assertNull(
            matchSongToKnownAlbum(
                KnownAlbumQuery(songId = 5, title = "/storage/emulated/0/Music/x.mp3"),
                album
            )
        )
    }

    @Test
    fun durationMismatchRejectsExactTitle() {
        val album = espejosAlbum()
        assertNull(
            matchSongToKnownAlbum(
                KnownAlbumQuery(songId = 8, title = "Míralo", durationMs = 400_000L),
                album
            )
        )
    }

    @Test
    fun promoteAttachesMediumSuggestion() {
        val proposal = IdentifyProposal(
            songId = 20L,
            queryArtist = "Unknown Artist",
            queryTitle = "Astros",
            confidence = IdentifyConfidence.NONE
        )
        val promoted = promoteKnownAlbumMatches(
            proposals = listOf(proposal),
            queries = listOf(KnownAlbumQuery(songId = 20L, title = "Astros", durationMs = 230_000L)),
            albums = listOf(espejosAlbum())
        )
        assertEquals(IdentifyConfidence.MEDIUM, promoted.single().confidence)
        assertEquals("Astros", promoted.single().suggested?.title)
        assertEquals("Espejos", promoted.single().suggested?.album)
        assertTrue(promoted.single().suggested!!.reasons.contains(KNOWN_ALBUM_REASON))
    }

    @Test
    fun knownAlbumQueryUsesFilenameTitle() {
        val query = knownAlbumQueryOf(
            song(
                id = 4,
                title = "05 - Astros",
                artist = "Unknown Artist",
                album = "Unknown Album"
            )
        )
        assertEquals("Astros", query.title)
        assertEquals(5, query.trackNumber)
    }
}
