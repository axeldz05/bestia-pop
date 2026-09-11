package com.bestiapop.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.SongPathRef
import com.bestiapop.android.data.model.SongPlayStat
import kotlinx.coroutines.flow.Flow

/** Identity columns only — skips `lyrics` blobs and play stamps (`song_play_stats`). Full rows: [MusicDao.getSongById] / [MusicDao.getAllSongs]. */
internal const val IDENTITY_SONG_SELECT = """
        SELECT id, uriString, title, artist, album, genre, durationMs, year, trackNumber,
               artworkUri, CAST(NULL AS TEXT) AS lyrics, folderPath, dateAdded,
               0 AS lastPlayedAt
        FROM songs
"""

internal const val PLAYLIST_SONG_SELECT = """
        SELECT songs.id, songs.uriString, songs.title, songs.artist, songs.album, songs.genre,
               songs.durationMs, songs.year, songs.trackNumber, songs.artworkUri,
               CAST(NULL AS TEXT) AS lyrics, songs.folderPath, songs.dateAdded,
               0 AS lastPlayedAt
        FROM songs
"""

@Dao
interface MusicDao {

    // Songs
    @Query(IDENTITY_SONG_SELECT)
    fun getAllSongsFlow(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY title ASC")
    suspend fun getAllSongs(): List<Song>

    @Query("$IDENTITY_SONG_SELECT ORDER BY title ASC")
    suspend fun getIdentitySongs(): List<Song>

    @Query("$IDENTITY_SONG_SELECT WHERE id IN (:ids)")
    suspend fun getIdentitySongsByIds(ids: List<Long>): List<Song>

    @Query("SELECT uriString, folderPath FROM songs")
    suspend fun getAllSongPathRefs(): List<SongPathRef>

    @Query("SELECT * FROM songs WHERE album = 'YouTube Music'")
    suspend fun getLegacyYouTubeMusicSongs(): List<Song>

    @Query("SELECT * FROM songs WHERE durationMs <= 0")
    suspend fun getSongsWithNonPositiveDuration(): List<Song>

    @Query("SELECT * FROM songs WHERE uriString = :uri LIMIT 1")
    suspend fun getSongByUri(uri: String): Song?

    @Query("SELECT * FROM songs WHERE uriString IN (:uris)")
    suspend fun getSongsByUris(uris: List<String>): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): Song?

    @Query("SELECT * FROM songs WHERE album = :albumName COLLATE NOCASE AND artist = :artistName COLLATE NOCASE AND uriString LIKE 'remote://%'")
    suspend fun getSavedRemoteAlbumSongs(albumName: String, artistName: String): List<Song>

    @Query("DELETE FROM songs WHERE album = :albumName COLLATE NOCASE AND artist = :artistName COLLATE NOCASE AND uriString LIKE 'remote://%'")
    suspend fun deleteSavedRemoteAlbum(albumName: String, artistName: String): Int

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

    /** Identity columns only — never writes `lyrics`. */
    @Query(
        """
        UPDATE songs SET title = :title, artist = :artist, album = :album,
               artworkUri = :artworkUri, trackNumber = :trackNumber, year = :year,
               durationMs = :durationMs
        WHERE id = :songId
        """
    )
    suspend fun updateSongIdentity(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        artworkUri: String?,
        trackNumber: Int,
        year: Int,
        durationMs: Long
    )

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSong(songId: Long)

    @Query("DELETE FROM songs WHERE id IN (:songIds)")
    suspend fun deleteSongsByIds(songIds: List<Long>)

    @Query("UPDATE songs SET artworkUri = :artworkUri, lyrics = :lyrics WHERE id = :songId")
    suspend fun updateMetadataAndLyrics(songId: Long, artworkUri: String?, lyrics: String?)

    @Query("UPDATE songs SET lyrics = :lyrics WHERE id = :songId")
    suspend fun updateSongLyrics(songId: Long, lyrics: String?)

    @Query("UPDATE songs SET dateAdded = :dateAdded WHERE id = :songId")
    suspend fun updateSongDateAdded(songId: Long, dateAdded: Long)

    @Query("UPDATE songs SET uriString = :uriString, folderPath = :folderPath WHERE id = :songId")
    suspend fun updateSongUri(songId: Long, uriString: String, folderPath: String)

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

    @Query("UPDATE songs SET trackNumber = :trackNumber WHERE id = :songId")
    suspend fun updateTrackNumber(songId: Long, trackNumber: Int)

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

    @Query("$IDENTITY_SONG_SELECT WHERE album = :albumName COLLATE NOCASE")
    suspend fun getSongsForAlbum(albumName: String): List<Song>

