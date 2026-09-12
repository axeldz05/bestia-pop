package com.bestiapop.android.data.repository

import android.content.Context
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.MusicDao
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.AudioFileMetadata
import com.bestiapop.android.data.util.AudioPersistRef
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.fillSongGapsFromFileTags
import com.bestiapop.android.domain.util.needsGapIdentify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LegacyDatabaseMigrationOperator(
    private val context: Context,
    private val database: AppDatabase,
    private val musicDao: MusicDao,
    private val audioStore: RepositoryFileStore,
    private val identityCache: RepositoryIdentityCache,
    private val libraryScanOperator: LibraryScanOperator,
    private val identifySongMetadata: suspend (Song) -> Unit
) {
    suspend fun migrateCanonicalAudioUris() = withContext(Dispatchers.IO) {
        try {
            val songs = musicDao.getIdentitySongs()
            if (songs.isEmpty()) return@withContext
            val planned = songs.map { song ->
                val ref = audioStore.canonicalize(song.uriString, song.folderPath)
                val resolvedPath = SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
                    ?.takeIf { it.isNotBlank() && it.startsWith("/") }
                    ?.lowercase()
                val dedupKey = resolvedPath ?: ref.uriString.lowercase()
                Triple(song, ref, dedupKey)
            }
            val groups = planned.groupBy { it.third }
            database.withTransaction {
                for ((_, members) in groups) {
                    if (members.size == 1) {
                        val (song, ref, _) = members[0]
                        if (song.uriString != ref.uriString || song.folderPath != ref.folderPath) {
                            musicDao.updateSongUri(song.id, ref.uriString, ref.folderPath)
                        }
                        continue
                    }
                    val keepTriple = members.maxWithOrNull(
                        compareBy<Triple<Song, AudioPersistRef, String>> { !it.first.artworkUri.isNullOrBlank() }
                            .thenBy { it.first.durationMs > 0L }
                            .thenBy { it.second.uriString.startsWith("/") }
                            .thenByDescending { it.first.id }
                    ) ?: members.first()
                    val keep = keepTriple.first
                    val keepRef = keepTriple.second
                    var targetDuration = keep.durationMs
                    for ((drop, _, _) in members) {
                        if (drop.id == keep.id) continue
                        if (targetDuration <= 0L && drop.durationMs > 0L) {
                            targetDuration = drop.durationMs
                        }
                        remapPlaylistsThenDelete(dropId = drop.id, keepId = keep.id)
                    }
                    if (keep.uriString != keepRef.uriString || keep.folderPath != keepRef.folderPath) {
                        musicDao.updateSongUri(keep.id, keepRef.uriString, keepRef.folderPath)
                    }
                    if (targetDuration > 0L && targetDuration != keep.durationMs) {
                        musicDao.updateSongDuration(keep.id, targetDuration)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            CrashReporter.recordNonFatal(
                e,
                mapOf("migrate_phase" to "canonical_audio_uris")
            )
        }
    }

    private suspend fun remapPlaylistsThenDelete(dropId: Long, keepId: Long) {
        val keepPlaylists = musicDao.getPlaylistIdsForSong(keepId)
        if (keepPlaylists.isNotEmpty()) {
            musicDao.deleteSongFromPlaylists(dropId, keepPlaylists)
        }
        musicDao.remapPlaylistSongId(dropId, keepId)
        musicDao.deletePlayStatsForSongs(listOf(dropId))
        musicDao.deleteSong(dropId)
    }

    suspend fun migrateLegacyYouTubeMusicSongs() = withContext(Dispatchers.IO) {
        try {
            val legacySongs = musicDao.getLegacyYouTubeMusicSongs()
            if (legacySongs.isEmpty()) return@withContext

            for (song in legacySongs) {
                identifySongMetadata(song)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun migrateDateAddedFromDevice() = withContext(Dispatchers.IO) {
        try {
            val songs = musicDao.getIdentitySongs()
            if (songs.isEmpty()) return@withContext
            val updates = ArrayList<Pair<Long, Long>>()
            for (song in songs) {
                val resolvedDate = resolveDeviceDateForSong(song)
                if (resolvedDate != null && resolvedDate > 0L && resolvedDate != song.dateAdded) {
                    updates += song.id to resolvedDate
                }
            }
            if (updates.isEmpty()) return@withContext
            database.withTransaction {
                for ((id, dateAdded) in updates) {
                    musicDao.updateSongDateAdded(id, dateAdded)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            CrashReporter.recordNonFatal(
                e,
                mapOf("migrate_phase" to "device_date_added")
            )
        }
    }

    suspend fun migrateEmbeddedFileTags(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val songs = musicDao.getIdentitySongs()
            if (songs.isEmpty()) return@withContext emptyList()
            val candidates = songs.filter { song ->
                IdentifyRanking.isPlaceholderArtist(song.artist) ||
                        IdentifyRanking.isGenericAlbum(song.album) ||
                        IdentifyRanking.isGenericIdentifyTitle(song.title)
            }
            if (candidates.isEmpty()) return@withContext emptyList()
            val library = identityCache.getSongs()
            val updated = ArrayList<Song>()
            for (song in candidates) {
                val fallbackTitle = SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?: song.title
                val meta = try {
                    AudioFileMetadata.fromPath(
                        context = context,
                        path = song.uriString,
                        fallbackTitle = fallbackTitle,
                        artworkIdentifier = "${song.id}_${song.title}",
                        persistEmbeddedArtwork = libraryScanOperator::persistEmbeddedArtwork
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    continue
                }
                val filled = fillSongGapsFromFileTags(song, meta, library + updated)
                if (filled != song) updated += filled
            }
            if (updated.isNotEmpty()) {
                database.withTransaction {
                    for (song in updated) {
                        musicDao.updateSong(song)
                    }
                }
                identityCache.remember(updated)
            }
            val byId = songs.associateBy { it.id }.toMutableMap()
            for (song in updated) byId[song.id] = song
            candidates.mapNotNull { byId[it.id] }.filter { needsGapIdentify(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            CrashReporter.recordNonFatal(
                e,
                mapOf("migrate_phase" to "embedded_file_tags")
            )
            emptyList()
        }
    }

    private fun resolveDeviceDateForSong(song: Song): Long? {
        val uri = song.uriString
        if (uri.startsWith("content://media/")) {
            try {
                context.contentResolver.query(
                    uri.toUri(),
                    arrayOf(
                        MediaStore.Audio.Media.DATE_ADDED,
                        MediaStore.Audio.Media.DATE_MODIFIED,
                        MediaStore.Audio.Media.DATA
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dateAddedIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                        val dateAddedSec = if (dateAddedIdx != -1) cursor.getLong(dateAddedIdx) else 0L
                        val dateModifiedIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                        val dateModifiedSec = if (dateModifiedIdx != -1) cursor.getLong(dateModifiedIdx) else 0L
                        val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        val dataPath = if (dataIdx != -1) cursor.getString(dataIdx) else null

                        return when {
                            dateAddedSec > 0L -> dateAddedSec * 1000L
                            dateModifiedSec > 0L -> dateModifiedSec * 1000L
                            !dataPath.isNullOrBlank() && File(dataPath).exists() && File(dataPath).lastModified() > 0L ->
                                File(dataPath).lastModified()

                            else -> null
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val directPath = SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
            ?: song.folderPath.takeIf { it.isNotBlank() && !it.startsWith("content://") }
            ?: song.uriString.takeIf { it.isNotBlank() && !it.startsWith("content://") }

        if (!directPath.isNullOrBlank()) {
            try {
                val f = File(directPath)
                if (f.exists() && f.lastModified() > 0L) {
                    return f.lastModified()
                }
            } catch (_: Exception) {
            }
        }

        if (uri.startsWith("content://")) {
            try {
                context.contentResolver.query(
                    uri.toUri(),
                    arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (idx != -1) {
                            val modified = cursor.getLong(idx)
                            if (modified > 0L) return modified
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return null
    }
}
