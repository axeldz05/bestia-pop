package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyAlbumBatchResolverTest {

    @Test
    fun isEligibleForAlbumBatchIdentify_validatesNonGeneric() {
        assertTrue(isEligibleForAlbumBatchIdentify("Muse", "Absolution"))
        assertTrue(isEligibleForAlbumBatchIdentify("Queen", "A Night at the Opera"))

        assertFalse(isEligibleForAlbumBatchIdentify("", "Absolution"))
        assertFalse(isEligibleForAlbumBatchIdentify("Unknown Artist", "Absolution"))
        assertFalse(isEligibleForAlbumBatchIdentify("Muse", ""))
        assertFalse(isEligibleForAlbumBatchIdentify("Muse", "Unknown Album"))
        assertFalse(isEligibleForAlbumBatchIdentify("Muse", "Single"))
        assertFalse(isEligibleForAlbumBatchIdentify("Muse", "Álbum"))
    }

    @Test
    fun partitionAlbumBatchSongs_separatesArtworkOnlyAndOtherGaps() {
        val s1 = song(1L, "Time is Running Out", "Muse", "Absolution", artworkUri = null, year = 2003, track = 3)
        val s2 = song(2L, "Hysteria", "Muse", "Absolution", artworkUri = null, year = 2003, track = 8)
        val s3 = song(3L, "- unknown track", "Muse", "Absolution", artworkUri = null, year = 2003, track = 9)

        val partition = partitionAlbumBatchSongs(
            songs = listOf(s1, s2, s3),
            batchFields = IdentifyApplyFields.ALL
        )

        assertEquals(listOf(1L, 2L), partition.artworkOnlyIds)
        assertEquals(listOf(3L), partition.otherGapsIds)

        // Verifies component destructuring
        val (artworkOnly, otherGaps) = partition
        assertEquals(listOf(1L, 2L), artworkOnly.map { it.id })
        assertEquals(listOf(3L), otherGaps.map { it.id })
    }

    @Test
    fun findAlbumBatchCandidates_groupsMultipleSongsAndIgnoresSinglesAndGeneric() {
        val muse1 = song(1L, "Stockholm Syndrome", "Muse", "Absolution", artworkUri = null)
        val muse2 = song(2L, "Hysteria", "Muse", "Absolution", artworkUri = null)
        val singleSong = song(3L, "Bohemian Rhapsody", "Queen", "A Night at the Opera", artworkUri = null)
        val generic1 = song(4L, "Track 1", "Unknown Artist", "Unknown Album", artworkUri = null)
        val generic2 = song(5L, "Track 2", "Unknown Artist", "Unknown Album", artworkUri = null)

        val candidates = findAlbumBatchCandidates(
            songs = listOf(muse1, muse2, singleSong, generic1, generic2),
            remainingIds = setOf(1L, 2L, 3L, 4L, 5L),
            inFlightIds = emptySet(),
            batchFields = IdentifyApplyFields.ALL,
            minGroupSize = 2
        )

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("Muse", candidate.artist)
        assertEquals("Absolution", candidate.album)
        assertEquals(listOf(1L, 2L), candidate.songIds)
        assertEquals(listOf(1L, 2L), candidate.artworkOnlyIds)
        assertTrue(candidate.otherGapsIds.isEmpty())
    }

    @Test
    fun findNextAlbumBatchCandidate_returnsFirstEligibleGroup() {
        val s1 = song(10L, "Track A", "Coldplay", "Parachutes", artworkUri = null)
        val s2 = song(11L, "Track B", "Coldplay", "Parachutes", artworkUri = null)

        val candidate = findNextAlbumBatchCandidate(
            availableSongs = listOf(s1, s2),
            batchFields = IdentifyApplyFields.ALL
        )

        assertNotNull(candidate)
        assertEquals("Coldplay", candidate?.artist)
        assertEquals("Parachutes", candidate?.album)
        assertEquals(listOf(10L, 11L), candidate?.songIds)
    }

    @Test
    fun findNextAlbumBatchCandidateForSong_returnsCandidateWhenAvailable() {
        val s1 = song(10L, "Track A", "Coldplay", "Parachutes", artworkUri = null)
        val s2 = song(11L, "Track B", "Coldplay", "Parachutes", artworkUri = null)

        val candidate = findNextAlbumBatchCandidateForSong(
            targetSong = s1,
            songs = listOf(s1, s2),
            remainingIds = setOf(10L, 11L),
            inFlightIds = emptySet(),
            batchFields = IdentifyApplyFields.ALL,
            minGroupSize = 2
        )

        assertNotNull(candidate)
        assertEquals("Coldplay", candidate?.artist)
        assertEquals("Parachutes", candidate?.album)
        assertEquals(listOf(10L, 11L), candidate?.songIds)
    }

    @Test
    fun findNextAlbumBatchCandidateForSong_returnsNullIfAlreadyAttemptedOrInFlight() {
        val s1 = song(10L, "Track A", "Coldplay", "Parachutes", artworkUri = null)
        val s2 = song(11L, "Track B", "Coldplay", "Parachutes", artworkUri = null)
        val key = albumGroupKey("Coldplay", "Parachutes")

        val candidateAttempted = findNextAlbumBatchCandidateForSong(
            targetSong = s1,
            songs = listOf(s1, s2),
            remainingIds = setOf(10L, 11L),
            inFlightIds = emptySet(),
            batchFields = IdentifyApplyFields.ALL,
            attemptedGroupKeys = setOf(key)
        )
        assertNull(candidateAttempted)

        val candidateInFlight = findNextAlbumBatchCandidateForSong(
            targetSong = s1,
            songs = listOf(s1, s2),
            remainingIds = setOf(10L, 11L),
            inFlightIds = setOf(11L),
            batchFields = IdentifyApplyFields.ALL
        )
        assertNull(candidateInFlight)
    }

    @Test
    fun buildKnownAlbumProposal_createsHighConfidenceProposal() {
        val song = song(1L, "Time is Running Out", "Muse", "Absolution", year = 2003, track = 3)
        val album = KnownAlbumTracks(
            key = "muse_absolution",
            artist = "Muse",
            album = "Absolution",
            artworkUri = "http://art.jpg",
            tracks = listOf(
                KnownAlbumTrack("Time is Running Out", durationMs = 237_000L, trackNumber = 3, year = 2003)
            )
        )
        val match = KnownAlbumMatch(album, album.tracks.first(), 0.95f)

        val proposal = buildKnownAlbumProposal(song, match)

        assertEquals(1L, proposal.songId)
        assertEquals("Muse", proposal.queryArtist)
        assertEquals("Time is Running Out", proposal.queryTitle)
        assertEquals(IdentifyConfidence.HIGH, proposal.confidence)
        assertNotNull(proposal.suggested)
        assertEquals("Time is Running Out", proposal.suggested?.title)
        assertEquals("Muse", proposal.suggested?.artist)
        assertEquals("Absolution", proposal.suggested?.album)
    }

    @Test
    fun matchAndApplyKnownAlbumTracks_appliesMatchesAndCollectsReview() = runBlocking {
        val s1 = song(1L, "Time is Running Out", "Muse", "Absolution", year = 2003, track = 3)
        val s2 = song(2L, "Apocalypse Please", "Muse", "Absolution", year = 2003, track = 1)
        val album = KnownAlbumTracks(
            key = "muse_absolution",
            artist = "Muse",
            album = "Absolution",
            artworkUri = "http://art.jpg",
            tracks = listOf(
                KnownAlbumTrack("Time is Running Out", durationMs = 237_000L, trackNumber = 3, year = 2003),
                KnownAlbumTrack("Apocalypse Please", durationMs = 252_000L, trackNumber = 1, year = 2003)
            )
        )

        val result = matchAndApplyKnownAlbumTracks(
            songs = listOf(s1, s2),
            knownAlbum = album,
            batchFields = IdentifyApplyFields.ALL,
            apply = { id, _, _ ->
                if (id == 1L) IdentifyResult.Updated(id) else IdentifyResult.NoMatch
            }
        )

        assertEquals(setOf(1L), result.appliedSongIds)
        assertEquals(listOf(2L), result.reviewProposals.map { it.songId })
        assertEquals(setOf(1L, 2L), result.completedSongIds)
    }

    private fun song(
        id: Long,
        title: String,
        artist: String,
        album: String,
        artworkUri: String? = null,
        year: Int = 2000,
        track: Int = 1
    ) = Song(
        id = id,
        uriString = "file://song-$id.mp3",
        title = title,
        artist = artist,
        album = album,
        artworkUri = artworkUri,
        year = year,
        trackNumber = track
    )
}
