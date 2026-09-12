package com.bestiapop.android.data.repository

import android.content.Context
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.MusicDao
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.libraryAlbumKeysInBucket
import com.bestiapop.android.domain.util.normalizeAlbumName
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AlbumMetadataOperator(
    private val context: Context,
    private val database: AppDatabase,
    private val musicDao: MusicDao,
    private val metadataSource: RepositoryMetadataSource,
    private val identityCache: RepositoryIdentityCache,
    private val fileTagSyncOperator: FileTagSyncOperator
) {
    suspend fun getAlbumOverride(albumKey: String): AlbumOverride? =
        withContext(Dispatchers.IO) {
            musicDao.getAlbumOverride(albumKey)
        }

    suspend fun upsertAlbumOverride(override: AlbumOverride) =
        withContext(Dispatchers.IO) {
            persistAlbumOverride(override)
            Unit
        }

    suspend fun searchAlbums(query: String): List<CatalogAlbum> =
        withContext(Dispatchers.IO) {
            metadataSource.searchAlbums(query)
        }

    suspend fun setAlbumArtwork(albumKey: String, artworkUri: String?) =
        withContext(Dispatchers.IO) {
            val existing = musicDao.getAlbumOverride(albumKey)
            val override = existing?.copy(artworkUri = artworkUri)
                ?: AlbumOverride(albumKey = albumKey, displayName = albumKey, artworkUri = artworkUri)
            val savedArt = persistAlbumOverride(override)
            setArtworkOnAlbumBucket(albumKey, savedArt)
            fileTagSyncOperator.maybeWriteTagsForAlbum(albumKey)
        }

    suspend fun persistAlbumOverride(override: AlbumOverride): String? {
        val savedArt = saveAlbumCoverImage(override.artworkUri) ?: override.artworkUri
        musicDao.upsertAlbumOverride(persistOverride(override, savedArt))
        return savedArt
    }

    suspend fun setArtworkOnAlbumBucket(
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

    suspend fun updateAlbumMetadataPropagateToSongs(
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

        database.withTransaction {
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

    suspend fun mergeAlbumInto(
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

        database.withTransaction {
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

    fun persistOverride(
        override: AlbumOverride,
        savedArt: String?
    ): AlbumOverride = override.copy(
        displayName = override.displayName.ifBlank { override.albumKey },
        artist = override.artist?.takeIf { it.isNotBlank() },
        genre = override.genre?.takeIf { it.isNotBlank() },
        year = override.year.coerceAtLeast(0),
        artworkUri = savedArt
    )

    fun saveAlbumCoverImage(sourceUriStr: String?): String? =
        persistUserCover(sourceUriStr, "album_covers") { uri ->
            val inAppStorage = uri.contains("album_covers") ||
                    uri.contains("playlist_covers") ||
                    uri.contains("artwork")
            inAppStorage && (uri.startsWith("file://") || uri.startsWith("/"))
        }

    fun savePlaylistCoverImage(sourceUriStr: String?): String? =
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

    suspend fun saveAlbumTracksToLibrary(
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
            syncSongsRelations(database, musicDao, inserted)
            return@withContext inserted
        }

        musicDao.getSavedRemoteAlbumSongs(albumClean, artistClean)
    }

    suspend fun removeSavedAlbumFromLibrary(albumName: String, artistName: String): Int =
        withContext(Dispatchers.IO) {
            musicDao.deleteSavedRemoteAlbum(albumName.trim(), artistName.trim())
        }
}
