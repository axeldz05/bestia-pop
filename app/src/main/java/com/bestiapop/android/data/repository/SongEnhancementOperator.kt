package com.bestiapop.android.data.repository

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.room.withTransaction
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.MusicDao
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.AudioTagReader
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.isTrackNumberLabel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SongEnhancementOperator(
    private val database: AppDatabase,
    private val musicDao: MusicDao,
    private val audioStore: RepositoryFileStore,
    private val metadataSource: RepositoryMetadataSource,
    private val libraryScanOperator: LibraryScanOperator,
    private val songIdentifyOperator: SongIdentifyOperator,
    private val fileTagSyncOperator: FileTagSyncOperator,
    private val setArtworkOnAlbumBucket: suspend (String, String?, List<Song>?) -> Unit
) {
    suspend fun findLocalLyrics(song: Song): String? =
        withContext(Dispatchers.IO) {
            val dbSong = if (song.id > 0L) musicDao.getSongById(song.id) else null
            val dbLyrics = dbSong?.lyrics?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            if (dbLyrics != null) return@withContext dbLyrics

            val file: File? = audioStore.readableFile(song.uriString, song.folderPath)
            val parent = file?.parentFile
            if (file != null && file.isFile && parent != null) {
                val companionText = readCleanTextFile(File(parent, "${file.nameWithoutExtension}.lrc"))
                    ?: readCleanTextFile(File(parent, "${song.title}.lrc"))
                if (companionText != null) return@withContext companionText

                val rawTags = AudioTagReader.read(file)
                val tagLyrics = rawTags?.lyrics?.trim()
                    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                if (tagLyrics != null) return@withContext tagLyrics
            }
            null
        }

    private fun readCleanTextFile(file: File?): String? {
        if (file == null || !file.isFile || !file.canRead()) return null
        return runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    suspend fun saveCompanionLrc(song: Song, lyrics: String): Boolean =
        withContext(Dispatchers.IO) {
            if (lyrics.isBlank()) return@withContext false
            val file: File = audioStore.readableFile(song.uriString, song.folderPath) ?: return@withContext false
            val parent = file.parentFile ?: return@withContext false
            if (!parent.canWrite()) return@withContext false
            val lrcFile = File(parent, "${file.nameWithoutExtension}.lrc")
            runCatching {
                lrcFile.writeText(lyrics.trim(), Charsets.UTF_8)
                true
            }.getOrDefault(false)
        }

    suspend fun fetchSongLyrics(song: Song): String? =
        withContext(Dispatchers.IO) {
            metadataSource.fetchLyrics(song.artist, song.title)
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        }

    suspend fun enhanceSongMetadataAndLyrics(song: Song) =
        enhanceSongMetadataAndLyricsBatch(listOf(song))

    suspend fun enhanceSongMetadataAndLyricsBatch(songs: List<Song>) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        val patches = songs.mapNotNull { prepareEnhancePatch(it) }
        if (patches.isEmpty()) return@withContext
        val identitySongs = musicDao.getIdentitySongs()
        database.withTransaction {
            for (patch in patches) {
                if (patch.metadataChanged) {
                    musicDao.updateMetadataAndLyrics(patch.songId, patch.artworkUri, patch.lyrics)
                }
                patch.durationMs?.let { musicDao.updateSongDuration(patch.songId, it) }
                patch.trackNumber?.let { musicDao.updateTrackNumber(patch.songId, it) }
            }
            val albumStamps = LinkedHashMap<String, String>()
            for (patch in patches) {
                val stamp = patch.albumStamp ?: continue
                albumStamps.putIfAbsent(stamp.first, stamp.second)
            }
            for ((album, art) in albumStamps) {
                setArtworkOnAlbumBucket(album, art, identitySongs)
            }
        }
        for (patch in patches) {
            patch.tagSong?.let { fileTagSyncOperator.maybeWriteTags(it) }
        }
    }

    private data class EnhancePatch(
        val songId: Long,
        val artworkUri: String?,
        val lyrics: String?,
        val metadataChanged: Boolean,
        val durationMs: Long?,
        val trackNumber: Int?,
        val albumStamp: Pair<String, String>?,
        val tagSong: Song?
    )

    private suspend fun prepareEnhancePatch(song: Song): EnhancePatch? {
        val persisted = musicDao.getSongById(song.id) ?: song
        val hasUsableArt = SongPathNormalizer.hasUsableArtwork(persisted.artworkUri)
        val hasLyrics = !persisted.lyrics.isNullOrEmpty()
        val hasDuration = persisted.durationMs > 0
        val hasTrackNumber = persisted.trackNumber > 0
        if (hasUsableArt && hasLyrics && hasDuration && hasTrackNumber) return null

        val albumName = if (persisted.album.isBlank()) "Unknown Album" else persisted.album
        val existingAlbumArt = musicDao.getArtworkForAlbum(albumName)

        var artUrl = if (hasUsableArt) persisted.artworkUri else existingAlbumArt

        if (!SongPathNormalizer.hasUsableArtwork(artUrl)) {
            val ref = audioStore.canonicalize(persisted.uriString, persisted.folderPath)
            val embedded = libraryScanOperator.extractAndSaveEmbeddedArtwork(ref.uriString, "${persisted.artist}_${albumName}")
            if (!embedded.isNullOrEmpty()) {
                artUrl = embedded
            } else if (!IdentifyRanking.isPlaceholderArtist(persisted.artist)) {
                val queryTerm = if (albumName != "Unknown Album") albumName else persisted.title
                artUrl = metadataSource.fetchAlbumArtUrl(persisted.artist, queryTerm)
            }
        }

        var lyricsStr = persisted.lyrics?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        if (lyricsStr.isNullOrEmpty() && !IdentifyRanking.isPlaceholderArtist(persisted.artist)) {
            lyricsStr = metadataSource.fetchLyrics(persisted.artist, persisted.title)
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        }

        var trackNumber: Int? = null
        if (!hasTrackNumber && hasUsableIdentity(persisted.artist, persisted.title)) {
            val resolvedTrack = songIdentifyOperator.resolveTrackNumberFallback(persisted.artist, persisted.title)
            if (resolvedTrack > 0) {
                trackNumber = resolvedTrack
            }
        }

        val metadataChanged = artUrl != persisted.artworkUri || lyricsStr != persisted.lyrics
        val tagSong = if ((metadataChanged || trackNumber != null) && (artUrl != persisted.artworkUri || trackNumber != null)) {
            persisted.copy(artworkUri = artUrl, lyrics = lyricsStr, trackNumber = trackNumber ?: persisted.trackNumber)
        } else {
            null
        }

        val albumStamp = if (!artUrl.isNullOrEmpty() &&
            !IdentifyRanking.isGenericAlbum(albumName) &&
            (existingAlbumArt.isNullOrEmpty() || existingAlbumArt != artUrl)
        ) {
            albumName to artUrl
        } else {
            null
        }

        var durationMs: Long? = null
        if (!hasDuration) {
            val ref = audioStore.canonicalize(persisted.uriString, persisted.folderPath)
            var calculatedDur = calculateAudioDurationMs(ref.uriString)
            if (calculatedDur <= 0 && !IdentifyRanking.isPlaceholderArtist(persisted.artist)) {
                calculatedDur = metadataSource.fetchTrackDurationMs(persisted.artist, persisted.title)
            }
            if (calculatedDur > 0) durationMs = calculatedDur
        }

        if (!metadataChanged && durationMs == null && albumStamp == null && trackNumber == null) return null
        return EnhancePatch(
            songId = persisted.id,
            artworkUri = artUrl,
            lyrics = lyricsStr,
            metadataChanged = metadataChanged,
            durationMs = durationMs,
            trackNumber = trackNumber,
            albumStamp = albumStamp,
            tagSong = tagSong
        )
    }

    fun calculateAudioDurationMs(audioPathOrUri: String): Long {
        val ref = audioStore.canonicalize(audioPathOrUri)

        try {
            val retriever = MediaMetadataRetriever()
            audioStore.applyDataSource(retriever, ref)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            val dur = durStr?.toLongOrNull() ?: 0L
            if (dur > 0) return dur
        } catch (_: Exception) {
        }

        try {
            val extractor = MediaExtractor()
            audioStore.applyDataSource(extractor, ref)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true && format.containsKey(MediaFormat.KEY_DURATION)) {
                    val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    extractor.release()
                    val durMs = durationUs / 1000L
                    if (durMs > 0) return durMs
                    break
                }
            }
            extractor.release()
        } catch (_: Exception) {
        }

        try {
            val player = MediaPlayer()
            audioStore.applyDataSource(player, ref)
            val dur = player.duration.toLong()
            player.release()
            if (dur > 0) return dur
        } catch (_: Exception) {
        }

        return 0L
    }

    private fun hasUsableIdentity(artist: String, title: String): Boolean =
        !IdentifyRanking.isPlaceholderArtist(artist) ||
                (!isTrackNumberLabel(title) && !isPlaceholderTitle(title))
}
