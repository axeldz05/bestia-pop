package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.LbPlaylistDetail
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.LbPlaylistTrack
import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchListenBrainzTracksUseCaseTest {

    private val useCase = MatchListenBrainzTracksUseCase()

    private fun song(id: Long, title: String, artist: String, album: String = "Album") = Song(
        id = id,
        uriString = "file:///$id",
        title = title,
        artist = artist,
        album = album,
        durationMs = 180_000L
    )

    @Test
    fun matchesLocalByNormalizedArtistTitle_andLeavesUnmatchedRemote() {
        val detail = LbPlaylistDetail(
            summary = LbPlaylistSummary(
                mbid = "pl-1",
                title = "Daily Jams",
                description = null,
                trackCount = 2
            ),
            tracks = listOf(
                LbPlaylistTrack(
                    title = "Local Hit",
                    artist = "Artist A",
                    album = "LP",
                    recordingMbid = "mbid-local"
                ),
                LbPlaylistTrack(
                    title = "Missing Song",
                    artist = "Other",
                    album = "EP",
                    recordingMbid = "mbid-remote"
                )
            )
        )
        val library = listOf(song(1, "Local Hit", "Artist A"))

        val matched = useCase.execute(detail, library)

        assertEquals(2, matched.matches.size)
        assertEquals(1, matched.matchedCount)
        assertEquals(1, matched.streamCount)
        assertEquals(1L, matched.matches[0].localSong?.id)
        assertEquals("mbid-local", matched.matches[0].recordingMbid)
        assertNull(matched.matches[1].localSong)
        assertEquals("Missing Song", matched.matches[1].title)
        assertEquals("mbid-remote", matched.matches[1].recordingMbid)
    }

    @Test
    fun punctuationAndCaseFold_stillMatchLibrary() {
        val detail = LbPlaylistDetail(
            summary = LbPlaylistSummary("pl", "T", null, 1),
            tracks = listOf(LbPlaylistTrack(title = "Canción!", artist = "The Band"))
        )
        val library = listOf(song(9, "cancion", "the band"))

        val matched = useCase.execute(detail, library)

        assertEquals(1, matched.matchedCount)
        assertEquals(9L, matched.matches.single().localSong?.id)
    }

    @Test
    fun emptyLibrary_allTracksAreRemote() {
        val detail = LbPlaylistDetail(
            summary = LbPlaylistSummary("pl", "T", null, 1),
            tracks = listOf(LbPlaylistTrack(title = "A", artist = "B"))
        )

        val matched = useCase.execute(detail, emptyList())

        assertTrue(matched.matches.isNotEmpty())
        assertEquals(0, matched.matchedCount)
        assertEquals(1, matched.streamCount)
    }
}
