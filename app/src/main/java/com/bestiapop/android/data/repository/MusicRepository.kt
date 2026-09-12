package com.bestiapop.android.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.PlaylistEntity
import com.bestiapop.android.data.db.PlaylistSongCrossRef
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
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.SongPathRef
import com.bestiapop.android.data.network.HttpClients
import com.bestiapop.android.data.preferences.LibraryTagWritePreferencesRepository
import com.bestiapop.android.data.stream.StreamResolver
import com.bestiapop.android.data.util.AudioFileMetadata
import com.bestiapop.android.data.util.AudioPersistRef
import com.bestiapop.android.data.util.AudioTagReader
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.PlaybackDiagnostics
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.TagSyncSummary
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.repository.LibraryScanProgress
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.KnownAlbumTrack
import com.bestiapop.android.domain.util.KnownAlbumTracks
import com.bestiapop.android.domain.util.MetadataSplitter
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.fillSongGapsFromFileTags
import com.bestiapop.android.domain.util.isTrackNumberLabel
import com.bestiapop.android.domain.util.libraryAlbumKeysInBucket
import com.bestiapop.android.domain.util.needsGapIdentify
import com.bestiapop.android.domain.util.normalizeAlbumName
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
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
        musicDao.getPlaylistSongsOrderedFlow(playlistId)

    override fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> =
        combine(
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

    override suspend fun getPlaylistSongsOrdered(playlistId: Long): List<Song> =
        withContext(Dispatchers.IO) {
            musicDao.getPlaylistSongsOrdered(playlistId)
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

    // Metadata enhancement
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
            val extractor = android.media.MediaExtractor()
            audioStore.applyDataSource(extractor, ref)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true && format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    val durationUs = format.getLong(android.media.MediaFormat.KEY_DURATION)
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
            val player = android.media.MediaPlayer()
            audioStore.applyDataSource(player, ref)
            val dur = player.duration.toLong()
            player.release()
            if (dur > 0) return dur
        } catch (_: Exception) {
        }

        return 0L
    }

    // Album management
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
            fileTagSyncOperator.maybeWriteTagsForAlbum(albumKey)
        }

    private suspend fun persistAlbumOverride(override: AlbumOverride): String? {
        val savedArt = saveAlbumCoverImage(override.artworkUri) ?: override.artworkUri
        musicDao.upsertAlbumOverride(persistOverride(override, savedArt))
        return savedArt
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

    override suspend fun updateAlbumMetadataPropagateToSongs(
        override: AlbumOverride
    ) = withContext(Dispatchers.IO) {
        val oldKey = override.albumKey
        val newName = normalizeAlbumName(override.displayName).ifBlank { oldKey }
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
        identityCache.invalidate()
        fileTagSyncOperator.maybeWriteTagsForAlbum(newName)
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

        db.withTransaction {
            rewriteAlbumKey(sourceAlbumKey)
            val remaining = musicDao.getIdentitySongs()
            libraryAlbumKeysInBucket(
                remaining,
                canonicalTarget,
                IdentifyRanking::isGenericAlbum
            ).forEach { rewriteAlbumKey(it) }
        }

        identityCache.invalidate()
        fileTagSyncOperator.maybeWriteTagsForAlbum(canonicalTarget)
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

    override fun saveAlbumCoverImage(sourceUriStr: String?): String? =
        persistUserCover(sourceUriStr, "album_covers") { uri ->
            val inAppStorage = uri.contains("album_covers") ||
                    uri.contains("playlist_covers") ||
                    uri.contains("artwork")
            inAppStorage && (uri.startsWith("file://") || uri.startsWith("/"))
        }

    override fun savePlaylistCoverImage(sourceUriStr: String?): String? =
        persistUserCover(sourceUriStr, "playlist_covers") { uri ->
            uri.startsWith("file://") && uri.contains("playlist_covers")
        }

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

    // Remote album library saving
    override suspend fun saveAlbumTracksToLibrary(
        albumTitle: String,
        artistName: String,
        coverUrl: String?,
        year: Int,
        genre: String,
        tracks: List<CatalogTrackCandidate>
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
            syncSongsRelations(db, musicDao, inserted)
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

    // Migrations
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
            db.withTransaction {
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
                db.withTransaction {
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

    private fun hasUsableIdentity(artist: String, title: String): Boolean =
        !IdentifyRanking.isPlaceholderArtist(artist) ||
                (!isTrackNumberLabel(title) && !isPlaceholderTitle(title))
}
