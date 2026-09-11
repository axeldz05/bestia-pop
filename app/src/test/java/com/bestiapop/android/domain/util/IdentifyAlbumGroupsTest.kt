package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyAlbumGroupsTest {

    private fun track(
        artist: String,
        title: String,
        album: String,
        artworkUri: String? = null
    ) = OnlineCatalogTrack(
        id = "$artist|$title|$album",
        title = title,
        artist = artist,
        album = album,
        artworkUri = artworkUri,
        durationMs = 200_000L,
        audioUrl = "https://cdn.example/x",
        provider = "Deezer"
    )

    private fun proposal(
        songId: Long,
        title: String,
        album: String,
        artist: String = "Muse",
        confidence: IdentifyConfidence = IdentifyConfidence.MEDIUM,
        artworkUri: String? = "https://img.example/a.jpg"
    ): IdentifyProposal {
        val candidate = IdentifyCandidate(
            track = track(artist, title, album, artworkUri),
            score = 0.7f,
            reasons = listOf("título similar")
        )
        return IdentifyProposal(
            songId = songId,
            queryArtist = "Unknown Artist",
            queryTitle = title,
            candidates = listOf(candidate),
            confidence = confidence,
            suggested = candidate
        )
    }

    @Test
    fun clustersDeluxeAndCaseVariants() {
        val groups = clusterIdentifyAlbumGroups(
            listOf(
                proposal(1, "Hysteria", "Absolution"),
                proposal(2, "Time Is Running Out", "absolution (deluxe)"),
                proposal(3, "Stockholm Syndrome", "Absolution (Remastered 2012)")
            )
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].songIds.size)
    }

    @Test
    fun clustersMediumSameAlbum() {
        val groups = clusterIdentifyAlbumGroups(
            listOf(
                proposal(1, "Time Is Running Out", "Absolution"),
                proposal(2, "Hysteria", "absolution"),
                proposal(3, "Stockholm Syndrome", "Absolution")
            )
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].songIds.size)
        assertEquals("Muse", groups[0].artist)
        assertEquals("Absolution", groups[0].album)
        assertEquals("https://img.example/a.jpg", groups[0].artworkUri)
    }

    @Test
    fun sizeOneIsNotAGroup() {
        val groups = clusterIdentifyAlbumGroups(
            listOf(proposal(1, "Hysteria", "Absolution"))
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun genericAlbumExcluded() {
        val groups = clusterIdentifyAlbumGroups(
            listOf(
                proposal(1, "A", "YouTube Music"),
                proposal(2, "B", "YouTube Music"),
                proposal(3, "C", "Unknown Album"),
                proposal(4, "D", "Unknown Album")
            )
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun lowAndNoneDoNotGroup() {
        val groups = clusterIdentifyAlbumGroups(
            listOf(
                proposal(1, "Hysteria", "Absolution", confidence = IdentifyConfidence.LOW),
                proposal(2, "Time Is Running Out", "Absolution", confidence = IdentifyConfidence.LOW),
                proposal(3, "Alone", "Absolution", confidence = IdentifyConfidence.NONE)
            )
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun differentArtistsDoNotMix() {
        val groups = clusterIdentifyAlbumGroups(
            listOf(
                proposal(1, "Hysteria", "Absolution", artist = "Muse"),
                proposal(2, "Creep", "Absolution", artist = "Radiohead")
            )
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun clustersFromSources_separatesArtworkOnlyFromOtherGaps() {
        val song1 = Song(
            id = 101L,
            uriString = "content://media/audio/101",
            title = "Hysteria",
            artist = "Muse",
            album = "Absolution",
            year = 2003,
            artworkUri = null
        )
        val song2 = Song(
            id = 102L,
            uriString = "content://media/audio/102",
            title = "02 - Time Is Running Out",
            artist = "Muse",
            album = "Absolution",
            year = 2003,
            artworkUri = null
        )
        val sources = listOf(
            IdentifyAlbumGroupSource(song1, proposal(101L, "Hysteria", "Absolution")),
            IdentifyAlbumGroupSource(song2, proposal(102L, "Time Is Running Out", "Absolution"))
        )
        val applyFields = com.bestiapop.android.data.model.IdentifyApplyFields(
            title = true,
            artwork = true,
            artist = false,
            album = false,
            year = false,
            trackNumber = false
        )

        val groups = clusterIdentifyAlbumGroupsFromSources(sources, applyFields)
        assertEquals(1, groups.size)
        val group = groups.first()
        assertEquals(listOf(101L, 102L), group.songIds)
        assertEquals(listOf(101L), group.artworkOnlySongIds)
        assertEquals(listOf(102L), group.otherGapsSongIds)
        assertEquals("Absolution", group.proposedAlbum)
        assertEquals("Muse", group.proposedArtist)
    }

    @Test
    fun clustersFromSources_mergesSearchedCandidatesAndAppliesSelection() {
        val song1 = Song(
            id = 1L,
            uriString = "content://media/audio/1",
            title = "Hysteria",
            artist = "Muse",
            album = "Absolution",
            artworkUri = null
        )
        val song2 = Song(
            id = 2L,
            uriString = "content://media/audio/2",
            title = "Time Is Running Out",
            artist = "Muse",
            album = "Absolution",
            artworkUri = null
        )
        val sources = listOf(
            IdentifyAlbumGroupSource(song1, proposal(1L, "Hysteria", "Absolution")),
            IdentifyAlbumGroupSource(song2, proposal(2L, "Time Is Running Out", "Absolution"))
        )

        val searchedAlbum = com.bestiapop.android.data.model.CatalogAlbum(
            id = "custom_search",
            title = "Absolution (Tour Edition)",
            artist = "Muse",
            coverUrl = "https://img.example/tour.jpg",
            trackCount = 14,
            releaseYear = "2004"
        )
        val key = albumGroupKey("Muse", "Absolution")

        val groups = clusterIdentifyAlbumGroupsFromSources(
            sources = sources,
            searchedCandidates = mapOf(key to listOf(searchedAlbum)),
            selectedCandidateIndices = mapOf(key to 0)
        )

        assertEquals(1, groups.size)
        val group = groups.first()
        assertEquals(0, group.selectedCandidateIndex)
        assertEquals("Absolution (Tour Edition)", group.selectedCandidate?.title)
        assertEquals("https://img.example/tour.jpg", group.selectedCandidate?.coverUrl)
    }
}

