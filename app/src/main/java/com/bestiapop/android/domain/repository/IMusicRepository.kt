package com.bestiapop.android.domain.repository

import android.net.Uri
import com.bestiapop.android.data.db.SongEntity
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import kotlinx.coroutines.flow.Flow

interface IMusicRepository {
    val allSongsFlow: Flow<List<Song>>
    val playlistsFlow: Flow<List<Playlist>>
    val albumOverridesFlow: Flow<List<AlbumOverride>>

    fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>>
    fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?>

    suspend fun scanMediaStore()
    suspend fun scanFolderUri(treeUri: Uri)
    suspend fun getAllSongsSync(): List<Song>
    suspend fun findSongByArtistTitle(artist: String, title: String): Song?
    suspend fun saveUploadedSong(song: SongEntity): Long
    suspend fun deleteSongsFromApp(songs: List<Song>)
    suspend fun deleteSongsFromDevice(songs: List<Song>)
    suspend fun enhanceSongMetadataAndLyrics(song: Song)
    suspend fun updateSongDuration(songId: Long, durationMs: Long)
    suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int = 0
    )

    /** Persist album-level override only (does not rewrite songs). */
    suspend fun upsertAlbumOverride(override: AlbumOverride)

    /**
     * Persist album override and rewrite artist/album/genre/year/artwork on all songs
     * currently under [AlbumOverride.albumKey].
     */
    suspend fun updateAlbumMetadataPropagateToSongs(override: AlbumOverride)

    /**
     * Move all songs under [sourceAlbumKey] into [targetAlbumKey], rewriting their
     * album/artist/genre/year/artwork to match the effective metadata of the target album.
     * Deletes the source album override; leaves the target override untouched.
     */
    suspend fun mergeAlbumInto(sourceAlbumKey: String, targetAlbumKey: String)

    suspend fun getAlbumOverride(albumKey: String): AlbumOverride?

    fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String?
    fun savePlaylistCoverImage(sourceUriStr: String?): String?
    fun saveAlbumCoverImage(sourceUriStr: String?): String?

    suspend fun createPlaylist(name: String, description: String? = null, coverUri: String? = null): Long
    suspend fun updatePlaylist(id: Long, name: String, description: String? = null, coverUri: String? = null)
    suspend fun deletePlaylist(id: Long)
    suspend fun addSongToPlaylist(playlistId: Long, songId: Long)
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    /** Song ids that share at least one playlist with [songId] (for local radio scoring). */
    suspend fun getCoPlaylistSongIds(songId: Long): Set<Long>

    fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrack>>
    suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>)
    suspend fun removePlaylistPendingTrack(playlistId: Long, artist: String, title: String)

    suspend fun downloadAndSaveOnlineTrack(
        track: OnlineCatalogTrack,
        onProgress: ((String) -> Unit)? = null,
        conflictPolicy: com.bestiapop.android.data.model.DownloadConflictPolicy? = null
    ): Song
}
