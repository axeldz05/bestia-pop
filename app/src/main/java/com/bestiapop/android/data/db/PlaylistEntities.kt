package com.bestiapop.android.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

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
    primaryKeys = ["playlistId", "songId"]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val position: Int = 0
)

data class PlaylistWithSongs(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "id",
        associateBy = Junction(PlaylistSongCrossRef::class, parentColumn = "playlistId", entityColumn = "songId")
    )
    val songs: List<SongEntity>
)
