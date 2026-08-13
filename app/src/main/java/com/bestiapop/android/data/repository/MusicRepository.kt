package com.bestiapop.android.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.PlaylistEntity
import com.bestiapop.android.data.db.PlaylistPendingTrackEntity
import com.bestiapop.android.data.db.PlaylistSongCrossRef
import com.bestiapop.android.data.db.toSong
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.DownloadConflictPolicy
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.DuplicateSongException
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.model.mergePreferring
import com.bestiapop.android.data.model.toIdentity
import com.bestiapop.android.data.model.toListenBrainzCatalogTrack
import com.bestiapop.android.data.model.withIdentity
import com.bestiapop.android.data.model.youtubeSearchQuery
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.preferences.LibraryTagWritePreferencesRepository
import com.bestiapop.android.data.stream.StreamResolver
import com.bestiapop.android.data.util.AudioFileMetadata
import com.bestiapop.android.data.util.AudioPersistRef
import com.bestiapop.android.data.util.AudioTagWriter
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.data.util.TagSyncSummary
import com.bestiapop.android.data.util.TagWriteResult
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.data.util.copyTransferToFile
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.repository.LibraryScanProgress
import com.bestiapop.android.domain.util.FilenameMetadataHints
import com.bestiapop.android.domain.util.IdentifyCatalogQuery
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.mergeIdentityHints
import com.bestiapop.android.domain.util.parseFilenameMetadataHints
import com.bestiapop.android.domain.util.resolveWeakIdentityHints
import com.bestiapop.android.domain.util.isTrackNumberLabel
import com.bestiapop.android.domain.util.stripLeadingTitleJunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private class ScanProgressTicker(
    private val total: Int,
    private val onProgress: LibraryScanProgress?
) {
    private val done = AtomicInteger(0)
    fun tick(fileName: String) {
        onProgress?.invoke(done.incrementAndGet(), total, fileName)
    }
}

/**
 * Internal I/O seam used by repository integration tests. Production still delegates every
 * operation to the single [MusicFileStore] implementation.
 */
internal interface RepositoryFileStore {
    fun canonicalize(uriString: String, folderPath: String = ""): AudioPersistRef
    fun applyDataSource(retriever: MediaMetadataRetriever, ref: AudioPersistRef)
    fun applyDataSource(extractor: android.media.MediaExtractor, ref: AudioPersistRef)
    fun applyDataSource(player: android.media.MediaPlayer, ref: AudioPersistRef)
    fun prepareWrite(displayName: String): StorageUtils.PendingWrite
    fun delete(ref: AudioPersistRef)
    fun listManaged(): List<File>
    fun writableFile(uriString: String, folderPath: String = ""): File?
}

private class AndroidRepositoryFileStore(
    private val delegate: MusicFileStore
) : RepositoryFileStore {
    override fun canonicalize(uriString: String, folderPath: String): AudioPersistRef =
        delegate.canonicalize(uriString, folderPath)

    override fun applyDataSource(retriever: MediaMetadataRetriever, ref: AudioPersistRef) =
        delegate.applyDataSource(retriever, ref)

    override fun applyDataSource(
        extractor: android.media.MediaExtractor,
        ref: AudioPersistRef
    ) = delegate.applyDataSource(extractor, ref)

    override fun applyDataSource(player: android.media.MediaPlayer, ref: AudioPersistRef) =
        delegate.applyDataSource(player, ref)

    override fun prepareWrite(displayName: String): StorageUtils.PendingWrite =
        delegate.prepareWrite(displayName)

    override fun delete(ref: AudioPersistRef) = delegate.delete(ref)

    override fun listManaged(): List<File> = delegate.listManaged()

    override fun writableFile(uriString: String, folderPath: String): File? =
        delegate.writableFile(uriString, folderPath)
}

/** Network metadata seam; keeps repository tests hermetic without changing production behavior. */
internal interface RepositoryMetadataSource {
    suspend fun fetchAlbumArtUrl(artist: String, titleOrAlbum: String): String?
    suspend fun fetchLyrics(artist: String, title: String): String?
    suspend fun fetchTrackDurationMs(artist: String, title: String): Long
    suspend fun fetchFullTrackMetadata(artist: String, title: String): TrackIdentity?
    suspend fun searchOnlineCatalog(
        query: String,
        limit: Int = 25,
        index: Int = 0
    ): List<OnlineCatalogTrack>
}

private object ProductionRepositoryMetadataSource : RepositoryMetadataSource {
    override suspend fun fetchAlbumArtUrl(artist: String, titleOrAlbum: String): String? =
        MetadataFetcher.fetchAlbumArtUrl(artist, titleOrAlbum)

    override suspend fun fetchLyrics(artist: String, title: String): String? =
        MetadataFetcher.fetchLyrics(artist, title)

    override suspend fun fetchTrackDurationMs(artist: String, title: String): Long =
        MetadataFetcher.fetchTrackDurationMs(artist, title)

    override suspend fun fetchFullTrackMetadata(artist: String, title: String): TrackIdentity? =
        MetadataFetcher.fetchFullTrackMetadata(artist, title)

    override suspend fun searchOnlineCatalog(
        query: String,
        limit: Int,
        index: Int
    ): List<OnlineCatalogTrack> = MetadataFetcher.searchOnlineCatalog(query, limit, index)
}

/**
 * OkHttp's blocking execute is not coroutine-aware. Keep cancellation wired to [okhttp3.Call.cancel]
 * for the entire response-body copy so cancelling a download closes the socket and releases the
 * partial file cleanup path immediately.
 */
private suspend fun <T> okhttp3.Call.useCancellable(
    block: (okhttp3.Response) -> T
): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    try {
        val value = execute().use(block)
        continuation.resume(value)
    } catch (error: Throwable) {
        continuation.resumeWithException(error)
    }
}

internal fun PlaylistPendingTrackEntity.toPendingTrack() = PlaylistPendingTrack(
    identity = TrackIdentity(
        title = title,
        artist = artist,
        album = releaseName.orEmpty(),
        trackNumber = trackNumber
    ),
    id = id,
    playlistId = playlistId,
    recordingMbid = recordingMbid,
    position = position
)

internal fun PlaylistPendingTrack.toEntity() = PlaylistPendingTrackEntity(
    id = id,
    playlistId = playlistId,
    title = title,
    artist = artist,
    releaseName = album.takeIf { it.isNotBlank() },
    trackNumber = trackNumber,
    recordingMbid = recordingMbid,
    position = position
)

private data class MusicRepositoryDependencies(
    val db: AppDatabase,
    val streamResolver: StreamResolver,
    val audioStore: RepositoryFileStore,
    val downloadCallFactory: okhttp3.Call.Factory,
    val metadataSource: RepositoryMetadataSource,
    val downloadRetryDelay: suspend (Long) -> Unit
)

private fun productionDependencies(context: Context) = MusicRepositoryDependencies(
    db = AppDatabase.getDatabase(context),
    streamResolver = StreamResolver(),
    audioStore = AndroidRepositoryFileStore(MusicFileStore(context)),
    downloadCallFactory = com.bestiapop.android.data.network.HttpClients.transfer,
    metadataSource = ProductionRepositoryMetadataSource,
    downloadRetryDelay = { millis -> delay(millis) }
)

