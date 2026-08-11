package com.bestiapop.android.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [Index(value = ["uriString"], unique = true)]
)
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uriString: String,
    override val title: String,
    override val artist: String = "Unknown Artist",
    override val album: String = "Unknown Album",
    val genre: String = "Unknown Genre",
    override val durationMs: Long = 0,
    val year: Int = 0,
    override val trackNumber: Int = 0,
    override val artworkUri: String? = null,
    val lyrics: String? = null,
    val folderPath: String = "",
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = 0
) : TrackMeta
