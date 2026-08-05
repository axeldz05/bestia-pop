package com.bestiapop.android.data.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.bestiapop.android.data.db.SongEntity

data class AudioFileMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    val artworkUri: String?
) {
    fun toSongEntity(
        uriString: String,
        folderPath: String,
        dateAdded: Long = System.currentTimeMillis()
    ): SongEntity = SongEntity(
        uriString = uriString,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        durationMs = durationMs,
        year = 0,
        trackNumber = 0,
        artworkUri = artworkUri,
        lyrics = null,
        folderPath = folderPath,
        dateAdded = dateAdded
    )

    companion object {
        fun fromPath(
            context: Context,
            path: String,
            fallbackTitle: String,
            artworkIdentifier: String = path,
            extractEmbeddedArtwork: (path: String, identifier: String) -> String?
        ): AudioFileMetadata {
            val retriever = MediaMetadataRetriever()
            try {
                if (path.startsWith("content://")) {
                    retriever.setDataSource(context, Uri.parse(path))
                } else {
                    retriever.setDataSource(path)
                }
                return AudioFileMetadata(
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?: fallbackTitle,
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: "Unknown Artist",
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        ?: "Unknown Album",
                    genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                        ?: "Music",
                    durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: 0L,
                    artworkUri = extractEmbeddedArtwork(path, artworkIdentifier)
                )
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                    // Best effort: some platform retrievers throw while releasing invalid media.
                }
            }
        }
    }
}
