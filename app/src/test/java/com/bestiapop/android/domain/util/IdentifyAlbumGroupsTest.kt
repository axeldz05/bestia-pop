package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.OnlineCatalogTrack
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
}
