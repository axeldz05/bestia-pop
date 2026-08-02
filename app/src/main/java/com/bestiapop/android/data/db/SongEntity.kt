package com.bestiapop.android.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bestiapop.android.data.model.Song

@Entity(
    tableName = "songs",
    indices = [Index(value = ["uriString"], unique = true)]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uriString: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    val year: Int,
    val trackNumber: Int,
    val artworkUri: String?,
    val lyrics: String?,
    val folderPath: String,
    val dateAdded: Long
) {
    fun toSong(): Song = Song(
        id = id,
        uriString = uriString,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        durationMs = durationMs,
        year = year,
        trackNumber = trackNumber,
        artworkUri = artworkUri,
        lyrics = lyrics,
        folderPath = folderPath,
        dateAdded = dateAdded
    )
}

fun Song.toEntity(): SongEntity = SongEntity(
    id = id,
    uriString = uriString,
    title = title,
    artist = artist,
    album = album,
    genre = genre,
    durationMs = durationMs,
    year = year,
    trackNumber = trackNumber,
    artworkUri = artworkUri,
    lyrics = lyrics,
    folderPath = folderPath,
    dateAdded = dateAdded
)

fun android.database.Cursor.toSongEntity(): SongEntity {
    val id = getLong(getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID))
    val title = getString(getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)) ?: "Track $id"
    val rawArtist = getString(getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST))
    val artist = if (rawArtist == android.provider.MediaStore.UNKNOWN_STRING || rawArtist.isNullOrEmpty()) "Unknown Artist" else rawArtist
    val rawAlbum = getString(getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM))
    val album = if (rawAlbum == android.provider.MediaStore.UNKNOWN_STRING || rawAlbum.isNullOrEmpty()) "Unknown Album" else rawAlbum
    val duration = getLong(getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION))
    val year = getInt(getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.YEAR))
    val track = getInt(getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TRACK))
    val filePath = getString(getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)) ?: ""
    val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()

    return SongEntity(
        uriString = uri,
        title = title,
        artist = artist,
        album = album,
        genre = "Music",
        durationMs = duration,
        year = year,
        trackNumber = track,
        artworkUri = null,
        lyrics = null,
        folderPath = filePath,
        dateAdded = System.currentTimeMillis()
    )
}