class MusicRepository private constructor(
    private val context: Context,
    dependencies: MusicRepositoryDependencies
) : IMusicRepository {

    constructor(context: Context) : this(
        context = context.applicationContext,
        dependencies = productionDependencies(context.applicationContext)
    )

    internal constructor(
        context: Context,
        database: AppDatabase,
        streamResolver: StreamResolver = StreamResolver(),
        audioStore: RepositoryFileStore = AndroidRepositoryFileStore(
            MusicFileStore(context.applicationContext)
        ),
        downloadCallFactory: okhttp3.Call.Factory =
            com.bestiapop.android.data.network.HttpClients.transfer,
        metadataSource: RepositoryMetadataSource = ProductionRepositoryMetadataSource,
        downloadRetryDelay: suspend (Long) -> Unit = { millis -> delay(millis) }
    ) : this(
        context = context.applicationContext,
        dependencies = MusicRepositoryDependencies(
            db = database,
            streamResolver = streamResolver,
            audioStore = audioStore,
            downloadCallFactory = downloadCallFactory,
            metadataSource = metadataSource,
            downloadRetryDelay = downloadRetryDelay
        )
    )

    private val db = dependencies.db
    val streamResolver = dependencies.streamResolver
    private val audioStore = dependencies.audioStore
    private val downloadCallFactory = dependencies.downloadCallFactory
    private val metadataSource = dependencies.metadataSource
    private val downloadRetryDelay = dependencies.downloadRetryDelay
    private val musicDao = db.musicDao()
    private val tagWritePreferences = LibraryTagWritePreferencesRepository(context)

    override val allSongsFlow: Flow<List<Song>> = musicDao.getAllSongsFlow()

    override val albumOverridesFlow: Flow<List<AlbumOverride>> =
        musicDao.getAllAlbumOverridesFlow()

    override val playlistsFlow: Flow<List<Playlist>> = musicDao.getAllPlaylistsFlow().map { entities ->
        entities.map { entity ->
            Playlist(
                id = entity.playlistId,
                name = entity.name,
                description = entity.description,
                coverUri = entity.coverUri,
                createdAt = entity.createdAt
            )
        }
    }

    override fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> {
        return musicDao.getPlaylistWithSongsFlow(playlistId).map { withSongs ->
            withSongs?.songs ?: emptyList()
        }
    }

    override fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> {
        return musicDao.getPlaylistWithSongsFlow(playlistId).map { withSongs ->
            if (withSongs == null) null
            else {
                val entity = withSongs.playlist
                val playlist = Playlist(
                    id = entity.playlistId,
                    name = entity.name,
                    description = entity.description,
                    coverUri = entity.coverUri,
                    songCount = withSongs.songs.size,
                    createdAt = entity.createdAt
                )
                Pair(playlist, withSongs.songs)
            }
        }
    }

    override suspend fun getPlaylistSongsOrdered(playlistId: Long): List<Song> =
        withContext(Dispatchers.IO) {
            musicDao.getPlaylistSongsOrdered(playlistId)
        }

    override suspend fun scanMediaStore(onProgress: LibraryScanProgress?) = withContext(Dispatchers.IO) {
        val existing = musicDao.getAllSongs()
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
            MediaStore.Audio.Media.ALBUM_ID
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
                existingPaths.add(ref.uriString.lowercase())
            }
        }

        if (scanned.isNotEmpty()) {
            musicDao.insertSongs(scanned)
        }
    }

    override suspend fun resyncAppManagedMusic(onProgress: LibraryScanProgress?): Int =
        withContext(Dispatchers.IO) {
            val managed = audioStore.listManaged()
            if (managed.isEmpty()) return@withContext 0

            val existing = musicDao.getAllSongs()
            val dedup = libraryDedupSets(existing)

            val ticker = ScanProgressTicker(managed.size, onProgress)
            val scanned = mutableListOf<Song>()
            indexAudioFiles(
                files = managed,
                list = scanned,
                existingKeys = dedup.existingKeys,
                existingPaths = dedup.existingPaths,
                onFileVisited = ticker::tick
            )
            if (scanned.isNotEmpty()) {
                musicDao.insertSongs(scanned)
            }
            scanned.size
        }

    override suspend fun findSongByArtistTitle(artist: String, title: String): Song? =
        withContext(Dispatchers.IO) {
            lookupSongByArtistTitle(artist, title)
        }

    override suspend fun scanFolderUri(treeUri: Uri, onProgress: LibraryScanProgress?): Int =
        withContext(Dispatchers.IO) {
            val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
            val existing = musicDao.getAllSongs()
            val dedup = libraryDedupSets(existing)

            val documents = collectAudioDocuments(rootFolder)
            val ticker = ScanProgressTicker(documents.size, onProgress)
            val scanned = mutableListOf<Song>()
            for (document in documents) {
                val file = document.file
                val fileName = file.name ?: "audio"
                ticker.tick(fileName)
                tryIndexOneFile(
                    sourcePath = file.uri.toString(),
                    folderHint = document.folderName,
                    fallbackTitle = file.name?.substringBeforeLast(".") ?: "Unknown Track",
                    fileName = file.name ?: "",
                    existingKeys = dedup.existingKeys,
                    existingPaths = dedup.existingPaths,
                    list = scanned,
                    useCanonicalPathForMetadata = false,
                    scanPhase = "folder_import_file",
                    crashPathKey = "uri"
                )
            }
            if (scanned.isNotEmpty()) {
                musicDao.insertSongs(scanned)
            }
            scanned.size
        }

    private data class AudioDocument(
        val file: DocumentFile,
        val folderName: String
    )

    private fun collectAudioDocuments(
        folder: DocumentFile,
        destination: MutableList<AudioDocument> = mutableListOf()
    ): List<AudioDocument> {
        for (file in folder.listFiles()) {
            if (file.isDirectory) {
                collectAudioDocuments(file, destination)
            } else if (file.isFile && isAudioFile(file.name ?: "")) {
                destination += AudioDocument(file, folder.name ?: "")
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
        // Both spellings: scans probe the canonical uriString, which for a SAF folder import is a
        // `content://…/documents/…` URI, while resolveFilePath only ever yields an absolute path —
        // so indexing just the resolved path made path dedupe miss every SAF re-import.
        existingPaths = existing.flatMap { song ->
            listOfNotNull(
                SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath),
                song.uriString.takeIf { it.isNotBlank() }
            )
        }.map { it.lowercase() }.toHashSet()
    )

    private fun tryIndexOneFile(
        sourcePath: String,
        folderHint: String,
        fallbackTitle: String,
        fileName: String,
        existingKeys: MutableSet<String>,
        existingPaths: MutableSet<String>,
        list: MutableList<Song>,
        useCanonicalPathForMetadata: Boolean,
        scanPhase: String,
        crashPathKey: String = "path"
    ): Boolean {
        val ref = audioStore.canonicalize(sourcePath, folderHint)
        val pathKey = ref.uriString.lowercase()
        if (existingPaths.contains(pathKey) || existingPaths.contains(sourcePath.lowercase())) return false
        return try {
            val metadata = AudioFileMetadata.fromPath(
                context = context,
                path = if (useCanonicalPathForMetadata) ref.uriString else sourcePath,
                fallbackTitle = fallbackTitle,
                persistEmbeddedArtwork = ::persistEmbeddedArtwork
            )
            if (!isRealMusicTrack(
                    durationMs = metadata.durationMs,
                    filePath = ref.uriString,
                    fileName = fileName,
                    allowUnknownDuration = true
                )
            ) {
                return false
            }
            val key = TrackMatchKeys.matchKey(metadata.artist, metadata.title)
            if (key.isNotEmpty() && existingKeys.contains(key)) return false
            list.add(
                metadata.toSong(
                    uriString = ref.uriString,
                    folderPath = ref.folderPath
                )
            )
            if (key.isNotEmpty()) existingKeys.add(key)
            existingPaths.add(pathKey)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            com.bestiapop.android.data.util.CrashReporter.recordNonFatal(
                e,
                mapOf("scan_phase" to scanPhase, crashPathKey to sourcePath)
            )
            false
        }
    }

    /** Index audio files under public Music/BestiaPop (absolute paths, same as downloads). */
    private fun indexAudioFiles(
        files: List<File>,
        list: MutableList<Song>,
        existingKeys: MutableSet<String>,
        existingPaths: MutableSet<String>,
        onFileVisited: ((String) -> Unit)? = null
    ) {
        for (file in files) {
            if (!file.isFile || !isAudioFile(file.name)) continue
            onFileVisited?.invoke(file.name)
            tryIndexOneFile(
                sourcePath = file.absolutePath,
                folderHint = file.parent ?: "",
                fallbackTitle = file.nameWithoutExtension,
                fileName = file.name,
                existingKeys = existingKeys,
                existingPaths = existingPaths,
                list = list,
                useCanonicalPathForMetadata = true,
                scanPhase = "app_music_file"
            )
        }
    }

    override fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String? {
        val retriever = MediaMetadataRetriever()
        try {
            audioStore.applyDataSource(retriever, audioStore.canonicalize(audioPathOrUri))
            return retriever.embeddedPicture
                ?.takeIf(ByteArray::isNotEmpty)
                ?.let { persistEmbeddedArtwork(it, identifier) }
        } catch (e: Exception) {
            // ignore
        } finally {
            try { retriever.release() } catch (ignored: Exception) {}
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

    private fun isRealMusicTrack(
        durationMs: Long,
        filePath: String,
        fileName: String = "",
        allowUnknownDuration: Boolean = false
    ): Boolean {
        // Minimum duration 30s; unknown duration (0) allowed for explicit folder / app-dir reindex.
        if (durationMs < 30_000) {
            val nameForExt = fileName.ifBlank { filePath.substringAfterLast('/') }
            val unknownOk = allowUnknownDuration && durationMs == 0L && isAudioFile(nameForExt)
            if (!unknownOk) return false
        }

        // Exclude WhatsApp, Telegram, Notifications, Ringtones, Voice Notes folders
        val pathLower = filePath.lowercase()
        val excludedFolders = listOf(
            "whatsapp", "telegram", "notifications", "ringtones",
            "alarms", "voice recorder", "callrecord", "recorder",
            "voice_notes", "cache"
        )
        if (excludedFolders.any { pathLower.contains(it) }) return false

        // Exclude WhatsApp voice note filename patterns (e.g. AUD-..., PTT-...)
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

    override suspend fun getAllSongsSync(): List<Song> = withContext(Dispatchers.IO) {
        musicDao.getAllSongs()
    }

    override suspend fun saveUploadedSong(song: Song): Long = withContext(Dispatchers.IO) {
        val ref = audioStore.canonicalize(song.uriString, song.folderPath)
        val normalized = song.copy(uriString = ref.uriString, folderPath = ref.folderPath)
        val key = TrackMatchKeys.matchKey(normalized.artist, normalized.title)
        if (key.isNotEmpty()) {
            val existing = lookupSongByArtistTitle(normalized.artist, normalized.title)
            if (existing != null) {
                val oldRef = audioStore.canonicalize(existing.uriString, existing.folderPath)
                if (oldRef.uriString != normalized.uriString &&
                    !SongPathNormalizer.pathsReferToSameFile(oldRef.uriString, normalized.uriString)
                ) {
                    audioStore.delete(oldRef)
                }
                val updated = existing.copy(
                    uriString = normalized.uriString,
                    title = normalized.title,
                    artist = normalized.artist,
                    album = normalized.album,
                    genre = normalized.genre,
                    durationMs = normalized.durationMs,
                    artworkUri = normalized.artworkUri ?: existing.artworkUri,
                    folderPath = normalized.folderPath.ifBlank { existing.folderPath },
                    trackNumber = if (normalized.trackNumber > 0) {
                        normalized.trackNumber
                    } else {
                        existing.trackNumber
                    }
                )
                musicDao.updateSong(updated)
                return@withContext existing.id
            }
        }
        insertOrUpdateByUri(normalized)
    }

    /**
     * Insert keeping the existing row id when `uriString` already exists, so playlist membership and
     * app-side state (lyrics, lastPlayedAt, dateAdded) survive a re-import of the same file.
     */
    private suspend fun insertOrUpdateByUri(song: Song): Long {
        val insertedId = musicDao.insertSong(song)
        if (insertedId != -1L) return insertedId
        val existing = musicDao.getSongByUri(song.uriString) ?: return -1L
        musicDao.updateSong(
            song.copy(
                id = existing.id,
                dateAdded = existing.dateAdded,
                lastPlayedAt = existing.lastPlayedAt,
                lyrics = song.lyrics ?: existing.lyrics,
                artworkUri = song.artworkUri ?: existing.artworkUri
            )
        )
        return existing.id
    }

    override suspend fun deleteSongsFromApp(songs: List<Song>) = withContext(Dispatchers.IO) {
        deleteSongRows(songs)
    }

    /** Single exit for row removal so no caller can forget the cross-ref cleanup (no FK/cascade). */
    private suspend fun deleteSongRows(songs: List<Song>) {
        val ids = songs.map { it.id }
        if (ids.isEmpty()) return
        musicDao.deletePlaylistRefsForSongs(ids)
        musicDao.deleteSongsByIds(ids)
    }

    override suspend fun deleteSongsFromDevice(songs: List<Song>) = withContext(Dispatchers.IO) {
        songs.forEach { song ->
            try {
                audioStore.delete(audioStore.canonicalize(song.uriString, song.folderPath))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        deleteSongRows(songs)
    }

    private fun hasUsableArtwork(artworkUri: String?): Boolean =
        SongPathNormalizer.hasUsableArtwork(artworkUri)

    override suspend fun enhanceSongMetadataAndLyrics(song: Song) = withContext(Dispatchers.IO) {
        val hasUsableArt = hasUsableArtwork(song.artworkUri)
        val hasLyrics = !song.lyrics.isNullOrEmpty()
        val hasDuration = song.durationMs > 0
        if (hasUsableArt && hasLyrics && hasDuration) return@withContext

        val albumName = if (song.album.isBlank()) "Unknown Album" else song.album
        val existingAlbumArt = musicDao.getArtworkForAlbum(albumName)

        var artUrl = if (hasUsableArt) song.artworkUri else existingAlbumArt

        if (!hasUsableArtwork(artUrl)) {
            val ref = audioStore.canonicalize(song.uriString, song.folderPath)
            val embedded = extractAndSaveEmbeddedArtwork(ref.uriString, "${song.artist}_${albumName}")
            if (!embedded.isNullOrEmpty()) {
                artUrl = embedded
            } else {
                val queryTerm = if (albumName != "Unknown Album") albumName else song.title
                artUrl = metadataSource.fetchAlbumArtUrl(song.artist, queryTerm)
            }
        }

        var lyricsStr = song.lyrics
        if (lyricsStr.isNullOrEmpty()) {
            lyricsStr = metadataSource.fetchLyrics(song.artist, song.title)
        }

        if (artUrl != song.artworkUri || lyricsStr != song.lyrics) {
            musicDao.updateMetadataAndLyrics(song.id, artUrl, lyricsStr)
            if (artUrl != song.artworkUri) {
                maybeWriteTags(song.copy(artworkUri = artUrl, lyrics = lyricsStr))
            }
        }

        // Never album-wide for a generic album: "Unknown Album" is the literal stored for every
        // albumless song, so one fetched cover was stamped across all of them.
        if (!artUrl.isNullOrEmpty() &&
            !IdentifyRanking.isGenericAlbum(albumName) &&
            (existingAlbumArt.isNullOrEmpty() || existingAlbumArt != artUrl)
        ) {
            musicDao.setAlbumArtwork(albumName, artUrl)
        }

        if (song.durationMs <= 0) {
            val ref = audioStore.canonicalize(song.uriString, song.folderPath)
            var calculatedDur = calculateAudioDurationMs(ref.uriString)
            if (calculatedDur <= 0) {
                calculatedDur = metadataSource.fetchTrackDurationMs(song.artist, song.title)
            }
            if (calculatedDur > 0) {
                musicDao.updateSongDuration(song.id, calculatedDur)
            }
        }
    }

    override suspend fun proposeSongIdentity(
        song: Song,
        customQuery: String?,
        force: Boolean,
        listenBrainzToken: String?,
        filters: IdentifySearchFilters,
        catalogIndex: Int,
        existingCandidates: List<IdentifyCandidate>
    ): IdentifyProposal = withContext(Dispatchers.IO) {
        val normalizedFilters = filters.normalized()
        val isExpand = catalogIndex > 0 || existingCandidates.isNotEmpty()
        if (!force && !isExpand && !needsMetadataIdentify(song.artist, song.album)) {
            return@withContext IdentifyProposal(
                songId = song.id,
                queryArtist = song.artist,
                queryTitle = song.title,
                alreadyIdentified = true,
                confidence = IdentifyConfidence.NONE
            )
        }

        val path = SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
        val baseName = path
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?: song.uriString.substringAfterLast('/').substringBeforeLast('.')
        val fileHints = if (looksLikeStoragePath(baseName)) {
            FilenameMetadataHints(artist = null, title = null)
        } else {
            parseFilenameMetadataHints(baseName)
        }
        val hints = mergeIdentityHints(
            resolveWeakIdentityHints(song.artist, song.title),
            fileHints
        )
        val working = if (isExpand) song else persistWeakIdentityCleanup(song, hints)
        val filenameArtist = hints.artist?.takeUnless { looksLikeStoragePath(it) }
        val filenameTitle = hints.title?.takeUnless { looksLikeStoragePath(it) }

        var queryArtist = working.artist
        var queryTitle = working.title
        if (IdentifyRanking.isPlaceholderArtist(queryArtist)) {
            // Drop track-number / Unknown artists from the search string; title-only works better.
            queryArtist = hints.artist?.takeIf { it.isNotBlank() }.orEmpty()
            if (!hints.title.isNullOrBlank()) queryTitle = hints.title
        } else if (!hints.title.isNullOrBlank() &&
            (queryTitle.trimStart().startsWith("-") || looksLikeStoragePath(queryTitle))
        ) {
            queryTitle = hints.title
        }

        val tagHints = listOfNotNull(
            working.artist.takeUnless { IdentifyRanking.isPlaceholderArtist(it) },
            working.title.takeUnless { it.isBlank() || looksLikeStoragePath(it) },
            working.album.takeUnless { IdentifyRanking.isGenericAlbum(it) }
        ).joinToString(" · ").ifBlank { null }
        val filenameHint = listOfNotNull(
            filenameArtist?.takeIf { it.isNotBlank() },
            filenameTitle?.takeIf { it.isNotBlank() }
        ).takeIf { it.size == 2 }?.joinToString(" · ")
            ?: filenameTitle?.takeIf { it.isNotBlank() }
        val sourceHints = tagHints ?: filenameHint

        val trimmedCustom = customQuery?.trim().orEmpty()
        val artistPlaceholder = queryArtist.isBlank() ||
            IdentifyRanking.isPlaceholderArtist(queryArtist)
        val filterArtist = normalizedFilters.artist.takeUnless {
            it.isBlank() || IdentifyRanking.isPlaceholderArtist(it)
        }
        val filterAlbum = normalizedFilters.album.takeUnless {
            it.isBlank() || IdentifyRanking.isGenericAlbum(it)
        }
        val preferYear = when {
            normalizedFilters.year in 1000..9999 -> normalizedFilters.year
            working.year in 1000..9999 -> working.year
            else -> 0
        }

        val refineSearch = trimmedCustom.isNotEmpty() || normalizedFilters.hasAny || isExpand
        val catalogFreeText: String? = when {
            trimmedCustom.isNotEmpty() -> trimmedCustom
            // Expand / load-more without refine fields: same default artist+title query.
            isExpand && !normalizedFilters.hasAny ->
                if (artistPlaceholder) queryTitle else youtubeSearchQuery(queryArtist, queryTitle)
            else -> null
        }
        val catalogQuery = if (refineSearch) {
            IdentifyCatalogQuery.build(catalogFreeText, normalizedFilters).ifBlank {
                if (artistPlaceholder) queryTitle else youtubeSearchQuery(queryArtist, queryTitle)
            }
        } else {
            ""
        }

        val pageIndex = catalogIndex.coerceAtLeast(0)
        var fetchedCount = 0
        var tracks = if (refineSearch) {
            val page = metadataSource.searchOnlineCatalog(
                query = catalogQuery,
                limit = IdentifyRanking.CATALOG_PAGE,
                index = pageIndex
            )
            fetchedCount = page.size
            page
        } else {
            fetchIdentifyCatalogTracks(queryArtist, queryTitle, artistPlaceholder)
        }

        val rankingQuery = IdentifyRanking.Query(
            artist = filterArtist ?: queryArtist,
            title = if (trimmedCustom.isNotEmpty()) trimmedCustom else queryTitle,
            durationMs = working.durationMs,
            filenameArtist = filenameArtist,
            filenameTitle = filenameTitle,
            artistIsPlaceholder = filterArtist == null && artistPlaceholder && trimmedCustom.isEmpty(),
            sourceArtist = filterArtist
                ?: working.artist.takeUnless { IdentifyRanking.isPlaceholderArtist(it) },
            sourceTitle = working.title.takeUnless { it.isBlank() || looksLikeStoragePath(it) },
            sourceAlbum = filterAlbum
                ?: working.album.takeUnless { IdentifyRanking.isGenericAlbum(it) },
            preferYear = preferYear
        )

        val rankLimit = if (isExpand || refineSearch) {
            IdentifyRanking.CATALOG_PAGE
        } else {
            IdentifyRanking.TOP_N
        }
        var ranked = IdentifyRanking.rank(rankingQuery, tracks, limit = rankLimit)
        if (existingCandidates.isNotEmpty()) {
            ranked = IdentifyRanking.appendCandidates(existingCandidates, ranked)
        }
        var confidence = IdentifyRanking.confidence(ranked)
        var usedListenBrainz = false

        val token = listenBrainzToken?.trim().orEmpty()
        val canEnrichLb = !isExpand &&
            trimmedCustom.isEmpty() &&
            !normalizedFilters.hasAny &&
            token.isNotEmpty() &&
            !artistPlaceholder &&
            confidence != IdentifyConfidence.HIGH
        if (canEnrichLb) {
            val releaseHint = working.album.takeUnless { IdentifyRanking.isGenericAlbum(it) }
            val lbTrack = fetchListenBrainzIdentifyTrack(
                artist = queryArtist,
                title = queryTitle,
                releaseName = releaseHint,
                token = token
            )
            if (lbTrack != null) {
                usedListenBrainz = true
                tracks = mergeIdentifyCatalogTracks(tracks, listOf(lbTrack))
                ranked = IdentifyRanking.rank(rankingQuery, tracks, limit = rankLimit)
                confidence = IdentifyRanking.confidence(ranked)
            }
        }

        val nextIndex = if (refineSearch) {
            pageIndex + fetchedCount
        } else {
            0
        }
        val mayHaveMore = when {
            refineSearch -> fetchedCount >= IdentifyRanking.CATALOG_PAGE
            ranked.isNotEmpty() -> true
            else -> false
        }

        IdentifyProposal(
            songId = song.id,
            queryArtist = queryArtist,
            queryTitle = if (trimmedCustom.isNotEmpty()) trimmedCustom else queryTitle,
            sourceHints = sourceHints,
            candidates = ranked,
            confidence = confidence,
            suggested = ranked.firstOrNull(),
            usedListenBrainz = usedListenBrainz,
            nextCatalogIndex = nextIndex,
            catalogMayHaveMore = mayHaveMore
        )
    }

    override suspend fun applySongIdentity(
        songId: Long,
        candidate: IdentifyCandidate
    ): IdentifyResult = withContext(Dispatchers.IO) {
        val entity = musicDao.getSongById(songId) ?: return@withContext IdentifyResult.NoMatch
        // Prefer candidate over Room; strip generic album before merge so entity can fill.
        val preferred = candidate.track.identity.copy(
            album = candidate.album
                .takeIf { it.isNotBlank() && !IdentifyRanking.isGenericAlbum(it) }
                .orEmpty()
        )
        val merged = preferred.mergePreferring(entity.toIdentity())
        val cleaned = merged.copy(
            title = IdentifyRanking.cleanIdentityTitle(merged.title).ifBlank { merged.title },
            album = IdentifyRanking.fallbackAlbum(merged.artist, merged.album),
            // The local file's own length wins: mergePreferring keeps the receiver's positive
            // durationMs, and the receiver is the catalog hit, so a remaster or radio edit (or the
            // 180000ms iTunes default) rewrote the real duration and nothing repaired it afterwards.
            durationMs = if (entity.durationMs > 0) entity.durationMs else merged.durationMs
        )
        musicDao.updateSong(entity.withIdentity(cleaned))
        val updated = entity.withIdentity(cleaned)
        maybeWriteTags(updated)
        IdentifyResult.Updated(songId = songId)
    }

    override suspend fun identifySongMetadata(song: Song): IdentifyResult = withContext(Dispatchers.IO) {
        val proposal = proposeSongIdentity(song)
        if (proposal.alreadyIdentified) return@withContext IdentifyResult.Skipped
        val suggested = proposal.suggested
        if (proposal.confidence == IdentifyConfidence.HIGH && suggested != null) {
            return@withContext applySongIdentity(song.id, suggested)
        }
        IdentifyResult.NoMatch
    }

    private suspend fun fetchIdentifyCatalogTracks(
        artist: String,
        title: String,
        artistPlaceholder: Boolean
    ): List<OnlineCatalogTrack> {
        val tracks = ArrayList<OnlineCatalogTrack>()
        metadataSource.fetchFullTrackMetadata(artist, title)?.let { meta ->
            tracks.add(meta.toIdentifyCatalogTrack())
        }
        val primaryQuery = if (artistPlaceholder) title else youtubeSearchQuery(artist, title)
        tracks.addAll(metadataSource.searchOnlineCatalog(primaryQuery.trim()))
        if (!artistPlaceholder) {
            val titleOnly = title.trim()
            if (titleOnly.isNotEmpty() && !titleOnly.equals(primaryQuery.trim(), ignoreCase = true)) {
                tracks.addAll(metadataSource.searchOnlineCatalog(titleOnly))
            }
        }
        return mergeIdentifyCatalogTracks(emptyList(), tracks)
    }

    /**
     * Edge-case enrich: MusicBrainz lookup via ListenBrainz when catalog confidence is not HIGH.
     * Failures are swallowed so identify never blocks on LB.
     */
    private suspend fun fetchListenBrainzIdentifyTrack(
        artist: String,
        title: String,
        releaseName: String?,
        token: String
    ): OnlineCatalogTrack? {
        val lookup = when (
            val result = ListenBrainzClient.lookupRecordingMetadata(
                artistName = artist,
                recordingName = title,
                token = token,
                releaseName = releaseName
            )
        ) {
            is LbApiResult.Success -> result.data
            is LbApiResult.Failure -> {
                if (result.isNetworkError) {
                    CrashReporter.log("identify_phase=lb_lookup network_error")
                }
                return null
            }
        }
        val mbid = lookup.recordingMbid?.trim().orEmpty()
        if (mbid.isEmpty()) return null
        val metaByMbid = when (
            val result = ListenBrainzClient.fetchRecordingMetadata(listOf(mbid), token)
        ) {
            is LbApiResult.Success -> result.data
            is LbApiResult.Failure -> {
                if (result.isNetworkError) {
                    CrashReporter.log("identify_phase=lb_metadata network_error")
                }
                return null
            }
        }
        val recording = metaByMbid[mbid] ?: metaByMbid.values.firstOrNull() ?: return null
        return recording.identity.toListenBrainzCatalogTrack(recording.recordingMbid)
    }

    private fun mergeIdentifyCatalogTracks(
        existing: List<OnlineCatalogTrack>,
        extra: List<OnlineCatalogTrack>
    ): List<OnlineCatalogTrack> {
        val merged = LinkedHashMap<String, OnlineCatalogTrack>()
        for (track in existing + extra) {
            val key = IdentifyRanking.dedupeKey(track.artist, track.title, track.album)
            if (key !in merged) merged[key] = track
        }
        return merged.values.toList()
    }

    private fun TrackIdentity.toIdentifyCatalogTrack(): OnlineCatalogTrack = OnlineCatalogTrack(
        identity = this,
        id = "identify:${TrackMatchKeys.matchKey(artist, title)}",
        audioUrl = "",
        provider = "Catalog"
    )

    private fun needsMetadataIdentify(artist: String, album: String): Boolean {
        return IdentifyRanking.isPlaceholderArtist(artist) || IdentifyRanking.isGenericAlbum(album)
    }

    /**
     * Persist rip-style tag cleanup (`01` + `- Title`) so library/review stop showing junk
     * even when catalog confidence is not HIGH enough to auto-apply.
     */
    private suspend fun persistWeakIdentityCleanup(
        song: Song,
        hints: FilenameMetadataHints
    ): Song {
        val artistWeak = IdentifyRanking.isPlaceholderArtist(song.artist)
        val titleJunk = song.title.trimStart().let {
            it.startsWith("-") || it.startsWith("_") || looksLikeStoragePath(it)
        } || (
            artistWeak &&
                hints.title != null &&
                stripLeadingTitleJunk(song.title) == hints.title &&
                song.title != hints.title
        )

        val newArtist = when {
            artistWeak && !hints.artist.isNullOrBlank() -> hints.artist
            isTrackNumberLabel(song.artist) -> "Unknown Artist"
            else -> null
        }
        val newTitle = when {
            !hints.title.isNullOrBlank() && (titleJunk || (artistWeak && song.title != hints.title)) ->
                hints.title
            else -> null
        }
        val newTrack = hints.trackNumber?.takeIf { it > 0 && song.trackNumber <= 0 }

        if (newArtist == null && newTitle == null && newTrack == null) return song

        val updated = song.copy(
            artist = newArtist ?: song.artist,
            title = newTitle ?: song.title,
            trackNumber = newTrack ?: song.trackNumber
        )
        if (updated.artist == song.artist &&
            updated.title == song.title &&
            updated.trackNumber == song.trackNumber
        ) {
            return song
        }
        musicDao.updateSongMetadata(
            songId = song.id,
            title = updated.title,
            artist = updated.artist,
            album = song.album,
            genre = song.genre,
            year = song.year,
            trackNumber = updated.trackNumber
        )
        return updated
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
        } catch (ignored: Exception) {}

        try {
            val extractor = android.media.MediaExtractor()
            audioStore.applyDataSource(extractor, ref)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true && format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    val durationUs = format.getLong(android.media.MediaFormat.KEY_DURATION)
                    extractor.release()
                    if (durationUs > 0) return durationUs / 1000L
                }
            }
            extractor.release()
        } catch (ignored: Exception) {}

        try {
            val mp = android.media.MediaPlayer()
            audioStore.applyDataSource(mp, ref)
            mp.prepare()
            val dur = mp.duration.toLong()
            mp.release()
            if (dur > 0) return dur
        } catch (ignored: Exception) {}

        return 0L
    }

    override suspend fun updateSongDuration(songId: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        musicDao.updateSongDuration(songId, durationMs)
    }

    override suspend fun touchSongLastPlayed(
        songId: Long,
        playedAt: Long
    ) = withContext(Dispatchers.IO) {
        musicDao.updateLastPlayedAt(songId, playedAt)
    }

    override suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int,
        trackNumber: Int
    ) = withContext(Dispatchers.IO) {
        val safeTitle = title.ifBlank { "Unknown Track" }
        val safeArtist = artist.ifBlank { "Unknown Artist" }
        val safeAlbum = album.ifBlank { "Unknown Album" }
        val safeGenre = genre.ifBlank { "Music" }
        val safeYear = year.coerceAtLeast(0)
        val safeTrack = trackNumber.coerceAtLeast(0)

        // Per-song edit only — does not rewrite sibling songs or album overrides.
        musicDao.updateSongMetadata(
            songId, safeTitle, safeArtist, safeAlbum, safeGenre, safeYear, safeTrack
        )
        val updated = musicDao.getSongById(songId)
        if (updated != null) maybeWriteTags(updated)
    }

    override suspend fun getAlbumOverride(albumKey: String): AlbumOverride? =
        withContext(Dispatchers.IO) {
            musicDao.getAlbumOverride(albumKey)
        }

    override suspend fun upsertAlbumOverride(override: AlbumOverride) =
        withContext(Dispatchers.IO) {
            persistAlbumOverride(override)
            Unit
        }

    override suspend fun setAlbumArtwork(albumKey: String, artworkUri: String?) =
        withContext(Dispatchers.IO) {
            val existing = musicDao.getAlbumOverride(albumKey)
            val override = existing?.copy(artworkUri = artworkUri)
                ?: AlbumOverride(albumKey = albumKey, displayName = albumKey, artworkUri = artworkUri)
            val savedArt = persistAlbumOverride(override)
            musicDao.setAlbumArtwork(albumKey, savedArt)
            maybeWriteTagsForAlbum(albumKey)
        }

    /** Copies the cover into filesDir, stores the override, and returns the persisted artwork path. */
    private suspend fun persistAlbumOverride(override: AlbumOverride): String? {
        val savedArt = saveAlbumCoverImage(override.artworkUri) ?: override.artworkUri
        musicDao.upsertAlbumOverride(persistOverride(override, savedArt))
        return savedArt
    }

    override suspend fun updateAlbumMetadataPropagateToSongs(
        override: AlbumOverride
    ) = withContext(Dispatchers.IO) {
        val oldKey = override.albumKey
        val newName = com.bestiapop.android.domain.util.normalizeAlbumName(override.displayName)
            .ifBlank { oldKey }
        val safeArtist = override.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        val safeGenre = override.genre?.takeIf { it.isNotBlank() } ?: "Music"
        val safeYear = override.year.coerceAtLeast(0)
        val savedArt = saveAlbumCoverImage(override.artworkUri) ?: override.artworkUri

        // One transaction: the rename, the old override delete and the new upsert are one edit. Split,
        // a crash in between renamed the songs while the override stayed under the old key, so the
        // album silently lost its custom cover / artist / genre / year.
        db.withTransaction {
            musicDao.updateSongsAlbumMetadata(
                oldAlbum = oldKey,
                newAlbum = newName,
                artist = safeArtist,
                genre = safeGenre,
                year = safeYear,
                artworkUri = savedArt
            )
            if (oldKey != newName) {
                musicDao.deleteAlbumOverride(oldKey)
            }
            musicDao.upsertAlbumOverride(
                persistOverride(
                    override.copy(
                        albumKey = newName,
                        displayName = newName,
                        artist = safeArtist,
                        genre = safeGenre,
                        year = safeYear,
                        artworkUri = savedArt
                    ),
                    savedArt
                )
            )
        }
        // After the commit: writing tags can take a while on a big album.
        maybeWriteTagsForAlbum(newName)
    }

    override suspend fun mergeAlbumInto(
        sourceAlbumKey: String,
        targetAlbumKey: String
    ) = withContext(Dispatchers.IO) {
        if (sourceAlbumKey == targetAlbumKey) return@withContext

        val targetSongs = musicDao.getSongsForAlbum(targetAlbumKey)
        val canonicalTarget = targetSongs.firstOrNull()?.album ?: targetAlbumKey
        val override = musicDao.getAlbumOverride(canonicalTarget)
            ?: musicDao.getAlbumOverride(targetAlbumKey)

        val safeArtist = override?.artist?.takeIf { it.isNotBlank() }
            ?: targetSongs.firstOrNull()?.artist?.takeIf { it.isNotBlank() }
            ?: "Unknown Artist"
        val safeGenre = override?.genre?.takeIf { it.isNotBlank() }
            ?: targetSongs.map { it.genre }.firstOrNull { it.isNotBlank() }
            ?: "Music"
        val safeYear = when {
            override != null && override.year > 0 -> override.year
            else -> targetSongs.map { it.year }.firstOrNull { it > 0 } ?: 0
        }
        val artwork = override?.artworkUri?.takeIf { it.isNotBlank() }
            ?: targetSongs.firstOrNull { !it.artworkUri.isNullOrBlank() }?.artworkUri

        suspend fun rewriteAlbumKey(oldKey: String) {
            if (oldKey == canonicalTarget) return
            musicDao.updateSongsAlbumMetadata(
                oldAlbum = oldKey,
                newAlbum = canonicalTarget,
                artist = safeArtist,
                genre = safeGenre,
                year = safeYear,
                artworkUri = artwork
            )
            musicDao.deleteAlbumOverride(oldKey)
        }

        // Same reason as updateAlbumMetadataPropagateToSongs: rename + override delete is one edit.
        db.withTransaction {
            rewriteAlbumKey(sourceAlbumKey)

            // Fold other equivalent titles (e.g. Takk… + Takkâ€¦ after renaming Takk. → Takk...)
            val remainingKeys = musicDao.getAllSongs().map { it.album }.distinct()
            com.bestiapop.android.domain.util.findEquivalentAlbumKeys(
                albumKeys = remainingKeys,
                targetName = canonicalTarget,
                excludeKey = canonicalTarget
            ).forEach { rewriteAlbumKey(it) }
        }

        maybeWriteTagsForAlbum(canonicalTarget)
    }

    private fun persistOverride(
        override: AlbumOverride,
        savedArt: String?
    ): AlbumOverride = override.copy(
        displayName = override.displayName.ifBlank { override.albumKey },
        artist = override.artist?.takeIf { it.isNotBlank() },
        genre = override.genre?.takeIf { it.isNotBlank() },
        year = override.year.coerceAtLeast(0),
        artworkUri = savedArt
    )

    /** L1: copy a user-chosen image into [subdir] under filesDir. */
    private fun copyUserImageTo(subdir: String, sourceUriStr: String?): File? {
        if (sourceUriStr.isNullOrBlank()) return null
        try {
            val uri = sourceUriStr.toUri()
            val coversDir = File(context.filesDir, subdir)
            if (!coversDir.exists()) coversDir.mkdirs()
            val destFile = File(coversDir, "cover_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            return destFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * L2: persist user cover under [subdir] with a single URI policy (`file.toURI()`).
     * [alreadyOwned] returns true when [sourceUriStr] already lives in app storage.
     */
    private fun persistUserCover(
        sourceUriStr: String?,
        subdir: String,
        alreadyOwned: (String) -> Boolean
    ): String? {
        if (sourceUriStr.isNullOrBlank()) return null
        if (alreadyOwned(sourceUriStr)) return sourceUriStr
        val dest = copyUserImageTo(subdir, sourceUriStr)
        if (dest != null) return dest.toURI().toString()
        return if (sourceUriStr.startsWith("http")) sourceUriStr else null
    }

    override fun saveAlbumCoverImage(sourceUriStr: String?): String? =
        persistUserCover(sourceUriStr, "album_covers") { uri ->
            val inAppStorage = uri.contains("album_covers") ||
                uri.contains("playlist_covers") ||
                uri.contains("artwork")
            inAppStorage && (uri.startsWith("file://") || uri.startsWith("/"))
        }

    // Playlists
    override fun savePlaylistCoverImage(sourceUriStr: String?): String? =
        persistUserCover(sourceUriStr, "playlist_covers") { uri ->
            uri.startsWith("file://") && uri.contains("playlist_covers")
        }

    override suspend fun createPlaylist(name: String, description: String?, coverUri: String?): Long = withContext(Dispatchers.IO) {
        val savedCover = savePlaylistCoverImage(coverUri)
        musicDao.insertPlaylist(
            PlaylistEntity(
                name = name,
                description = description?.ifBlank { null },
                coverUri = savedCover
            )
        )
    }

    override suspend fun updatePlaylist(id: Long, name: String, description: String?, coverUri: String?) = withContext(Dispatchers.IO) {
        val existing = musicDao.getPlaylistById(id) ?: return@withContext
        val savedCover = if (!coverUri.isNullOrEmpty() && coverUri != existing.coverUri) {
            savePlaylistCoverImage(coverUri)
        } else {
            coverUri
        }
        val updated = existing.copy(
            name = name,
            description = description?.ifBlank { null },
            coverUri = savedCover
        )
        musicDao.updatePlaylist(updated)
    }

    override suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        musicDao.clearPlaylistSongs(id)
        musicDao.clearPlaylistPendingTracks(id)
        musicDao.deletePlaylist(id)
    }

    override suspend fun addSongToPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        musicDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId = playlistId, songId = songId))
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        musicDao.removeSongFromPlaylist(playlistId, songId)
    }

    override suspend fun getPlaylistIdsForSong(songId: Long): List<Long> = withContext(Dispatchers.IO) {
        musicDao.getPlaylistIdsForSong(songId)
    }

    override suspend fun getCoPlaylistSongIds(songId: Long): Set<Long> = withContext(Dispatchers.IO) {
        musicDao.getCoPlaylistSongIds(songId).toSet()
    }

    override fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrack>> =
        musicDao.getPlaylistPendingTracksFlow(playlistId).map { list ->
            list.map { it.toPendingTrack() }
        }

    override suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>) =
        withContext(Dispatchers.IO) {
            if (tracks.isEmpty()) return@withContext
            musicDao.insertPlaylistPendingTracks(tracks.map { it.toEntity() })
        }

    override suspend fun removePlaylistPendingTrack(playlistId: Long, artist: String, title: String) =
        withContext(Dispatchers.IO) {
            musicDao.deletePlaylistPendingTrackByArtistTitle(playlistId, artist, title)
        }

    override suspend fun downloadAndSaveOnlineTrack(
        track: OnlineCatalogTrack,
        onProgress: ((DownloadPhase) -> Unit)?,
        conflictPolicy: DownloadConflictPolicy?
    ): Song = withContext(Dispatchers.IO) {
        onProgress?.invoke(DownloadPhase.Searching)

        val queryOrId = com.bestiapop.android.data.network.YouTubeExtractor.resolveYouTubeQueryOrId(track)
        val ytStream = streamResolver.resolveQuery(queryOrId, forceRefresh = true).getOrElse { e ->
            throw java.io.IOException(e.message ?: "No se pudo resolver el stream de YouTube")
        }
        val downloadUrl = ytStream.audioUrl
        val userAgentToUse = ytStream.userAgent
        var identity = track.identity.copy(
            title = track.title.takeUnless { isPlaceholderTitle(it) }.orEmpty(),
            artist = track.artist.takeUnless { IdentifyRanking.isPlaceholderArtist(it) }.orEmpty()
        ).mergePreferring(ytStream.identity)

        var overwriteTarget: Song? = null
        when (conflictPolicy) {
            is DownloadConflictPolicy.Overwrite -> {
                overwriteTarget = musicDao.getSongById(conflictPolicy.existingSongId)
                    ?: throw IllegalArgumentException("Canción a sobrescribir no encontrada")
                identity = identity.copy(
                    title = overwriteTarget.title,
                    artist = identity.artist.ifBlank { overwriteTarget.artist }
                )
            }
            is DownloadConflictPolicy.SaveAs -> {
                identity = identity.copy(
                    title = conflictPolicy.newTitle.trim().ifBlank { identity.title }
                )
            }
            null -> {
                val existing = lookupSongByArtistTitle(identity.artist, identity.title)
                if (existing != null) {
                    throw DuplicateSongException(existing, track.copy(identity = identity))
                }
            }
        }
        var finalTitle = identity.title
        var finalArtist = identity.artist
        var finalArtwork = identity.artworkUri
        var finalDurationMs = identity.durationMs
        var finalTrackNumber = identity.trackNumber

        val ext = when {
            downloadUrl.contains("audio/mp4") || downloadUrl.contains("mime=audio%2Fmp4") || downloadUrl.endsWith(".m4a") -> "m4a"
            downloadUrl.contains("audio/webm") || downloadUrl.contains("mime=audio%2Fwebm") || downloadUrl.endsWith(".webm") -> "webm"
            downloadUrl.endsWith(".aac") -> "aac"
            downloadUrl.endsWith(".ogg") -> "ogg"
            downloadUrl.endsWith(".wav") -> "wav"
            else -> "m4a"
        }

        val sanitizedName = (finalArtist + "_" + finalTitle).replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val displayName = "$sanitizedName.$ext"
        overwriteTarget?.let { target ->
            audioStore.delete(audioStore.canonicalize(target.uriString, target.folderPath))
        }
        val pendingWrite = audioStore.prepareWrite(displayName)
        val file = pendingWrite.stagingFile
        if (file.exists()) {
            file.delete()
        }

        onProgress?.invoke(DownloadPhase.Downloading(finalTitle))

        var currentUrl = downloadUrl
        var downloadedBytes = 0L
        var expectedTotalBytes = -1L
        var attempts = 0
        var downloadSuccess = false
        var lastResponseCode = 0
        var lastHttpError: String? = null

        while (attempts < MAX_DOWNLOAD_ATTEMPTS && !downloadSuccess) {
            attempts++
            lastResponseCode = 0
            try {
                val reqBuilder = okhttp3.Request.Builder()
                    .url(currentUrl)
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", userAgentToUse)

                if (downloadedBytes > 0) {
                    reqBuilder.header("Range", "bytes=$downloadedBytes-")
                }

                downloadCallFactory.newCall(reqBuilder.build()).useCancellable { response ->
                    lastResponseCode = response.code
                    if (!response.isSuccessful) {
                        lastHttpError = "HTTP ${response.code} (${response.message.ifBlank { "Error de servidor" }})"
                        return@useCancellable
                    }
                    val body = response.body
                    if (body == null) {
                        lastHttpError = "Respuesta sin cuerpo"
                        return@useCancellable
                    }
                    // 200 to a ranged request = the server ignored Range and is resending the whole
                    // body; appending it after the partial bytes would corrupt the file.
                    val resuming = response.code == 206 && downloadedBytes > 0
                    if (!resuming) downloadedBytes = 0L
                    val bodyLength = body.contentLength()
                    expectedTotalBytes = if (bodyLength > 0) downloadedBytes + bodyLength else -1L

                    body.byteStream().use { input ->
                        val baseBytes = downloadedBytes
                        copyTransferToFile(
                            input = input,
                            destination = file,
                            append = resuming,
                            bufferSize = 65536
                        ) { copied ->
                            downloadedBytes = baseBytes + copied
                        }
                    }
                    // A clean EOF short of Content-Length is a truncated body, not a finished file.
                    downloadSuccess = expectedTotalBytes <= 0L || downloadedBytes >= expectedTotalBytes
                    if (!downloadSuccess) {
                        lastHttpError = "Descarga incompleta ($downloadedBytes/$expectedTotalBytes bytes)"
                    }
                }
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                lastHttpError = e.localizedMessage ?: "Error de red"
                downloadedBytes = if (file.exists()) file.length() else 0L
            }

            if (!downloadSuccess && attempts < MAX_DOWNLOAD_ATTEMPTS) {
                // CDN URLs expire mid-download: without a fresh extract every retry hits the same
                // dead URL and the whole budget is burnt for nothing.
                if (lastResponseCode == 403 || lastResponseCode == 410) {
                    streamResolver.resolveQuery(queryOrId, forceRefresh = true)
                        .getOrNull()
                        ?.let { refreshed ->
                            currentUrl = refreshed.audioUrl
                            downloadedBytes = 0L
                            expectedTotalBytes = -1L
                        }
                }
                try {
                    downloadRetryDelay(DOWNLOAD_RETRY_BACKOFF_MS * attempts)
                } catch (e: CancellationException) {
                    file.delete()
                    throw e
                }
            }
        }

        if (!downloadSuccess) {
            file.delete()
            val errorDetails = if (!lastHttpError.isNullOrBlank()) " ($lastHttpError)" else ""
            throw java.io.IOException(
                "No se pudo descargar el archivo de audio de YouTube$errorDetails. Verifica tu conexión a internet o intenta con otra canción."
            )
        }

        val savedRef = audioStore.canonicalize(pendingWrite.publish())

        onProgress?.invoke(DownloadPhase.FetchingMetadata)

        var finalAlbum = track.album
        val hasUsefulAlbum = !IdentifyRanking.isGenericAlbum(finalAlbum)
        val hasArtwork = !finalArtwork.isNullOrEmpty()

        if (!hasUsefulAlbum || !hasArtwork) {
            val fullMeta = metadataSource.fetchFullTrackMetadata(finalArtist, finalTitle)
            if (fullMeta != null) {
                val lookedUpAlbum = fullMeta.album
                if (lookedUpAlbum.isNotBlank() && !IdentifyRanking.isGenericAlbum(lookedUpAlbum)) {
                    finalAlbum = lookedUpAlbum
                }
                if (finalArtwork.isNullOrEmpty() && !fullMeta.artworkUri.isNullOrEmpty()) {
                    finalArtwork = fullMeta.artworkUri
                }
                if (fullMeta.artist.isNotBlank() && IdentifyRanking.isPlaceholderArtist(finalArtist)) {
                    finalArtist = fullMeta.artist
                }
                if (finalDurationMs <= 0 && fullMeta.durationMs > 0) {
                    finalDurationMs = fullMeta.durationMs
                }
                if (finalTrackNumber <= 0 && fullMeta.trackNumber > 0) {
                    finalTrackNumber = fullMeta.trackNumber
                }
            }
        }

        if (IdentifyRanking.isGenericAlbum(finalAlbum)) {
            finalAlbum = IdentifyRanking.fallbackAlbum(
                finalArtist,
                overwriteTarget?.album.orEmpty()
            )
        }

        val lyrics = metadataSource.fetchLyrics(finalArtist, finalTitle)

        onProgress?.invoke(DownloadPhase.Saving)

        if (overwriteTarget != null) {
            val updated = overwriteTarget.withIdentity(
                TrackIdentity(
                    title = finalTitle,
                    artist = finalArtist.ifBlank { overwriteTarget.artist },
                    album = finalAlbum,
                    artworkUri = finalArtwork ?: overwriteTarget.artworkUri,
                    durationMs = if (finalDurationMs > 0) finalDurationMs else overwriteTarget.durationMs,
                    trackNumber = if (finalTrackNumber > 0) finalTrackNumber else overwriteTarget.trackNumber
                )
            ).copy(
                uriString = savedRef.uriString,
                lyrics = lyrics ?: overwriteTarget.lyrics,
                folderPath = savedRef.folderPath
            )
            musicDao.updateSong(updated)
            maybeWriteTags(updated)
            onProgress?.invoke(DownloadPhase.Overwritten)
            return@withContext updated
        }

        val song = Song(
            uriString = savedRef.uriString,
            title = finalTitle,
            artist = finalArtist,
            album = finalAlbum,
            genre = "Music",
            durationMs = if (finalDurationMs > 0) finalDurationMs else 180000L,
            year = 0,
            trackNumber = finalTrackNumber,
            artworkUri = finalArtwork,
            lyrics = lyrics,
            folderPath = savedRef.folderPath,
            dateAdded = System.currentTimeMillis()
        )

        val insertedId = insertOrUpdateByUri(song)
        val savedSong = song.copy(id = insertedId)
        maybeWriteTags(savedSong)

        onProgress?.invoke(DownloadPhase.Completed)
        return@withContext savedSong
    }

    override suspend fun syncTagsToFiles(onProgress: LibraryScanProgress?): TagSyncSummary =
        withContext(Dispatchers.IO) {
            val songs = musicDao.getAllSongs()
            var updated = 0
            var skipped = 0
            var errors = 0
            val total = songs.size
            songs.forEachIndexed { index, song ->
                onProgress?.invoke(index, total, song.title)
                when (writeTagsToFile(song)) {
                    TagWriteResult.Success -> updated++
                    TagWriteResult.Unsupported, TagWriteResult.NotWritable -> skipped++
                    is TagWriteResult.IoError -> errors++
                }
            }
            onProgress?.invoke(total, total, "")
            TagSyncSummary(updated = updated, skipped = skipped, errors = errors)
        }

    /** Best-effort tag write when auto-write is enabled in Ajustes → Archivos. */
    private suspend fun maybeWriteTags(song: Song) {
        val enabled = tagWritePreferences.settingsFlow.first().autoWriteTagsEnabled
        if (!enabled) return
        writeTagsToFile(song)
    }

    private suspend fun maybeWriteTagsForAlbum(album: String) {
        val enabled = tagWritePreferences.settingsFlow.first().autoWriteTagsEnabled
        if (!enabled) return
        musicDao.getSongsForAlbum(album).forEach { writeTagsToFile(it) }
    }

    private fun writeTagsToFile(song: Song): TagWriteResult {
        val file = audioStore.writableFile(song.uriString, song.folderPath)
            ?: return TagWriteResult.NotWritable
        return AudioTagWriter.write(song, file)
    }

    private suspend fun lookupSongByArtistTitle(artist: String, title: String): Song? {
        val key = TrackMatchKeys.matchKey(artist, title)
        if (key.isEmpty()) return null
        val songs = musicDao.getAllSongs()
        return TrackMatchKeys.buildIndex(songs, { it.artist }, { it.title })[key]
    }

    suspend fun migrateCanonicalAudioUris() = withContext(Dispatchers.IO) {
        try {
            val songs = musicDao.getAllSongs()
            if (songs.isEmpty()) return@withContext
            val planned = songs.map { song ->
                song to audioStore.canonicalize(song.uriString, song.folderPath)
            }
            val groups = planned.groupBy { it.second.uriString }
            for ((_, members) in groups) {
                if (members.size == 1) {
                    val (song, ref) = members[0]
                    if (song.uriString != ref.uriString || song.folderPath != ref.folderPath) {
                        musicDao.updateSong(
                            song.copy(uriString = ref.uriString, folderPath = ref.folderPath)
                        )
                    }
                    continue
                }
                val keepPair = members.firstOrNull { it.first.uriString == it.second.uriString }
                    ?: members.minBy { it.first.id }
                val keep = keepPair.first
                val keepRef = keepPair.second
                if (keep.uriString != keepRef.uriString || keep.folderPath != keepRef.folderPath) {
                    musicDao.updateSong(
                        keep.copy(uriString = keepRef.uriString, folderPath = keepRef.folderPath)
                    )
                }
                for ((drop, _) in members) {
                    if (drop.id == keep.id) continue
                    remapPlaylistsThenDelete(dropId = drop.id, keepId = keep.id)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            com.bestiapop.android.data.util.CrashReporter.recordNonFatal(
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

    private fun isPlaceholderTitle(title: String): Boolean =
        title.isBlank() ||
            title == "YouTube Track" ||
            title == "Canción desde Link" ||
            title == "Enlace YouTube" ||
            title == "Descarga"

    private companion object {
        const val MAX_DOWNLOAD_ATTEMPTS = 5
        const val DOWNLOAD_RETRY_BACKOFF_MS = 750L
    }
}
