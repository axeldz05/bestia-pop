package com.bestiapop.android.data.util

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Raw nullable fields from one tag reader (jaudiotagger or MediaMetadataRetriever).
 * Not a song DTO — callers coalesce into [AudioFileMetadata].
 */
internal data class RawAudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val durationMs: Long = 0L,
    val lyrics: String? = null,
    val artworkBytes: ByteArray? = null
) {
    val hasIdentity: Boolean
        get() = !artist.isNullOrBlank() || !album.isNullOrBlank() || !title.isNullOrBlank()

    val isComplete: Boolean
        get() = !title.isNullOrBlank() &&
            !artist.isNullOrBlank() &&
            !album.isNullOrBlank() &&
            durationMs > 0L &&
            artworkBytes != null &&
            artworkBytes.isNotEmpty()
}

/**
 * Reads embedded tags with jaudiotagger (same formats as [AudioTagWriter]).
 * Android [android.media.MediaMetadataRetriever] often returns null for ID3v2.4 / UTF-8.
 */
object AudioTagReader {

    fun isSupportedExtension(file: File): Boolean = AudioTagWriter.isSupportedExtension(file)

    internal fun read(file: File): RawAudioTags? {
        if (!file.isFile || !file.canRead()) return null
        if (!isSupportedExtension(file)) return null
        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag
            val header = audioFile.audioHeader
            val durationMs = headerDurationMs(header)
            if (tag == null && durationMs <= 0L) return null
            RawAudioTags(
                title = tag.tagValue(FieldKey.TITLE),
                artist = tag.tagValue(FieldKey.ARTIST),
                album = tag.tagValue(FieldKey.ALBUM),
                genre = tag.tagValue(FieldKey.GENRE),
                year = parseTagYear(tag.tagValue(FieldKey.YEAR)),
                trackNumber = parseCdTrackNumber(
                    tag.tagValue(FieldKey.TRACK),
                    tag.tagValue(FieldKey.DISC_NO)
                ),
                durationMs = durationMs,
                lyrics = tag.tagValue(FieldKey.LYRICS),
                artworkBytes = tag.artworkBytes()
            )
        } catch (_: Exception) {
            null
        }
    }

    fun readArtworkBytes(file: File): ByteArray? = read(file)?.artworkBytes
}

internal fun coalesceRawTags(primary: RawAudioTags?, fallback: RawAudioTags?): RawAudioTags {
    if (primary == null) return fallback ?: RawAudioTags()
    if (fallback == null) return primary
    return RawAudioTags(
        title = primary.title.orBlankToNull() ?: fallback.title,
        artist = primary.artist.orBlankToNull() ?: fallback.artist,
        album = primary.album.orBlankToNull() ?: fallback.album,
        genre = primary.genre.orBlankToNull() ?: fallback.genre,
        year = if (primary.year > 0) primary.year else fallback.year,
        trackNumber = if (primary.trackNumber > 0) primary.trackNumber else fallback.trackNumber,
        durationMs = if (primary.durationMs > 0L) primary.durationMs else fallback.durationMs,
        lyrics = primary.lyrics.orBlankToNull() ?: fallback.lyrics,
        artworkBytes = primary.artworkBytes?.takeIf { it.isNotEmpty() } ?: fallback.artworkBytes
    )
}

internal fun parseTagYear(raw: String?): Int {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return 0
    val four = Regex("""\b((?:19|20)\d{2})\b""").find(s)?.groupValues?.get(1)?.toIntOrNull()
    return four ?: 0
}

private fun String?.orBlankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun org.jaudiotagger.tag.Tag?.tagValue(key: FieldKey): String? {
    if (this == null) return null
    return runCatching { getFirst(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun org.jaudiotagger.tag.Tag?.artworkBytes(): ByteArray? {
    if (this == null) return null
    return runCatching { firstArtwork?.binaryData }.getOrNull()?.takeIf { it.isNotEmpty() }
}

private fun headerDurationMs(header: org.jaudiotagger.audio.AudioHeader?): Long {
    if (header == null) return 0L
    val precise = runCatching { header.preciseTrackLength }.getOrNull() ?: 0.0
    if (precise > 0.0) return (precise * 1000.0).toLong()
    val seconds = runCatching { header.trackLength }.getOrNull() ?: 0
    return if (seconds > 0) seconds * 1000L else 0L
}
