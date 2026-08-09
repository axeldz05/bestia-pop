package com.bestiapop.android.data.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.PlaylistEntity
import com.bestiapop.android.data.db.PlaylistPendingTrackEntity
import com.bestiapop.android.data.db.PlaylistSongCrossRef
import com.bestiapop.android.data.db.SongEntity
import com.bestiapop.android.data.db.toEntity
import com.bestiapop.android.data.db.toSongEntity
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.DownloadConflictPolicy
import com.bestiapop.android.data.model.DuplicateSongException
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.FullTrackMetadata
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.stream.StreamResolver
import com.bestiapop.android.data.util.AudioFileMetadata
import com.bestiapop.android.data.util.FilenameMetadataHints
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.data.util.parseFilenameMetadataHints
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.repository.LibraryScanProgress
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.TrackMatchKeys
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private class ScanProgressTicker(
    private val total: Int,
    private val onProgress: LibraryScanProgress?
) {
    private val done = AtomicInteger(0)
    fun tick(fileName: String) {
        onProgress?.invoke(done.incrementAndGet(), total, fileName)
    }
}

private fun PlaylistPendingTrackEntity.toPendingTrack() = PlaylistPendingTrack(
    id = id,
    playlistId = playlistId,
    title = title,
    artist = artist,
    releaseName = releaseName,
    recordingMbid = recordingMbid,
    position = position
)

private fun PlaylistPendingTrack.toEntity() = PlaylistPendingTrackEntity(
    id = id,
    playlistId = playlistId,
    title = title,
    artist = artist,
    releaseName = releaseName,
    recordingMbid = recordingMbid,
    position = position
)

class MusicRepository(private val context: Context) : IMusicRepository {

    private val db = AppDatabase.getDatabase(context)
    private val musicDao = db.musicDao()
    private val streamResolver = StreamResolver()

    private val sharedDownloadClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override val allSongsFlow: Flow<List<Song>> = musicDao.getAllSongsFlow().map { entities ->
        entities.map { it.toSong() }
    }

