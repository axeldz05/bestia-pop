package com.bestiapop.android.data.db

import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.bestiapop.android.data.model.Song

fun Cursor.toSong(): Song {
    val id = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
    val title = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)) ?: "Track $id"
    val rawArtist = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
    val artist = if (rawArtist == MediaStore.UNKNOWN_STRING || rawArtist.isNullOrEmpty()) {
        "Unknown Artist"
    } else {
        rawArtist
    }
    val rawAlbum = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM))
    val album = if (rawAlbum == MediaStore.UNKNOWN_STRING || rawAlbum.isNullOrEmpty()) {
        "Unknown Album"
    } else {
        rawAlbum
    }
    val duration = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
    val year = getInt(getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR))
    val track = getInt(getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK))
    val filePath = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)) ?: ""
    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
    val albumIdIdx = getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
    val albumId = if (albumIdIdx != -1) getLong(albumIdIdx) else -1L
    val artworkUri = if (albumId > 0) {
        ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId
        ).toString()
    } else {
        null
    }

    return Song(
        uriString = uri,
        title = title,
        artist = artist,
        album = album,
        genre = "Music",
        durationMs = duration,
        year = year,
        trackNumber = track,
        artworkUri = artworkUri,
        lyrics = null,
        folderPath = filePath,
        dateAdded = System.currentTimeMillis()
    )
}
