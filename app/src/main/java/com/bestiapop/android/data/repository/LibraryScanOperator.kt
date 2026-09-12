package com.bestiapop.android.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.MusicDao
import com.bestiapop.android.data.db.toSong
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.AlbumArtworkCache
import com.bestiapop.android.data.util.AudioFileMetadata
import com.bestiapop.android.data.util.AudioTagReader
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.domain.repository.LibraryScanProgress
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.isTrackNumberLabel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Encapsulates scanning and importing songs from MediaStore, SAF folder trees,
 * and the app-managed Music/BestiaPop directory.
 */
internal class LibraryScanOperator(
    private val context: Context,
    private val database: AppDatabase,
    private val musicDao: MusicDao,
    private val audioStore: RepositoryFileStore,
    private val identityCache: RepositoryIdentityCache,
    private val pruneUnplayableCorruptSongs: suspend () -> List<Song>
) {
    suspend fun scanMediaStore(onProgress: LibraryScanProgress?): List<Song> = withContext(Dispatchers.IO) {
        val existing = musicDao.getIdentitySongs()
        val dedup = libraryDedupSets(existing)
        val existingKeys = dedup.existingKeys
        val existingPaths = dedup.existingPaths

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )

        val scanned = mutableListOf<Song>()
        val ticker = ScanProgressTicker(cursor?.count ?: 0, onProgress)

        cursor?.use {
            while (it.moveToNext()) {
                val song = it.toSong()
                val tickName = song.title.ifBlank { song.folderPath.substringAfterLast('/') }
                ticker.tick(tickName)
                if (!isRealMusicTrack(song.durationMs, song.folderPath)) continue
                if (SongPathNormalizer.isUnderBestiaPop(song.folderPath) ||
                    SongPathNormalizer.isUnderBestiaPop(song.uriString)
                ) {
                    continue
                }
                val dataPath = song.folderPath.trim()
                val resolvedData = SongPathNormalizer.resolveFilePath(song.uriString, dataPath)?.lowercase()
                if (resolvedData != null && existingPaths.contains(resolvedData)) {
                    continue
                }
                if (dataPath.isNotEmpty() && existingPaths.contains(dataPath.lowercase())) {
                    continue
                }
                val key = TrackMatchKeys.matchKey(song.artist, song.title)
                if (key.isNotEmpty() && existingKeys.contains(key)) {
                    continue
                }
                val ref = audioStore.canonicalize(song.uriString, song.folderPath)
                scanned.add(song.copy(uriString = ref.uriString, folderPath = ref.folderPath))
                if (key.isNotEmpty()) existingKeys.add(key)
                if (dataPath.isNotEmpty()) existingPaths.add(dataPath.lowercase())
                if (resolvedData != null) existingPaths.add(resolvedData)
                existingPaths.add(ref.uriString.lowercase())
            }
        }

        persistInsertedSongs(scanned)
    }

    suspend fun resyncAppManagedMusic(onProgress: LibraryScanProgress?): List<Song> =
        withContext(Dispatchers.IO) {
            pruneUnplayableCorruptSongs()
            val managed = audioStore.listManaged()
            if (managed.isEmpty()) return@withContext emptyList()

            val existing = musicDao.getIdentitySongs()
            val dedup = libraryDedupSets(existing)

            val ticker = ScanProgressTicker(managed.size, onProgress)
            val inserted = persistInsertedSongs(
                indexSourcesParallel(
                    sources = managed.mapNotNull { file ->
                        if (!file.isFile || !isAudioFile(file.name)) return@mapNotNull null
                        IndexSource.fromFile(file, useCanonicalPathForMetadata = true)
                    },
                    dedup = dedup,
                    ticker = ticker
                )
            )
            if (existing.isNotEmpty()) {
                syncSongsRelations(database, musicDao, existing)
            }
            inserted
        }

    suspend fun scanFolderUri(treeUri: Uri, onProgress: LibraryScanProgress?): List<Song> =
        withContext(Dispatchers.IO) {
            val existing = musicDao.getIdentitySongs()
            val dedup = libraryDedupSets(existing)

            onProgress?.invoke(0, 0, "Buscando archivos…")
            val onFound: (Int) -> Unit = { found ->
                onProgress?.invoke(found, 0, "Buscando archivos…")
            }
            val mappedDir = SongPathNormalizer.toAbsolutePath(treeUri.toString())
                ?.let(::File)
                ?.takeIf { it.isDirectory }
            val diskFiles = mappedDir?.let { collectAudioFilesOrNull(it, onFound) }
            val sources = if (diskFiles != null) {
                diskFiles.map { IndexSource.fromFile(it, useCanonicalPathForMetadata = true) }
            } else {
                val rootFolder = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return@withContext emptyList()
                collectAudioDocuments(rootFolder, onFound = onFound).map { document ->
                    val file = document.file
                    IndexSource(
                        sourcePath = file.uri.toString(),
                        folderHint = document.folderName,
                        fallbackTitle = file.name?.substringBeforeLast(".") ?: "Unknown Track",
                        fileName = file.name ?: "audio",
                        fileDate = file.lastModified().takeIf { it > 0L },
                        useCanonicalPathForMetadata = false,
                        scanPhase = "folder_import_file",
                        crashPathKey = "uri"
                    )
                }
            }
            val ticker = ScanProgressTicker(sources.size, onProgress)
            persistInsertedSongs(indexSourcesParallel(sources, dedup, ticker))
        }

    fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String? {
        val ref = audioStore.canonicalize(audioPathOrUri)
        val fromTags = audioStore.readableFile(ref.uriString, ref.folderPath)
            ?.let { AudioTagReader.readArtworkBytes(it) }
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let { persistEmbeddedArtwork(it, identifier) }
        if (fromTags != null) return fromTags
        val retriever = MediaMetadataRetriever()
        try {
            audioStore.applyDataSource(retriever, ref)
            return retriever.embeddedPicture
                ?.takeIf(ByteArray::isNotEmpty)
                ?.let { persistEmbeddedArtwork(it, identifier) }
        } catch (e: Exception) {
            // ignore
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    internal fun persistEmbeddedArtwork(pictureBytes: ByteArray, identifier: String): String? {
        if (pictureBytes.isEmpty()) return null
        return try {
            val artDir = File(context.cacheDir, "album_art")
            if (!artDir.exists()) artDir.mkdirs()
            val artFile = File(artDir, "art_${identifier.hashCode()}.jpg")
            artFile.outputStream().use { out -> out.write(pictureBytes) }
            artFile.toURI().toString()
        } catch (_: Exception) {
            null
        }
    }

    private data class AudioDocument(
        val file: DocumentFile,
        val folderName: String
    )

    private fun collectAudioDocuments(
        folder: DocumentFile,
        destination: MutableList<AudioDocument> = mutableListOf(),
        onFound: ((Int) -> Unit)? = null
    ): List<AudioDocument> {
        for (file in folder.listFiles()) {
            if (file.isDirectory) {
                collectAudioDocuments(file, destination, onFound)
            } else if (file.isFile && isAudioFile(file.name ?: "")) {
                destination += AudioDocument(file, folder.name ?: "")
                onFound?.invoke(destination.size)
            }
        }
        return destination
    }

    private data class LibraryDedupSets(
        val existingKeys: MutableSet<String>,
        val existingPaths: MutableSet<String>
    )

    private fun libraryDedupSets(existing: List<Song>): LibraryDedupSets = LibraryDedupSets(
        existingKeys = existing.mapNotNull { song ->
            TrackMatchKeys.matchKey(song.artist, song.title).takeIf { it.isNotEmpty() }
        }.toHashSet(),
        existingPaths = existing.flatMap { song ->
            listOfNotNull(
                SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath),
                song.folderPath.takeIf { it.isNotBlank() && it.startsWith("/") },
                song.uriString.takeIf { it.isNotBlank() }
            )
        }.map { it.lowercase() }.toHashSet()
    )

    private suspend fun persistInsertedSongs(scanned: List<Song>): List<Song> {
        if (scanned.isEmpty()) return emptyList()
        musicDao.insertSongs(scanned)
        identityCache.invalidate()
        val inserted = scanned.map { it.uriString }
            .chunked(IDENTITY_SONG_ID_CHUNK)
            .flatMap { chunk -> musicDao.getSongsByUris(chunk) }
        syncSongsRelations(database, musicDao, inserted)
        return inserted
    }

    private data class IndexSource(
        val sourcePath: String,
        val folderHint: String,
        val fallbackTitle: String,
        val fileName: String,
        val fileDate: Long?,
        val useCanonicalPathForMetadata: Boolean,
        val scanPhase: String,
        val crashPathKey: String
    ) {
        companion object {
            fun fromFile(file: File, useCanonicalPathForMetadata: Boolean) = IndexSource(
                sourcePath = file.absolutePath,
                folderHint = file.parent ?: "",
                fallbackTitle = file.nameWithoutExtension,
                fileName = file.name,
                fileDate = file.lastModified().takeIf { it > 0L },
                useCanonicalPathForMetadata = useCanonicalPathForMetadata,
                scanPhase = if (useCanonicalPathForMetadata) "app_music_file" else "folder_import_file",
                crashPathKey = "path"
            )
        }
    }

    private fun collectAudioFilesOrNull(
        root: File,
        onFound: (Int) -> Unit
    ): List<File>? {
        if (root.listFiles() == null) return null
        val destination = ArrayList<File>()
        root.walkTopDown()
            .onEnter { dir -> dir.listFiles() != null }
            .forEach { file ->
                if (file.isFile && isAudioFile(file.name)) {
                    destination += file
                    onFound(destination.size)
                }
            }
        return destination
    }

    private suspend fun indexSourcesParallel(
        sources: List<IndexSource>,
        dedup: LibraryDedupSets,
        ticker: ScanProgressTicker
    ): List<Song> {
        if (sources.isEmpty()) return emptyList()
        val scanned = mutableListOf<Song>()
        val mutex = Mutex()
        val artworkCache = AlbumArtworkCache()
        coroutineScope {
            val gate = Semaphore(SCAN_FILE_PARALLEL)
            for (source in sources) {
                launch {
                    gate.withPermit {
                        ticker.tick(source.fileName)
                        tryIndexOneFile(
                            source = source,
                            dedup = dedup,
                            scanned = scanned,
                            mutex = mutex,
                            artworkCache = artworkCache
                        )
                    }
                }
            }
        }
        return scanned
    }

    private suspend fun tryIndexOneFile(
        source: IndexSource,
        dedup: LibraryDedupSets,
        scanned: MutableList<Song>,
        mutex: Mutex,
        artworkCache: AlbumArtworkCache
    ): Boolean {
        val ref = audioStore.canonicalize(source.sourcePath, source.folderHint)
        val pathKey = ref.uriString.lowercase()
        val sourceKey = source.sourcePath.lowercase()
        val resolvedSource = SongPathNormalizer.resolveFilePath(source.sourcePath, source.folderHint)
            ?.takeIf { it.isNotBlank() && it.startsWith("/") }
            ?.lowercase()
        val reserved = mutex.withLock {
            if (dedup.existingPaths.contains(pathKey) ||
                dedup.existingPaths.contains(sourceKey) ||
                (resolvedSource != null && dedup.existingPaths.contains(resolvedSource))
            ) {
                false
            } else {
                dedup.existingPaths.add(pathKey)
                dedup.existingPaths.add(sourceKey)
                if (resolvedSource != null) dedup.existingPaths.add(resolvedSource)
                true
            }
        }
        if (!reserved) return false
        return try {
            val metadata = AudioFileMetadata.fromPath(
                context = context,
                path = if (source.useCanonicalPathForMetadata) ref.uriString else source.sourcePath,
                fallbackTitle = source.fallbackTitle,
                persistEmbeddedArtwork = ::persistEmbeddedArtwork,
                artworkCache = artworkCache
            )
            if (!isRealMusicTrack(
                    durationMs = metadata.durationMs,
                    filePath = ref.uriString,
                    fileName = source.fileName,
                    allowUnknownDuration = true,
                    artist = metadata.artist,
                    title = metadata.title
                )
            ) {
                mutex.withLock {
                    dedup.existingPaths.remove(pathKey)
                    dedup.existingPaths.remove(sourceKey)
                    if (resolvedSource != null) dedup.existingPaths.remove(resolvedSource)
                }
                return false
            }
            val key = TrackMatchKeys.matchKey(metadata.artist, metadata.title)
            val resolvedDate = source.fileDate?.takeIf { it > 0L }
                ?: (if (!source.sourcePath.startsWith("content://")) {
                    File(source.sourcePath).takeIf { it.exists() && it.lastModified() > 0L }?.lastModified()
                } else null)
                ?: System.currentTimeMillis()
            mutex.withLock {
                if (key.isNotEmpty() && dedup.existingKeys.contains(key)) {
                    dedup.existingPaths.remove(pathKey)
                    dedup.existingPaths.remove(sourceKey)
                    if (resolvedSource != null) dedup.existingPaths.remove(resolvedSource)
                    return false
                }
                scanned.add(
                    metadata.toSong(
                        uriString = ref.uriString,
                        folderPath = ref.folderPath,
                        dateAdded = resolvedDate
                    )
                )
                if (key.isNotEmpty()) dedup.existingKeys.add(key)
            }
            true
        } catch (e: Exception) {
            mutex.withLock {
                dedup.existingPaths.remove(pathKey)
                dedup.existingPaths.remove(sourceKey)
                if (resolvedSource != null) dedup.existingPaths.remove(resolvedSource)
            }
            e.printStackTrace()
            CrashReporter.recordNonFatal(
                e,
                mapOf("scan_phase" to source.scanPhase, source.crashPathKey to source.sourcePath)
            )
            false
        }
    }

    private fun isRealMusicTrack(
        durationMs: Long,
        filePath: String,
        fileName: String = "",
        allowUnknownDuration: Boolean = false,
        artist: String = "",
        title: String = ""
    ): Boolean {
        if (durationMs <= 0L) {
            val nameForExt = fileName.ifBlank { filePath.substringAfterLast('/') }
            val unknownOk = allowUnknownDuration && isAudioFile(nameForExt) && hasUsableIdentity(artist, title)
            if (!unknownOk) return false
        } else if (durationMs < 30_000) {
            val nameForExt = fileName.ifBlank { filePath.substringAfterLast('/') }
            val shortOk = allowUnknownDuration && isAudioFile(nameForExt)
            if (!shortOk) return false
        }

        val pathLower = filePath.lowercase()
        val excludedFolders = listOf(
            "whatsapp", "telegram", "notifications", "ringtones",
            "alarms", "voice recorder", "callrecord", "recorder",
            "voice_notes", "cache"
        )
        if (excludedFolders.any { pathLower.contains(it) }) return false

        val fileLower = fileName.lowercase()
        if (fileLower.startsWith("aud-") || fileLower.startsWith("ptt-") || fileLower.startsWith("rec_")) {
            return false
        }

        return true
    }

    private fun isAudioFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".m4a") ||
                lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".aac") ||
                lower.endsWith(".webm") || lower.endsWith(".opus")
    }

    private fun hasUsableIdentity(artist: String, title: String): Boolean =
        !IdentifyRanking.isPlaceholderArtist(artist) ||
            (!isTrackNumberLabel(title) && !isPlaceholderTitle(title))
}