    override val albumOverridesFlow: Flow<List<com.bestiapop.android.data.model.AlbumOverride>> =
        musicDao.getAllAlbumOverridesFlow().map { entities ->
            entities.map { it.toModel() }
        }

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
            withSongs?.songs?.map { it.toSong() } ?: emptyList()
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
                val songs = withSongs.songs.map { it.toSong() }
                Pair(playlist, songs)
            }
        }
    }

    override suspend fun scanMediaStore(onProgress: LibraryScanProgress?) = withContext(Dispatchers.IO) {
        val existing = musicDao.getAllSongs()
        val existingKeys = existing.mapNotNull { song ->
            MatchListenBrainzTracksUseCase.matchKey(song.artist, song.title).takeIf { it.isNotEmpty() }
        }.toHashSet()
        val existingPaths = existing.mapNotNull { song ->
            SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
        }.map { it.lowercase() }.toHashSet()

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

        val scannedEntities = mutableListOf<SongEntity>()
        val ticker = ScanProgressTicker(cursor?.count ?: 0, onProgress)

        cursor?.use {
            while (it.moveToNext()) {
                val entity = it.toSongEntity()
                val tickName = entity.title.ifBlank { entity.folderPath.substringAfterLast('/') }
                ticker.tick(tickName)
                if (!isRealMusicTrack(entity.durationMs, entity.folderPath)) continue
                if (SongPathNormalizer.isUnderBestiaPop(entity.folderPath) ||
                    SongPathNormalizer.isUnderBestiaPop(entity.uriString)
                ) {
                    continue
                }
                val dataPath = entity.folderPath.trim()
                if (dataPath.isNotEmpty() && existingPaths.contains(dataPath.lowercase())) {
                    continue
                }
                val key = MatchListenBrainzTracksUseCase.matchKey(entity.artist, entity.title)
                if (key.isNotEmpty() && existingKeys.contains(key)) {
                    continue
                }
                scannedEntities.add(entity)
                if (key.isNotEmpty()) existingKeys.add(key)
                if (dataPath.isNotEmpty()) existingPaths.add(dataPath.lowercase())
            }
        }

        if (scannedEntities.isNotEmpty()) {
            musicDao.insertSongs(scannedEntities)
        }
    }

    override suspend fun resyncAppManagedMusic(onProgress: LibraryScanProgress?): Int =
        withContext(Dispatchers.IO) {
            val files = com.bestiapop.android.data.util.StorageUtils.listManagedAudioFiles(context)
            if (files.isEmpty()) return@withContext 0

            val existing = musicDao.getAllSongs()
            val existingKeys = existing.mapNotNull { song ->
                MatchListenBrainzTracksUseCase.matchKey(song.artist, song.title).takeIf { it.isNotEmpty() }
            }.toHashSet()
            val existingPaths = existing.mapNotNull { song ->
                SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
            }.map { it.lowercase() }.toHashSet()

            val ticker = ScanProgressTicker(files.size, onProgress)
            val scanned = mutableListOf<SongEntity>()
            indexAudioFiles(
                files = files,
                list = scanned,
                existingKeys = existingKeys,
                existingPaths = existingPaths,
                onFileVisited = ticker::tick
            )
            if (scanned.isNotEmpty()) {
                musicDao.insertSongs(scanned)
            }
            scanned.size
        }

    override suspend fun findSongByArtistTitle(artist: String, title: String): Song? =
        withContext(Dispatchers.IO) {
            findSongEntityByArtistTitle(artist, title)?.toSong()
        }

    override suspend fun scanFolderUri(treeUri: Uri, onProgress: LibraryScanProgress?): Int =
        withContext(Dispatchers.IO) {
            val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
            val existing = musicDao.getAllSongs()
            val existingKeys = existing.mapNotNull { song ->
                MatchListenBrainzTracksUseCase.matchKey(song.artist, song.title).takeIf { it.isNotEmpty() }
            }.toHashSet()
            val existingPaths = existing.mapNotNull { song ->
                SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
            }.map { it.lowercase() }.toHashSet()

            val total = countAudioDocuments(rootFolder)
            val ticker = ScanProgressTicker(total, onProgress)
            val scanned = mutableListOf<SongEntity>()
            scanDocumentFolderRecursively(
                folder = rootFolder,
                list = scanned,
                existingKeys = existingKeys,
                existingPaths = existingPaths,
                onFileVisited = ticker::tick
            )
            if (scanned.isNotEmpty()) {
                musicDao.insertSongs(scanned)
            }
            scanned.size
        }

    private fun countAudioDocuments(folder: DocumentFile): Int {
        var count = 0
        for (file in folder.listFiles()) {
            if (file.isDirectory) {
                count += countAudioDocuments(file)
            } else if (file.isFile && isAudioFile(file.name ?: "")) {
                count++
            }
        }
        return count
    }

    /** Index audio files under public Music/BestiaPop (absolute paths, same as downloads). */
    private fun indexAudioFiles(
        files: List<File>,
        list: MutableList<SongEntity>,
        existingKeys: MutableSet<String>,
        existingPaths: MutableSet<String>,
        onFileVisited: ((String) -> Unit)? = null
    ) {
        for (file in files) {
            if (!file.isFile || !isAudioFile(file.name)) continue
            onFileVisited?.invoke(file.name)
            val path = file.absolutePath
            val pathKey = path.lowercase()
            if (existingPaths.contains(pathKey)) continue
            try {
                val metadata = AudioFileMetadata.fromPath(
                    context = context,
                    path = path,
                    fallbackTitle = file.nameWithoutExtension,
                    extractEmbeddedArtwork = ::extractAndSaveEmbeddedArtwork
                )
                if (!isRealMusicTrack(
                        durationMs = metadata.durationMs,
                        filePath = path,
                        fileName = file.name,
                        allowUnknownDuration = true
                    )
                ) {
                    continue
                }
                val key = MatchListenBrainzTracksUseCase.matchKey(metadata.artist, metadata.title)
                if (key.isNotEmpty() && existingKeys.contains(key)) continue

                list.add(
                    metadata.toSongEntity(
                        uriString = path,
                        folderPath = file.parent ?: ""
                    )
                )
                if (key.isNotEmpty()) existingKeys.add(key)
                existingPaths.add(pathKey)
            } catch (e: Exception) {
                e.printStackTrace()
                com.bestiapop.android.data.util.CrashReporter.recordNonFatal(
                    e,
                    mapOf("scan_phase" to "app_music_file", "path" to path)
                )
            }
        }
    }

    private fun scanDocumentFolderRecursively(
        folder: DocumentFile,
        list: MutableList<SongEntity>,
        existingKeys: MutableSet<String>,
        existingPaths: MutableSet<String>,
        onFileVisited: ((String) -> Unit)? = null
    ) {
        val files = folder.listFiles()

        for (file in files) {
            if (file.isDirectory) {
                scanDocumentFolderRecursively(
                    file, list, existingKeys, existingPaths, onFileVisited
                )
            } else if (file.isFile && isAudioFile(file.name ?: "")) {
                val fileName = file.name ?: "audio"
                onFileVisited?.invoke(fileName)
                val uri = file.uri
                try {
                    val path = uri.toString()
                    val pathKey = path.lowercase()
                    if (existingPaths.contains(pathKey)) continue

                    val metadata = AudioFileMetadata.fromPath(
                        context = context,
                        path = path,
                        fallbackTitle = file.name?.substringBeforeLast(".") ?: "Unknown Track",
                        extractEmbeddedArtwork = ::extractAndSaveEmbeddedArtwork
                    )

                    // Folder import is explicit: do not skip Music/BestiaPop; allow unknown duration.
                    if (!isRealMusicTrack(
                            durationMs = metadata.durationMs,
                            filePath = path,
                            fileName = file.name ?: "",
                            allowUnknownDuration = true
                        )
                    ) {
                        continue
                    }
                    val key = MatchListenBrainzTracksUseCase.matchKey(metadata.artist, metadata.title)
                    if (key.isNotEmpty() && existingKeys.contains(key)) continue

                    list.add(
                        metadata.toSongEntity(
                            uriString = path,
                            folderPath = folder.name ?: ""
                        )
                    )
                    if (key.isNotEmpty()) existingKeys.add(key)
                    existingPaths.add(pathKey)
                } catch (e: Exception) {
                    e.printStackTrace()
                    com.bestiapop.android.data.util.CrashReporter.recordNonFatal(
                        e,
                        mapOf("scan_phase" to "folder_import_file", "uri" to uri.toString())
                    )
                }
            }
        }
    }

    override fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String? {
        val retriever = MediaMetadataRetriever()
        try {
            if (audioPathOrUri.startsWith("content://")) {
                retriever.setDataSource(context, android.net.Uri.parse(audioPathOrUri))
            } else {
                retriever.setDataSource(audioPathOrUri)
            }
            val pictureBytes = retriever.embeddedPicture
            if (pictureBytes != null && pictureBytes.isNotEmpty()) {
                val artDir = File(context.cacheDir, "album_art")
                if (!artDir.exists()) artDir.mkdirs()
                val artFile = File(artDir, "art_${identifier.hashCode()}.jpg")
                artFile.outputStream().use { out ->
                    out.write(pictureBytes)
                }
                return artFile.toURI().toString()
            }
        } catch (e: Exception) {
            // ignore
        } finally {
            try { retriever.release() } catch (ignored: Exception) {}
        }
        return null
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
        musicDao.getAllSongs().map { it.toSong() }
    }

    override suspend fun saveUploadedSong(song: SongEntity): Long = withContext(Dispatchers.IO) {
        val normalizedUri = SongPathNormalizer.toAbsolutePath(song.uriString) ?: song.uriString
        val normalized = song.copy(uriString = normalizedUri)
        val key = MatchListenBrainzTracksUseCase.matchKey(normalized.artist, normalized.title)
        if (key.isNotEmpty()) {
            val existing = findSongEntityByArtistTitle(normalized.artist, normalized.title)
            if (existing != null) {
                val oldPath = SongPathNormalizer.resolveFilePath(existing.uriString, existing.folderPath)
                val newPath = SongPathNormalizer.resolveFilePath(normalized.uriString, normalized.folderPath)
                if (oldPath != null && newPath != null &&
                    !SongPathNormalizer.pathsReferToSameFile(oldPath, newPath) &&
                    SongPathNormalizer.isSafeToDeleteAppManagedFile(oldPath)
                ) {
                    StorageUtils.deleteManagedAudio(context, oldPath)
                }
                val updated = existing.copy(
                    uriString = normalized.uriString,
                    title = normalized.title,
                    artist = normalized.artist,
                    album = normalized.album,
                    genre = normalized.genre,
                    durationMs = normalized.durationMs,
                    artworkUri = normalized.artworkUri ?: existing.artworkUri,
                    folderPath = normalized.folderPath.ifBlank { existing.folderPath }
                )
                musicDao.updateSong(updated)
                return@withContext existing.id
            }
        }
        musicDao.insertSong(normalized)
    }

    override suspend fun deleteSongsFromApp(songs: List<Song>) = withContext(Dispatchers.IO) {
        val ids = songs.map { it.id }
        if (ids.isNotEmpty()) {
            musicDao.deleteSongsByIds(ids)
        }
    }

    override suspend fun deleteSongsFromDevice(songs: List<Song>) = withContext(Dispatchers.IO) {
        val oldUploadDir = File(context.getExternalFilesDir(null), "UploadedMusic")
        val oldDownloadDir = File(context.getExternalFilesDir(null), "DownloadedMusic")
        songs.forEach { song ->
            try {
                if (song.uriString.startsWith("content://")) {
                    context.contentResolver.delete(Uri.parse(song.uriString), null, null)
                }

                val cleanPath = cleanFilePath(song.uriString)
                if (cleanPath.isNotBlank()) {
                    StorageUtils.deleteManagedAudio(context, cleanPath)
                }

                val fileName = SongPathNormalizer.fileName(song.uriString, song.folderPath)
                if (fileName.isNotEmpty()) {
                    if (oldUploadDir.exists()) File(oldUploadDir, fileName).takeIf { it.exists() }?.delete()
                    if (oldDownloadDir.exists()) File(oldDownloadDir, fileName).takeIf { it.exists() }?.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val ids = songs.map { it.id }
        if (ids.isNotEmpty()) {
            musicDao.deleteSongsByIds(ids)
        }
    }

    private fun cleanFilePath(uriString: String): String =
        SongPathNormalizer.toAbsolutePath(uriString) ?: uriString

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
            val cleanPath = cleanFilePath(song.uriString)
            val embedded = extractAndSaveEmbeddedArtwork(cleanPath, "${song.artist}_${albumName}")
            if (!embedded.isNullOrEmpty()) {
                artUrl = embedded
            } else {
                val queryTerm = if (albumName != "Unknown Album") albumName else song.title
                artUrl = MetadataFetcher.fetchAlbumArtUrl(song.artist, queryTerm)
            }
        }

        var lyricsStr = song.lyrics
        if (lyricsStr.isNullOrEmpty()) {
            lyricsStr = MetadataFetcher.fetchLyrics(song.artist, song.title)
        }

        if (artUrl != song.artworkUri || lyricsStr != song.lyrics) {
            musicDao.updateMetadataAndLyrics(song.id, artUrl, lyricsStr)
        }

        if (!artUrl.isNullOrEmpty() && (existingAlbumArt.isNullOrEmpty() || existingAlbumArt != artUrl)) {
            musicDao.setAlbumArtwork(albumName, artUrl)
        }

        if (song.durationMs <= 0) {
            val cleanPath = cleanFilePath(song.uriString)
            var calculatedDur = calculateAudioDurationMs(cleanPath)
            if (calculatedDur <= 0) {
                calculatedDur = MetadataFetcher.fetchTrackDurationMs(song.artist, song.title)
            }
            if (calculatedDur > 0) {
                musicDao.updateSongDuration(song.id, calculatedDur)
            }
        }
    }

    override suspend fun proposeSongIdentity(
        song: Song,
        customQuery: String?,
        force: Boolean
    ): IdentifyProposal = withContext(Dispatchers.IO) {
        if (!force && !needsMetadataIdentify(song.artist, song.album)) {
            return@withContext IdentifyProposal(
                songId = song.id,
                queryArtist = song.artist,
                queryTitle = song.title,
                alreadyIdentified = true,
                confidence = IdentifyConfidence.NONE
            )
        }

        var queryArtist = song.artist
        var queryTitle = song.title
        val path = SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
        val baseName = path
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?: song.uriString.substringAfterLast('/').substringBeforeLast('.')
        val hints = if (looksLikeStoragePath(baseName)) {
            FilenameMetadataHints(artist = null, title = null)
        } else {
            parseFilenameMetadataHints(baseName)
        }
        val filenameArtist = hints.artist?.takeUnless { looksLikeStoragePath(it) }
        val filenameTitle = hints.title?.takeUnless { looksLikeStoragePath(it) }
        if (IdentifyRanking.isPlaceholderArtist(queryArtist)) {
            if (!hints.artist.isNullOrBlank()) queryArtist = hints.artist
            if (!hints.title.isNullOrBlank()) queryTitle = hints.title
        }

        val tagHints = listOfNotNull(
            song.artist.takeUnless { IdentifyRanking.isPlaceholderArtist(it) },
            song.title.takeUnless { it.isBlank() || looksLikeStoragePath(it) },
            song.album.takeUnless { IdentifyRanking.isGenericAlbum(it) }
        ).joinToString(" · ").ifBlank { null }
        val filenameHint = listOfNotNull(
            filenameArtist?.takeIf { it.isNotBlank() },
            filenameTitle?.takeIf { it.isNotBlank() }
        ).takeIf { it.size == 2 }?.joinToString(" · ")
            ?: filenameTitle?.takeIf { it.isNotBlank() }
        val sourceHints = tagHints ?: filenameHint

        val trimmedCustom = customQuery?.trim().orEmpty()
        val artistPlaceholder = IdentifyRanking.isPlaceholderArtist(queryArtist)
        val tracks = if (trimmedCustom.isNotEmpty()) {
            MetadataFetcher.searchOnlineCatalog(trimmedCustom)
        } else {
            fetchIdentifyCatalogTracks(queryArtist, queryTitle, artistPlaceholder)
        }
        val rankingQuery = IdentifyRanking.Query(
            artist = queryArtist,
            title = if (trimmedCustom.isNotEmpty()) trimmedCustom else queryTitle,
            durationMs = song.durationMs,
            filenameArtist = filenameArtist,
            filenameTitle = filenameTitle,
            artistIsPlaceholder = artistPlaceholder && trimmedCustom.isEmpty(),
            sourceArtist = song.artist.takeUnless { IdentifyRanking.isPlaceholderArtist(it) },
            sourceTitle = song.title.takeUnless { it.isBlank() || looksLikeStoragePath(it) },
            sourceAlbum = song.album.takeUnless { IdentifyRanking.isGenericAlbum(it) }
        )
        val ranked = IdentifyRanking.rank(rankingQuery, tracks)
        val confidence = IdentifyRanking.confidence(ranked)
        IdentifyProposal(
            songId = song.id,
            queryArtist = queryArtist,
            queryTitle = if (trimmedCustom.isNotEmpty()) trimmedCustom else queryTitle,
            sourceHints = sourceHints,
            candidates = ranked,
            confidence = confidence,
            suggested = ranked.firstOrNull()
        )
    }

    override suspend fun applySongIdentity(
        songId: Long,
        candidate: IdentifyCandidate
    ): IdentifyResult = withContext(Dispatchers.IO) {
        val entity = musicDao.getSongById(songId) ?: return@withContext IdentifyResult.NoMatch
        val newArtist = candidate.artist.takeIf { it.isNotBlank() } ?: entity.artist
        val newAlbum = candidate.album.takeIf { it.isNotBlank() && !IdentifyRanking.isGenericAlbum(it) }
            ?: entity.album.takeUnless { IdentifyRanking.isGenericAlbum(it) }
            ?: "$newArtist - Single"
        val rawTitle = candidate.title.takeIf { it.isNotBlank() } ?: entity.title
        val newTitle = IdentifyRanking.cleanIdentityTitle(rawTitle).ifBlank { rawTitle }
        val newArt = candidate.artworkUrl?.takeIf { it.isNotBlank() } ?: entity.artworkUri
        val newDuration = if (candidate.durationMs > 0) candidate.durationMs else entity.durationMs

        musicDao.updateSong(
            entity.copy(
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                artworkUri = newArt,
                durationMs = newDuration
            )
        )
        IdentifyResult.Updated(
            songId = songId,
            title = newTitle,
            artist = newArtist,
            album = newAlbum
        )
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
        val merged = LinkedHashMap<String, OnlineCatalogTrack>()
        fun addAll(tracks: List<OnlineCatalogTrack>) {
            for (track in tracks) {
                val key = IdentifyRanking.dedupeKey(track.artist, track.title, track.album)
                if (key !in merged) merged[key] = track
            }
        }

        MetadataFetcher.fetchFullTrackMetadata(artist, title)?.let { meta ->
            addAll(listOf(meta.toOnlineCatalogTrack()))
        }

        val primaryQuery = if (artistPlaceholder) title else "$artist $title"
        addAll(MetadataFetcher.searchOnlineCatalog(primaryQuery.trim()))

        if (!artistPlaceholder) {
            val titleOnly = title.trim()
            if (titleOnly.isNotEmpty() && !titleOnly.equals(primaryQuery.trim(), ignoreCase = true)) {
                addAll(MetadataFetcher.searchOnlineCatalog(titleOnly))
            }
        }

        return merged.values.toList()
    }

    private fun FullTrackMetadata.toOnlineCatalogTrack(): OnlineCatalogTrack {
        val safeTitle = title?.takeIf { it.isNotBlank() } ?: ""
        val safeArtist = artistName?.takeIf { it.isNotBlank() } ?: ""
        return OnlineCatalogTrack(
            id = "identify:${TrackMatchKeys.matchKey(safeArtist, safeTitle)}",
            title = safeTitle,
            artist = safeArtist,
            album = album.orEmpty(),
            artworkUrl = artworkUrl,
            durationMs = durationMs,
            audioUrl = "",
            provider = "Catalog"
        )
    }

    private fun needsMetadataIdentify(artist: String, album: String): Boolean {
        return IdentifyRanking.isPlaceholderArtist(artist) || IdentifyRanking.isGenericAlbum(album)
    }

    fun calculateAudioDurationMs(audioPathOrUri: String): Long {
        val uri = Uri.parse(audioPathOrUri)

        try {
            val retriever = MediaMetadataRetriever()
            if (audioPathOrUri.startsWith("content://")) {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(audioPathOrUri)
            }
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            val dur = durStr?.toLongOrNull() ?: 0L
            if (dur > 0) return dur
        } catch (ignored: Exception) {}

        try {
            val extractor = android.media.MediaExtractor()
            if (audioPathOrUri.startsWith("content://")) {
                extractor.setDataSource(context, uri, null)
            } else {
                extractor.setDataSource(audioPathOrUri)
            }
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
            if (audioPathOrUri.startsWith("content://")) {
                mp.setDataSource(context, uri)
            } else {
                mp.setDataSource(audioPathOrUri)
            }
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

    override suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int
    ) = withContext(Dispatchers.IO) {
        val safeTitle = title.ifBlank { "Unknown Track" }
        val safeArtist = artist.ifBlank { "Unknown Artist" }
        val safeAlbum = album.ifBlank { "Unknown Album" }
        val safeGenre = genre.ifBlank { "Music" }
        val safeYear = year.coerceAtLeast(0)

        // Per-song edit only — does not rewrite sibling songs or album overrides.
        musicDao.updateSongMetadata(songId, safeTitle, safeArtist, safeAlbum, safeGenre, safeYear)
    }

    override suspend fun getAlbumOverride(albumKey: String): com.bestiapop.android.data.model.AlbumOverride? =
        withContext(Dispatchers.IO) {
            musicDao.getAlbumOverride(albumKey)?.toModel()
        }

    override suspend fun upsertAlbumOverride(override: com.bestiapop.android.data.model.AlbumOverride) =
        withContext(Dispatchers.IO) {
            val savedArt = saveAlbumCoverImage(override.artworkUri) ?: override.artworkUri
            musicDao.upsertAlbumOverride(persistOverrideEntity(override, savedArt))
        }

    override suspend fun updateAlbumMetadataPropagateToSongs(
        override: com.bestiapop.android.data.model.AlbumOverride
    ) = withContext(Dispatchers.IO) {
        val oldKey = override.albumKey
        val newName = com.bestiapop.android.domain.util.normalizeAlbumName(override.displayName)
            .ifBlank { oldKey }
        val safeArtist = override.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        val safeGenre = override.genre?.takeIf { it.isNotBlank() } ?: "Music"
        val safeYear = override.year.coerceAtLeast(0)
        val savedArt = saveAlbumCoverImage(override.artworkUri) ?: override.artworkUri

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
            persistOverrideEntity(
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

    override suspend fun mergeAlbumInto(
        sourceAlbumKey: String,
        targetAlbumKey: String
    ) = withContext(Dispatchers.IO) {
        if (sourceAlbumKey == targetAlbumKey) return@withContext

        val targetSongs = musicDao.getSongsForAlbum(targetAlbumKey)
        val canonicalTarget = targetSongs.firstOrNull()?.album ?: targetAlbumKey
        val override = musicDao.getAlbumOverride(canonicalTarget)?.toModel()
            ?: musicDao.getAlbumOverride(targetAlbumKey)?.toModel()

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

        rewriteAlbumKey(sourceAlbumKey)

        // Fold other equivalent titles (e.g. Takk… + Takkâ€¦ after renaming Takk. → Takk...)
        val remainingKeys = musicDao.getAllSongs().map { it.album }.distinct()
        com.bestiapop.android.domain.util.findEquivalentAlbumKeys(
            albumKeys = remainingKeys,
            targetName = canonicalTarget,
            excludeKey = canonicalTarget
        ).forEach { rewriteAlbumKey(it) }
    }

    private fun persistOverrideEntity(
        override: com.bestiapop.android.data.model.AlbumOverride,
        savedArt: String?
    ): com.bestiapop.android.data.db.AlbumOverrideEntity =
        com.bestiapop.android.data.db.AlbumOverrideEntity(
            albumKey = override.albumKey,
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
            val uri = Uri.parse(sourceUriStr)
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
        onProgress: ((String) -> Unit)?,
        conflictPolicy: DownloadConflictPolicy?
    ): Song = withContext(Dispatchers.IO) {
        onProgress?.invoke("Buscando audio de alta calidad en YouTube...")

        var downloadUrl = track.audioUrl
        var userAgentToUse = track.userAgent

        var finalTitle = track.title
        var finalArtist = track.artist
        var finalArtwork = track.artworkUrl
        var finalDurationMs = track.durationMs

        val queryOrId = com.bestiapop.android.data.network.YouTubeExtractor.resolveYouTubeQueryOrId(track)
        val ytStream = streamResolver.resolveQuery(queryOrId).getOrElse { e ->
            throw java.io.IOException(e.message ?: "No se pudo resolver el stream de YouTube")
        }
        downloadUrl = ytStream.audioUrl
        userAgentToUse = ytStream.userAgent
        if (isPlaceholderTitle(finalTitle) && ytStream.title.isNotBlank()) {
            finalTitle = ytStream.title
        }
        if (IdentifyRanking.isPlaceholderArtist(finalArtist) && ytStream.artist.isNotBlank()) {
            finalArtist = ytStream.artist
        }
        if (finalArtwork.isNullOrBlank()) finalArtwork = ytStream.artworkUrl
        if (finalDurationMs <= 0) finalDurationMs = ytStream.durationMs

        var overwriteTarget: SongEntity? = null
        when (conflictPolicy) {
            is DownloadConflictPolicy.Overwrite -> {
                overwriteTarget = musicDao.getSongById(conflictPolicy.existingSongId)
                    ?: throw IllegalArgumentException("Canción a sobrescribir no encontrada")
                finalTitle = overwriteTarget.title
                if (finalArtist.isBlank()) finalArtist = overwriteTarget.artist
            }
            is DownloadConflictPolicy.SaveAs -> {
                finalTitle = conflictPolicy.newTitle.trim().ifBlank { finalTitle }
            }
            null -> {
                val existing = findSongEntityByArtistTitle(finalArtist, finalTitle)
                if (existing != null) {
                    throw DuplicateSongException(existing.toSong(), track.copy(title = finalTitle, artist = finalArtist))
                }
            }
        }

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
            val existingPath = SongPathNormalizer.resolveFilePath(target.uriString, target.folderPath)
            if (existingPath != null && SongPathNormalizer.isSafeToDeleteAppManagedFile(existingPath)) {
                StorageUtils.deleteManagedAudio(context, existingPath)
            }
        }
        val pendingWrite = StorageUtils.prepareWrite(
            context,
            displayName,
            StorageUtils.mimeFromFileName(displayName)
        )
        val file = pendingWrite.stagingFile
        if (file.exists()) {
            file.delete()
        }

        onProgress?.invoke("Descargando audio (${finalTitle})...")

        var downloadedBytes = 0L
        var maxResumes = 5
        var attempts = 0
        var downloadSuccess = false
        var lastHttpError: String? = null

        while (attempts < maxResumes && !downloadSuccess) {
            attempts++
            try {
                val reqBuilder = okhttp3.Request.Builder()
                    .url(downloadUrl)
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", userAgentToUse)

                if (downloadedBytes > 0) {
                    reqBuilder.header("Range", "bytes=$downloadedBytes-")
                }

                sharedDownloadClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful || response.code == 206) {
                        val body = response.body
                        if (body != null) {
                            val inputStream = body.byteStream()
                            val fos = java.io.FileOutputStream(file, downloadedBytes > 0)
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                fos.write(buffer, 0, read)
                                downloadedBytes += read
                            }
                            fos.flush()
                            fos.close()
                            downloadSuccess = true
                        }
                    } else {
                        lastHttpError = "HTTP ${response.code} (${response.message.ifBlank { "Error de servidor" }})"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                lastHttpError = e.localizedMessage ?: "Error de red"
                if (file.exists() && file.length() > 0) {
                    downloadedBytes = file.length()
                }
            }
        }

        if (!file.exists() || file.length() == 0L) {
            val errorDetails = if (!lastHttpError.isNullOrBlank()) " ($lastHttpError)" else ""
            throw java.io.IOException(
                "No se pudo descargar el archivo de audio de YouTube$errorDetails. Verifica tu conexión a internet o intenta con otra canción."
            )
        }

        val savedUri = pendingWrite.publish()

        onProgress?.invoke("Obteniendo información del álbum y portada...")

        var finalAlbum = track.album
        val hasUsefulAlbum = !IdentifyRanking.isGenericAlbum(finalAlbum)
        val hasArtwork = !finalArtwork.isNullOrEmpty()

        if (!hasUsefulAlbum || !hasArtwork) {
            val fullMeta = MetadataFetcher.fetchFullTrackMetadata(finalArtist, finalTitle)
            if (fullMeta != null) {
                val lookedUpAlbum = fullMeta.album
                if (!lookedUpAlbum.isNullOrBlank() && !IdentifyRanking.isGenericAlbum(lookedUpAlbum)) {
                    finalAlbum = lookedUpAlbum
                }
                if (finalArtwork.isNullOrEmpty() && !fullMeta.artworkUrl.isNullOrEmpty()) {
                    finalArtwork = fullMeta.artworkUrl
                }
                if (!fullMeta.artistName.isNullOrBlank() && IdentifyRanking.isPlaceholderArtist(finalArtist)) {
                    finalArtist = fullMeta.artistName
                }
                if (finalDurationMs <= 0 && fullMeta.durationMs > 0) {
                    finalDurationMs = fullMeta.durationMs
                }
            }
        }

        if (IdentifyRanking.isGenericAlbum(finalAlbum)) {
            finalAlbum = overwriteTarget?.album
                ?.takeIf { it.isNotBlank() && !IdentifyRanking.isGenericAlbum(it) }
                ?: "$finalArtist - Single"
        }

        val lyrics = MetadataFetcher.fetchLyrics(finalArtist, finalTitle)

        onProgress?.invoke("Guardando en la biblioteca...")

        if (overwriteTarget != null) {
            val updated = overwriteTarget.copy(
                uriString = savedUri,
                title = finalTitle,
                artist = finalArtist.ifBlank { overwriteTarget.artist },
                album = finalAlbum,
                durationMs = if (finalDurationMs > 0) finalDurationMs else overwriteTarget.durationMs,
                artworkUri = finalArtwork ?: overwriteTarget.artworkUri,
                lyrics = lyrics ?: overwriteTarget.lyrics,
                folderPath = "Music/BestiaPop"
            )
            musicDao.updateSong(updated)
            onProgress?.invoke("¡Canción sobrescrita con éxito!")
            return@withContext updated.toSong()
        }

        val songEntity = SongEntity(
            uriString = savedUri,
            title = finalTitle,
            artist = finalArtist,
            album = finalAlbum,
            genre = "Music",
            durationMs = if (finalDurationMs > 0) finalDurationMs else 180000L,
            year = 0,
            trackNumber = 0,
            artworkUri = finalArtwork,
            lyrics = lyrics,
            folderPath = "Music/BestiaPop",
            dateAdded = System.currentTimeMillis()
        )

        val insertedId = musicDao.insertSong(songEntity)
        val savedSong = songEntity.copy(id = insertedId).toSong()

        onProgress?.invoke("¡Canción agregada con éxito!")
        return@withContext savedSong
    }

    private suspend fun findSongEntityByArtistTitle(artist: String, title: String): SongEntity? {
        val key = TrackMatchKeys.matchKey(artist, title)
        if (key.isEmpty()) return null
        val entities = musicDao.getAllSongs()
        return TrackMatchKeys.buildIndex(entities, { it.artist }, { it.title })[key]
    }

    suspend fun migrateLegacyYouTubeMusicSongs() = withContext(Dispatchers.IO) {
        try {
            val legacySongs = musicDao.getLegacyYouTubeMusicSongs()
            if (legacySongs.isEmpty()) return@withContext

            for (song in legacySongs) {
                identifySongMetadata(song.toSong())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isPlaceholderTitle(title: String): Boolean =
        title.isBlank() || title == "YouTube Track" || title == "Canción desde Link"
}
