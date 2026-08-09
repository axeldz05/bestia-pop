package com.bestiapop.android.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.PlaylistEntity
import com.bestiapop.android.data.db.PlaylistPendingTrackEntity
import com.bestiapop.android.data.db.PlaylistSongCrossRef
import com.bestiapop.android.data.db.toSong
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.AlbumOverride
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
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.mergePreferring
import com.bestiapop.android.data.model.withIdentity
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.stream.StreamResolver
import com.bestiapop.android.data.util.AudioFileMetadata
import com.bestiapop.android.data.util.FilenameMetadataHints
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.data.util.parseFilenameMetadataHints
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.repository.LibraryScanProgress
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

internal fun PlaylistPendingTrackEntity.toPendingTrack() = PlaylistPendingTrack(
    identity = TrackIdentity(
        title = title,
        artist = artist,
        album = releaseName.orEmpty()
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
    recordingMbid = recordingMbid,
    position = position
)

class MusicRepository(private val context: Context) : IMusicRepository {

    private val db = AppDatabase.getDatabase(context)
    private val musicDao = db.musicDao()
    val streamResolver = StreamResolver()
    private val audioStore = MusicFileStore(context)

    private val sharedDownloadClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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

            val total = countAudioDocuments(rootFolder)
            val ticker = ScanProgressTicker(total, onProgress)
            val scanned = mutableListOf<Song>()
            scanDocumentFolderRecursively(
                folder = rootFolder,
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

    private data class LibraryDedupSets(
        val existingKeys: MutableSet<String>,
        val existingPaths: MutableSet<String>
    )

    private fun libraryDedupSets(existing: List<Song>): LibraryDedupSets = LibraryDedupSets(
        existingKeys = existing.mapNotNull { song ->
            TrackMatchKeys.matchKey(song.artist, song.title).takeIf { it.isNotEmpty() }
        }.toHashSet(),
        existingPaths = existing.mapNotNull { song ->
            SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
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
                extractEmbeddedArtwork = ::extractAndSaveEmbeddedArtwork
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

    private fun scanDocumentFolderRecursively(
        folder: DocumentFile,
        list: MutableList<Song>,
        existingKeys: MutableSet<String>,
        existingPaths: MutableSet<String>,
        onFileVisited: ((String) -> Unit)? = null
    ) {
        for (file in folder.listFiles()) {
            if (file.isDirectory) {
                scanDocumentFolderRecursively(
                    file, list, existingKeys, existingPaths, onFileVisited
                )
            } else if (file.isFile && isAudioFile(file.name ?: "")) {
                val fileName = file.name ?: "audio"
                onFileVisited?.invoke(fileName)
                tryIndexOneFile(
                    sourcePath = file.uri.toString(),
                    folderHint = folder.name ?: "",
                    fallbackTitle = file.name?.substringBeforeLast(".") ?: "Unknown Track",
                    fileName = file.name ?: "",
                    existingKeys = existingKeys,
                    existingPaths = existingPaths,
                    list = list,
                    useCanonicalPathForMetadata = false,
                    scanPhase = "folder_import_file",
                    crashPathKey = "uri"
                )
            }
        }
    }

    override fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String? {
        val retriever = MediaMetadataRetriever()
        try {
            audioStore.applyDataSource(retriever, audioStore.canonicalize(audioPathOrUri))
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
        musicDao.insertSong(normalized)
    }

    override suspend fun deleteSongsFromApp(songs: List<Song>) = withContext(Dispatchers.IO) {
        val ids = songs.map { it.id }
        if (ids.isNotEmpty()) {
            musicDao.deleteSongsByIds(ids)
        }
    }

    override suspend fun deleteSongsFromDevice(songs: List<Song>) = withContext(Dispatchers.IO) {
        songs.forEach { song ->
            try {
                audioStore.delete(audioStore.canonicalize(song.uriString, song.folderPath))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val ids = songs.map { it.id }
        if (ids.isNotEmpty()) {
            musicDao.deleteSongsByIds(ids)
        }
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
            val ref = audioStore.canonicalize(song.uriString, song.folderPath)
            var calculatedDur = calculateAudioDurationMs(ref.uriString)
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
            ?: IdentifyRanking.fallbackAlbum(newArtist, entity.album)
        val rawTitle = candidate.title.takeIf { it.isNotBlank() } ?: entity.title
        val newTitle = IdentifyRanking.cleanIdentityTitle(rawTitle).ifBlank { rawTitle }
        val newArt = candidate.artworkUri?.takeIf { it.isNotBlank() } ?: entity.artworkUri
        val newDuration = if (candidate.durationMs > 0) candidate.durationMs else entity.durationMs

        musicDao.updateSong(
            entity.copy(
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                artworkUri = newArt,
                durationMs = newDuration,
                trackNumber = if (candidate.trackNumber > 0) candidate.trackNumber else entity.trackNumber
            )
        )
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
        val merged = LinkedHashMap<String, OnlineCatalogTrack>()
        fun addAll(tracks: List<OnlineCatalogTrack>) {
            for (track in tracks) {
                val key = IdentifyRanking.dedupeKey(track.artist, track.title, track.album)
                if (key !in merged) merged[key] = track
            }
        }

        MetadataFetcher.fetchFullTrackMetadata(artist, title)?.let { meta ->
            addAll(listOf(meta.toIdentifyCatalogTrack()))
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

    private fun TrackIdentity.toIdentifyCatalogTrack(): OnlineCatalogTrack = OnlineCatalogTrack(
        identity = this,
        id = "identify:${TrackMatchKeys.matchKey(artist, title)}",
        audioUrl = "",
        provider = "Catalog"
    )

    private fun needsMetadataIdentify(artist: String, album: String): Boolean {
        return IdentifyRanking.isPlaceholderArtist(artist) || IdentifyRanking.isGenericAlbum(album)
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
    }

    override suspend fun getAlbumOverride(albumKey: String): AlbumOverride? =
        withContext(Dispatchers.IO) {
            musicDao.getAlbumOverride(albumKey)
        }

    override suspend fun upsertAlbumOverride(override: AlbumOverride) =
        withContext(Dispatchers.IO) {
            val savedArt = saveAlbumCoverImage(override.artworkUri) ?: override.artworkUri
            musicDao.upsertAlbumOverride(persistOverride(override, savedArt))
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

        rewriteAlbumKey(sourceAlbumKey)

        // Fold other equivalent titles (e.g. Takk… + Takkâ€¦ after renaming Takk. → Takk...)
        val remainingKeys = musicDao.getAllSongs().map { it.album }.distinct()
        com.bestiapop.android.domain.util.findEquivalentAlbumKeys(
            albumKeys = remainingKeys,
            targetName = canonicalTarget,
            excludeKey = canonicalTarget
        ).forEach { rewriteAlbumKey(it) }
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
        onProgress: ((String) -> Unit)?,
        conflictPolicy: DownloadConflictPolicy?
    ): Song = withContext(Dispatchers.IO) {
        onProgress?.invoke("Buscando audio de alta calidad en YouTube...")

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

        val savedRef = audioStore.canonicalize(pendingWrite.publish())

        onProgress?.invoke("Obteniendo información del álbum y portada...")

        var finalAlbum = track.album
        val hasUsefulAlbum = !IdentifyRanking.isGenericAlbum(finalAlbum)
        val hasArtwork = !finalArtwork.isNullOrEmpty()

        if (!hasUsefulAlbum || !hasArtwork) {
            val fullMeta = MetadataFetcher.fetchFullTrackMetadata(finalArtist, finalTitle)
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

        val lyrics = MetadataFetcher.fetchLyrics(finalArtist, finalTitle)

        onProgress?.invoke("Guardando en la biblioteca...")

        if (overwriteTarget != null) {
            val updated = overwriteTarget.copy(
                uriString = savedRef.uriString,
                title = finalTitle,
                artist = finalArtist.ifBlank { overwriteTarget.artist },
                album = finalAlbum,
                durationMs = if (finalDurationMs > 0) finalDurationMs else overwriteTarget.durationMs,
                artworkUri = finalArtwork ?: overwriteTarget.artworkUri,
                lyrics = lyrics ?: overwriteTarget.lyrics,
                folderPath = savedRef.folderPath,
                trackNumber = if (finalTrackNumber > 0) finalTrackNumber else overwriteTarget.trackNumber
            )
            musicDao.updateSong(updated)
            onProgress?.invoke("¡Canción sobrescrita con éxito!")
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

        val insertedId = musicDao.insertSong(song)
        val savedSong = song.copy(id = insertedId)

        onProgress?.invoke("¡Canción agregada con éxito!")
        return@withContext savedSong
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
}
