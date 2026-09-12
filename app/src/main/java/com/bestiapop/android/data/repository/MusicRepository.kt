package com.bestiapop.android.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
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
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyApplyRequest
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.SongPathRef
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
import com.bestiapop.android.data.util.AlbumArtworkCache
import com.bestiapop.android.data.util.AudioFileMetadata
import com.bestiapop.android.data.util.AudioPersistRef
import com.bestiapop.android.data.util.AudioTagReader
import com.bestiapop.android.data.util.AudioTagWriter
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.PlaybackDiagnostics
import com.bestiapop.android.data.network.GoogleVideoRange
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.SongPathNormalizer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.data.util.TagSyncSummary
import com.bestiapop.android.data.util.TagWriteResult
import com.bestiapop.android.data.util.UploadNameSanitizer
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.data.util.copyTransferToFile
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.repository.LibraryScanProgress
import com.bestiapop.android.domain.util.FilenameMetadataHints
import com.bestiapop.android.domain.util.IdentifyCatalogQuery
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.MetadataSplitter
import com.bestiapop.android.data.db.ArtistEntity
import com.bestiapop.android.data.db.GenreEntity
import com.bestiapop.android.data.db.SongArtistCrossRef
import com.bestiapop.android.data.db.SongGenreCrossRef
import com.bestiapop.android.data.db.AlbumArtistCrossRef
import com.bestiapop.android.data.db.AlbumGenreCrossRef
import com.bestiapop.android.domain.util.KnownAlbumTrack
import com.bestiapop.android.domain.util.KnownAlbumTracks
import com.bestiapop.android.domain.util.albumGroupKey
import com.bestiapop.android.domain.util.assignUniqueKnownAlbumMatches
import com.bestiapop.android.domain.util.artistsCompatible
import com.bestiapop.android.domain.util.knownAlbumQueryOf
import com.bestiapop.android.domain.util.knownAlbumsFromLibrary
import com.bestiapop.android.domain.util.mergeKnownAlbumTracks
import com.bestiapop.android.domain.util.toIdentifyCandidate
import com.bestiapop.android.domain.util.toKnownAlbumTrack
import com.bestiapop.android.domain.util.fillSongGapsFromFileTags
import com.bestiapop.android.domain.util.needsGapIdentify
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.identifySearchTexts
import com.bestiapop.android.domain.usecase.IdentifyPipeline
import com.bestiapop.android.domain.util.libraryAlbumKeysInBucket
import com.bestiapop.android.domain.util.pickPersistedAlbumName
import com.bestiapop.android.domain.util.pickPersistedArtistName
import com.bestiapop.android.domain.util.albumIdentityKey
import com.bestiapop.android.domain.util.albumNamesMatch
import com.bestiapop.android.domain.util.studioAlbumKeysByArtist
import com.bestiapop.android.domain.util.isTrackNumberLabel
import com.bestiapop.android.domain.util.stripLeadingTitleJunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val SCAN_FILE_PARALLEL = 4

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
    fun readableFile(uriString: String, folderPath: String = ""): File?
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

    override fun readableFile(uriString: String, folderPath: String): File? =
        delegate.readableFile(uriString, folderPath)
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

    /** iTunes + YouTube when Deezer already returned unrelated hits. */
    suspend fun searchIdentifyFallbacks(
        query: String,
        limit: Int = 25,
        durationMs: Long = 0L
    ): List<OnlineCatalogTrack> = emptyList()

    suspend fun searchAlbums(query: String): List<CatalogAlbum> = emptyList()

    suspend fun fetchAlbumTracks(
        albumId: String,
        albumTitle: String,
        artistName: String,
        coverUrl: String?
    ): List<OnlineCatalogTrack> = emptyList()

    /** MusicBrainz WS2 recording search (identify). */
    suspend fun searchMusicBrainzRecordings(
        query: String,
        durationMs: Long = 0L,
        limit: Int = 25
    ): List<OnlineCatalogTrack> = emptyList()

    /** ListenBrainz metadata lookup. Null on miss/error. */
    suspend fun lookupListenBrainzIdentifyTrack(
        artist: String,
        title: String,
        releaseName: String?,
        token: String
    ): OnlineCatalogTrack? = null
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

    override suspend fun searchIdentifyFallbacks(
        query: String,
        limit: Int,
        durationMs: Long
    ): List<OnlineCatalogTrack> =
        MetadataFetcher.searchIdentifyFallbacks(query, limit, durationMs)

    override suspend fun searchAlbums(query: String): List<CatalogAlbum> =
        MetadataFetcher.searchAlbums(query)

    override suspend fun fetchAlbumTracks(
        albumId: String,
        albumTitle: String,
        artistName: String,
        coverUrl: String?
    ): List<OnlineCatalogTrack> =
        MetadataFetcher.fetchAlbumTrackCandidates(albumId, albumTitle, artistName, coverUrl)
            .mapNotNull { it.currentTrack }

    override suspend fun searchMusicBrainzRecordings(
        query: String,
        durationMs: Long,
        limit: Int
    ): List<OnlineCatalogTrack> = try {
        com.bestiapop.android.data.network.MusicBrainzClient.searchRecordings(
            query = query,
            durationMs = durationMs.takeIf { it > 0L },
            limit = limit
        )
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    override suspend fun lookupListenBrainzIdentifyTrack(
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
    private val tagWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogAlbumTracksCache = ConcurrentHashMap<String, List<KnownAlbumTrack>>()
    private val identityLibraryMutex = Mutex()
    private var identityLibrary: List<Song>? = null
    private var cachedLibraryArtists: List<String>? = null
    private var cachedKnownAlbums: List<KnownAlbumTracks>? = null
    private val libraryShareScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val libraryQueryStartedAtNs = System.nanoTime()
    private val loggedFirstLibraryEmit = java.util.concurrent.atomic.AtomicBoolean(false)

    override val allSongsFlow: Flow<List<Song>> = musicDao.getAllSongsFlow()
        .distinctUntilChanged()
        .onEach { songs ->
            if (loggedFirstLibraryEmit.compareAndSet(false, true)) {
                val ms = (System.nanoTime() - libraryQueryStartedAtNs) / 1_000_000L
                PlaybackDiagnostics.log(
                    PlaybackDiagnostics.TAG_LIFECYCLE,
                    "first allSongsFlow size=${songs.size} in ${ms}ms"
                )
            }
        }
        .shareIn(libraryShareScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    override val songPlayStatsFlow: Flow<Map<Long, Long>> = musicDao.getPlayStatsFlow()
        .map { stats -> stats.associate { it.songId to it.lastPlayedAt } }
        .distinctUntilChanged()

    override val albumOverridesFlow: Flow<List<AlbumOverride>> =
        musicDao.getAllAlbumOverridesFlow()

    override val playlistsFlow: Flow<List<Playlist>> = musicDao.getAllPlaylistSummariesFlow().map { entities ->
        entities.map { entity ->
            Playlist(
                id = entity.playlistId,
                name = entity.name,
                description = entity.description,
                coverUri = entity.coverUri,
                songCount = entity.songCount,
                createdAt = entity.createdAt
            )
        }
    }

    override fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> {
        return musicDao.getPlaylistSongsOrderedFlow(playlistId)
    }

    override fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> {
        return combine(
            musicDao.getPlaylistByIdFlow(playlistId),
            musicDao.getPlaylistSongsOrderedFlow(playlistId)
        ) { entity, songs ->
            if (entity == null) null
            else {
                val effectiveCover = entity.coverUri?.takeIf(String::isNotBlank)
                    ?: songs.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) }
                val playlist = Playlist(
                    id = entity.playlistId,
                    name = entity.name,
                    description = entity.description,
                    coverUri = effectiveCover,
                    songCount = songs.size,
                    createdAt = entity.createdAt
                )
                Pair(playlist, songs)
            }
        }
    }

    override suspend fun getPlaylistSongsOrdered(playlistId: Long): List<Song> =
        withContext(Dispatchers.IO) {
            musicDao.getPlaylistSongsOrdered(playlistId)
        }

    override suspend fun scanMediaStore(onProgress: LibraryScanProgress?): List<Song> = withContext(Dispatchers.IO) {
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

    override suspend fun resyncAppManagedMusic(onProgress: LibraryScanProgress?): List<Song> =
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
                syncSongsRelations(existing)
            }
            inserted
        }

    override suspend fun findSongByArtistTitle(artist: String, title: String): Song? =
        withContext(Dispatchers.IO) {
            lookupSongByArtistTitle(artist, title)
        }

    override suspend fun scanFolderUri(treeUri: Uri, onProgress: LibraryScanProgress?): List<Song> =
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
        // Both spellings: scans probe the canonical uriString, which for a SAF folder import is a
        // `content://…/documents/…` URI, while resolveFilePath only ever yields an absolute path —
        // so indexing just the resolved path made path dedupe miss every SAF re-import.
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
        invalidateIdentityLibrary()
        val inserted = scanned.map { it.uriString }
            .chunked(IDENTITY_SONG_ID_CHUNK)
            .flatMap { chunk -> musicDao.getSongsByUris(chunk) }
        syncSongsRelations(inserted)
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

    /**
     * [File.listFiles] returns null when scoped storage blocks the path; caller then falls
     * back to DocumentFile. An empty list means the directory was readable but had no audio.
     */
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
            com.bestiapop.android.data.util.CrashReporter.recordNonFatal(
                e,
                mapOf("scan_phase" to source.scanPhase, source.crashPathKey to source.sourcePath)
            )
            false
        }
    }

    override fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String? {
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

    private fun isRealMusicTrack(
        durationMs: Long,
        filePath: String,
        fileName: String = "",
        allowUnknownDuration: Boolean = false,
        artist: String = "",
        title: String = ""
    ): Boolean {
        // Files with non-positive duration and no recognizable identity (placeholder artist + track number title)
        // are corrupt or truncated and cannot be played.
        if (durationMs <= 0L) {
            val nameForExt = fileName.ifBlank { filePath.substringAfterLast('/') }
            val unknownOk = allowUnknownDuration && isAudioFile(nameForExt) && hasUsableIdentity(artist, title)
            if (!unknownOk) return false
        } else if (durationMs < 30_000) {
            val nameForExt = fileName.ifBlank { filePath.substringAfterLast('/') }
            val shortOk = allowUnknownDuration && isAudioFile(nameForExt)
            if (!shortOk) return false
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
        musicDao.getIdentitySongs()
    }

    override suspend fun getAllSongPathRefs(): List<SongPathRef> = withContext(Dispatchers.IO) {
        musicDao.getAllSongPathRefs()
    }

    override suspend fun getSongById(id: Long): Song? = withContext(Dispatchers.IO) {
        musicDao.getSongById(id)
    }

    override suspend fun getSongsByIds(ids: List<Long>): List<Song> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val idSet = ids.toSet()
        identityLibraryMutex.withLock {
            identityLibrary?.let { cached ->
                return@withContext cached.filter { it.id in idSet }
            }
        }
        ids.chunked(IDENTITY_SONG_ID_CHUNK).flatMap { chunk ->
            musicDao.getIdentitySongsByIds(chunk)
        }
    }

    private suspend fun identityLibrarySongsLocked(): List<Song> {
        identityLibrary?.let { return it }
        val loaded = musicDao.getIdentitySongs()
        identityLibrary = loaded
        return loaded
    }

    private suspend fun identityLibrarySongs(): List<Song> {
        identityLibraryMutex.withLock {
            return identityLibrarySongsLocked()
        }
    }

    private suspend fun libraryArtistsCached(): List<String> {
        identityLibraryMutex.withLock {
            cachedLibraryArtists?.let { return it }
            val artists = identityLibrarySongsLocked().map { it.artist }.distinct()
            cachedLibraryArtists = artists
            return artists
        }
    }

    private suspend fun libraryKnownAlbumsCached(): List<KnownAlbumTracks> {
        identityLibraryMutex.withLock {
            cachedKnownAlbums?.let { return it }
            val albums = knownAlbumsFromLibrary(identityLibrarySongsLocked())
            cachedKnownAlbums = albums
            return albums
        }
    }

    private suspend fun rememberIdentitySong(updated: Song) {
        rememberIdentitySongs(listOf(updated))
    }

    private suspend fun rememberIdentitySongs(updated: List<Song>) {
        if (updated.isEmpty()) return
        identityLibraryMutex.withLock {
            val cache = identityLibrary?.toMutableList() ?: return@withLock
            for (song in updated) {
                val slim = song.copy(lyrics = null)
                val index = cache.indexOfFirst { it.id == slim.id }
                if (index >= 0) cache[index] = slim else cache.add(slim)
            }
            identityLibrary = cache
            cachedLibraryArtists = null
            cachedKnownAlbums = null
        }
    }

    private suspend fun invalidateIdentityLibrary() {
        identityLibraryMutex.withLock {
            identityLibrary = null
            cachedLibraryArtists = null
            cachedKnownAlbums = null
        }
    }

    override suspend fun saveUploadedSong(song: Song): Long = withContext(Dispatchers.IO) {
        val ref = audioStore.canonicalize(song.uriString, song.folderPath)
        val normalized = song.copy(uriString = ref.uriString, folderPath = ref.folderPath)
        val trackNum = if (normalized.trackNumber > 0) {
            normalized.trackNumber
        } else {
            resolveTrackNumberFallback(normalized.artist, normalized.title)
        }
        val songWithTrack = if (trackNum != normalized.trackNumber) {
            normalized.copy(trackNumber = trackNum)
        } else {
            normalized
        }
        val key = TrackMatchKeys.matchKey(songWithTrack.artist, songWithTrack.title)
        if (key.isNotEmpty()) {
            val existing = lookupSongByArtistTitle(songWithTrack.artist, songWithTrack.title)
            if (existing != null) {
                val oldRef = audioStore.canonicalize(existing.uriString, existing.folderPath)
                if (oldRef.uriString != songWithTrack.uriString &&
                    !SongPathNormalizer.pathsReferToSameFile(oldRef.uriString, songWithTrack.uriString)
                ) {
                    audioStore.delete(oldRef)
                }
                val existingTrack = if (songWithTrack.trackNumber > 0) {
                    songWithTrack.trackNumber
                } else if (existing.trackNumber > 0) {
                    existing.trackNumber
                } else {
                    resolveTrackNumberFallback(existing.artist, existing.title)
                }
                val updated = existing.copy(
                    uriString = songWithTrack.uriString,
                    title = songWithTrack.title,
                    artist = songWithTrack.artist,
                    album = songWithTrack.album,
                    genre = songWithTrack.genre,
                    durationMs = songWithTrack.durationMs,
                    artworkUri = songWithTrack.artworkUri ?: existing.artworkUri,
                    folderPath = songWithTrack.folderPath.ifBlank { existing.folderPath },
                    trackNumber = existingTrack
                )
                musicDao.updateSong(updated)
                syncSongsRelations(listOf(updated))
                rememberIdentitySong(updated)
                return@withContext existing.id
            }
        }
        insertOrUpdateByUri(songWithTrack)
    }

    /**
     * Insert keeping the existing row id when `uriString` already exists, so playlist membership and
     * app-side state (lyrics, lastPlayedAt, dateAdded) survive a re-import of the same file.
     */
    private suspend fun insertOrUpdateByUri(song: Song): Long {
        val insertedId = musicDao.insertSong(song)
        if (insertedId != -1L) {
            invalidateIdentityLibrary()
            syncSongsRelations(listOf(song.copy(id = insertedId)))
            return insertedId
        }
        val existing = musicDao.getSongByUri(song.uriString) ?: return -1L
        val updated = song.copy(
            id = existing.id,
            dateAdded = existing.dateAdded,
            lastPlayedAt = existing.lastPlayedAt,
            lyrics = song.lyrics ?: existing.lyrics,
            artworkUri = song.artworkUri ?: existing.artworkUri
        )
        musicDao.updateSong(updated)
        syncSongsRelations(listOf(updated))
        rememberIdentitySong(updated)
        return existing.id
    }

    override suspend fun deleteSongsFromApp(songs: List<Song>) = withContext(Dispatchers.IO) {
        deleteSongRows(songs)
    }

    override suspend fun pruneUnplayableCorruptSongs(): List<Song> = withContext(Dispatchers.IO) {
        val corrupted = musicDao.getSongsWithNonPositiveDuration()
        val toDelete = corrupted.filterNot { hasUsableIdentity(it.artist, it.title) }
        if (toDelete.isNotEmpty()) {
            deleteSongRows(toDelete)
        }
        toDelete
    }

    /** Single exit for row removal so no caller can forget the cross-ref cleanup (no FK/cascade). */
    private suspend fun deleteSongRows(songs: List<Song>) {
        val ids = songs.map { it.id }
        if (ids.isEmpty()) return
        db.withTransaction {
            ids.chunked(IDENTITY_SONG_ID_CHUNK).forEach { chunk ->
                musicDao.deletePlayStatsForSongs(chunk)
                musicDao.deletePlaylistRefsForSongs(chunk)
                chunk.forEach { sId ->
                    musicDao.deleteSongArtistCrossRefs(sId)
                    musicDao.deleteSongGenreCrossRefs(sId)
                }
                musicDao.deleteSongsByIds(chunk)
            }
        }
        invalidateIdentityLibrary()
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

    override suspend fun enhanceSongMetadataAndLyrics(song: Song) =
        enhanceSongMetadataAndLyricsBatch(listOf(song))

    suspend fun enhanceSongMetadataAndLyricsBatch(songs: List<Song>) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        val patches = songs.mapNotNull { prepareEnhancePatch(it) }
        if (patches.isEmpty()) return@withContext
        val identitySongs = musicDao.getIdentitySongs()
        db.withTransaction {
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
            patch.tagSong?.let { maybeWriteTags(it) }
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
        val hasUsableArt = hasUsableArtwork(persisted.artworkUri)
        val hasLyrics = !persisted.lyrics.isNullOrEmpty()
        val hasDuration = persisted.durationMs > 0
        val hasTrackNumber = persisted.trackNumber > 0
        if (hasUsableArt && hasLyrics && hasDuration && hasTrackNumber) return null

        val albumName = if (persisted.album.isBlank()) "Unknown Album" else persisted.album
        val existingAlbumArt = musicDao.getArtworkForAlbum(albumName)

        var artUrl = if (hasUsableArt) persisted.artworkUri else existingAlbumArt

        if (!hasUsableArtwork(artUrl)) {
            val ref = audioStore.canonicalize(persisted.uriString, persisted.folderPath)
            val embedded = extractAndSaveEmbeddedArtwork(ref.uriString, "${persisted.artist}_${albumName}")
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
            val resolvedTrack = resolveTrackNumberFallback(persisted.artist, persisted.title)
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
        if (!force && !isExpand && !needsMetadataIdentify(song)) {
            return@withContext IdentifyProposal(
                songId = song.id,
                queryArtist = song.artist,
                queryTitle = song.title,
                alreadyIdentified = true,
                confidence = IdentifyConfidence.NONE
            )
        }

        val stage1 = IdentifyPipeline.parseLocalFile(song)
        val baseName = stage1.baseName
        val stage2 = IdentifyPipeline.narrowArtist(stage1, libraryArtistsCached())
        val working = if (isExpand) song else persistWeakIdentityCleanup(song, stage2.mergedHints)
        val filenameArtist = stage2.filenameArtist
        val filenameTitle = stage2.filenameTitle
        val queryArtist = stage2.queryArtist
        val queryTitle = stage2.queryTitle
        val sourceHints = stage2.sourceHints

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
        val isRefineSearch = trimmedCustom.isNotEmpty() || normalizedFilters.hasAny || isExpand
        if (!isRefineSearch) {
            matchKnownAlbumFromLibrary(working, queryArtist, queryTitle)?.let { candidate ->
                return@withContext IdentifyProposal(
                    songId = song.id,
                    queryArtist = queryArtist,
                    queryTitle = queryTitle,
                    sourceHints = sourceHints,
                    candidates = listOf(candidate),
                    confidence = IdentifyConfidence.HIGH,
                    suggested = candidate
                )
            }
        }

        val defaultSearch = IdentifyRanking.catalogSearchText(
            artist = queryArtist,
            title = queryTitle,
            album = working.album,
            artistIsPlaceholder = artistPlaceholder
        )
        val titleCollidesArtist = IdentifyRanking.titleCollidesWithArtistOrAlbum(
            title = working.title,
            artist = working.artist.takeUnless { IdentifyRanking.isPlaceholderArtist(it) }
        )

        val catalogFreeText: String? = when {
            trimmedCustom.isNotEmpty() -> trimmedCustom
            // Expand / load-more without refine fields: same default artist+title query.
            isExpand && !normalizedFilters.hasAny -> defaultSearch
            else -> null
        }
        val catalogQuery = if (isRefineSearch) {
            IdentifyCatalogQuery.build(catalogFreeText, normalizedFilters).ifBlank { defaultSearch }
        } else {
            ""
        }

        val pageIndex = catalogIndex.coerceAtLeast(0)
        var fetchedCount = 0
        var usedListenBrainz = false
        var tracks = if (isRefineSearch) {
            val page = metadataSource.searchOnlineCatalog(
                query = catalogQuery,
                limit = IdentifyRanking.CATALOG_PAGE,
                index = pageIndex
            )
            fetchedCount = page.size
            page
        } else {
            val token = listenBrainzToken?.trim().orEmpty()
            val fetched = fetchIdentifyCatalogTracks(
                queryArtist = queryArtist,
                queryTitle = queryTitle,
                artistPlaceholder = artistPlaceholder,
                album = working.album,
                skipExactTitleLookup = titleCollidesArtist,
                durationMs = working.durationMs,
                listenBrainzToken = token.takeIf { it.isNotEmpty() },
                titleCollidesArtist = titleCollidesArtist
            )
            usedListenBrainz = fetched.usedListenBrainz
            fetched.tracks
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

        val rankLimit = if (isExpand || isRefineSearch) {
            IdentifyRanking.CATALOG_PAGE
        } else {
            IdentifyRanking.TOP_N
        }
        var ranked = IdentifyRanking.rank(rankingQuery, tracks, limit = rankLimit)
        if (existingCandidates.isNotEmpty()) {
            ranked = IdentifyRanking.appendCandidates(existingCandidates, ranked)
        }
        var confidence = IdentifyRanking.confidence(ranked, rankingQuery)
        if (!isRefineSearch && confidence != IdentifyConfidence.HIGH) {
            val searchTexts = identifySearchTexts(defaultSearch, baseName)
            val extraQueries = searchTexts.filter { variant ->
                variant.isNotBlank() && !variant.equals(defaultSearch, ignoreCase = true)
            }
            if (extraQueries.isNotEmpty()) {
                tracks = mergeIdentifyCatalogTracks(
                    tracks,
                    extraQueries.flatMap { metadataSource.searchOnlineCatalog(it) }
                )
                ranked = IdentifyRanking.rank(rankingQuery, tracks, limit = rankLimit)
                if (existingCandidates.isNotEmpty()) {
                    ranked = IdentifyRanking.appendCandidates(existingCandidates, ranked)
                }
                confidence = IdentifyRanking.confidence(ranked, rankingQuery)
            }
            if (confidence != IdentifyConfidence.HIGH) {
                val fallbackQuery = searchTexts.firstOrNull().orEmpty().ifBlank { defaultSearch }
                val extraFallback = extraQueries.firstOrNull()
                val fallbackQueries = listOfNotNull(
                    fallbackQuery.takeIf { it.isNotBlank() },
                    extraFallback
                ).distinct()
                if (fallbackQueries.isNotEmpty()) {
                    tracks = mergeIdentifyCatalogTracks(
                        tracks,
                        fallbackQueries.flatMap { query ->
                            metadataSource.searchIdentifyFallbacks(
                                query = query,
                                durationMs = working.durationMs
                            )
                        }
                    )
                    ranked = IdentifyRanking.rank(rankingQuery, tracks, limit = rankLimit)
                    if (existingCandidates.isNotEmpty()) {
                        ranked = IdentifyRanking.appendCandidates(existingCandidates, ranked)
                    }
                    confidence = IdentifyRanking.confidence(ranked, rankingQuery)
                }
            }
        }

        val nextIndex = if (isRefineSearch) {
            pageIndex + fetchedCount
        } else {
            0
        }
        val mayHaveMore = when {
            isRefineSearch -> fetchedCount >= IdentifyRanking.CATALOG_PAGE
            ranked.isNotEmpty() -> true
            else -> false
        }
        val enrichedRanked = IdentifyPipeline.enrichTrackNumberIfMissing(ranked) { first ->
            findTrackNumberInAlbum(
                artist = first.artist,
                album = first.album,
                title = first.title,
                durationMs = working.durationMs
            )
        }

        if (confidence == IdentifyConfidence.HIGH && enrichedRanked.isNotEmpty()) {
            val best = enrichedRanked.first()
            if (best.artist.isNotBlank() && best.album.isNotBlank() && !IdentifyRanking.isGenericAlbum(best.album)) {
                try {
                    loadKnownAlbumTracks(best.artist, best.album, fetchCatalog = true)
                } catch (_: Exception) {}
            }
        }

        IdentifyProposal(
            songId = song.id,
            queryArtist = queryArtist,
            queryTitle = if (trimmedCustom.isNotEmpty()) trimmedCustom else queryTitle,
            sourceHints = sourceHints,
            candidates = enrichedRanked,
            confidence = confidence,
            suggested = enrichedRanked.firstOrNull(),
            usedListenBrainz = usedListenBrainz,
            nextCatalogIndex = nextIndex,
            catalogMayHaveMore = mayHaveMore
        )
    }

    private suspend fun matchKnownAlbumFromLibrary(
        song: Song,
        queryArtist: String,
        queryTitle: String
    ): IdentifyCandidate? {
        val albums = libraryKnownAlbumsCached()
        if (albums.isEmpty()) return null
        val query = knownAlbumQueryOf(song, queryArtist = queryArtist, queryTitle = queryTitle)
        return assignUniqueKnownAlbumMatches(listOf(query), albums, scoped = false)[song.id]
            ?.toIdentifyCandidate()
    }

    override suspend fun loadKnownAlbumTracks(
        artist: String,
        album: String,
        fetchCatalog: Boolean
    ): KnownAlbumTracks? = withContext(Dispatchers.IO) {
        if (album.isBlank() || IdentifyRanking.isGenericAlbum(album)) return@withContext null
        val key = albumGroupKey(artist, album)
        val library = libraryKnownAlbumsCached().firstOrNull { it.key == key }
        val catalog = if (fetchCatalog) {
            catalogAlbumTracksCache.getOrPut(key) {
                fetchCatalogAlbumTracks(artist, album)
            }
        } else {
            emptyList()
        }
        mergeKnownAlbumTracks(artist, album, library, catalog)
    }

    override suspend fun loadLibraryKnownAlbums(): List<KnownAlbumTracks> = withContext(Dispatchers.IO) {
        libraryKnownAlbumsCached()
    }

    private suspend fun fetchCatalogAlbumTracks(
        artist: String,
        album: String
    ): List<KnownAlbumTrack> {
        val hits = metadataSource.searchAlbums("$artist $album".trim())
        val chosen = hits.firstOrNull { hit ->
            albumNamesMatch(hit.title, album) && artistsCompatible(hit.artist, artist)
        } ?: hits.firstOrNull { hit -> albumNamesMatch(hit.title, album) }
        if (chosen == null) return emptyList()
        return metadataSource.fetchAlbumTracks(
            albumId = chosen.id,
            albumTitle = chosen.title,
            artistName = chosen.artist,
            coverUrl = chosen.coverUrl
        ).map { it.toKnownAlbumTrack() }
    }

    internal suspend fun findTrackNumberInAlbum(
        artist: String,
        album: String,
        title: String,
        durationMs: Long = 0L
    ): Int? {
        if (album.isBlank() || IdentifyRanking.isGenericAlbum(album) || IdentifyRanking.isPlaceholderArtist(artist)) {
            return null
        }
        val albumTracks = loadKnownAlbumTracks(artist, album, fetchCatalog = false)
            ?: loadKnownAlbumTracks(artist, album, fetchCatalog = true)
            ?: return null
        val cleanedTitle = IdentifyRanking.cleanIdentityTitle(title, artist).lowercase()
        val strippedQuery = IdentifyRanking.stripTitleNoise(cleanedTitle).lowercase()
        val match = albumTracks.tracks.firstOrNull { track ->
            val trackCleaned = IdentifyRanking.cleanIdentityTitle(track.title, albumTracks.artist).lowercase()
            val trackStripped = IdentifyRanking.stripTitleNoise(trackCleaned).lowercase()
            trackCleaned == cleanedTitle ||
                trackStripped == strippedQuery ||
                (trackStripped.isNotEmpty() && strippedQuery.isNotEmpty() &&
                    IdentifyRanking.fieldSimilarity(trackStripped, strippedQuery) >= 0.80f) ||
                (durationMs > 0 && track.durationMs > 0 && kotlin.math.abs(track.durationMs - durationMs) <= 3500 &&
                    IdentifyRanking.fieldSimilarity(trackStripped, strippedQuery) >= 0.55f)
        }
        return match?.trackNumber?.takeIf { it > 0 }
    }

    override suspend fun applySongIdentity(
        songId: Long,
        candidate: IdentifyCandidate,
        fields: IdentifyApplyFields
    ): IdentifyResult {
        val applied = applySongIdentities(listOf(IdentifyApplyRequest(songId, candidate, fields)))
        return if (songId in applied) IdentifyResult.Updated(songId) else IdentifyResult.NoMatch
    }

    override suspend fun applySongIdentities(
        requests: List<IdentifyApplyRequest>
    ): Set<Long> = withContext(Dispatchers.IO) {
        if (requests.isEmpty()) return@withContext emptySet()
        val library = identityLibrarySongs()
        val byId = library.associateBy { it.id }
        val studio = studioAlbumKeysByArtist(library, IdentifyRanking::isGenericAlbum)
        val bucketArtistsByAlbum = HashMap<String, List<String>>()
        val resolved = requests.mapNotNull { request ->
            val entity = byId[request.songId] ?: return@mapNotNull null
            resolveAppliedIdentity(
                entity,
                request.candidate,
                request.fields,
                library,
                studio,
                bucketArtistsByAlbum
            )
        }
        if (resolved.isEmpty()) return@withContext emptySet()
        db.withTransaction {
            for (updated in resolved) {
                musicDao.updateSongIdentity(
                    songId = updated.id,
                    title = updated.title,
                    artist = updated.artist,
                    album = updated.album,
                    artworkUri = updated.artworkUri,
                    trackNumber = updated.trackNumber,
                    year = updated.year,
                    durationMs = updated.durationMs
                )
            }
        }
        syncSongsRelations(resolved)
        rememberIdentitySongs(resolved)
        val appliedIds = resolved.mapTo(LinkedHashSet(resolved.size)) { it.id }
        if (tagWritePreferences.settingsFlow.first().autoWriteTagsEnabled) {
            tagWriteScope.launch {
                resolved.forEach { writeTagsToFile(it) }
            }
        }
        appliedIds
    }

    private suspend fun resolveAppliedIdentity(
        entity: Song,
        candidate: IdentifyCandidate,
        fields: IdentifyApplyFields,
        library: List<Song>,
        studioKeysByArtist: Map<String, List<String>>,
        bucketArtistsByAlbum: MutableMap<String, List<String>>
    ): Song {
        // Prefer candidate over Room; strip generic album before merge so entity can fill.
        val preferred = candidate.track.identity.copy(
            album = candidate.album
                .takeIf { it.isNotBlank() && !IdentifyRanking.isGenericAlbum(it) }
                .orEmpty()
        )
        val merged = preferred.mergePreferring(entity.toIdentity())
        val candidateArtist = merged.artist
        val candidateTitle = IdentifyRanking.cleanIdentityTitle(merged.title, candidateArtist).ifBlank { merged.title }
        val bilingualTitle = IdentifyRanking.preferBilingualTitle(candidateTitle, entity.title)
        val candidateAlbum = IdentifyRanking.fallbackAlbum(merged.artist, merged.album)
        val candidateArtwork = merged.artworkUri
        val candidateTrackNumber = merged.trackNumber

        val resolvedAlbum = if (fields.album) {
            pickPersistedAlbumName(
                library = library,
                proposedAlbum = candidateAlbum.ifBlank { entity.album },
                proposedArtist = if (fields.artist) candidateArtist.ifBlank { entity.artist } else entity.artist,
                sourceAlbum = entity.album,
                isGeneric = IdentifyRanking::isGenericAlbum,
                studioKeysByArtist = studioKeysByArtist
            )
        } else {
            entity.album
        }
        val bucketArtists = if (fields.artist) {
            val cacheKey = albumIdentityKey(resolvedAlbum).ifBlank { resolvedAlbum }
            bucketArtistsByAlbum.getOrPut(cacheKey) {
                library.mapNotNull { song ->
                    song.artist.takeIf { albumNamesMatch(song.album, resolvedAlbum) }
                }
            } + entity.artist
        } else {
            emptyList()
        }
        val finalTitle = when {
            fields.title -> bilingualTitle.ifBlank { entity.title }
            IdentifyRanking.shouldApplyBilingualTitle(candidateTitle, entity.title) ->
                bilingualTitle.ifBlank { entity.title }

            else -> entity.title
        }
        val finalArtist = if (fields.artist) {
            pickPersistedArtistName(bucketArtists, candidateArtist.ifBlank { entity.artist })
        } else {
            entity.artist
        }
        val finalArtwork = if (fields.artwork) (candidateArtwork ?: entity.artworkUri) else entity.artworkUri
        val finalTrackNumber = if (fields.trackNumber) {
            if (candidateTrackNumber > 0) {
                candidateTrackNumber
            } else {
                findTrackNumberInAlbum(
                    artist = finalArtist,
                    album = resolvedAlbum,
                    title = finalTitle,
                    durationMs = entity.durationMs
                ) ?: entity.trackNumber
            }
        } else {
            entity.trackNumber
        }
        val finalYear = if (fields.year && candidate.year > 0) candidate.year else entity.year
        val finalDuration = if (entity.durationMs > 0) entity.durationMs else merged.durationMs
        return entity.copy(
            title = finalTitle,
            artist = finalArtist,
            album = resolvedAlbum,
            artworkUri = finalArtwork,
            trackNumber = finalTrackNumber,
            year = finalYear,
            durationMs = finalDuration
        )
    }

    override suspend fun identifySongMetadata(song: Song): IdentifyResult = withContext(Dispatchers.IO) {
        val proposal = proposeSongIdentity(song)
        if (proposal.alreadyIdentified) return@withContext IdentifyResult.Skipped
        val reviewGate = IdentifyPipeline.evaluateReviewGate(proposal)
        if (!reviewGate.requiresReview && proposal.suggested != null) {
            return@withContext applySongIdentity(
                song.id,
                proposal.suggested,
                IdentifyApplyFields.ALL.copy(title = false)
            )
        }
        IdentifyResult.NoMatch
    }

    private data class IdentifyCatalogFetch(
        val tracks: List<OnlineCatalogTrack>,
        val usedListenBrainz: Boolean
    )

    private suspend fun fetchIdentifyCatalogTracks(
        queryArtist: String,
        queryTitle: String,
        artistPlaceholder: Boolean,
        album: String,
        skipExactTitleLookup: Boolean,
        durationMs: Long,
        listenBrainzToken: String?,
        titleCollidesArtist: Boolean
    ): IdentifyCatalogFetch {
        val tracks = ArrayList<OnlineCatalogTrack>()
        var usedListenBrainz = false
        val token = listenBrainzToken?.trim().orEmpty()
        if (token.isNotEmpty() && !titleCollidesArtist && queryTitle.isNotBlank()) {
            val releaseHint = album.takeUnless { IdentifyRanking.isGenericAlbum(it) }
            metadataSource.lookupListenBrainzIdentifyTrack(
                artist = queryArtist,
                title = queryTitle,
                releaseName = releaseHint,
                token = token
            )?.let { lbTrack ->
                usedListenBrainz = true
                tracks.add(lbTrack)
            }
        }
        val primary = IdentifyRanking.catalogSearchText(
            artist = queryArtist,
            title = queryTitle,
            album = album,
            artistIsPlaceholder = artistPlaceholder
        )
        if (primary.isNotEmpty()) {
            tracks.addAll(
                metadataSource.searchMusicBrainzRecordings(
                    query = primary,
                    durationMs = durationMs
                )
            )
        }
        if (!artistPlaceholder && !skipExactTitleLookup) {
            metadataSource.fetchFullTrackMetadata(queryArtist, queryTitle)?.let { meta ->
                tracks.add(meta.toIdentifyCatalogTrack())
            }
        }
        if (primary.isNotEmpty()) {
            tracks.addAll(metadataSource.searchOnlineCatalog(primary))
        }
        return IdentifyCatalogFetch(
            tracks = mergeIdentifyCatalogTracks(emptyList(), tracks),
            usedListenBrainz = usedListenBrainz
        )
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

    private fun needsMetadataIdentify(song: Song): Boolean = needsGapIdentify(song)

    /**
     * Persist rip-style tag cleanup (`01` + `- Title`) so library/review stop showing junk
     * even when catalog confidence is not HIGH enough to auto-apply. Does not replace a
     * real ID3 title with a filename guess.
     */
    private suspend fun persistWeakIdentityCleanup(
        song: Song,
        hints: FilenameMetadataHints
    ): Song {
        val artistWeak = IdentifyRanking.isPlaceholderArtist(song.artist)
        val titleJunk = song.title.trimStart().let {
            it.startsWith("-") || it.startsWith("_") || looksLikeStoragePath(it)
        } || isTrackNumberLabel(song.title.trim()) || (
                artistWeak &&
                        hints.title != null &&
                        stripLeadingTitleJunk(song.title) == hints.title &&
                        song.title != hints.title
                ) || (
                artistWeak &&
                        !hints.artist.isNullOrBlank() &&
                        !hints.title.isNullOrBlank() &&
                        song.title != hints.title
                )

        val newArtist = when {
            artistWeak && !hints.artist.isNullOrBlank() -> hints.artist
            isTrackNumberLabel(song.artist) -> "Unknown Artist"
            else -> null
        }
        val newTitle = when {
            !hints.title.isNullOrBlank() && titleJunk -> hints.title
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
        rememberIdentitySong(updated)
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
        } catch (ignored: Exception) {
        }

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
        } catch (ignored: Exception) {
        }

        try {
            val mp = android.media.MediaPlayer()
            audioStore.applyDataSource(mp, ref)
            mp.prepare()
            val dur = mp.duration.toLong()
            mp.release()
            if (dur > 0) return dur
        } catch (ignored: Exception) {
        }

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
        if (updated != null) {
            syncSongsRelations(listOf(updated))
            rememberIdentitySong(updated)
            maybeWriteTags(updated)
        }
    }

    override suspend fun updateSongLyrics(songId: Long, lyrics: String?) =
        withContext(Dispatchers.IO) {
            val cleanLyrics = lyrics?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            musicDao.updateSongLyrics(songId, cleanLyrics)
        }

    override suspend fun findLocalLyrics(song: Song): String? =
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

    override suspend fun saveCompanionLrc(song: Song, lyrics: String): Boolean =
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

    override suspend fun fetchSongLyrics(song: Song): String? =
        withContext(Dispatchers.IO) {
            metadataSource.fetchLyrics(song.artist, song.title)
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
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

    override suspend fun searchAlbums(query: String): List<CatalogAlbum> =
        withContext(Dispatchers.IO) {
            metadataSource.searchAlbums(query)
        }

    override suspend fun setAlbumArtwork(albumKey: String, artworkUri: String?) =
        withContext(Dispatchers.IO) {
            val existing = musicDao.getAlbumOverride(albumKey)
            val override = existing?.copy(artworkUri = artworkUri)
                ?: AlbumOverride(albumKey = albumKey, displayName = albumKey, artworkUri = artworkUri)
            val savedArt = persistAlbumOverride(override)
            setArtworkOnAlbumBucket(albumKey, savedArt)
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

        val allSongs = musicDao.getIdentitySongs()
        val bucketKeys = libraryAlbumKeysInBucket(
            allSongs,
            oldKey,
            IdentifyRanking::isGenericAlbum
        ).ifEmpty { listOf(oldKey) }

        // One transaction: the rename, the old override delete and the new upsert are one edit. Split,
        // a crash in between renamed the songs while the override stayed under the old key, so the
        // album silently lost its custom cover / artist / genre / year.
        db.withTransaction {
            bucketKeys.forEach { key ->
                musicDao.updateSongsAlbumMetadata(
                    oldAlbum = key,
                    newAlbum = newName,
                    artist = safeArtist,
                    genre = safeGenre,
                    year = safeYear,
                    artworkUri = savedArt
                )
                if (key != newName) {
                    musicDao.deleteAlbumOverride(key)
                }
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
        invalidateIdentityLibrary()
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

            val remaining = musicDao.getIdentitySongs()
            libraryAlbumKeysInBucket(
                remaining,
                canonicalTarget,
                IdentifyRanking::isGenericAlbum
            ).forEach { rewriteAlbumKey(it) }
        }

        invalidateIdentityLibrary()
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

    override suspend fun createPlaylist(name: String, description: String?, coverUri: String?): Long =
        withContext(Dispatchers.IO) {
            val savedCover = savePlaylistCoverImage(coverUri)
            musicDao.insertPlaylist(
                PlaylistEntity(
                    name = name,
                    description = description?.ifBlank { null },
                    coverUri = savedCover
                )
            )
        }

    override suspend fun updatePlaylist(id: Long, name: String, description: String?, coverUri: String?) =
        withContext(Dispatchers.IO) {
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

    override suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        addSongsToPlaylist(playlistId, listOf(songId))
    }

    override suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext
        val startPos = (musicDao.getMaxPositionInPlaylist(playlistId) ?: -1) + 1
        val refs = songIds.mapIndexed { index, songId ->
            PlaylistSongCrossRef(playlistId = playlistId, songId = songId, position = startPos + index)
        }
        musicDao.addSongsToPlaylist(refs)
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        musicDao.removeSongFromPlaylist(playlistId, songId)
    }

    override suspend fun reorderPlaylistSongs(playlistId: Long, songIds: List<Long>) = withContext(Dispatchers.IO) {
        musicDao.reorderPlaylistSongs(playlistId, songIds)
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

        val ytStream = resolveTrackStreamForDownload(track, forceRefresh = true).getOrElse { e ->
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

        val sanitizedName = UploadNameSanitizer.sanitize(finalArtist + "_" + finalTitle)
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

        val cachedPrefix = com.bestiapop.android.data.stream.BestiaPopMediaCache.copyCachedPrefixToFile(
            context = context,
            videoId = ytStream.videoId,
            destination = file
        )
        if (cachedPrefix > 0L) {
            downloadedBytes = cachedPrefix
            val parsedUrl = currentUrl.toHttpUrlOrNull()
            val clen = GoogleVideoRange.remainingLength(
                parsedUrl?.host,
                parsedUrl?.queryParameter("clen"),
                0L
            )
            if (clen != null && downloadedBytes >= clen) {
                expectedTotalBytes = clen
                downloadSuccess = true
            }
        }

        while (attempts < MAX_DOWNLOAD_ATTEMPTS && !downloadSuccess) {
            attempts++
            lastResponseCode = 0
            try {
                var continueChunks = true
                while (continueChunks) {
                    continueChunks = false
                    val chunkStart = downloadedBytes
                    val reqBuilder = okhttp3.Request.Builder()
                        .url(currentUrl)
                        .header("Accept", "*/*")
                        .header("Accept-Encoding", "identity")
                        .header("User-Agent", userAgentToUse)

                    val parsedUrl = currentUrl.toHttpUrlOrNull()
                    val googlevideoClen = GoogleVideoRange.remainingLength(
                        parsedUrl?.host,
                        parsedUrl?.queryParameter("clen"),
                        0L
                    )
                    val rangeHeader = GoogleVideoRange.nextChunkRange(
                        parsedUrl?.host,
                        parsedUrl?.queryParameter("clen"),
                        downloadedBytes
                    ) ?: downloadedBytes.takeIf { it > 0L }?.let { "bytes=$it-" }
                    if (rangeHeader != null) {
                        reqBuilder.header("Range", rangeHeader)
                    }

                    downloadCallFactory.newCall(reqBuilder.build()).useCancellable { response ->
                        lastResponseCode = response.code
                        if (!response.isSuccessful) {
                            lastHttpError =
                                "HTTP ${response.code} (${response.message.ifBlank { "Error de servidor" }})"
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
                        expectedTotalBytes = googlevideoClen
                            ?: if (bodyLength > 0) downloadedBytes + bodyLength else -1L

                        body.byteStream().use { input ->
                            val baseBytes = downloadedBytes
                            val cacheChunkCapacity = 512 * 1024
                            val cacheChunkBuffer = ByteArray(cacheChunkCapacity)
                            var cacheBufferCount = 0
                            var cacheBufferStartPos = baseBytes
                            copyTransferToFile(
                                input = input,
                                destination = file,
                                append = resuming,
                                bufferSize = 65536,
                                onChunk = { relPos, buf, len ->
                                    if (cacheBufferCount == 0) {
                                        cacheBufferStartPos = baseBytes + relPos
                                    }
                                    var remainingLen = len
                                    var bufOffset = 0
                                    while (remainingLen > 0) {
                                        val space = cacheChunkCapacity - cacheBufferCount
                                        val toCopy = minOf(remainingLen, space)
                                        System.arraycopy(buf, bufOffset, cacheChunkBuffer, cacheBufferCount, toCopy)
                                        cacheBufferCount += toCopy
                                        bufOffset += toCopy
                                        remainingLen -= toCopy
                                        if (cacheBufferCount >= cacheChunkCapacity) {
                                            com.bestiapop.android.data.stream.BestiaPopMediaCache.writeChunkToCache(
                                                context = context,
                                                videoId = ytStream.videoId,
                                                position = cacheBufferStartPos,
                                                bytes = cacheChunkBuffer,
                                                offset = 0,
                                                length = cacheBufferCount
                                            )
                                            cacheBufferStartPos += cacheBufferCount
                                            cacheBufferCount = 0
                                        }
                                    }
                                }
                            ) { copied ->
                                downloadedBytes = baseBytes + copied
                            }
                            if (cacheBufferCount > 0) {
                                com.bestiapop.android.data.stream.BestiaPopMediaCache.writeChunkToCache(
                                    context = context,
                                    videoId = ytStream.videoId,
                                    position = cacheBufferStartPos,
                                    bytes = cacheChunkBuffer,
                                    offset = 0,
                                    length = cacheBufferCount
                                )
                                cacheBufferCount = 0
                            }
                        }
                        // A clean EOF short of Content-Length is a truncated body, not a finished file.
                        val minAudioBytes = 200_000L
                        downloadSuccess = if (expectedTotalBytes > 0L) {
                            downloadedBytes >= expectedTotalBytes
                        } else {
                            downloadedBytes >= minAudioBytes
                        }
                        if (!downloadSuccess) {
                            val targetStr = if (expectedTotalBytes > 0L) "/$expectedTotalBytes" else ""
                            lastHttpError = "Descarga incompleta ($downloadedBytes$targetStr bytes)"
                        }
                    }
                    continueChunks = googlevideoClen != null &&
                            !downloadSuccess &&
                            lastResponseCode == 206 &&
                            downloadedBytes > chunkStart &&
                            downloadedBytes < googlevideoClen
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
                if (lastResponseCode == 416) {
                    // HTTP 416 Range Not Satisfiable: cached/partial file offset desynced from stream.
                    // Reset to offset 0 and clean up partial file to restart cleanly.
                    downloadedBytes = 0L
                    expectedTotalBytes = -1L
                    if (file.exists()) {
                        file.delete()
                    }
                } else if (lastResponseCode == 403 || lastResponseCode == 410) {
                    // CDN URLs expire mid-download: without a fresh extract every retry hits the same
                    val refreshed = resolveTrackStreamForDownload(track, forceRefresh = true).getOrNull()
                    refreshed?.let {
                        currentUrl = it.audioUrl
                        downloadedBytes = 0L
                        expectedTotalBytes = -1L
                        if (file.exists()) {
                            file.delete()
                        }
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

        val library = identityLibrarySongs()
        finalAlbum = pickPersistedAlbumName(
            library = library,
            proposedAlbum = finalAlbum,
            proposedArtist = finalArtist,
            sourceAlbum = overwriteTarget?.album.orEmpty(),
            isGeneric = IdentifyRanking::isGenericAlbum
        )
        val albumArtists = library
            .filter { albumNamesMatch(it.album, finalAlbum) }
            .map { it.artist } + listOfNotNull(overwriteTarget?.artist)
        finalArtist = pickPersistedArtistName(albumArtists, finalArtist)

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
            rememberIdentitySong(updated)
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

    private suspend fun resolveTrackStreamForDownload(
        track: OnlineCatalogTrack,
        forceRefresh: Boolean = true
    ): Result<com.bestiapop.android.data.network.YouTubeStreamResult> {
        val queryOrId = com.bestiapop.android.data.network.YouTubeExtractor.resolveYouTubeQueryOrId(track)
        return streamResolver.resolveQuery(
            queryOrId = queryOrId,
            forceRefresh = forceRefresh,
            expected = track.identity,
            fallbackQuery = track.youtubeSearchQuery()
        )
    }

    override suspend fun syncTagsToFiles(onProgress: LibraryScanProgress?): TagSyncSummary =
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
                    TagWriteResult.Unsupported, TagWriteResult.NotWritable, TagWriteResult.PostponedActivePlayback -> skipped++
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

    private suspend fun setArtworkOnAlbumBucket(
        albumKey: String,
        artworkUri: String?,
        identitySongs: List<Song>? = null
    ) {
        val library = identitySongs ?: musicDao.getIdentitySongs()
        val keys = libraryAlbumKeysInBucket(
            library,
            albumKey,
            IdentifyRanking::isGenericAlbum
        ).ifEmpty { listOf(albumKey) }
        keys.forEach { musicDao.setAlbumArtwork(it, artworkUri) }
    }

    private suspend fun maybeWriteTagsForAlbum(album: String) {
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

    private val postponedTagWrites = java.util.concurrent.ConcurrentHashMap<Long, Song>()
    var isSongActiveInPlayback: (Long) -> Boolean = { false }

    suspend fun flushPostponedTagWrites(activeSongId: Long? = null) = withContext(Dispatchers.IO) {
        if (postponedTagWrites.isEmpty()) return@withContext
        val toWrite = postponedTagWrites.filterKeys { it != activeSongId }
        for ((id, song) in toWrite) {
            postponedTagWrites.remove(id)
            writeTagsToFile(song)
        }
    }

    private fun writeTagsToFile(song: Song): TagWriteResult {
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

    private suspend fun lookupSongByArtistTitle(artist: String, title: String): Song? {
        val key = TrackMatchKeys.matchKey(artist, title)
        if (key.isEmpty()) return null
        val songs = identityLibrarySongs()
        for (song in songs) {
            if (TrackMatchKeys.matchKey(song.artist, song.title) == key) {
                return song
            }
        }
        return null
    }

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
            db.withTransaction {
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
            db.withTransaction {
                for ((id, dateAdded) in updates) {
                    musicDao.updateSongDateAdded(id, dateAdded)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            com.bestiapop.android.data.util.CrashReporter.recordNonFatal(
                e,
                mapOf("migrate_phase" to "device_date_added")
            )
        }
    }

    /**
     * One-shot: re-read jaudiotagger/ID3 tags for Unknown Artist / generic album rows
     * and fill only those gaps. Returns songs that still need online identify.
     */
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
            val library = identityLibrarySongs()
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
                        persistEmbeddedArtwork = ::persistEmbeddedArtwork
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    continue
                }
                val filled = fillSongGapsFromFileTags(song, meta, library + updated)
                if (filled != song) updated += filled
            }
            if (updated.isNotEmpty()) {
                db.withTransaction {
                    for (song in updated) {
                        musicDao.updateSong(song)
                    }
                }
                rememberIdentitySongs(updated)
            }
            val byId = songs.associateBy { it.id }.toMutableMap()
            for (song in updated) byId[song.id] = song
            candidates.mapNotNull { byId[it.id] }.filter { needsGapIdentify(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            com.bestiapop.android.data.util.CrashReporter.recordNonFatal(
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
                // fall through
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

    private fun isPlaceholderTitle(title: String): Boolean =
        title.isBlank() ||
                title == "YouTube Track" ||
                title == "Canción desde Link" ||
                title == "Enlace YouTube" ||
                title == "Descarga"

    private fun hasUsableIdentity(artist: String, title: String): Boolean =
        !IdentifyRanking.isPlaceholderArtist(artist) ||
            (!isTrackNumberLabel(title) && !isPlaceholderTitle(title))

    override suspend fun saveAlbumTracksToLibrary(
        albumTitle: String,
        artistName: String,
        coverUrl: String?,
        year: Int,
        genre: String,
        tracks: List<com.bestiapop.android.data.model.CatalogTrackCandidate>
    ): List<Song> = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext emptyList()
        val albumClean = albumTitle.trim()
        val artistClean = artistName.trim()
        val albumHash = albumClean.lowercase().hashCode().toUInt().toString(16)
        val now = System.currentTimeMillis()

        val songsToInsert = ArrayList<Song>(tracks.size)
        tracks.forEachIndexed { index, candidate ->
            val trackNum = candidate.trackNumber.takeIf { it > 0 } ?: (index + 1)
            val titleClean = candidate.title.trim()
            val trackArtist = candidate.artist.trim().ifBlank { artistClean }
            val trackHash = "$trackArtist-$titleClean".lowercase().hashCode().toUInt().toString(16)
            val uri = "remote://catalog/$albumHash/$trackNum/$trackHash"

            val existing = musicDao.getSongByUri(uri)
            if (existing == null) {
                songsToInsert.add(
                    Song(
                        uriString = uri,
                        title = titleClean,
                        artist = trackArtist,
                        album = albumClean,
                        genre = genre.trim().ifBlank { Song.UNKNOWN_GENRE },
                        durationMs = candidate.durationMs,
                        year = year,
                        trackNumber = trackNum,
                        artworkUri = coverUrl,
                        dateAdded = now
                    )
                )
            }
        }

        if (songsToInsert.isNotEmpty()) {
            musicDao.insertSongs(songsToInsert)
            val inserted = musicDao.getSavedRemoteAlbumSongs(albumClean, artistClean)
            syncSongsRelations(inserted)
            return@withContext inserted
        }

        musicDao.getSavedRemoteAlbumSongs(albumClean, artistClean)
    }

    override suspend fun removeSavedAlbumFromLibrary(albumName: String, artistName: String): Int =
        withContext(Dispatchers.IO) {
            musicDao.deleteSavedRemoteAlbum(albumName.trim(), artistName.trim())
        }

    override suspend fun getSongsForArtist(artistName: String): List<Song> = withContext(Dispatchers.IO) {
        val norm = MetadataSplitter.artistIdentityKey(artistName)
        if (norm.isBlank()) emptyList()
        else musicDao.getSongsForArtistNormalized(norm)
    }

    override suspend fun getSongsForGenre(genreName: String): List<Song> = withContext(Dispatchers.IO) {
        val norm = MetadataSplitter.genreIdentityKey(genreName)
        if (norm.isBlank()) emptyList()
        else musicDao.getSongsForGenreNormalized(norm)
    }

    override suspend fun getAlbumsForArtist(artistName: String): List<String> = withContext(Dispatchers.IO) {
        val norm = MetadataSplitter.artistIdentityKey(artistName)
        if (norm.isBlank()) emptyList()
        else musicDao.getAlbumsForArtistNormalized(norm)
    }

    private suspend fun resolveTrackNumberFallback(artist: String, title: String): Int {
        if (!hasUsableIdentity(artist, title)) return 0
        return try {
            val fetched = metadataSource.fetchFullTrackMetadata(artist, title)
            fetched?.trackNumber?.takeIf { it > 0 } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun syncSongsRelations(songs: List<Song>) {
        if (songs.isEmpty()) return
        val candidateArtists = MetadataSplitter.buildCandidateArtists(
            songs, { it.artist }, IdentifyRanking::isPlaceholderArtist
        )
        db.withTransaction {
            for (song in songs) {
                val songId = song.id
                if (songId <= 0L) continue

                // 1. Artists — use identity key for normalizedName, update display name when better
                val artistTokens = MetadataSplitter.splitArtists(song.artist, candidateArtists)
                val artistIds = mutableListOf<Long>()
                val seenArtistKeys = mutableSetOf<String>()
                for (token in artistTokens) {
                    val identityKey = MetadataSplitter.artistIdentityKey(token)
                    if (!seenArtistKeys.add(identityKey)) continue
                    val existing = musicDao.getArtistByNormalizedName(identityKey)
                    val id: Long
                    if (existing != null) {
                        id = existing.id
                        // Update display name if this variant is better than the stored one
                        val preferred = MetadataSplitter.preferredArtistDisplayName(
                            listOf(existing.name, token)
                        )
                        if (preferred != existing.name) {
                            musicDao.updateArtistName(existing.id, preferred)
                        }
                    } else {
                        val inserted = musicDao.insertArtist(
                            ArtistEntity(name = token, normalizedName = identityKey)
                        )
                        id = if (inserted != -1L) {
                            inserted
                        } else {
                            musicDao.getArtistByNormalizedName(identityKey)?.id ?: continue
                        }
                    }
                    artistIds.add(id)
                }
                musicDao.deleteSongArtistCrossRefs(songId)
                if (artistIds.isNotEmpty()) {
                    musicDao.insertSongArtistCrossRefs(
                        artistIds.mapIndexed { idx, aId ->
                            SongArtistCrossRef(
                                songId = songId,
                                artistId = aId,
                                isPrimary = idx == 0,
                                position = idx
                            )
                        }
                    )
                }

                // 2. Genres — use identity key for normalizedName, update display name when better
                val genreTokens = MetadataSplitter.splitGenres(song.genre)
                val genreIds = mutableListOf<Long>()
                val seenGenreKeys = mutableSetOf<String>()
                for (token in genreTokens) {
                    val identityKey = MetadataSplitter.genreIdentityKey(token)
                    if (!seenGenreKeys.add(identityKey)) continue
                    val existing = musicDao.getGenreByNormalizedName(identityKey)
                    val id: Long
                    if (existing != null) {
                        id = existing.id
                        // Update display name if this variant is better than the stored one
                        val preferred = MetadataSplitter.preferredGenreDisplayName(
                            listOf(existing.name, token)
                        )
                        if (preferred != existing.name) {
                            musicDao.updateGenreName(existing.id, preferred)
                        }
                    } else {
                        val inserted = musicDao.insertGenre(
                            GenreEntity(name = token, normalizedName = identityKey)
                        )
                        id = if (inserted != -1L) {
                            inserted
                        } else {
                            musicDao.getGenreByNormalizedName(identityKey)?.id ?: continue
                        }
                    }
                    genreIds.add(id)
                }
                musicDao.deleteSongGenreCrossRefs(songId)
                if (genreIds.isNotEmpty()) {
                    musicDao.insertSongGenreCrossRefs(
                        genreIds.map { gId ->
                            SongGenreCrossRef(
                                songId = songId,
                                genreId = gId
                            )
                        }
                    )
                }

                // 3. Album Relations
                val albumKey = albumIdentityKey(song.album)
                if (albumKey.isNotBlank()) {
                    if (artistIds.isNotEmpty()) {
                        val albumArtistRefs = artistIds.map { aId ->
                            AlbumArtistCrossRef(
                                albumKey = albumKey,
                                artistId = aId
                            )
                        }
                        musicDao.insertAlbumArtistCrossRefs(albumArtistRefs)
                    }

                    if (genreIds.isNotEmpty()) {
                        val albumGenreRefs = genreIds.map { gId ->
                            AlbumGenreCrossRef(
                                albumKey = albumKey,
                                genreId = gId
                            )
                        }
                        musicDao.insertAlbumGenreCrossRefs(albumGenreRefs)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_DOWNLOAD_ATTEMPTS = 5
        const val DOWNLOAD_RETRY_BACKOFF_MS = 750L
        const val IDENTITY_SONG_ID_CHUNK = 500
    }
}
