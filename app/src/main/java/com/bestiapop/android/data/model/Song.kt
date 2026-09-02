package com.bestiapop.android.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["uriString"], unique = true),
        Index(value = ["title"]),
        Index(value = ["album"]),
        Index(value = ["artist"]),
        Index(value = ["dateAdded"])
    ]
)
@Immutable
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uriString: String,
    override val title: String,
    override val artist: String = "Unknown Artist",
    override val album: String = "Unknown Album",
    val genre: String = UNKNOWN_GENRE,
    override val durationMs: Long = 0,
    val year: Int = 0,
    override val trackNumber: Int = 0,
    override val artworkUri: String? = null,
    val lyrics: String? = null,
    val folderPath: String = "",
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = 0
) : TrackMeta {
    companion object {
        const val UNKNOWN_GENRE = "Unknown Genre"
    }
}

val Song.isRemote: Boolean
    get() = uriString.startsWith("remote://")

