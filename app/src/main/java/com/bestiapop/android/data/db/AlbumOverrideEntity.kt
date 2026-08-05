package com.bestiapop.android.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bestiapop.android.data.model.AlbumOverride

@Entity(tableName = "album_overrides")
data class AlbumOverrideEntity(
    @PrimaryKey
    val albumKey: String,
    val displayName: String,
    val artist: String? = null,
    val genre: String? = null,
    val year: Int = 0,
    val artworkUri: String? = null
) {
    fun toModel(): AlbumOverride = AlbumOverride(
        albumKey = albumKey,
        displayName = displayName,
        artist = artist,
        genre = genre,
        year = year,
        artworkUri = artworkUri
    )
}

fun AlbumOverride.toEntity(): AlbumOverrideEntity = AlbumOverrideEntity(
    albumKey = albumKey,
    displayName = displayName,
    artist = artist,
    genre = genre,
    year = year,
    artworkUri = artworkUri
)
