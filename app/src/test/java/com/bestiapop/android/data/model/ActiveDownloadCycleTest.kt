package com.bestiapop.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveDownloadCycleTest {

    private fun track(
        id: String,
        title: String = "Song",
        artist: String = "Artist"
    ) = OnlineCatalogTrack(
        id = id,
        title = title,
        artist = artist,
        album = "Album",
        artworkUri = null,
        durationMs = 1000L,
        audioUrl = id
    )

    private fun download(
        candidates: List<OnlineCatalogTrack>,
        index: Int = 0,
        state: CandidateDownloadState = CandidateDownloadState.ERROR
    ) = ActiveDownload(
        id = "job-1",
        source = ActiveDownloadSource.CATALOG,
        candidates = candidates,
        currentCandidateIndex = index,
        state = state,
        errorMessage = "fail"
    )

    @Test
    fun withCycledCandidate_rotatesIndexWithinExpandedList() {
        val a = track("a")
        val b = track("b")
        val c = track("c")
        val result = ActiveDownload.withCycledCandidate(
            download(listOf(a, b, c), index = 0),
            listOf(a, b, c)
        )
        assertEquals(1, result.currentCandidateIndex)
        assertEquals("b", result.currentTrack?.id)
        assertEquals(CandidateDownloadState.ERROR, result.state)
        assertTrue(result.errorMessage!!.contains("Reintentar"))
    }

    @Test
    fun withCycledCandidate_expandsSinglePlaceholderToSearchResults() {
        val placeholder = track("artist song")
        val yt1 = track("vid1", title = "Song (Official)")
        val yt2 = track("vid2", title = "Song Live")
        val result = ActiveDownload.withCycledCandidate(
            download(listOf(placeholder)),
            listOf(yt1, yt2)
        )
        assertNotEquals(placeholder.id, result.currentTrack?.id)
        assertEquals(2, result.candidates.size)
        assertEquals(CandidateDownloadState.ERROR, result.state)
    }

    @Test
    fun withCycledCandidate_fromIdleKeepsIdle() {
        val a = track("a")
        val b = track("b")
        val result = ActiveDownload.withCycledCandidate(
            download(listOf(a, b), state = CandidateDownloadState.IDLE),
            listOf(a, b)
        )
        assertEquals(CandidateDownloadState.IDLE, result.state)
        assertEquals(null, result.errorMessage)
        assertEquals("b", result.currentTrack?.id)
    }

    @Test
    fun laneAndInFlightPredicates_keepSourceAndStateSemanticsExplicit() {
        assertEquals(DownloadLane.AUTOSAVE, ActiveDownloadSource.SAVE_WHILE_LISTENING.lane)
        assertEquals(DownloadLane.EXPLICIT, ActiveDownloadSource.DISCOVER.lane)
        assertTrue(CandidateDownloadState.QUEUED.isInFlight)
        assertTrue(CandidateDownloadState.DOWNLOADING.isInFlight)
        assertTrue(CandidateDownloadState.ERROR.isFailed)
    }

    @Test
    fun asError_preservesDurableExecutionContext() {
        val identity = TrackIdentity(title = "Song", artist = "Artist")
        val target = DownloadPlaylistDestination(7L, identity)
        val active = download(listOf(track("a")), state = CandidateDownloadState.DOWNLOADING)
            .copy(
                playlistTargets = listOf(target),
                lookupIdentity = identity,
                downloadStarted = true,
                storageCommitted = true,
                batchId = "batch-1"
            )

        val failed = active.asError("offline", interrupted = true)

        assertEquals(CandidateDownloadState.ERROR, failed.state)
        assertEquals("offline", failed.errorMessage)
        assertTrue(failed.interrupted)
        assertEquals(listOf(target), failed.playlistTargets)
        assertTrue(failed.storageCommitted)
        assertEquals("batch-1", failed.batchId)
    }

    @Test
    fun playlistDestinations_fallBackToLegacyTargetOnlyWhenNeeded() {
        val identity = TrackIdentity(title = "Song", artist = "Artist")
        val explicit = DownloadPlaylistDestination(8L, identity)

        assertEquals(
            listOf(explicit),
            resolveDownloadPlaylistDestinations(listOf(explicit), 7L, identity)
        )
        assertEquals(
            listOf(DownloadPlaylistDestination(7L, identity)),
            resolveDownloadPlaylistDestinations(emptyList(), 7L, identity)
        )
    }
}
