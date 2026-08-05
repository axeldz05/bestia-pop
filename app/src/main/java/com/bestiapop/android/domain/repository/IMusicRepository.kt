package com.bestiapop.android.domain.repository

import android.net.Uri
import com.bestiapop.android.data.db.SongEntity
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import kotlinx.coroutines.flow.Flow

interface IMusicRepository {
    val allSongsFlow: Flow<List<Song>>
    val playlistsFlow: Flow<List<Playlist>>

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
        genre: String
    )
    fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String?
    fun savePlaylistCoverImage(sourceUriStr: String?): String?

    suspend fun createPlaylist(name: String, description: String? = null, coverUri: String? = null): Long
    suspend fun updatePlaylist(id: Long, name: String, description: String? = null, coverUri: String? = null)
    suspend fun deletePlaylist(id: Long)
    suspend fun addSongToPlaylist(playlistId: Long, songId: Long)
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrack>>
    suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>)
    suspend fun removePlaylistPendingTrack(playlistId: Long, artist: String, title: String)

    suspend fun downloadAndSaveOnlineTrack(
        track: OnlineCatalogTrack,
        onProgress: ((String) -> Unit)? = null,
        conflictPolicy: com.bestiapop.android.data.model.DownloadConflictPolicy? = null
    ): Song
}
