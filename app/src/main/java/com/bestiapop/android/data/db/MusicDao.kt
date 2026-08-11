package com.bestiapop.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    // Songs
    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongsFlow(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY title ASC")
    suspend fun getAllSongs(): List<Song>

    @Query("SELECT * FROM songs WHERE album = 'YouTube Music'")
    suspend fun getLegacyYouTubeMusicSongs(): List<Song>

    @Query("SELECT * FROM songs WHERE uriString = :uri LIMIT 1")
    suspend fun getSongByUri(uri: String): Song?

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): Song?

    /**
     * IGNORE, not REPLACE: `songs.uriString` is unique, and REPLACE deletes the conflicting row and
     * reinserts it with a fresh id, which orphans `playlist_song_cross_ref` (no FK/cascade) and
     * drops lyrics, lastPlayedAt and dateAdded. Returns -1 when the row already exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSong(song: Song): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<Song>)

    @Update
    suspend fun updateSong(song: Song)

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSong(songId: Long)

    @Query("DELETE FROM songs WHERE id IN (:songIds)")
    suspend fun deleteSongsByIds(songIds: List<Long>)

    @Query("UPDATE songs SET artworkUri = :artworkUri, lyrics = :lyrics WHERE id = :songId")
    suspend fun updateMetadataAndLyrics(songId: Long, artworkUri: String?, lyrics: String?)

    @Query("UPDATE songs SET title = :title, artist = :artist, album = :album, genre = :genre, year = :year, trackNumber = :trackNumber WHERE id = :songId")
    suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int,
        trackNumber: Int
    )

    @Query(
        """
        UPDATE songs SET artist = :artist, album = :newAlbum, genre = :genre, year = :year,
        artworkUri = CASE WHEN :artworkUri IS NOT NULL AND :artworkUri != '' THEN :artworkUri ELSE artworkUri END
        WHERE album = :oldAlbum COLLATE NOCASE
        """
    )
    suspend fun updateSongsAlbumMetadata(
        oldAlbum: String,
        newAlbum: String,
        artist: String,
        genre: String,
        year: Int,
        artworkUri: String?
    )

    @Query("SELECT * FROM songs WHERE album = :albumName COLLATE NOCASE")
    suspend fun getSongsForAlbum(albumName: String): List<Song>

    @Query("SELECT artworkUri FROM songs WHERE album = :albumName AND artworkUri IS NOT NULL AND artworkUri != '' LIMIT 1")
    suspend fun getArtworkForAlbum(albumName: String): String?

    @Query("UPDATE songs SET artworkUri = :artworkUri WHERE album = :albumName")
    suspend fun setAlbumArtwork(albumName: String, artworkUri: String?)

    @Query("UPDATE songs SET durationMs = :durationMs WHERE id = :songId")
    suspend fun updateSongDuration(songId: Long, durationMs: Long)

    @Query("UPDATE songs SET lastPlayedAt = :ts WHERE id = :songId")
    suspend fun updateLastPlayedAt(songId: Long, ts: Long)

    // Album overrides
    @Query("SELECT * FROM album_overrides")
    fun getAllAlbumOverridesFlow(): Flow<List<AlbumOverride>>

    @Query("SELECT * FROM album_overrides WHERE albumKey = :albumKey LIMIT 1")
    suspend fun getAlbumOverride(albumKey: String): AlbumOverride?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbumOverride(override: AlbumOverride)

    @Query("DELETE FROM album_overrides WHERE albumKey = :albumKey")
    suspend fun deleteAlbumOverride(albumKey: String)

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

    @Query("SELECT playlistId FROM playlist_song_cross_ref WHERE songId = :songId")
    suspend fun getPlaylistIdsForSong(songId: Long): List<Long>

    @Query("DELETE FROM playlist_song_cross_ref WHERE songId = :songId AND playlistId IN (:playlistIds)")
    suspend fun deleteSongFromPlaylists(songId: Long, playlistIds: List<Long>)

    /** No FK/cascade on the cross-ref table, so deleting songs has to clean up their rows. */
    @Query("DELETE FROM playlist_song_cross_ref WHERE songId IN (:songIds)")
    suspend fun deletePlaylistRefsForSongs(songIds: List<Long>)

    @Query("UPDATE playlist_song_cross_ref SET songId = :keepId WHERE songId = :dropId")
    suspend fun remapPlaylistSongId(dropId: Long, keepId: Long)

    /** Song ids that share at least one playlist with [songId] (excluding [songId]). */
    @Query(
        """
        SELECT DISTINCT other.songId FROM playlist_song_cross_ref AS other
        WHERE other.playlistId IN (
            SELECT playlistId FROM playlist_song_cross_ref WHERE songId = :songId
        )
        AND other.songId != :songId
        """
    )
    suspend fun getCoPlaylistSongIds(songId: Long): List<Long>

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
