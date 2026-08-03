package com.bestiapop.android.data.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.PlaylistEntity
import com.bestiapop.android.data.db.PlaylistSongCrossRef
import com.bestiapop.android.data.db.SongEntity
import com.bestiapop.android.data.db.toEntity
import com.bestiapop.android.data.db.toSongEntity
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.MetadataFetcher
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class MusicRepository(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val musicDao = db.musicDao()

    val allSongsFlow: Flow<List<Song>> = musicDao.getAllSongsFlow().map { entities ->
        entities.map { it.toSong() }
    }



    val playlistsFlow: Flow<List<Playlist>> = musicDao.getAllPlaylistsFlow().map { entities ->
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

    fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> {
        return musicDao.getPlaylistWithSongsFlow(playlistId).map { withSongs ->
            withSongs?.songs?.map { it.toSong() } ?: emptyList()
        }
    }

    fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> {
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

    suspend fun scanMediaStore() = withContext(Dispatchers.IO) {
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
                if (isRealMusicTrack(entity.durationMs, entity.folderPath)) {
                    scannedEntities.add(entity)
                }
            }
        }

        if (scannedEntities.isNotEmpty()) {
            musicDao.insertSongs(scannedEntities)
        }
    }

    suspend fun scanFolderUri(treeUri: Uri) = withContext(Dispatchers.IO) {
        val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
        val scanned = mutableListOf<SongEntity>()
        scanDocumentFolderRecursively(rootFolder, scanned)
        if (scanned.isNotEmpty()) {
            musicDao.insertSongs(scanned)
        }
    }

    private fun scanDocumentFolderRecursively(folder: DocumentFile, list: MutableList<SongEntity>) {
        val files = folder.listFiles()
        val retriever = MediaMetadataRetriever()

        for (file in files) {
            if (file.isDirectory) {
                scanDocumentFolderRecursively(file, list)
            } else if (file.isFile && isAudioFile(file.name ?: "")) {
                val uri = file.uri
                try {
                    retriever.setDataSource(context, uri)
                    val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.name?.substringBeforeLast(".") ?: "Unknown Track"
                    val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                    val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown Album"
                    val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "Music"
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr?.toLongOrNull() ?: 0L

                    val embeddedArt = extractAndSaveEmbeddedArtwork(uri.toString(), uri.toString())

                    if (isRealMusicTrack(durationMs, uri.toString(), file.name ?: "")) {
                        list.add(
                            SongEntity(
                                uriString = uri.toString(),
                                title = title,
                                artist = artist,
                                album = album,
                                genre = genre,
                                durationMs = durationMs,
                                year = 0,
                                trackNumber = 0,
                                artworkUri = embeddedArt,
                                lyrics = null,
                                folderPath = folder.name ?: "",
                                dateAdded = System.currentTimeMillis()
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        retriever.release()
    }

    fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String? {
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

    suspend fun getAllSongsSync(): List<Song> = withContext(Dispatchers.IO) {
        musicDao.getAllSongsFlow().first().map { it.toSong() }
    }

    suspend fun saveUploadedSong(song: SongEntity) = withContext(Dispatchers.IO) {
        musicDao.insertSong(song)
    }

    suspend fun deleteSongsFromApp(songs: List<Song>) = withContext(Dispatchers.IO) {
        val ids = songs.map { it.id }
        if (ids.isNotEmpty()) {
            musicDao.deleteSongsByIds(ids)
        }
    }

    suspend fun deleteSongsFromDevice(songs: List<Song>) = withContext(Dispatchers.IO) {
        val uploadDir = File(context.getExternalFilesDir(null), "UploadedMusic")
        songs.forEach { song ->
            try {
                if (song.uriString.startsWith("content://")) {
                    context.contentResolver.delete(Uri.parse(song.uriString), null, null)
                }

                val cleanPath = when {
                    song.uriString.startsWith("file://") -> song.uriString.removePrefix("file://")
                    song.uriString.startsWith("file:") -> song.uriString.removePrefix("file:")
                    else -> song.uriString
                }

                val file = File(cleanPath)
                if (file.exists()) {
                    file.delete()
                }

                val fileName = cleanPath.substringAfterLast("/").substringAfterLast("\\")
                if (fileName.isNotEmpty() && uploadDir.exists()) {
                    val fileInUploadDir = File(uploadDir, fileName)
                    if (fileInUploadDir.exists()) {
                        fileInUploadDir.delete()
                    }
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

    suspend fun enhanceSongMetadataAndLyrics(song: Song) = withContext(Dispatchers.IO) {
        val albumName = if (song.album.isBlank()) "Unknown Album" else song.album
        val existingAlbumArt = musicDao.getArtworkForAlbum(albumName)

        var artUrl = if (!song.artworkUri.isNullOrEmpty() && !song.artworkUri.startsWith("content://")) song.artworkUri else existingAlbumArt

        if (artUrl.isNullOrEmpty() || artUrl.startsWith("content://")) {
            val cleanPath = when {
                song.uriString.startsWith("file://") -> song.uriString.removePrefix("file://")
                song.uriString.startsWith("file:") -> song.uriString.removePrefix("file:")
                else -> song.uriString
            }
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

        if (!artUrl.isNullOrEmpty()) {
            musicDao.setAlbumArtwork(albumName, artUrl)
        }

        if (song.durationMs <= 0) {
            val cleanPath = when {
                song.uriString.startsWith("file://") -> song.uriString.removePrefix("file://")
                song.uriString.startsWith("file:") -> song.uriString.removePrefix("file:")
                else -> song.uriString
            }
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

    suspend fun updateSongDuration(songId: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        musicDao.updateSongDuration(songId, durationMs)
    }

    suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String
    ) = withContext(Dispatchers.IO) {
        val safeTitle = title.ifBlank { "Unknown Track" }
        val safeArtist = artist.ifBlank { "Unknown Artist" }
        val safeAlbum = album.ifBlank { "Unknown Album" }
        val safeGenre = genre.ifBlank { "Music" }

        musicDao.updateSongMetadata(songId, safeTitle, safeArtist, safeAlbum, safeGenre)

        val existingArt = musicDao.getArtworkForAlbum(safeAlbum)
        if (!existingArt.isNullOrEmpty()) {
            musicDao.setAlbumArtwork(safeAlbum, existingArt)
        } else {
            val fetchedArt = MetadataFetcher.fetchAlbumArtUrl(safeArtist, safeAlbum)
            if (!fetchedArt.isNullOrEmpty()) {
                musicDao.setAlbumArtwork(safeAlbum, fetchedArt)
            }
        }
    }

    // Playlists
    fun savePlaylistCoverImage(sourceUriStr: String?): String? {
        if (sourceUriStr.isNullOrBlank()) return null
        if (sourceUriStr.startsWith("file://") && sourceUriStr.contains("playlist_covers")) {
            return sourceUriStr
        }
        try {
            val uri = Uri.parse(sourceUriStr)
            val coversDir = File(context.filesDir, "playlist_covers")
            if (!coversDir.exists()) coversDir.mkdirs()

            val destFile = File(coversDir, "cover_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return destFile.toURI().toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return sourceUriStr
        }
    }

    suspend fun createPlaylist(name: String, description: String? = null, coverUri: String? = null): Long = withContext(Dispatchers.IO) {
        val savedCover = savePlaylistCoverImage(coverUri)
        musicDao.insertPlaylist(
            PlaylistEntity(
                name = name,
                description = description?.ifBlank { null },
                coverUri = savedCover
            )
        )
    }

    suspend fun updatePlaylist(id: Long, name: String, description: String? = null, coverUri: String? = null) = withContext(Dispatchers.IO) {
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

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        musicDao.clearPlaylistSongs(id)
        musicDao.deletePlaylist(id)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        musicDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId = playlistId, songId = songId))
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        musicDao.removeSongFromPlaylist(playlistId, songId)
    }

    suspend fun downloadAndSaveOnlineTrack(
        track: OnlineCatalogTrack,
        onProgress: ((String) -> Unit)? = null
    ): Song = withContext(Dispatchers.IO) {
        onProgress?.invoke("Buscando audio de alta calidad en YouTube...")

        var downloadUrl = track.audioUrl
        var userAgentToUse = track.userAgent

        var finalTitle = track.title
        var finalArtist = track.artist
        var finalArtwork = track.artworkUrl
        var finalDurationMs = track.durationMs

        // Re-obtener siempre un stream fresco de YouTube justo antes de descargar para evitar URLs de CDN caducadas (HTTP 403)
        val queryOrId = track.id.ifBlank { track.audioUrl }
        val extractRes = com.bestiapop.android.data.network.YouTubeExtractor.extractAudioStreamDetailed(queryOrId)

        if (extractRes is com.bestiapop.android.data.network.YouTubeExtractResult.Success) {
            val ytStream = extractRes.result
            downloadUrl = ytStream.audioUrl
            userAgentToUse = ytStream.userAgent
            if (finalTitle.isBlank() || finalTitle == "YouTube Track" || finalTitle == "Canción desde Link") finalTitle = ytStream.title
            if (finalArtist.isBlank() || finalArtist == "YouTube Artist" || finalArtist == "Enlace Web") finalArtist = ytStream.artist
            if (finalArtwork.isNullOrBlank()) finalArtwork = ytStream.artworkUrl
            if (finalDurationMs <= 0) finalDurationMs = ytStream.durationMs
        } else if (extractRes is com.bestiapop.android.data.network.YouTubeExtractResult.Error) {
            throw java.io.IOException(extractRes.message)
        }


        val musicDir = File(context.getExternalFilesDir(null), "DownloadedMusic")
        if (!musicDir.exists()) musicDir.mkdirs()

        val ext = when {
            downloadUrl.contains("audio/mp4") || downloadUrl.contains("mime=audio%2Fmp4") || downloadUrl.endsWith(".m4a") -> "m4a"
            downloadUrl.contains("audio/webm") || downloadUrl.contains("mime=audio%2Fwebm") || downloadUrl.endsWith(".webm") -> "webm"
            downloadUrl.endsWith(".aac") -> "aac"
            downloadUrl.endsWith(".ogg") -> "ogg"
            downloadUrl.endsWith(".wav") -> "wav"
            else -> "m4a"
        }
        val sanitizedName = (finalArtist + "_" + finalTitle).replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val file = File(musicDir, "$sanitizedName.$ext")

        onProgress?.invoke("Descargando audio (${finalTitle})...")

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

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

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful || response.code == 206) {
                        val body = response.body
                        if (body != null) {
                            val inputStream = body.byteStream()
                            val fos = java.io.FileOutputStream(file, downloadedBytes > 0)
                            val buffer = ByteArray(8192)
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
            throw java.io.IOException("No se pudo descargar el archivo de audio de YouTube$errorDetails. Verifica tu conexión a internet o intenta con otra canción.")
        }

        val savedUri = file.absolutePath




        onProgress?.invoke("Obteniendo portada e información...")
        if (finalArtwork.isNullOrEmpty()) {
            finalArtwork = MetadataFetcher.fetchAlbumArtUrl(finalArtist, finalTitle)
        }

        val lyrics = MetadataFetcher.fetchLyrics(finalArtist, finalTitle)

        val songEntity = SongEntity(
            uriString = savedUri,
            title = finalTitle,
            artist = finalArtist,
            album = "YouTube Music",
            genre = "Music",
            durationMs = if (finalDurationMs > 0) finalDurationMs else 180000L,
            year = 0,
            trackNumber = 0,
            artworkUri = finalArtwork,
            lyrics = lyrics,
            folderPath = "DownloadedMusic",
            dateAdded = System.currentTimeMillis()
        )

        onProgress?.invoke("Guardando en la biblioteca...")
        val insertedId = musicDao.insertSong(songEntity)
        val savedSong = songEntity.copy(id = insertedId).toSong()

        onProgress?.invoke("¡Canción agregada con éxito!")
        return@withContext savedSong
    }
}