    @Query("SELECT artworkUri FROM songs WHERE album = :albumName AND artworkUri IS NOT NULL AND artworkUri != '' LIMIT 1")
    suspend fun getArtworkForAlbum(albumName: String): String?

    @Query("UPDATE songs SET artworkUri = :artworkUri WHERE album = :albumName")
    suspend fun setAlbumArtwork(albumName: String, artworkUri: String?)

    @Query("UPDATE songs SET durationMs = :durationMs WHERE id = :songId")
    suspend fun updateSongDuration(songId: Long, durationMs: Long)

    @Query("INSERT OR REPLACE INTO song_play_stats (songId, lastPlayedAt) VALUES (:songId, :ts)")
    suspend fun updateLastPlayedAt(songId: Long, ts: Long)

    @Query("SELECT * FROM song_play_stats")
    fun getPlayStatsFlow(): Flow<List<SongPlayStat>>

    @Query("SELECT lastPlayedAt FROM song_play_stats WHERE songId = :songId")
    suspend fun getPlayStat(songId: Long): Long?

    @Query("DELETE FROM song_play_stats WHERE songId IN (:songIds)")
    suspend fun deletePlayStatsForSongs(songIds: List<Long>)

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
    @Query(
        """
        SELECT 
            playlistId, 
            name, 
            description, 
            COALESCE(NULLIF(coverUri, ''), (
                SELECT songs.artworkUri FROM songs 
                INNER JOIN playlist_song_cross_ref AS refs ON refs.songId = songs.id 
                WHERE refs.playlistId = playlists.playlistId AND songs.artworkUri IS NOT NULL AND songs.artworkUri != '' 
                ORDER BY refs.position ASC, refs.id ASC 
                LIMIT 1
            )) AS coverUri, 
            createdAt 
        FROM playlists 
        ORDER BY createdAt DESC
        """
    )
    fun getAllPlaylistsFlow(): Flow<List<PlaylistEntity>>

    @Query(
        """
        SELECT 
            playlists.playlistId, 
            playlists.name, 
            playlists.description, 
            COALESCE(NULLIF(playlists.coverUri, ''), (
                SELECT songs.artworkUri FROM songs 
                INNER JOIN playlist_song_cross_ref AS refs ON refs.songId = songs.id 
                WHERE refs.playlistId = playlists.playlistId AND songs.artworkUri IS NOT NULL AND songs.artworkUri != '' 
                ORDER BY refs.position ASC, refs.id ASC 
                LIMIT 1
            )) AS coverUri, 
            (
                (SELECT COUNT(*) FROM playlist_song_cross_ref WHERE playlist_song_cross_ref.playlistId = playlists.playlistId) +
                (SELECT COUNT(*) FROM playlist_pending_tracks WHERE playlist_pending_tracks.playlistId = playlists.playlistId)
            ) AS songCount,
            playlists.createdAt 
        FROM playlists 
        ORDER BY playlists.createdAt DESC
        """
    )
    fun getAllPlaylistSummariesFlow(): Flow<List<PlaylistSummary>>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongsToPlaylist(refs: List<PlaylistSongCrossRef>)

    @Transaction
    suspend fun reorderPlaylistSongs(playlistId: Long, songIds: List<Long>) {
        clearPlaylistSongs(playlistId)
        val refs = songIds.mapIndexed { index, songId ->
            PlaylistSongCrossRef(
                playlistId = playlistId,
                songId = songId,
                position = index
            )
        }
        addSongsToPlaylist(refs)
    }

