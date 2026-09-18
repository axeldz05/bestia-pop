package com.bestiapop.android.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.PlaylistPendingTrackEntity
import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.model.isRemote
import com.bestiapop.android.data.model.toListenBrainzCatalogTrack
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.stream.StreamResolver
import com.bestiapop.android.data.util.AudioPersistRef
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.domain.repository.LibraryScanProgress
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.room.withTransaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock

internal const val SCAN_FILE_PARALLEL = 4

internal class ScanProgressTicker(
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

internal class AndroidRepositoryFileStore(
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
    suspend fun fetchTrackArtwork(track: TrackMeta): String? {
        val query = track.album.takeIf { it.isNotBlank() && !it.equals("Unknown Album", ignoreCase = true) } ?: track.title
        var art = fetchAlbumArtUrl(track.artist, query)
        if (art.isNullOrBlank() && query != track.title && track.title.isNotBlank()) {
            art = fetchAlbumArtUrl(track.artist, track.title)
        }
        return art
    }
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

internal object ProductionRepositoryMetadataSource : RepositoryMetadataSource {
    override suspend fun fetchAlbumArtUrl(artist: String, titleOrAlbum: String): String? =
        MetadataFetcher.fetchAlbumArtUrl(artist, titleOrAlbum)

    override suspend fun fetchTrackArtwork(track: TrackMeta): String? =
        MetadataFetcher.fetchTrackArtwork(track)

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
internal suspend fun <T> okhttp3.Call.useCancellable(
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
        artworkUri = artworkUri,
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
    artworkUri = artworkUri,
    position = position
)

internal const val IDENTITY_SONG_ID_CHUNK = 500

internal data class MusicRepositoryDependencies(
    val db: AppDatabase,
    val streamResolver: StreamResolver,
    val audioStore: RepositoryFileStore,
    val downloadCallFactory: okhttp3.Call.Factory,
    val metadataSource: RepositoryMetadataSource,
    val downloadRetryDelay: suspend (Long) -> Unit
)

internal fun productionDependencies(context: Context) = MusicRepositoryDependencies(
    db = AppDatabase.getDatabase(context),
    streamResolver = StreamResolver(),
    audioStore = AndroidRepositoryFileStore(MusicFileStore(context)),
    downloadCallFactory = com.bestiapop.android.data.network.HttpClients.transfer,
    metadataSource = ProductionRepositoryMetadataSource,
    downloadRetryDelay = { millis -> delay(millis) }
)

internal class RepositoryIdentityCache(
    private val musicDao: com.bestiapop.android.data.db.MusicDao
) {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var identityLibrary: List<com.bestiapop.android.data.model.Song>? = null
    private var cachedLibraryArtists: List<String>? = null
    private var cachedKnownAlbums: List<com.bestiapop.android.domain.util.KnownAlbumTracks>? = null

    suspend fun getSongs(): List<com.bestiapop.android.data.model.Song> = mutex.withLock {
        identityLibrary?.let { return@withLock it }
        val loaded = musicDao.getIdentitySongs()
        identityLibrary = loaded
        loaded
    }

    suspend fun getArtists(): List<String> = mutex.withLock {
        cachedLibraryArtists?.let { return@withLock it }
        val songs = identityLibrary ?: musicDao.getIdentitySongs().also { identityLibrary = it }
        val artists = songs.map { it.artist }.distinct()
        cachedLibraryArtists = artists
        artists
    }

    suspend fun getKnownAlbums(): List<com.bestiapop.android.domain.util.KnownAlbumTracks> = mutex.withLock {
        cachedKnownAlbums?.let { return@withLock it }
        val songs = identityLibrary ?: musicDao.getIdentitySongs().also { identityLibrary = it }
        val albums = com.bestiapop.android.domain.util.knownAlbumsFromLibrary(songs)
        cachedKnownAlbums = albums
        albums
    }

    suspend fun remember(updated: List<com.bestiapop.android.data.model.Song>) {
        if (updated.isEmpty()) return
        mutex.withLock {
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

    suspend fun remember(updated: com.bestiapop.android.data.model.Song) = remember(listOf(updated))

    suspend fun invalidate() {
        mutex.withLock {
            identityLibrary = null
            cachedLibraryArtists = null
            cachedKnownAlbums = null
        }
    }

    suspend fun findSongByArtistTitle(artist: String, title: String): com.bestiapop.android.data.model.Song? {
        val key = com.bestiapop.android.domain.util.TrackMatchKeys.matchKey(artist, title)
        if (key.isEmpty()) return null
        val songs = getSongs()
        var remoteFallback: com.bestiapop.android.data.model.Song? = null
        for (song in songs) {
            if (com.bestiapop.android.domain.util.TrackMatchKeys.matchKey(song.artist, song.title) == key) {
                if (!song.isRemote) return song
                if (remoteFallback == null) remoteFallback = song
            }
        }
        return remoteFallback
    }
}

internal suspend fun syncSongsRelations(
    db: AppDatabase,
    musicDao: com.bestiapop.android.data.db.MusicDao,
    songs: List<com.bestiapop.android.data.model.Song>
) {
    if (songs.isEmpty()) return
    val candidateArtists = com.bestiapop.android.domain.util.MetadataSplitter.buildCandidateArtists(
        songs, { it.artist }, com.bestiapop.android.domain.util.IdentifyRanking::isPlaceholderArtist
    )
    db.withTransaction {
        for (song in songs) {
            val songId = song.id
            if (songId <= 0L) continue

            // 1. Artists — use identity key for normalizedName, update display name when better
            val artistTokens = com.bestiapop.android.domain.util.MetadataSplitter.splitArtists(song.artist, candidateArtists)
            val artistIds = mutableListOf<Long>()
            val seenArtistKeys = mutableSetOf<String>()
            for (token in artistTokens) {
                val identityKey = com.bestiapop.android.domain.util.MetadataSplitter.artistIdentityKey(token)
                if (!seenArtistKeys.add(identityKey)) continue
                val existing = musicDao.getArtistByNormalizedName(identityKey)
                val id: Long
                if (existing != null) {
                    id = existing.id
                    val preferred = com.bestiapop.android.domain.util.MetadataSplitter.preferredArtistDisplayName(
                        listOf(existing.name, token)
                    )
                    if (preferred != existing.name) {
                        musicDao.updateArtistName(existing.id, preferred)
                    }
                } else {
                    val inserted = musicDao.insertArtist(
                        com.bestiapop.android.data.db.ArtistEntity(name = token, normalizedName = identityKey)
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
                        com.bestiapop.android.data.db.SongArtistCrossRef(
                            songId = songId,
                            artistId = aId,
                            isPrimary = idx == 0,
                            position = idx
                        )
                    }
                )
            }

            // 2. Genres — use identity key for normalizedName, update display name when better
            val genreTokens = com.bestiapop.android.domain.util.MetadataSplitter.splitGenres(song.genre)
            val genreIds = mutableListOf<Long>()
            val seenGenreKeys = mutableSetOf<String>()
            for (token in genreTokens) {
                val identityKey = com.bestiapop.android.domain.util.MetadataSplitter.genreIdentityKey(token)
                if (!seenGenreKeys.add(identityKey)) continue
                val existing = musicDao.getGenreByNormalizedName(identityKey)
                val id: Long
                if (existing != null) {
                    id = existing.id
                    val preferred = com.bestiapop.android.domain.util.MetadataSplitter.preferredGenreDisplayName(
                        listOf(existing.name, token)
                    )
                    if (preferred != existing.name) {
                        musicDao.updateGenreName(existing.id, preferred)
                    }
                } else {
                    val inserted = musicDao.insertGenre(
                        com.bestiapop.android.data.db.GenreEntity(name = token, normalizedName = identityKey)
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
                        com.bestiapop.android.data.db.SongGenreCrossRef(
                            songId = songId,
                            genreId = gId
                        )
                    }
                )
            }

            // 3. Album Relations
            val albumKey = com.bestiapop.android.domain.util.albumIdentityKey(song.album)
            if (albumKey.isNotBlank()) {
                if (artistIds.isNotEmpty()) {
                    val albumArtistRefs = artistIds.map { aId ->
                        com.bestiapop.android.data.db.AlbumArtistCrossRef(
                            albumKey = albumKey,
                            artistId = aId
                        )
                    }
                    musicDao.insertAlbumArtistCrossRefs(albumArtistRefs)
                }

                if (genreIds.isNotEmpty()) {
                    val albumGenreRefs = genreIds.map { gId ->
                        com.bestiapop.android.data.db.AlbumGenreCrossRef(
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

internal fun isPlaceholderTitle(title: String): Boolean =
    title.isBlank() ||
            title == "YouTube Track" ||
            title == "Canción desde Link" ||
            title == "Enlace YouTube" ||
            title == "Descarga"

internal suspend fun insertOrUpdateSongByUri(
    database: AppDatabase,
    musicDao: com.bestiapop.android.data.db.MusicDao,
    identityCache: RepositoryIdentityCache,
    song: com.bestiapop.android.data.model.Song
): Long {
    val insertedId = musicDao.insertSong(song)
    if (insertedId != -1L) {
        identityCache.invalidate()
        syncSongsRelations(database, musicDao, listOf(song.copy(id = insertedId)))
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
    syncSongsRelations(database, musicDao, listOf(updated))
    identityCache.remember(updated)
    return existing.id
}
