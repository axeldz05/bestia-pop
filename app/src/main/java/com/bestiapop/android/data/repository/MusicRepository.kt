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
                createdAt = entity.createdAt
            )
        }
    }

    fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> {
        return musicDao.getPlaylistWithSongsFlow(playlistId).map { withSongs ->
            withSongs?.songs?.map { it.toSong() } ?: emptyList()
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
        if (!song.artworkUri.isNullOrEmpty() && !song.lyrics.isNullOrEmpty()) {
            return@withContext
        }

        var artUrl = song.artworkUri
        if (artUrl.isNullOrEmpty()) {
            artUrl = MetadataFetcher.fetchAlbumArtUrl(song.artist, song.title)
        }

        var lyricsStr = song.lyrics
        if (lyricsStr.isNullOrEmpty()) {
            lyricsStr = MetadataFetcher.fetchLyrics(song.artist, song.title)
        }

        if (artUrl != song.artworkUri || lyricsStr != song.lyrics) {
            musicDao.updateMetadataAndLyrics(song.id, artUrl, lyricsStr)
        }
    }

    // Playlists
    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        musicDao.insertPlaylist(PlaylistEntity(name = name))
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        musicDao.deletePlaylist(id)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        musicDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId = playlistId, songId = songId))
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        musicDao.removeSongFromPlaylist(playlistId, songId)
    }
}
