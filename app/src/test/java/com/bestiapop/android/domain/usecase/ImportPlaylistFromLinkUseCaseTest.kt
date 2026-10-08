package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.ImportedPlaylistData
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.PlaylistPlatform
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.testutil.FakeMusicRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlaylistFromLinkUseCaseTest {
    private class RecordingFakeMusicRepository : FakeMusicRepository() {
        val createdPlaylists = mutableListOf<Triple<String, String?, String?>>()
        val addedLocalSongIds = mutableListOf<Pair<Long, Long>>()
        val addedPendingTracks = mutableListOf<PlaylistPendingTrack>()

        val songsFlow = MutableStateFlow<List<Song>>(emptyList())
        override val allSongsFlow: Flow<List<Song>> = songsFlow

        override suspend fun createPlaylist(
            name: String,
            description: String?,
            coverUri: String?,
        ): Long {
            createdPlaylists.add(Triple(name, description, coverUri))
            return 42L
        }

        override suspend fun addSongToPlaylist(
            playlistId: Long,
            songId: Long,
        ) {
            addedLocalSongIds.add(playlistId to songId)
        }

        override suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>) {
            addedPendingTracks.addAll(tracks)
        }
    }

    @Test
    fun execute_matchesLocalSongsAndCreatesPendingForRemotes() =
        runBlocking {
            val repo = RecordingFakeMusicRepository()
            val localSong =
                Song(
                    id = 101L,
                    title = "Local Song",
                    artist = "Local Artist",
                    album = "Album",
                    uriString = "/path/to/song.mp3",
                    durationMs = 180000L,
                )
            repo.songsFlow.value = listOf(localSong)

            val useCase = ImportPlaylistFromLinkUseCase(repo)

            val playlistData =
                ImportedPlaylistData(
                    id = "pl-1",
                    title = "Mi Playlist Importada",
                    coverUrl = "https://cover.jpg",
                    platform = PlaylistPlatform.SPOTIFY,
                    tracks =
                        listOf(
                            TrackIdentity(
                                title = "Local Song",
                                artist = "Local Artist",
                                album = "Album",
                                durationMs = 180000L,
                            ),
                            TrackIdentity(
                                title = "Remote Track",
                                artist = "Remote Artist",
                                album = "Remote Album",
                                durationMs = 210000L,
                            ),
                        ),
                )

            val summary = useCase.execute(playlistData, isDownloading = false)

            assertEquals(42L, summary.playlistId)
            assertEquals("Mi Playlist Importada", summary.playlistTitle)
            assertEquals(2, summary.totalTracks)
            assertEquals(1, summary.matchedLocalCount)
            assertEquals(1, summary.pendingStreamCount)

            // Verified repository interactions
            assertEquals(1, repo.createdPlaylists.size)
            assertEquals("Mi Playlist Importada", repo.createdPlaylists[0].first)
            assertEquals("https://cover.jpg", repo.createdPlaylists[0].third)

            assertEquals(1, repo.addedLocalSongIds.size)
            assertEquals(42L to 101L, repo.addedLocalSongIds[0])

            assertEquals(1, repo.addedPendingTracks.size)
            assertEquals("Remote Track", repo.addedPendingTracks[0].identity.title)
            assertEquals("Remote Artist", repo.addedPendingTracks[0].identity.artist)
        }

    @Test
    fun execute_usesFirstTrackCoverIfPlaylistCoverIsNull() =
        runBlocking {
            val repo = RecordingFakeMusicRepository()
            val useCase = ImportPlaylistFromLinkUseCase(repo)

            val playlistData =
                ImportedPlaylistData(
                    id = "pl-2",
                    title = "Deezer Mix",
                    coverUrl = null,
                    platform = PlaylistPlatform.DEEZER,
                    tracks =
                        listOf(
                            TrackIdentity(
                                title = "Track 1",
                                artist = "Artist 1",
                                artworkUri = "https://art-1.jpg",
                            ),
                        ),
                )

            val summary = useCase.execute(playlistData)

            assertEquals("https://art-1.jpg", summary.coverUrl)
            assertEquals("https://art-1.jpg", repo.createdPlaylists[0].third)
        }
}
