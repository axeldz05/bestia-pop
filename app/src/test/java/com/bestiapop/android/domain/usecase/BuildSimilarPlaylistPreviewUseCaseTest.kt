package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.domain.radio.RadioEngine
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.testutil.FakeMusicRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildSimilarPlaylistPreviewUseCaseTest {

    private fun song(id: Long, title: String, artist: String, genre: String = "Rock") = Song(
        id = id,
        uriString = "file:///$id",
        title = title,
        artist = artist,
        album = "Album",
        genre = genre,
        durationMs = 180_000L
    )

    @Test
    fun execute_knownMode_returnsLocalSuggestionsFromEngine() = runBlocking {
        val seed = song(1, "Seed", "Artist")
        val neighbor = song(2, "Neighbor", "Artist")
        val repo = RecordingRepo(coPlaylist = mapOf(1L to setOf(2L)))
        val useCase = BuildSimilarPlaylistPreviewUseCase(RadioEngine(), repo)

        val preview = useCase.execute(
            seeds = listOf(PlayableItem.Local(seed)),
            library = listOf(seed, neighbor),
            mode = RadioMode.KNOWN,
            limit = 5
        )

        assertEquals(RadioMode.KNOWN, preview.mode)
        assertFalse(preview.usedOnline)
        assertFalse(preview.failedOnline)
        assertTrue(preview.items.any { it is PlayableItem.Local && it.song.id == 2L })
    }

    @Test
    fun createPlaylistFromPlayables_addsLocalsAndPendingRemotes_withoutCdn() = runBlocking {
        val repo = RecordingRepo()
        val useCase = BuildSimilarPlaylistPreviewUseCase(RadioEngine(), repo)
        val local = PlayableItem.Local(song(1, "Local", "A"))
        val remote = PlayableItem.Remote(
            identity = TrackIdentity(title = "Remote", artist = "B", album = "EP"),
            recordingMbid = "mbid-1",
            youtubeQueryOrId = "B Remote"
        )

        val id = useCase.createPlaylistFromPlayables("Similares · A", listOf(local, remote))

        assertEquals(10L, id)
        assertEquals("Similares · A", repo.createdName)
        assertEquals(listOf(1L), repo.addedSongIds)
        assertEquals(1, repo.pendingTracks.size)
        assertEquals("Remote", repo.pendingTracks[0].title)
        assertEquals("B", repo.pendingTracks[0].artist)
        assertEquals("mbid-1", repo.pendingTracks[0].recordingMbid)
        assertEquals(10L, repo.pendingTracks[0].playlistId)
    }

    @Test
    fun createPlaylistFromPlayables_emptyWithoutAllow_returnsNull() = runBlocking {
        val useCase = BuildSimilarPlaylistPreviewUseCase(RadioEngine(), RecordingRepo())
        assertNull(useCase.createPlaylistFromPlayables("X", emptyList(), allowEmpty = false))
    }

    @Test
    fun defaultPlaylistName_usesFirstArtistOrSeedCount() {
        val withArtist = listOf(
            PlayableItem.Remote(identity = TrackIdentity(title = "T", artist = "Queen"))
        )
        assertEquals("Similares · Queen", BuildSimilarPlaylistPreviewUseCase.defaultPlaylistName(withArtist))
        assertEquals("Similares (3 seeds)", BuildSimilarPlaylistPreviewUseCase.defaultPlaylistName(3))
        assertEquals("Similares", BuildSimilarPlaylistPreviewUseCase.defaultPlaylistName(0))
    }

    private class RecordingRepo(
        private val coPlaylist: Map<Long, Set<Long>> = emptyMap()
    ) : FakeMusicRepository() {
        var createdName: String? = null
        val addedSongIds = mutableListOf<Long>()
        val pendingTracks = mutableListOf<PlaylistPendingTrack>()

        override suspend fun createPlaylist(name: String, description: String?, coverUri: String?): Long {
            createdName = name
            return 10L
        }

        override suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
            addedSongIds.add(songId)
        }

        override suspend fun getCoPlaylistSongIds(songId: Long): Set<Long> =
            coPlaylist[songId].orEmpty()

        override suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>) {
            pendingTracks.addAll(tracks)
        }
    }
}
