package com.bestiapop.android.data.db

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.bestiapop.android.data.model.Song

@Entity(
    tableName = "song_artist_cross_ref",
    primaryKeys = ["songId", "artistId"],
    foreignKeys = [
        ForeignKey(
            entity = Song::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["songId"]),
        Index(value = ["artistId"])
    ]
)
@Immutable
data class SongArtistCrossRef(
    val songId: Long,
    val artistId: Long,
    val isPrimary: Boolean = true,
    val position: Int = 0
)

@Entity(
    tableName = "song_genre_cross_ref",
    primaryKeys = ["songId", "genreId"],
    foreignKeys = [
        ForeignKey(
            entity = Song::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GenreEntity::class,
            parentColumns = ["id"],
            childColumns = ["genreId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["songId"]),
        Index(value = ["genreId"])
    ]
)
@Immutable
data class SongGenreCrossRef(
    val songId: Long,
    val genreId: Long
)

@Entity(
    tableName = "album_artist_cross_ref",
    primaryKeys = ["albumKey", "artistId"],
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["albumKey"]),
        Index(value = ["artistId"])
    ]
)
@Immutable
data class AlbumArtistCrossRef(
    val albumKey: String,
    val artistId: Long
)

@Entity(
    tableName = "album_genre_cross_ref",
    primaryKeys = ["albumKey", "genreId"],
    foreignKeys = [
        ForeignKey(
            entity = GenreEntity::class,
            parentColumns = ["id"],
            childColumns = ["genreId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["albumKey"]),
        Index(value = ["genreId"])
    ]
)
@Immutable
data class AlbumGenreCrossRef(
    val albumKey: String,
    val genreId: Long
)
