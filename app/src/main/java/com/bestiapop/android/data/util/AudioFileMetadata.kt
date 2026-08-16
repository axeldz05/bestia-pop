package com.bestiapop.android.data.util

import android.content.Context
import android.media.MediaMetadataRetriever
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.isTrackNumberLabel
import com.bestiapop.android.domain.util.mergeIdentityHints
import com.bestiapop.android.domain.util.parseFilenameMetadataHints
import com.bestiapop.android.domain.util.resolveWeakIdentityHints
import com.bestiapop.android.domain.util.stripLeadingTitleJunk

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

/**
 * Reuses one extracted cover per artist+album during a bulk scan so later tracks
 * skip `MediaMetadataRetriever.embeddedPicture` (often the slowest tag read).
 */
class AlbumArtworkCache {
    private val lock = Any()
    private val uris = HashMap<String, String>()

    fun lookup(artist: String, album: String): String? {
        val key = key(artist, album) ?: return null
        synchronized(lock) { return uris[key] }
    }

    fun remember(artist: String, album: String, uri: String?) {
        if (!SongPathNormalizer.hasUsableArtwork(uri)) return
        val key = key(artist, album) ?: return
        synchronized(lock) { uris.putIfAbsent(key, uri!!) }
    }

    private fun key(artist: String, album: String): String? {
        if (IdentifyRanking.isPlaceholderArtist(artist)) return null
        if (IdentifyRanking.isGenericAlbum(album)) return null
        val trimmedAlbum = album.trim()
        if (trimmedAlbum.isEmpty()) return null
        return artist.trim().lowercase() + "\u0000" + trimmedAlbum.lowercase()
    }
}

data class AudioFileMetadata(
    val identity: TrackIdentity,
    val genre: String
) : TrackMeta by identity {
    fun withIdentity(transform: TrackIdentity.() -> TrackIdentity): AudioFileMetadata =
        copy(identity = identity.transform())

    fun toSong(
        uriString: String,
        folderPath: String,
        dateAdded: Long = System.currentTimeMillis()
    ): Song = Song(
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
        /** L2: flat file-tag construction. */
        operator fun invoke(
            title: String,
            artist: String,
            album: String,
            genre: String,
            durationMs: Long,
            artworkUri: String?,
            trackNumber: Int = 0
        ): AudioFileMetadata = AudioFileMetadata(
            identity = TrackIdentity(
                title = title,
                artist = artist,
                album = album,
                artworkUri = artworkUri,
                durationMs = durationMs,
                trackNumber = trackNumber
            ),
            genre = genre
        )

        fun fromPath(
            context: Context,
            path: String,
            fallbackTitle: String,
            artworkIdentifier: String = path,
            persistEmbeddedArtwork: (bytes: ByteArray, identifier: String) -> String?,
            artworkCache: AlbumArtworkCache? = null
        ): AudioFileMetadata {
            val store = MusicFileStore(context)
            val ref = AudioPersistRef.canonicalize(path)
            val retriever = MediaMetadataRetriever()
            try {
                store.applyDataSource(retriever, ref)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: "Unknown Artist"
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?: "Unknown Album"
                val reusedArt = artworkCache?.lookup(artist, album)
                val artworkUri = reusedArt ?: retriever.embeddedPicture
                    ?.takeIf(ByteArray::isNotEmpty)
                    ?.let { persistEmbeddedArtwork(it, artworkIdentifier) }
                    ?.also { artworkCache?.remember(artist, album, it) }
                val tagged = AudioFileMetadata(
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?: fallbackTitle,
                    artist = artist,
                    album = album,
                    genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                        ?: "Music",
                    durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: 0L,
                    artworkUri = artworkUri,
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
         * When embedded tags are Unknown / track-number rips, recover artist/title from
         * filename shapes (`Artist_Title`, `NN_-_Title`, `Artist - Song`). Does not invent
         * album and does not replace a real ID3 title.
         */
        internal fun applyFilenameHints(
            metadata: AudioFileMetadata,
            fallbackTitle: String
        ): AudioFileMetadata {
            val fromTags = resolveWeakIdentityHints(metadata.artist, metadata.title)
            val fromFile = parseFilenameMetadataHints(fallbackTitle)
            // When tags are placeholder + title is just the raw filename, prefer filename parse.
            val tagTitleIsFilename = metadata.title.isBlank() ||
                metadata.title.equals(fallbackTitle, ignoreCase = true)
            val hints = if (
                IdentifyRanking.isPlaceholderArtist(metadata.artist) && tagTitleIsFilename
            ) {
                mergeIdentityHints(fromFile, fromTags)
            } else {
                mergeIdentityHints(fromTags, fromFile)
            }
            val artistWeak = IdentifyRanking.isPlaceholderArtist(metadata.artist)
            val titleWeak = tagTitleIsFilename ||
                metadata.title.trimStart().let { it.startsWith("-") || it.startsWith("_") } ||
                looksLikeStoragePath(metadata.title) ||
                isTrackNumberLabel(metadata.title.trim()) ||
                (artistWeak && (metadata.title.contains(" - ") || metadata.title.contains("_-_")))

            if (!artistWeak && !IdentifyRanking.isGenericAlbum(metadata.album) && !titleWeak) {
                val cleaned = stripLeadingTitleJunk(metadata.title)
                return if (cleaned != metadata.title) {
                    metadata.withIdentity { copy(title = cleaned) }
                } else {
                    metadata
                }
            }

            val artist = when {
                artistWeak && !hints.artist.isNullOrBlank() -> hints.artist
                artistWeak -> "Unknown Artist"
                else -> metadata.artist
            }
            // Keep a real ID3 title even when artist is Unknown; filename hints are for search.
            val title = when {
                titleWeak && !hints.title.isNullOrBlank() -> hints.title
                else -> stripLeadingTitleJunk(metadata.title).ifBlank { metadata.title }
            }
            val trackNumber = metadata.trackNumber.takeIf { it > 0 }
                ?: hints.trackNumber
                ?: metadata.trackNumber
            return metadata.withIdentity {
                copy(artist = artist, title = title, trackNumber = trackNumber)
            }
        }
    }
}