    @Query("SELECT MAX(position) FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun getMaxPositionInPlaylist(playlistId: Long): Int?

    @Query("DELETE FROM playlist_song_cross_ref WHERE id = (SELECT id FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId ORDER BY position ASC, id ASC LIMIT 1)")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeAllOccurrencesOfSongFromPlaylist(playlistId: Long, songId: Long)

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

    @Query(
        """
        SELECT 
            playlistId, 
            name, 
            description, 
            COALESCE(NULLIF(coverUri, ''), (
                SELECT songs.artworkUri FROM songs 
                INNER JOIN playlist_song_cross_ref AS refs ON refs.songId = songs.id 
                WHERE refs.playlistId = playlists.playlistId AND songs.artworkUri IS NOT NULL AND songs.artworkUri != '' 
                ORDER BY refs.position ASC, refs.id ASC 
                LIMIT 1
            )) AS coverUri, 
            createdAt 
        FROM playlists 
        WHERE playlistId = :playlistId
        """
    )
    fun getPlaylistByIdFlow(playlistId: Long): Flow<PlaylistEntity?>

    @Transaction
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    fun getPlaylistWithSongsFlow(playlistId: Long): Flow<PlaylistWithSongs?>

    @Query(
        """
        $PLAYLIST_SONG_SELECT
        INNER JOIN playlist_song_cross_ref AS refs ON refs.songId = songs.id
        WHERE refs.playlistId = :playlistId
        ORDER BY refs.position ASC, refs.id ASC
        """
    )
    fun getPlaylistSongsOrderedFlow(playlistId: Long): Flow<List<Song>>

    @Query(
        """
        $PLAYLIST_SONG_SELECT
        INNER JOIN playlist_song_cross_ref AS refs ON refs.songId = songs.id
        WHERE refs.playlistId = :playlistId
        ORDER BY refs.position ASC, refs.id ASC
        """
    )
    suspend fun getPlaylistSongsOrdered(playlistId: Long): List<Song>

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

    // --- Normalized Artists & Genres (3FN) ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtist(artist: ArtistEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtists(artists: List<ArtistEntity>)

    @Query("SELECT * FROM artists WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun getArtistByNormalizedName(normalizedName: String): ArtistEntity?

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    fun getAllArtistsFlow(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllArtists(): List<ArtistEntity>

    @Query("SELECT photoUri FROM artists WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun getArtistPhotoUri(normalizedName: String): String?

    @Query("UPDATE artists SET photoUri = :photoUri WHERE normalizedName = :normalizedName")
    suspend fun setArtistPhotoUri(normalizedName: String, photoUri: String?)

    @Query("UPDATE artists SET name = :name WHERE id = :id")
    suspend fun updateArtistName(id: Long, name: String)

    @Query("UPDATE genres SET name = :name WHERE id = :id")
    suspend fun updateGenreName(id: Long, name: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGenre(genre: GenreEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGenres(genres: List<GenreEntity>)

    @Query("SELECT * FROM genres WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun getGenreByNormalizedName(normalizedName: String): GenreEntity?

    @Query("SELECT * FROM genres ORDER BY name COLLATE NOCASE ASC")
    fun getAllGenresFlow(): Flow<List<GenreEntity>>

    @Query("SELECT * FROM genres ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllGenres(): List<GenreEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongArtistCrossRefs(refs: List<SongArtistCrossRef>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongGenreCrossRefs(refs: List<SongGenreCrossRef>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumArtistCrossRefs(refs: List<AlbumArtistCrossRef>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumGenreCrossRefs(refs: List<AlbumGenreCrossRef>)

    @Query("DELETE FROM song_artist_cross_ref WHERE songId = :songId")
    suspend fun deleteSongArtistCrossRefs(songId: Long)

    @Query("DELETE FROM song_genre_cross_ref WHERE songId = :songId")
    suspend fun deleteSongGenreCrossRefs(songId: Long)

    @Query("DELETE FROM album_artist_cross_ref WHERE albumKey = :albumKey")
    suspend fun deleteAlbumArtistCrossRefs(albumKey: String)

    @Query("DELETE FROM album_genre_cross_ref WHERE albumKey = :albumKey")
    suspend fun deleteAlbumGenreCrossRefs(albumKey: String)

    @Query(
        """
        SELECT s.id, s.uriString, s.title, s.artist, s.album, s.genre, s.durationMs, s.year, s.trackNumber,
               s.artworkUri, CAST(NULL AS TEXT) AS lyrics, s.folderPath, s.dateAdded,
               0 AS lastPlayedAt
        FROM songs s
        JOIN song_artist_cross_ref x ON s.id = x.songId
        JOIN artists a ON x.artistId = a.id
        WHERE a.normalizedName = :normalizedArtistName
        ORDER BY x.isPrimary DESC, x.position ASC, s.title ASC
        """
    )
    suspend fun getSongsForArtistNormalized(normalizedArtistName: String): List<Song>

    @Query(
        """
        SELECT s.id, s.uriString, s.title, s.artist, s.album, s.genre, s.durationMs, s.year, s.trackNumber,
               s.artworkUri, CAST(NULL AS TEXT) AS lyrics, s.folderPath, s.dateAdded,
               0 AS lastPlayedAt
        FROM songs s
        JOIN song_genre_cross_ref x ON s.id = x.songId
        JOIN genres g ON x.genreId = g.id
        WHERE g.normalizedName = :normalizedGenreName
        ORDER BY s.title ASC
        """
    )
    suspend fun getSongsForGenreNormalized(normalizedGenreName: String): List<Song>

    @Query(
        """
        SELECT DISTINCT s.album
        FROM songs s
        JOIN song_artist_cross_ref x ON s.id = x.songId
        JOIN artists a ON x.artistId = a.id
        WHERE a.normalizedName = :normalizedArtistName AND s.album IS NOT NULL AND TRIM(s.album) != ''
        """
    )
    suspend fun getAlbumsForArtistNormalized(normalizedArtistName: String): List<String>
}
