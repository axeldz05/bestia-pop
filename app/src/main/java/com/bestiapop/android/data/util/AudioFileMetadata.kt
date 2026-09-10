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
    if (lower.startsWith("content:") || lower.startsWith("file:")) return true
    if (lower.contains("primary:") || lower.contains("primary%3a")) return true
    if (lower.contains("music/bestiapop") || lower.contains("music%2fbestiapop")) return true
    if (v.contains('\\')) return true
    if (v.startsWith("/")) return true
    if (v.contains('%') &&
        (lower.contains("tree") || lower.contains("document") || lower.contains("%2f"))
    ) {
        return true
    }
    val slashCount = v.count { it == '/' }
    if (slashCount >= 2) return true
    // A single spaced slash is a bilingual title (`ブラックホール / Black Hole`), not a path.
    if (slashCount == 1 && !v.contains(" / ")) return true
    return false
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
    val genre: String,
    val year: Int = 0,
    val lyrics: String? = null
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
        year = year,
        trackNumber = trackNumber,
        artworkUri = artworkUri,
        lyrics = lyrics,
        folderPath = folderPath,
        dateAdded = dateAdded
    )

    companion object {
        const val FALLBACK_ARTIST = "Unknown Artist"
        const val FALLBACK_ALBUM = "Unknown Album"
        const val FALLBACK_GENRE = "Music"

        /** L2: flat file-tag construction. */
        operator fun invoke(
            title: String,
            artist: String,
            album: String,
            genre: String,
            durationMs: Long,
            artworkUri: String?,
            trackNumber: Int = 0,
            year: Int = 0,
            lyrics: String? = null
        ): AudioFileMetadata = AudioFileMetadata(
            identity = TrackIdentity(
                title = title,
                artist = artist,
                album = album,
                artworkUri = artworkUri,
                durationMs = durationMs,
                trackNumber = trackNumber
            ),
            genre = genre,
            year = year,
            lyrics = lyrics
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
            val file = store.readableFile(ref)
            val tagged = file?.let { AudioTagReader.read(it) }
            val retrieverTags = if (tagged?.isComplete == true) {
                null
            } else {
                readRetrieverTags(store, ref)
            }
            val raw = coalesceRawTags(tagged, retrieverTags)
            val artist = raw.artist?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_ARTIST
            val album = raw.album?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_ALBUM
            val reusedArt = artworkCache?.lookup(artist, album)
            val artworkUri = reusedArt
                ?: raw.artworkBytes
                    ?.takeIf(ByteArray::isNotEmpty)
                    ?.let { persistEmbeddedArtwork(it, artworkIdentifier) }
                    ?.also { artworkCache?.remember(artist, album, it) }
            return fromRawTags(raw, fallbackTitle, artworkUri)
        }

        internal fun fromRawTags(
            raw: RawAudioTags,
            fallbackTitle: String,
            artworkUri: String? = null
        ): AudioFileMetadata {
            val artist = raw.artist?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_ARTIST
            val album = raw.album?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_ALBUM
            return applyFilenameHints(
                AudioFileMetadata(
                    title = raw.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle,
                    artist = artist,
                    album = album,
                    genre = raw.genre?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_GENRE,
                    durationMs = raw.durationMs,
                    artworkUri = artworkUri,
                    trackNumber = raw.trackNumber,
                    year = raw.year,
                    lyrics = raw.lyrics?.trim()?.takeIf { it.isNotEmpty() }
                ),
                fallbackTitle
            )
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
            val artistInTitle = !artistWeak && (
                metadata.title.startsWith("${metadata.artist} - ", ignoreCase = true) ||
                metadata.title.startsWith("${metadata.artist}_-_", ignoreCase = true)
            )
            val titleWeak = tagTitleIsFilename ||
                metadata.title.trimStart().let { it.startsWith("-") || it.startsWith("_") } ||
                looksLikeStoragePath(metadata.title) ||
                isTrackNumberLabel(metadata.title.trim()) ||
                artistInTitle ||
                (artistWeak && (metadata.title.contains(" - ") || metadata.title.contains("_-_")))

            val trackNumber = metadata.trackNumber.takeIf { it > 0 }
                ?: hints.trackNumber
                ?: metadata.trackNumber

            if (!artistWeak && !titleWeak) {
                // Real ID3 tags exist. Do not mutate valid tags (Tauon principle).
                // Only fill trackNumber if missing in tags.
                return if (trackNumber != metadata.trackNumber) {
                    metadata.withIdentity { copy(trackNumber = trackNumber) }
                } else {
                    metadata
                }
            }

            val artist = when {
                artistWeak && !hints.artist.isNullOrBlank() -> hints.artist
                artistWeak -> FALLBACK_ARTIST
                else -> metadata.artist
            }
            // Keep a real ID3 title even when artist is Unknown; filename hints are for search.
            val title = when {
                titleWeak && !hints.title.isNullOrBlank() ->
                    IdentifyRanking.cleanIdentityTitle(hints.title, artist).ifBlank { hints.title }
                else ->
                    IdentifyRanking.cleanIdentityTitle(metadata.title, artist).ifBlank {
                        stripLeadingTitleJunk(metadata.title).ifBlank { metadata.title }
                    }
            }
            return metadata.withIdentity {
                copy(artist = artist, title = title, trackNumber = trackNumber)
            }
        }

        private fun readRetrieverTags(
            store: MusicFileStore,
            ref: AudioPersistRef
        ): RawAudioTags? {
            val retriever = MediaMetadataRetriever()
            return try {
                store.applyDataSource(retriever, ref)
                RawAudioTags(
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    year = parseTagYear(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    ),
                    trackNumber = parseCdTrackNumber(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                    ),
                    durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: 0L,
                    lyrics = null,
                    artworkBytes = retriever.embeddedPicture?.takeIf(ByteArray::isNotEmpty)
                )
            } catch (_: Exception) {
                null
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
