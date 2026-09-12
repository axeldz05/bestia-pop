package com.bestiapop.android.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.DownloadConflictPolicy
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyApplyRequest
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.SongPathRef
import com.bestiapop.android.data.network.HttpClients
import com.bestiapop.android.data.preferences.LibraryTagWritePreferencesRepository
import com.bestiapop.android.data.stream.StreamResolver
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.PlaybackDiagnostics
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.TagSyncSummary
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.repository.LibraryScanProgress
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.KnownAlbumTracks
import com.bestiapop.android.domain.util.MetadataSplitter
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.isTrackNumberLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import okhttp3.Call

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
        downloadCallFactory: Call.Factory = HttpClients.transfer,
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

    private val identityCache = RepositoryIdentityCache(musicDao)

    private val fileTagSyncOperator = FileTagSyncOperator(
        context = context,
        musicDao = musicDao,
        audioStore = audioStore,
        tagWritePreferences = tagWritePreferences
    )

    private val libraryScanOperator = LibraryScanOperator(
        context = context,
        database = db,
        musicDao = musicDao,
        audioStore = audioStore,
        identityCache = identityCache,
        pruneUnplayableCorruptSongs = { pruneUnplayableCorruptSongs() }
    )

    private val songIdentifyOperator = SongIdentifyOperator(
        database = db,
        musicDao = musicDao,
        metadataSource = metadataSource,
        identityCache = identityCache,
        onSongMetadataPersisted = { songs ->
            for (song in songs) {
                fileTagSyncOperator.maybeWriteTags(song)
            }
        }
    )

    private val onlineTrackDownloadOperator = OnlineTrackDownloadOperator(
        context = context,
        database = db,
        musicDao = musicDao,
        audioStore = audioStore,
        streamResolver = streamResolver,
        downloadCallFactory = downloadCallFactory,
        downloadRetryDelay = downloadRetryDelay,
        metadataSource = metadataSource,
        identityCache = identityCache,
        onSongSaved = { fileTagSyncOperator.maybeWriteTags(it) }
    )

    private val albumMetadataOperator = AlbumMetadataOperator(
        context = context,
        database = db,
        musicDao = musicDao,
        metadataSource = metadataSource,
        identityCache = identityCache,
        fileTagSyncOperator = fileTagSyncOperator
    )

    private val playlistRepositoryOperator = PlaylistRepositoryOperator(
        musicDao = musicDao,
        savePlaylistCoverImage = { albumMetadataOperator.savePlaylistCoverImage(it) }
    )

    private val songEnhancementOperator = SongEnhancementOperator(
        database = db,
        musicDao = musicDao,
        audioStore = audioStore,
        metadataSource = metadataSource,
        libraryScanOperator = libraryScanOperator,
        songIdentifyOperator = songIdentifyOperator,
        fileTagSyncOperator = fileTagSyncOperator,
        setArtworkOnAlbumBucket = { album, art, songs ->
            albumMetadataOperator.setArtworkOnAlbumBucket(album, art, songs)
        }
    )

    private val legacyDatabaseMigrationOperator = LegacyDatabaseMigrationOperator(
        context = context,
        database = db,
        musicDao = musicDao,
        audioStore = audioStore,
        identityCache = identityCache,
        libraryScanOperator = libraryScanOperator,
        identifySongMetadata = { songIdentifyOperator.identifySongMetadata(it) }
    )

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

    var isSongActiveInPlayback: (Long) -> Boolean
        get() = fileTagSyncOperator.isSongActiveInPlayback
        set(value) {
            fileTagSyncOperator.isSongActiveInPlayback = value
        }

    suspend fun flushPostponedTagWrites(activeSongId: Long? = null) =
        fileTagSyncOperator.flushPostponedTagWrites(activeSongId)

    // Library scan & file indexing
    override suspend fun scanMediaStore(onProgress: LibraryScanProgress?): List<Song> =
        libraryScanOperator.scanMediaStore(onProgress)

    override suspend fun resyncAppManagedMusic(onProgress: LibraryScanProgress?): List<Song> =
        libraryScanOperator.resyncAppManagedMusic(onProgress)

    override suspend fun scanFolderUri(treeUri: Uri, onProgress: LibraryScanProgress?): List<Song> =
        libraryScanOperator.scanFolderUri(treeUri, onProgress)

    override fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String? =
        libraryScanOperator.extractAndSaveEmbeddedArtwork(audioPathOrUri, identifier)

    internal fun persistEmbeddedArtwork(pictureBytes: ByteArray, identifier: String): String? =
        libraryScanOperator.persistEmbeddedArtwork(pictureBytes, identifier)

    // Song identify
    override suspend fun proposeSongIdentity(
        song: Song,
        customQuery: String?,
        force: Boolean,
        listenBrainzToken: String?,
        filters: IdentifySearchFilters,
        catalogIndex: Int,
        existingCandidates: List<IdentifyCandidate>
    ): IdentifyProposal = songIdentifyOperator.proposeSongIdentity(
        song = song,
        customQuery = customQuery,
        force = force,
        listenBrainzToken = listenBrainzToken,
        filters = filters,
        catalogIndex = catalogIndex,
        existingCandidates = existingCandidates
    )

    override suspend fun applySongIdentity(
        songId: Long,
        candidate: IdentifyCandidate,
        fields: IdentifyApplyFields
    ): IdentifyResult = songIdentifyOperator.applySongIdentity(songId, candidate, fields)

    override suspend fun applySongIdentities(requests: List<IdentifyApplyRequest>): Set<Long> =
        songIdentifyOperator.applySongIdentities(requests)

    override suspend fun identifySongMetadata(song: Song): IdentifyResult =
        songIdentifyOperator.identifySongMetadata(song)

    override suspend fun loadKnownAlbumTracks(
        artist: String,
        album: String,
        fetchCatalog: Boolean
    ): KnownAlbumTracks? = songIdentifyOperator.loadKnownAlbumTracks(artist, album, fetchCatalog)

    override suspend fun loadLibraryKnownAlbums(): List<KnownAlbumTracks> =
        songIdentifyOperator.loadLibraryKnownAlbums()

    internal suspend fun findTrackNumberInAlbum(
        artist: String,
        album: String,
        title: String,
        durationMs: Long = 0L
    ): Int? = songIdentifyOperator.findTrackNumberInAlbum(artist, album, title, durationMs)

    // Online track downloading
    override suspend fun downloadAndSaveOnlineTrack(
        track: OnlineCatalogTrack,
        onProgress: ((DownloadPhase) -> Unit)?,
        conflictPolicy: DownloadConflictPolicy?
    ): Song = onlineTrackDownloadOperator.downloadAndSaveOnlineTrack(track, onProgress, conflictPolicy)

    suspend fun resolveTrackStreamForDownload(
        track: OnlineCatalogTrack,
        forceRefresh: Boolean = true
    ) = onlineTrackDownloadOperator.resolveTrackStreamForDownload(track, forceRefresh)

    // Tag synchronization
    override suspend fun syncTagsToFiles(onProgress: LibraryScanProgress?): TagSyncSummary =
        fileTagSyncOperator.syncTagsToFiles(onProgress)

    // Playlist queries & operations
    override fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> =
        playlistRepositoryOperator.getPlaylistSongsFlow(playlistId)

    override fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> =
        playlistRepositoryOperator.getPlaylistDetailsFlow(playlistId)

    override suspend fun getPlaylistSongsOrdered(playlistId: Long): List<Song> =
        playlistRepositoryOperator.getPlaylistSongsOrdered(playlistId)

    override suspend fun createPlaylist(name: String, description: String?, coverUri: String?): Long =
        playlistRepositoryOperator.createPlaylist(name, description, coverUri)

    override suspend fun updatePlaylist(id: Long, name: String, description: String?, coverUri: String?) =
        playlistRepositoryOperator.updatePlaylist(id, name, description, coverUri)

    override suspend fun deletePlaylist(id: Long) =
        playlistRepositoryOperator.deletePlaylist(id)

    override suspend fun addSongToPlaylist(playlistId: Long, songId: Long) =
        playlistRepositoryOperator.addSongToPlaylist(playlistId, songId)

    override suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) =
        playlistRepositoryOperator.addSongsToPlaylist(playlistId, songIds)

    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) =
        playlistRepositoryOperator.removeSongFromPlaylist(playlistId, songId)

    override suspend fun reorderPlaylistSongs(playlistId: Long, songIds: List<Long>) =
        playlistRepositoryOperator.reorderPlaylistSongs(playlistId, songIds)

    override suspend fun getPlaylistIdsForSong(songId: Long): List<Long> =
        playlistRepositoryOperator.getPlaylistIdsForSong(songId)

    override suspend fun getCoPlaylistSongIds(songId: Long): Set<Long> =
        playlistRepositoryOperator.getCoPlaylistSongIds(songId)

    override fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrack>> =
        playlistRepositoryOperator.getPlaylistPendingTracksFlow(playlistId)

    override suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>) =
        playlistRepositoryOperator.addPlaylistPendingTracks(tracks)

    override suspend fun removePlaylistPendingTrack(playlistId: Long, artist: String, title: String) =
        playlistRepositoryOperator.removePlaylistPendingTrack(playlistId, artist, title)

    // Songs library queries & CRUD
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
        val cached = identityCache.getSongs()
        if (cached.isNotEmpty()) {
            return@withContext cached.filter { it.id in idSet }
        }
        ids.chunked(IDENTITY_SONG_ID_CHUNK).flatMap { chunk ->
            musicDao.getIdentitySongsByIds(chunk)
        }
    }

    override suspend fun findSongByArtistTitle(artist: String, title: String): Song? =
        withContext(Dispatchers.IO) {
            identityCache.findSongByArtistTitle(artist, title)
        }

    override suspend fun saveUploadedSong(song: Song): Long = withContext(Dispatchers.IO) {
        val ref = audioStore.canonicalize(song.uriString, song.folderPath)
        val normalized = song.copy(uriString = ref.uriString, folderPath = ref.folderPath)
        val trackNum = if (normalized.trackNumber > 0) {
            normalized.trackNumber
        } else {
            songIdentifyOperator.resolveTrackNumberFallback(normalized.artist, normalized.title)
        }
        val songWithTrack = if (trackNum != normalized.trackNumber) {
            normalized.copy(trackNumber = trackNum)
        } else {
            normalized
        }
        val key = TrackMatchKeys.matchKey(songWithTrack.artist, songWithTrack.title)
        if (key.isNotEmpty()) {
            val existing = identityCache.findSongByArtistTitle(songWithTrack.artist, songWithTrack.title)
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
                    songIdentifyOperator.resolveTrackNumberFallback(existing.artist, existing.title)
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
                syncSongsRelations(db, musicDao, listOf(updated))
                identityCache.remember(updated)
                return@withContext existing.id
            }
        }
        insertOrUpdateSongByUri(db, musicDao, identityCache, songWithTrack)
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
        identityCache.invalidate()
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

    override suspend fun updateSongDuration(songId: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        musicDao.updateSongDuration(songId, durationMs)
    }

    override suspend fun touchSongLastPlayed(songId: Long, playedAt: Long) = withContext(Dispatchers.IO) {
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

        musicDao.updateSongMetadata(
            songId, safeTitle, safeArtist, safeAlbum, safeGenre, safeYear, safeTrack
        )
        val updated = musicDao.getSongById(songId)
        if (updated != null) {
            syncSongsRelations(db, musicDao, listOf(updated))
            identityCache.remember(updated)
            fileTagSyncOperator.maybeWriteTags(updated)
        }
    }

    override suspend fun updateSongLyrics(songId: Long, lyrics: String?) = withContext(Dispatchers.IO) {
        val cleanLyrics = lyrics?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        musicDao.updateSongLyrics(songId, cleanLyrics)
    }

    override suspend fun findLocalLyrics(song: Song): String? =
        songEnhancementOperator.findLocalLyrics(song)

    override suspend fun saveCompanionLrc(song: Song, lyrics: String): Boolean =
        songEnhancementOperator.saveCompanionLrc(song, lyrics)

    override suspend fun fetchSongLyrics(song: Song): String? =
        songEnhancementOperator.fetchSongLyrics(song)

    // Metadata enhancement
    override suspend fun enhanceSongMetadataAndLyrics(song: Song) =
        songEnhancementOperator.enhanceSongMetadataAndLyrics(song)

    suspend fun enhanceSongMetadataAndLyricsBatch(songs: List<Song>) =
        songEnhancementOperator.enhanceSongMetadataAndLyricsBatch(songs)

    fun calculateAudioDurationMs(audioPathOrUri: String): Long =
        songEnhancementOperator.calculateAudioDurationMs(audioPathOrUri)

    // Album management
    override suspend fun getAlbumOverride(albumKey: String): AlbumOverride? =
        albumMetadataOperator.getAlbumOverride(albumKey)

    override suspend fun upsertAlbumOverride(override: AlbumOverride) =
        albumMetadataOperator.upsertAlbumOverride(override)

    override suspend fun searchAlbums(query: String): List<CatalogAlbum> =
        albumMetadataOperator.searchAlbums(query)

    override suspend fun setAlbumArtwork(albumKey: String, artworkUri: String?) =
        albumMetadataOperator.setAlbumArtwork(albumKey, artworkUri)

    override suspend fun updateAlbumMetadataPropagateToSongs(override: AlbumOverride) =
        albumMetadataOperator.updateAlbumMetadataPropagateToSongs(override)

    override suspend fun mergeAlbumInto(sourceAlbumKey: String, targetAlbumKey: String) =
        albumMetadataOperator.mergeAlbumInto(sourceAlbumKey, targetAlbumKey)

    override fun saveAlbumCoverImage(sourceUriStr: String?): String? =
        albumMetadataOperator.saveAlbumCoverImage(sourceUriStr)

    override fun savePlaylistCoverImage(sourceUriStr: String?): String? =
        albumMetadataOperator.savePlaylistCoverImage(sourceUriStr)

    override suspend fun saveAlbumTracksToLibrary(
        albumTitle: String,
        artistName: String,
        coverUrl: String?,
        year: Int,
        genre: String,
        tracks: List<CatalogTrackCandidate>
    ): List<Song> = albumMetadataOperator.saveAlbumTracksToLibrary(
        albumTitle, artistName, coverUrl, year, genre, tracks
    )

    override suspend fun removeSavedAlbumFromLibrary(albumName: String, artistName: String): Int =
        albumMetadataOperator.removeSavedAlbumFromLibrary(albumName, artistName)

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

    // Migrations
    suspend fun migrateCanonicalAudioUris() =
        legacyDatabaseMigrationOperator.migrateCanonicalAudioUris()

    suspend fun migrateLegacyYouTubeMusicSongs() =
        legacyDatabaseMigrationOperator.migrateLegacyYouTubeMusicSongs()

    suspend fun migrateDateAddedFromDevice() =
        legacyDatabaseMigrationOperator.migrateDateAddedFromDevice()

    suspend fun migrateEmbeddedFileTags(): List<Song> =
        legacyDatabaseMigrationOperator.migrateEmbeddedFileTags()

    private fun hasUsableIdentity(artist: String, title: String): Boolean =
        !IdentifyRanking.isPlaceholderArtist(artist) ||
                (!isTrackNumberLabel(title) && !isPlaceholderTitle(title))
}
