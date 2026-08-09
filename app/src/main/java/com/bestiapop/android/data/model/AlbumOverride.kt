package com.bestiapop.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Persisted album-level metadata that can diverge from individual songs. */
@Entity(tableName = "album_overrides")
data class AlbumOverride(
    @PrimaryKey
    val albumKey: String,
    val displayName: String,
    val artist: String? = null,
    val genre: String? = null,
    val year: Int = 0,
    val artworkUri: String? = null
)
