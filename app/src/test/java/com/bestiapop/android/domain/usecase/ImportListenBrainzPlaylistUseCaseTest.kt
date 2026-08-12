package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.LbPlaylistDetail
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.LbPlaylistTrack
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.listenbrainz.toMatchedRemote
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.testutil.FakeMusicRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportListenBrainzPlaylistUseCaseTest {

    private val localA = Song(
        id = 1L,
        uriString = "/a",
        title = "Song A",
        artist = "Artist",
        album = "Album",
        durationMs = 1000L
    )
    private val localB = Song(
        id = 2L,
        uriString = "/b",
        title = "Song B",
        artist = "Artist",
        album = "Album",
        durationMs = 1000L
    )

    private fun matchedPlaylist(
        matched: List<Song>,
        unmatched: List<Pair<String, String>> = emptyList()
    ): MatchedLbPlaylist {
        val matchRows = matched.map {
            LbPlaylistTrack(title = it.title, artist = it.artist, album = it.album)
                .identity.toMatchedRemote(localSong = it)
        }
        val remoteRows = unmatched.map { (artist, title) ->
            LbPlaylistTrack(title = title, artist = artist, album = "Rel")
                .identity.toMatchedRemote(localSong = null)
        }
        return MatchedLbPlaylist(
            detail = LbPlaylistDetail(
                summary = LbPlaylistSummary(
                    mbid = "mbid-1",
                    title = "Daily Jams",
                    description = "From LB",
                    trackCount = matchRows.size + remoteRows.size
                ),
                tracks = (matchRows + remoteRows).map {
                    LbPlaylistTrack(identity = it.identity, recordingMbid = it.recordingMbid)
                }
            ),
            matches = matchRows + remoteRows
        )
    }

    @Test
    fun unmatchedCatalogTracks_mapsOnlyMissing() {
        val useCase = ImportListenBrainzPlaylistUseCase(FakeRepo())
        val matched = matchedPlaylist(
            matched = listOf(localA),
            unmatched = listOf("Other" to "Missing")
        )
        val tracks = useCase.unmatchedCatalogTracks(matched)
        assertEquals(1, tracks.size)
        assertEquals("Missing", tracks[0].title)
        assertEquals("Other", tracks[0].artist)
        assertEquals("Rel", tracks[0].album)
        assertEquals("Other Missing", tracks[0].id)
        assertEquals("", tracks[0].audioUrl)
        assertEquals("ListenBrainz", tracks[0].provider)
    }

    @Test
    fun createLocalFromMatched_addsMatchedAndPendingUnmatched() = runBlocking {
        val repo = FakeRepo()
        val useCase = ImportListenBrainzPlaylistUseCase(repo)
        val matched = matchedPlaylist(
            matched = listOf(localA, localB),
            unmatched = listOf("Other" to "Missing")
        )
        val id = useCase.createLocalFromMatched(matched)
        assertEquals(10L, id)
        assertEquals("Daily Jams", repo.createdName)
        assertEquals("From LB", repo.createdDescription)
        assertEquals(listOf(1L, 2L), repo.addedSongIds)
        assertEquals(1, repo.pendingTracks.size)
        assertEquals("Missing", repo.pendingTracks[0].title)
        assertEquals("Other", repo.pendingTracks[0].artist)
        assertEquals(10L, repo.pendingTracks[0].playlistId)
    }

    @Test
    fun createLocalFromMatched_onlyUnmatched_savesPending() = runBlocking {
        val repo = FakeRepo()
        val useCase = ImportListenBrainzPlaylistUseCase(repo)
        val matched = matchedPlaylist(matched = emptyList(), unmatched = listOf("A" to "B"))
        val id = useCase.createLocalFromMatched(matched)
        assertEquals(10L, id)
        assertTrue(repo.addedSongIds.isEmpty())
        assertEquals(1, repo.pendingTracks.size)
    }

    @Test
    fun createLocalFromMatched_returnsNullWhenNothing() = runBlocking {
        val useCase = ImportListenBrainzPlaylistUseCase(FakeRepo())
        val matched = matchedPlaylist(matched = emptyList(), unmatched = emptyList())
        assertNull(useCase.createLocalFromMatched(matched, allowEmpty = false))
    }

    private class FakeRepo : FakeMusicRepository() {
        var createdName: String? = null
        var createdDescription: String? = null
        val addedSongIds = mutableListOf<Long>()
        val pendingTracks = mutableListOf<PlaylistPendingTrack>()

        override suspend fun createPlaylist(
            name: String,
            description: String?,
            coverUri: String?
        ): Long {
            createdName = name
            createdDescription = description
            return 10L
        }

        override suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
            addedSongIds.add(songId)
        }

        override suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>) {
            pendingTracks.addAll(tracks)
        }
    }
}
