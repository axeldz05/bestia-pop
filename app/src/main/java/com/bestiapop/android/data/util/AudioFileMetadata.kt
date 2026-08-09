package com.bestiapop.android.data.util

import android.content.Context
import android.media.MediaMetadataRetriever
import com.bestiapop.android.data.db.SongEntity

data class FilenameMetadataHints(
    val artist: String?,
    val title: String?
)

/** True for SAF/document URIs or Music/BestiaPop paths mistaken for a track name. */
fun looksLikeStoragePath(value: String): Boolean {
    val v = value.trim()
    if (v.isEmpty()) return false
    val lower = v.lowercase()
    return v.contains('/') ||
        v.contains('\\') ||
        v.contains('%') ||
        lower.startsWith("content:") ||
        lower.startsWith("file:") ||
        lower.contains("primary:") ||
        lower.contains("music/bestiapop")
}

/** Parse BestiaPop-style `Artist_Title` filenames (underscores → spaces). */
fun parseFilenameMetadataHints(nameWithoutExtension: String): FilenameMetadataHints {
    val cleaned = nameWithoutExtension.trim()
    if (cleaned.isEmpty()) return FilenameMetadataHints(artist = null, title = null)
    val idx = cleaned.indexOf('_')
    if (idx <= 0 || idx >= cleaned.length - 1) {
        val titleOnly = cleaned.replace('_', ' ').trim().ifBlank { null }
        return FilenameMetadataHints(artist = null, title = titleOnly)
    }
    val artist = cleaned.substring(0, idx).replace('_', ' ').trim().ifBlank { null }
    val title = cleaned.substring(idx + 1).replace('_', ' ').trim().ifBlank { null }
    return FilenameMetadataHints(artist = artist, title = title)
}

data class AudioFileMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    val artworkUri: String?,
    val trackNumber: Int = 0
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
        trackNumber = trackNumber,
        artworkUri = artworkUri,
        lyrics = null,
        folderPath = folderPath,
        dateAdded = dateAdded
    )

    companion object {
        private fun isUnknownArtist(artist: String): Boolean =
            artist.isBlank() || artist.equals("Unknown Artist", ignoreCase = true)

        private fun isUnknownAlbum(album: String): Boolean =
            album.isBlank() || album.equals("Unknown Album", ignoreCase = true)

        fun fromPath(
            context: Context,
            path: String,
            fallbackTitle: String,
            artworkIdentifier: String = path,
            extractEmbeddedArtwork: (path: String, identifier: String) -> String?
        ): AudioFileMetadata {
            val store = MusicFileStore(context)
            val ref = AudioPersistRef.canonicalize(path)
            val retriever = MediaMetadataRetriever()
            try {
                store.applyDataSource(retriever, ref)
                val tagged = AudioFileMetadata(
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
                    artworkUri = extractEmbeddedArtwork(ref.uriString, artworkIdentifier),
                    trackNumber = parseCdTrackNumber(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                    )
                )
                return applyFilenameHints(tagged, fallbackTitle)
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                    // Best effort: some platform retrievers throw while releasing invalid media.
                }
            }
        }

        /**
         * When embedded tags are Unknown, recover artist/title from BestiaPop `Artist_Title` filenames.
         * Does not invent an album name.
         */
        internal fun applyFilenameHints(
            metadata: AudioFileMetadata,
            fallbackTitle: String
        ): AudioFileMetadata {
            if (!isUnknownArtist(metadata.artist) && !isUnknownAlbum(metadata.album)) {
                return metadata
            }
            val hints = parseFilenameMetadataHints(fallbackTitle)
            val artist = if (isUnknownArtist(metadata.artist) && !hints.artist.isNullOrBlank()) {
                hints.artist
            } else {
                metadata.artist
            }
            val titleFromFile = !hints.title.isNullOrBlank() &&
                (metadata.title.isBlank() || metadata.title.equals(fallbackTitle, ignoreCase = true))
            val title = if (titleFromFile) hints.title!! else metadata.title
            return metadata.copy(artist = artist, title = title)
        }
    }
}
