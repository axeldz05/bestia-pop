package com.bestiapop.android.domain.usecase

import android.net.Uri
import com.bestiapop.android.data.db.SongEntity
import com.bestiapop.android.data.listenbrainz.LbPlaylistDetail
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.LbPlaylistTrack
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.listenbrainz.MatchedLbTrack
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.repository.IMusicRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
            MatchedLbTrack(
                track = LbPlaylistTrack(title = it.title, artist = it.artist, releaseName = it.album),
                localSong = it
            )
        }
        val remoteRows = unmatched.map { (artist, title) ->
            MatchedLbTrack(
                track = LbPlaylistTrack(title = title, artist = artist, releaseName = "Rel"),
                localSong = null
            )
        }
        return MatchedLbPlaylist(
            detail = LbPlaylistDetail(
                summary = LbPlaylistSummary(
                    mbid = "mbid-1",
                    title = "Daily Jams",
                    description = "From LB",
                    trackCount = matchRows.size + remoteRows.size
                ),
                tracks = (matchRows + remoteRows).map { it.track }
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

    @Test
    fun downloadIdFor_usesMatchKey() {
        assertEquals(
            MatchListenBrainzTracksUseCase.matchKey("Artist", "Title"),
            ImportListenBrainzPlaylistUseCase.downloadIdFor("Artist", "Title")
        )
    }

    private class FakeRepo : IMusicRepository {
        var createdName: String? = null
        var createdDescription: String? = null
        val addedSongIds = mutableListOf<Long>()
        val pendingTracks = mutableListOf<PlaylistPendingTrack>()

        override val allSongsFlow: Flow<List<Song>> = emptyFlow()
        override val playlistsFlow: Flow<List<Playlist>> = emptyFlow()
        override val albumOverridesFlow: Flow<List<com.bestiapop.android.data.model.AlbumOverride>> = emptyFlow()
        override fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> = emptyFlow()
        override fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> = emptyFlow()
        override suspend fun scanMediaStore(onProgress: com.bestiapop.android.domain.repository.LibraryScanProgress?) = Unit
        override suspend fun resyncAppManagedMusic(onProgress: com.bestiapop.android.domain.repository.LibraryScanProgress?): Int = 0
        override suspend fun scanFolderUri(
            treeUri: Uri,
            onProgress: com.bestiapop.android.domain.repository.LibraryScanProgress?
        ) = 0
        override suspend fun getAllSongsSync(): List<Song> = emptyList()
        override suspend fun findSongByArtistTitle(artist: String, title: String): Song? = null
        override suspend fun saveUploadedSong(song: SongEntity): Long = 0L
        override suspend fun deleteSongsFromApp(songs: List<Song>) = Unit
        override suspend fun deleteSongsFromDevice(songs: List<Song>) = Unit
        override suspend fun enhanceSongMetadataAndLyrics(song: Song) = Unit
        override suspend fun proposeSongIdentity(
            song: Song,
            customQuery: String?,
            force: Boolean
        ) =
            com.bestiapop.android.data.model.IdentifyProposal(
                songId = song.id,
                queryArtist = song.artist,
                queryTitle = song.title,
                alreadyIdentified = true
            )
        override suspend fun applySongIdentity(
            songId: Long,
            candidate: com.bestiapop.android.data.model.IdentifyCandidate
        ) = com.bestiapop.android.data.model.IdentifyResult.Skipped
        override suspend fun identifySongMetadata(song: Song) =
            com.bestiapop.android.data.model.IdentifyResult.Skipped
        override suspend fun updateSongDuration(songId: Long, durationMs: Long) = Unit
        override suspend fun updateSongMetadata(
            songId: Long,
            title: String,
            artist: String,
            album: String,
            genre: String,
            year: Int,
            trackNumber: Int
        ) = Unit
        override suspend fun upsertAlbumOverride(override: com.bestiapop.android.data.model.AlbumOverride) = Unit
        override suspend fun updateAlbumMetadataPropagateToSongs(override: com.bestiapop.android.data.model.AlbumOverride) = Unit
        override suspend fun mergeAlbumInto(sourceAlbumKey: String, targetAlbumKey: String) = Unit
        override suspend fun getAlbumOverride(albumKey: String): com.bestiapop.android.data.model.AlbumOverride? = null
        override fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String? = null
        override fun savePlaylistCoverImage(sourceUriStr: String?): String? = null
        override fun saveAlbumCoverImage(sourceUriStr: String?): String? = null

        override suspend fun createPlaylist(
            name: String,
            description: String?,
            coverUri: String?
        ): Long {
            createdName = name
            createdDescription = description
            return 10L
        }

        override suspend fun updatePlaylist(
            id: Long,
            name: String,
            description: String?,
            coverUri: String?
        ) = Unit

        override suspend fun deletePlaylist(id: Long) = Unit

        override suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
            addedSongIds.add(songId)
        }

        override suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) = Unit

        override suspend fun getCoPlaylistSongIds(songId: Long): Set<Long> = emptySet()

        override fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrack>> =
            emptyFlow()

        override suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>) {
            pendingTracks.addAll(tracks)
        }

        override suspend fun removePlaylistPendingTrack(
            playlistId: Long,
            artist: String,
            title: String
        ) = Unit

        override suspend fun downloadAndSaveOnlineTrack(
            track: OnlineCatalogTrack,
            onProgress: ((String) -> Unit)?,
            conflictPolicy: com.bestiapop.android.data.model.DownloadConflictPolicy?
        ): Song = error("not used")
    }
}
