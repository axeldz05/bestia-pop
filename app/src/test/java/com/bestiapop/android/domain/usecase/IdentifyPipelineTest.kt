package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyPipelineTest {

    private fun testSong(
        id: Long = 1L,
        title: String = "Unknown Title",
        artist: String = "Unknown Artist",
        album: String = "Unknown Album",
        uriString: String = "/storage/emulated/0/Music/The_Doors_Break_On_Through.mp3",
        durationMs: Long = 148_000L
    ) = Song(
        id = id,
        uriString = uriString,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs
    )

    private fun sampleCatalogTrack(
        artist: String = "The Doors",
        title: String = "Break On Through",
        album: String = "The Doors",
        durationMs: Long = 148_000L,
        trackNumber: Int = 1
    ) = OnlineCatalogTrack(
        id = "track_1",
        identity = TrackIdentity(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            trackNumber = trackNumber
        ),
        provider = "test"
    )

    @Test
    fun parseLocalFile_extractsHintsFromFilename() {
        val song = testSong(
            uriString = "/storage/emulated/0/Music/The Doors - Break On Through.mp3",
            title = "The Doors - Break On Through"
        )
        val parsed = IdentifyPipeline.parseLocalFile(song)
        assertEquals("The Doors - Break On Through", parsed.baseName)
        assertEquals("The Doors", parsed.fileHints.artist)
        assertEquals("Break On Through", parsed.fileHints.title)
    }

    @Test
    fun narrowArtist_identifiesArtistFromLibrary() {
        val song = testSong(title = "The Doors Break On Through")
        val parsed = IdentifyPipeline.parseLocalFile(song)
        val narrowed = IdentifyPipeline.narrowArtist(parsed, listOf("The Doors"))
        assertEquals("The Doors", narrowed.queryArtist)
        assertEquals("Break On Through", narrowed.queryTitle)
    }

    @Test
    fun savedAlbums_takesPrecedenceAndPassesReviewGate() = runBlocking {
        val candidate = IdentifyCandidate(
            track = sampleCatalogTrack(),
            score = 0.95f,
            reasons = listOf("saved_album")
        )
        val result = IdentifyPipeline.execute(
            song = testSong(),
            libraryArtists = listOf("The Doors"),
            searchSavedAlbums = { _, _, _ -> candidate }
        )
        assertEquals(IdentifyConfidence.HIGH, result.proposal.confidence)
        assertEquals("The Doors", result.proposal.suggested?.artist)
        assertFalse(result.requiresReview)
    }

    @Test
    fun lowConfidence_requiresReviewGate() = runBlocking {
        val result = IdentifyPipeline.execute(
            song = testSong(),
            libraryArtists = emptyList(),
            searchCatalog = { _, _, _, _ ->
                listOf(sampleCatalogTrack(title = "Unrelated Song", durationMs = 500_000L))
            }
        )
        assertTrue(result.requiresReview)
    }

    @Test
    fun highConfidence_triggersAlbumSave() = runBlocking {
        var savedAlbum: Pair<String, String>? = null
        val catalogTrack = sampleCatalogTrack()
        val song = testSong(
            title = "Break On Through",
            artist = "The Doors",
            album = "The Doors",
            durationMs = 148_000L
        )
        val result = IdentifyPipeline.execute(
            song = song,
            libraryArtists = listOf("The Doors"),
            searchCatalog = { _, _, _, _ -> listOf(catalogTrack) },
            saveAlbumReference = { artist, album -> savedAlbum = artist to album }
        )
        assertEquals(IdentifyConfidence.HIGH, result.proposal.confidence)
        assertEquals("The Doors" to "The Doors", savedAlbum)
        assertFalse(result.requiresReview)
    }

    @Test
    fun enrichTrackNumber_updatesOnlyWhenMissing() {
        val candidateWithoutTrackNum = IdentifyCandidate(
            track = sampleCatalogTrack(trackNumber = 0),
            score = 0.9f
        )
        val enriched = IdentifyPipeline.enrichTrackNumber(listOf(candidateWithoutTrackNum), 5)
        assertEquals(5, enriched.first().track.identity.trackNumber)

        val candidateWithTrackNum = IdentifyCandidate(
            track = sampleCatalogTrack(trackNumber = 2),
            score = 0.9f
        )
        val unchanged = IdentifyPipeline.enrichTrackNumber(listOf(candidateWithTrackNum), 5)
        assertEquals(2, unchanged.first().track.identity.trackNumber)
    }

    @Test
    fun enrichTrackNumberIfMissing_invokesFetcherOnlyWhenNeeded() = runBlocking {
        var fetchCount = 0
        val candidateWithTrack = IdentifyCandidate(track = sampleCatalogTrack(trackNumber = 3), score = 0.9f)
        val result1 = IdentifyPipeline.enrichTrackNumberIfMissing(listOf(candidateWithTrack)) {
            fetchCount++
            10
        }
        assertEquals(0, fetchCount)
        assertEquals(3, result1.first().track.identity.trackNumber)

        val candidateWithoutTrack = IdentifyCandidate(track = sampleCatalogTrack(trackNumber = 0), score = 0.9f)
        val result2 = IdentifyPipeline.enrichTrackNumberIfMissing(listOf(candidateWithoutTrack)) {
            fetchCount++
            10
        }
        assertEquals(1, fetchCount)
        assertEquals(10, result2.first().track.identity.trackNumber)
    }
}
