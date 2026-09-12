package com.bestiapop.android.data.repository

import android.content.Context
import com.bestiapop.android.data.db.MusicDao
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.LibraryTagWritePreferencesRepository
import com.bestiapop.android.data.util.AudioTagWriter
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.data.util.TagSyncSummary
import com.bestiapop.android.data.util.TagWriteResult
import com.bestiapop.android.domain.repository.LibraryScanProgress
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.libraryAlbumKeysInBucket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class FileTagSyncOperator(
    private val context: Context,
    private val musicDao: MusicDao,
    private val audioStore: RepositoryFileStore,
    private val tagWritePreferences: LibraryTagWritePreferencesRepository
) {
    val postponedTagWrites = ConcurrentHashMap<Long, Song>()
    var isSongActiveInPlayback: (Long) -> Boolean = { false }

    suspend fun syncTagsToFiles(onProgress: LibraryScanProgress?): TagSyncSummary =
        withContext(Dispatchers.IO) {
            val songs = musicDao.getIdentitySongs()
            var updated = 0
            var skipped = 0
            var errors = 0
            val total = songs.size
            songs.forEachIndexed { index, song ->
                onProgress?.invoke(index, total, song.title)
                when (writeTagsToFile(song)) {
                    TagWriteResult.Success -> updated++
                    TagWriteResult.Unsupported,
                    TagWriteResult.NotWritable,
                    TagWriteResult.PostponedActivePlayback -> skipped++
                    is TagWriteResult.IoError -> errors++
                }
            }
            onProgress?.invoke(total, total, "")
            TagSyncSummary(updated = updated, skipped = skipped, errors = errors)
        }

    /** Best-effort tag write when auto-write is enabled in Ajustes → Archivos. */
    suspend fun maybeWriteTags(song: Song) {
        val enabled = tagWritePreferences.settingsFlow.first().autoWriteTagsEnabled
        if (!enabled) return
        writeTagsToFile(song)
    }

    suspend fun maybeWriteTagsForAlbum(album: String) {
        val enabled = tagWritePreferences.settingsFlow.first().autoWriteTagsEnabled
        if (!enabled) return
        val songs = libraryAlbumKeysInBucket(
            musicDao.getIdentitySongs(),
            album,
            IdentifyRanking::isGenericAlbum
        ).ifEmpty { listOf(album) }
            .flatMap { musicDao.getSongsForAlbum(it) }
            .distinctBy { it.id }
        songs.forEach { writeTagsToFile(it) }
    }

    suspend fun flushPostponedTagWrites(activeSongId: Long? = null) = withContext(Dispatchers.IO) {
        if (postponedTagWrites.isEmpty()) return@withContext
        val toWrite = postponedTagWrites.filterKeys { it != activeSongId }
        for ((id, song) in toWrite) {
            postponedTagWrites.remove(id)
            writeTagsToFile(song)
        }
    }

    fun writeTagsToFile(song: Song): TagWriteResult {
        if (isSongActiveInPlayback(song.id)) {
            postponedTagWrites[song.id] = song
            return TagWriteResult.PostponedActivePlayback
        }
        val file = audioStore.writableFile(song.uriString, song.folderPath)
            ?: return TagWriteResult.NotWritable
        val result = AudioTagWriter.write(song, file)
        if (result is TagWriteResult.Success) {
            StorageUtils.scanFile(context, file.absolutePath)
        }
        return result
    }
}
