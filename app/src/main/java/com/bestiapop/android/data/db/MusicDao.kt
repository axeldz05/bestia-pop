package com.bestiapop.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    // Songs
    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY title ASC")
    suspend fun getAllSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE album = 'YouTube Music'")
    suspend fun getLegacyYouTubeMusicSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE uriString = :uri LIMIT 1")
    suspend fun getSongByUri(uri: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): SongEntity?

    @Query("SELECT * FROM playlist_song_cross_ref WHERE songId = :songId")
    suspend fun getPlaylistRefsForSong(songId: Long): List<PlaylistSongCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Update
    suspend fun updateSong(song: SongEntity)

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSong(songId: Long)

    @Query("DELETE FROM songs WHERE id IN (:songIds)")
    suspend fun deleteSongsByIds(songIds: List<Long>)

    @Query("UPDATE songs SET artworkUri = :artworkUri, lyrics = :lyrics WHERE id = :songId")
    suspend fun updateMetadataAndLyrics(songId: Long, artworkUri: String?, lyrics: String?)

    @Query("UPDATE songs SET title = :title, artist = :artist, album = :album, genre = :genre WHERE id = :songId")
    suspend fun updateSongMetadata(songId: Long, title: String, artist: String, album: String, genre: String)

    @Query("SELECT artworkUri FROM songs WHERE album = :albumName AND artworkUri IS NOT NULL AND artworkUri != '' LIMIT 1")
    suspend fun getArtworkForAlbum(albumName: String): String?

    @Query("UPDATE songs SET artworkUri = :artworkUri WHERE album = :albumName")
    suspend fun setAlbumArtwork(albumName: String, artworkUri: String?)

    @Query("UPDATE songs SET artworkUri = :artworkUri WHERE id = :songId")
    suspend fun updateSongArtwork(songId: Long, artworkUri: String?)

    @Query("UPDATE songs SET durationMs = :durationMs WHERE id = :songId")
    suspend fun updateSongDuration(songId: Long, durationMs: Long)

    // Playlists
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylistsFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    suspend fun getPlaylistById(playlistId: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(ref: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    @Transaction
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    fun getPlaylistWithSongsFlow(playlistId: Long): Flow<PlaylistWithSongs?>

    // Pending playlist tracks (metadata until download)
    @Query("SELECT * FROM playlist_pending_tracks WHERE playlistId = :playlistId ORDER BY position ASC, id ASC")
    fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistPendingTracks(tracks: List<PlaylistPendingTrackEntity>)

    @Query("DELETE FROM playlist_pending_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistPendingTracks(playlistId: Long)

    @Query(
        """
        DELETE FROM playlist_pending_tracks
        WHERE playlistId = :playlistId
          AND lower(artist) = lower(:artist)
          AND lower(title) = lower(:title)
        """
    )
    suspend fun deletePlaylistPendingTrackByArtistTitle(
        playlistId: Long,
        artist: String,
        title: String
    )

    @Query("DELETE FROM playlist_pending_tracks WHERE id = :id")
    suspend fun deletePlaylistPendingTrackById(id: Long)
}
