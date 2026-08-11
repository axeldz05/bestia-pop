package com.bestiapop.android.data.util

import com.bestiapop.android.data.model.Song
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

sealed class TagWriteResult {
    data object Success : TagWriteResult()
    data object Unsupported : TagWriteResult()
    data object NotWritable : TagWriteResult()
    data class IoError(val message: String) : TagWriteResult()
}

data class TagSyncSummary(
    val updated: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0
)

/**
 * Writes Room [Song] metadata into local audio file tags (jaudiotagger).
 * Supports mp3 / m4a / flac / ogg; other extensions → [TagWriteResult.Unsupported].
 */
object AudioTagWriter {

    private val SUPPORTED_EXT = setOf("mp3", "m4a", "mp4", "flac", "ogg", "oga")

    fun isSupportedExtension(file: File): Boolean =
        file.extension.lowercase() in SUPPORTED_EXT

    fun write(song: Song, file: File): TagWriteResult {
        if (!file.exists() || !file.isFile) return TagWriteResult.NotWritable
        if (!file.canWrite()) return TagWriteResult.NotWritable
        if (!isSupportedExtension(file)) return TagWriteResult.Unsupported
        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, song.title.ifBlank { "Unknown Track" })
            tag.setField(FieldKey.ARTIST, song.artist.ifBlank { "Unknown Artist" })
            tag.setField(FieldKey.ALBUM, song.album.ifBlank { "Unknown Album" })
            tag.setField(FieldKey.GENRE, song.genre.ifBlank { "Music" })
            if (song.year > 0) {
                tag.setField(FieldKey.YEAR, song.year.toString())
            }
            val track = albumTrackDisplayNumber(song.trackNumber)
            if (track > 0) {
                tag.setField(FieldKey.TRACK, track.toString())
            }
            writeArtworkIfLocal(tag, song.artworkUri)
            audioFile.commit()
            TagWriteResult.Success
        } catch (e: Exception) {
            TagWriteResult.IoError(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun writeArtworkIfLocal(tag: org.jaudiotagger.tag.Tag, artworkUri: String?) {
        val path = localArtworkPath(artworkUri) ?: return
        val artFile = File(path)
        if (!artFile.isFile || !artFile.canRead()) return
        runCatching {
            val artwork = ArtworkFactory.createArtworkFromFile(artFile)
            tag.deleteArtworkField()
            tag.setField(artwork)
        }
    }

    /** Absolute path for file:// or bare filesystem URIs; null for http/content CDN. */
    fun localArtworkPath(artworkUri: String?): String? {
        val raw = artworkUri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val lower = raw.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return null
        if (lower.startsWith("content://")) return null
        if (lower.startsWith("file:")) {
            // Avoid Android Uri stub quirks in JVM unit tests: strip file: / file:// prefix.
            val stripped = raw.removePrefix("file://").removePrefix("file:")
            return stripped.takeIf { it.startsWith("/") }
        }
        return raw.takeIf { it.startsWith("/") }
    }
}
