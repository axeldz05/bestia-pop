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
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.util.AudioFileMetadata
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

import com.bestiapop.android.domain.repository.IMusicRepository

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

    override suspend fun scanMediaStore() = withContext(Dispatchers.IO) {
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

        cursor?.use {
            while (it.moveToNext()) {
                val entity = it.toSongEntity()
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

    override suspend fun findSongByArtistTitle(artist: String, title: String): Song? =
        withContext(Dispatchers.IO) {
            val key = MatchListenBrainzTracksUseCase.matchKey(artist, title)
            if (key.isEmpty()) return@withContext null
            musicDao.getAllSongs().firstOrNull {
                MatchListenBrainzTracksUseCase.matchKey(it.artist, it.title) == key
            }?.toSong()
        }

    override suspend fun scanFolderUri(treeUri: Uri) = withContext(Dispatchers.IO) {
        val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
        val existing = musicDao.getAllSongs()
        val existingKeys = existing.mapNotNull { song ->
            MatchListenBrainzTracksUseCase.matchKey(song.artist, song.title).takeIf { it.isNotEmpty() }
        }.toHashSet()

        val scanned = mutableListOf<SongEntity>()
        scanDocumentFolderRecursively(rootFolder, scanned, existingKeys)
        if (scanned.isNotEmpty()) {
            musicDao.insertSongs(scanned)
        }
    }

    private fun scanDocumentFolderRecursively(
        folder: DocumentFile,
        list: MutableList<SongEntity>,
        existingKeys: MutableSet<String>
    ) {
        val files = folder.listFiles()

        for (file in files) {
            if (file.isDirectory) {
                scanDocumentFolderRecursively(file, list, existingKeys)
            } else if (file.isFile && isAudioFile(file.name ?: "")) {
                val uri = file.uri
                try {
                    val path = uri.toString()
                    val metadata = AudioFileMetadata.fromPath(
                        context = context,
                        path = path,
                        fallbackTitle = file.name?.substringBeforeLast(".") ?: "Unknown Track",
                        extractEmbeddedArtwork = ::extractAndSaveEmbeddedArtwork
                    )

                    if (!isRealMusicTrack(metadata.durationMs, path, file.name ?: "")) continue
                    if (SongPathNormalizer.isUnderBestiaPop(path)) continue
                    val key = MatchListenBrainzTracksUseCase.matchKey(metadata.artist, metadata.title)
                    if (key.isNotEmpty() && existingKeys.contains(key)) continue

                    list.add(
                        metadata.toSongEntity(
                            uriString = path,
                            folderPath = folder.name ?: ""
                        )
                    )
                    if (key.isNotEmpty()) existingKeys.add(key)
                } catch (e: Exception) {
                    e.printStackTrace()
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

    private fun isRealMusicTrack(durationMs: Long, filePath: String, fileName: String = ""): Boolean {
        // 1. Minimum duration: 30 seconds (filters out voice notes, ringtones, UI sound effects)
        if (durationMs < 30_000) return false

        // 2. Exclude WhatsApp, Telegram, Notifications, Ringtones, Voice Notes folders
        val pathLower = filePath.lowercase()
        val excludedFolders = listOf(
            "whatsapp", "telegram", "notifications", "ringtones",
            "alarms", "voice recorder", "callrecord", "recorder",
            "voice_notes", "cache"
        )
        if (excludedFolders.any { pathLower.contains(it) }) return false

        // 3. Exclude WhatsApp voice note filename patterns (e.g. AUD-..., PTT-...)
        val fileLower = fileName.lowercase()
        if (fileLower.startsWith("aud-") || fileLower.startsWith("ptt-") || fileLower.startsWith("rec_")) {
            return false
        }

        return true
    }

    private fun isAudioFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".m4a") ||
                lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".aac")
    }

    override suspend fun getAllSongsSync(): List<Song> = withContext(Dispatchers.IO) {
        musicDao.getAllSongs().map { it.toSong() }
    }

    override suspend fun saveUploadedSong(song: SongEntity): Long = withContext(Dispatchers.IO) {
        val normalizedUri = SongPathNormalizer.toAbsolutePath(song.uriString) ?: song.uriString
        val normalized = song.copy(uriString = normalizedUri)
        val key = MatchListenBrainzTracksUseCase.matchKey(normalized.artist, normalized.title)
        if (key.isNotEmpty()) {
            val existing = musicDao.getAllSongs().firstOrNull {
                MatchListenBrainzTracksUseCase.matchKey(it.artist, it.title) == key
            }
            if (existing != null) {
                val oldPath = SongPathNormalizer.resolveFilePath(existing.uriString, existing.folderPath)
                val newPath = SongPathNormalizer.resolveFilePath(normalized.uriString, normalized.folderPath)
                if (oldPath != null && newPath != null &&
                    !SongPathNormalizer.pathsReferToSameFile(oldPath, newPath) &&
                    SongPathNormalizer.isSafeToDeleteAppManagedFile(oldPath)
                ) {
                    File(oldPath).takeIf { it.exists() }?.delete()
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
        val uploadDir = com.bestiapop.android.data.util.StorageUtils.getPublicMusicDirectory(context)
        val oldUploadDir = File(context.getExternalFilesDir(null), "UploadedMusic")
        val oldDownloadDir = File(context.getExternalFilesDir(null), "DownloadedMusic")
        songs.forEach { song ->
            try {
                if (song.uriString.startsWith("content://")) {
                    context.contentResolver.delete(Uri.parse(song.uriString), null, null)
                }

                val cleanPath = cleanFilePath(song.uriString)

                val file = File(cleanPath)
                if (file.exists()) {
                    file.delete()
                }

                val fileName = cleanPath.substringAfterLast("/").substringAfterLast("\\")
                if (fileName.isNotEmpty()) {
                    val fileInUploadDir = File(uploadDir, fileName)
                    if (fileInUploadDir.exists()) fileInUploadDir.delete()
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
        !artworkUri.isNullOrEmpty() && !artworkUri.startsWith("content://")

    override suspend fun enhanceSongMetadataAndLyrics(song: Song) = withContext(Dispatchers.IO) {
        val hasUsableArt = hasUsableArtwork(song.artworkUri)
        val hasLyrics = !song.lyrics.isNullOrEmpty()
        val hasDuration = song.durationMs > 0
        if (hasUsableArt && hasLyrics && hasDuration) return@withContext

        val albumName = if (song.album.isBlank()) "Unknown Album" else song.album
        val existingAlbumArt = musicDao.getArtworkForAlbum(albumName)

        var artUrl = if (hasUsableArt) song.artworkUri else existingAlbumArt

        if (artUrl.isNullOrEmpty() || artUrl.startsWith("content://")) {
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
        val newName = override.displayName.ifBlank { oldKey }
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

    override fun saveAlbumCoverImage(sourceUriStr: String?): String? {
        if (sourceUriStr.isNullOrBlank()) return null
        if (sourceUriStr.startsWith("file://") &&
            (sourceUriStr.contains("album_covers") || sourceUriStr.contains("playlist_covers") ||
                sourceUriStr.contains("artwork"))
        ) {
            return sourceUriStr
        }
        val dest = copyUserImageTo("album_covers", sourceUriStr)
        if (dest != null) return dest.absolutePath
        return if (sourceUriStr.startsWith("http")) sourceUriStr else null
    }

    // Playlists
    override fun savePlaylistCoverImage(sourceUriStr: String?): String? {
        if (sourceUriStr.isNullOrBlank()) return null
        if (sourceUriStr.startsWith("file://") && sourceUriStr.contains("playlist_covers")) {
            return sourceUriStr
        }
        val dest = copyUserImageTo("playlist_covers", sourceUriStr)
        return dest?.toURI()?.toString() ?: sourceUriStr
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
        val extractRes = com.bestiapop.android.data.network.YouTubeExtractor.extractAudioStreamDetailed(queryOrId)

        if (extractRes is com.bestiapop.android.data.network.YouTubeExtractResult.Success) {
            val ytStream = extractRes.result
            downloadUrl = ytStream.audioUrl
            userAgentToUse = ytStream.userAgent
            if (isPlaceholderTitle(finalTitle)) {
                finalTitle = ytStream.title
            }
            if (isPlaceholderArtist(finalArtist)) {
                finalArtist = ytStream.artist
            }
            if (finalArtwork.isNullOrBlank()) finalArtwork = ytStream.artworkUrl
            if (finalDurationMs <= 0) finalDurationMs = ytStream.durationMs
        } else if (extractRes is com.bestiapop.android.data.network.YouTubeExtractResult.Error) {
            throw java.io.IOException(extractRes.message)
        }

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

        val musicDir = com.bestiapop.android.data.util.StorageUtils.getPublicMusicDirectory(context)

        val ext = when {
            downloadUrl.contains("audio/mp4") || downloadUrl.contains("mime=audio%2Fmp4") || downloadUrl.endsWith(".m4a") -> "m4a"
            downloadUrl.contains("audio/webm") || downloadUrl.contains("mime=audio%2Fwebm") || downloadUrl.endsWith(".webm") -> "webm"
            downloadUrl.endsWith(".aac") -> "aac"
            downloadUrl.endsWith(".ogg") -> "ogg"
            downloadUrl.endsWith(".wav") -> "wav"
            else -> "m4a"
        }

        val file = when {
            overwriteTarget != null -> {
                val existingPath = SongPathNormalizer.resolveFilePath(
                    overwriteTarget.uriString,
                    overwriteTarget.folderPath
                )
                if (existingPath != null && SongPathNormalizer.isUnderBestiaPop(existingPath)) {
                    File(existingPath)
                } else {
                    val sanitizedName = (finalArtist + "_" + finalTitle).replace(Regex("[^a-zA-Z0-9_.-]"), "_")
                    File(musicDir, "$sanitizedName.$ext")
                }
            }
            else -> {
                val sanitizedName = (finalArtist + "_" + finalTitle).replace(Regex("[^a-zA-Z0-9_.-]"), "_")
                File(musicDir, "$sanitizedName.$ext")
            }
        }

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

        val savedUri = file.absolutePath

        onProgress?.invoke("Obteniendo información del álbum y portada...")

        var finalAlbum = track.album
        val hasUsefulAlbum = !isGenericAlbum(finalAlbum)
        val hasArtwork = !finalArtwork.isNullOrEmpty()

        if (!hasUsefulAlbum || !hasArtwork) {
            val fullMeta = MetadataFetcher.fetchFullTrackMetadata(finalArtist, finalTitle)
            if (fullMeta != null) {
                if (!fullMeta.album.isNullOrBlank()) {
                    finalAlbum = fullMeta.album
                }
                if (finalArtwork.isNullOrEmpty() && !fullMeta.artworkUrl.isNullOrEmpty()) {
                    finalArtwork = fullMeta.artworkUrl
                }
                if (!fullMeta.artistName.isNullOrBlank() && isPlaceholderArtist(finalArtist)) {
                    finalArtist = fullMeta.artistName
                }
                if (finalDurationMs <= 0 && fullMeta.durationMs > 0) {
                    finalDurationMs = fullMeta.durationMs
                }
            }
        }

        if (finalAlbum.isBlank() || finalAlbum.equals("YouTube Music", ignoreCase = true)) {
            finalAlbum = overwriteTarget?.album?.takeIf { it.isNotBlank() } ?: "$finalArtist - Single"
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
        val key = MatchListenBrainzTracksUseCase.matchKey(artist, title)
        if (key.isEmpty()) return null
        return musicDao.getAllSongs().firstOrNull {
            MatchListenBrainzTracksUseCase.matchKey(it.artist, it.title) == key
        }
    }

    suspend fun migrateLegacyYouTubeMusicSongs() = withContext(Dispatchers.IO) {
        try {
            val legacySongs = musicDao.getLegacyYouTubeMusicSongs()
            if (legacySongs.isEmpty()) return@withContext

            for (song in legacySongs) {
                val fullMeta = MetadataFetcher.fetchFullTrackMetadata(song.artist, song.title)
                val realAlbum = fullMeta?.album?.ifBlank { null } ?: "${song.artist} - Single"
                val realArtwork = fullMeta?.artworkUrl?.ifBlank { null } ?: song.artworkUri
                val realArtist = fullMeta?.artistName?.ifBlank { null } ?: song.artist

                val updated = song.copy(
                    artist = realArtist,
                    album = realAlbum,
                    artworkUri = realArtwork
                )
                musicDao.updateSong(updated)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isPlaceholderTitle(title: String): Boolean =
        title.isBlank() || title == "YouTube Track" || title == "Canción desde Link"

    private fun isPlaceholderArtist(artist: String): Boolean =
        artist.isBlank() || artist == "YouTube Artist" || artist == "Enlace Web"

    private fun isGenericAlbum(album: String): Boolean =
        album.isBlank() ||
            album.equals("YouTube Music", ignoreCase = true) ||
            album.equals("Single", ignoreCase = true)
}
