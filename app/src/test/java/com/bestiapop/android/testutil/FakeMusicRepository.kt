package com.bestiapop.android.testutil

import android.net.Uri
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.DownloadConflictPolicy
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.TagSyncSummary
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.repository.LibraryScanProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * L1 stub for unit tests: override only the methods under exercise.
 * Call sites that need playlist/download bookkeeping should subclass and record.
 */
open class FakeMusicRepository : IMusicRepository {
    override val allSongsFlow: Flow<List<Song>> = emptyFlow()
    override val playlistsFlow: Flow<List<Playlist>> = emptyFlow()
    override val albumOverridesFlow: Flow<List<AlbumOverride>> = emptyFlow()
    override fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> = emptyFlow()
    override fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> = emptyFlow()
    override suspend fun scanMediaStore(onProgress: LibraryScanProgress?) = Unit
    override suspend fun resyncAppManagedMusic(onProgress: LibraryScanProgress?): Int = 0
    override suspend fun scanFolderUri(treeUri: Uri, onProgress: LibraryScanProgress?) = 0
    override suspend fun getAllSongsSync(): List<Song> = emptyList()
    override suspend fun findSongByArtistTitle(artist: String, title: String): Song? = null
    override suspend fun saveUploadedSong(song: Song): Long = 0L
    override suspend fun deleteSongsFromApp(songs: List<Song>) = Unit
    override suspend fun deleteSongsFromDevice(songs: List<Song>) = Unit
    override suspend fun enhanceSongMetadataAndLyrics(song: Song) = Unit
    override suspend fun proposeSongIdentity(
        song: Song,
        customQuery: String?,
        force: Boolean,
        listenBrainzToken: String?,
        filters: IdentifySearchFilters,
        catalogIndex: Int,
        existingCandidates: List<IdentifyCandidate>
    ) = IdentifyProposal(
        songId = song.id,
        queryArtist = song.artist,
        queryTitle = song.title,
        alreadyIdentified = true
    )
    override suspend fun applySongIdentity(songId: Long, candidate: IdentifyCandidate) =
        IdentifyResult.Skipped
    override suspend fun identifySongMetadata(song: Song) = IdentifyResult.Skipped
    override suspend fun updateSongDuration(songId: Long, durationMs: Long) = Unit
    override suspend fun touchSongLastPlayed(songId: Long, playedAt: Long) = Unit
    override suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int,
        trackNumber: Int
    ) = Unit
    override suspend fun upsertAlbumOverride(override: AlbumOverride) = Unit
    override suspend fun updateAlbumMetadataPropagateToSongs(override: AlbumOverride) = Unit
    override suspend fun setAlbumArtwork(albumKey: String, artworkUri: String?) = Unit
    override suspend fun mergeAlbumInto(sourceAlbumKey: String, targetAlbumKey: String) = Unit
    override suspend fun getAlbumOverride(albumKey: String): AlbumOverride? = null
    override fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String? = null
    override fun savePlaylistCoverImage(sourceUriStr: String?): String? = null
    override fun saveAlbumCoverImage(sourceUriStr: String?): String? = null
    override suspend fun createPlaylist(name: String, description: String?, coverUri: String?): Long = 0L
    override suspend fun updatePlaylist(id: Long, name: String, description: String?, coverUri: String?) = Unit
    override suspend fun deletePlaylist(id: Long) = Unit
    override suspend fun addSongToPlaylist(playlistId: Long, songId: Long) = Unit
    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) = Unit
    override suspend fun getPlaylistIdsForSong(songId: Long): List<Long> = emptyList()
    override suspend fun getCoPlaylistSongIds(songId: Long): Set<Long> = emptySet()
    override fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrack>> =
        emptyFlow()
    override suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>) = Unit
    override suspend fun removePlaylistPendingTrack(playlistId: Long, artist: String, title: String) = Unit
    override suspend fun downloadAndSaveOnlineTrack(
        track: OnlineCatalogTrack,
        onProgress: ((DownloadPhase) -> Unit)?,
        conflictPolicy: DownloadConflictPolicy?
    ): Song = error("downloadAndSaveOnlineTrack not stubbed")
    override suspend fun syncTagsToFiles(onProgress: LibraryScanProgress?) = TagSyncSummary()
}
