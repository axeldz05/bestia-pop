package com.bestiapop.android.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.bestiapop.android.data.model.Song

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val playlistId: Long = 0,
    val name: String,
    val description: String? = null,
    val coverUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_song_cross_ref",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index(value = ["songId"])]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val position: Int = 0
)

/**
 * Metadata-only tracks saved with a playlist (e.g. LB import) until the user downloads them.
 * Never stores CDN audio URLs.
 */
@Entity(
    tableName = "playlist_pending_tracks",
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["playlistId", "artist", "title"])
    ]
)
data class PlaylistPendingTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val playlistId: Long,
    val title: String,
    val artist: String,
    val releaseName: String? = null,
    val trackNumber: Int = 0,
    val recordingMbid: String? = null,
    val position: Int = 0
)

data class PlaylistWithSongs(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        entity = Song::class,
        parentColumn = "playlistId",
        entityColumn = "id",
        associateBy = Junction(PlaylistSongCrossRef::class, parentColumn = "playlistId", entityColumn = "songId")
    )
    val songs: List<Song>
)
