package com.bestiapop.android.data.repository

import com.bestiapop.android.data.db.MusicDao
import com.bestiapop.android.data.db.PlaylistEntity
import com.bestiapop.android.data.db.PlaylistSongCrossRef
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.firstArtworkUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PlaylistRepositoryOperator(
    private val musicDao: MusicDao,
    private val metadataSource: RepositoryMetadataSource,
    private val savePlaylistCoverImage: (String?) -> String?
) {
    fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> =
        musicDao.getPlaylistSongsOrderedFlow(playlistId)

    fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> =
        combine(
            musicDao.getPlaylistByIdFlow(playlistId),
            musicDao.getPlaylistSongsOrderedFlow(playlistId),
            musicDao.getPlaylistPendingTracksFlow(playlistId)
        ) { entity, songs, pendingEntities ->
            if (entity == null) null
            else {
                val pending = pendingEntities.map { it.toPendingTrack() }
                val effectiveCover = entity.coverUri?.takeIf(String::isNotBlank)
                    ?: songs.firstArtworkUri()
                    ?: pending.firstArtworkUri()
                val playlist = Playlist(
                    id = entity.playlistId,
                    name = entity.name,
                    description = entity.description,
                    coverUri = effectiveCover,
                    songCount = songs.size + pending.size,
                    createdAt = entity.createdAt
                )
                Pair(playlist, songs)
            }
        }

    suspend fun getPlaylistSongsOrdered(playlistId: Long): List<Song> =
        withContext(Dispatchers.IO) {
            musicDao.getPlaylistSongsOrdered(playlistId)
        }

    suspend fun createPlaylist(name: String, description: String?, coverUri: String?): Long =
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

    suspend fun updatePlaylist(id: Long, name: String, description: String?, coverUri: String?) =
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

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        musicDao.clearPlaylistSongs(id)
        musicDao.clearPlaylistPendingTracks(id)
        musicDao.deletePlaylist(id)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        addSongsToPlaylist(playlistId, listOf(songId))
    }

    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext
        val startPos = (musicDao.getMaxPositionInPlaylist(playlistId) ?: -1) + 1
        val refs = songIds.mapIndexed { index, songId ->
            PlaylistSongCrossRef(playlistId = playlistId, songId = songId, position = startPos + index)
        }
        musicDao.addSongsToPlaylist(refs)
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        musicDao.removeSongFromPlaylist(playlistId, songId)
    }

    suspend fun reorderPlaylistSongs(playlistId: Long, songIds: List<Long>) = withContext(Dispatchers.IO) {
        musicDao.reorderPlaylistSongs(playlistId, songIds)
    }

    suspend fun getPlaylistIdsForSong(songId: Long): List<Long> = withContext(Dispatchers.IO) {
        musicDao.getPlaylistIdsForSong(songId)
    }

    suspend fun getCoPlaylistSongIds(songId: Long): Set<Long> = withContext(Dispatchers.IO) {
        musicDao.getCoPlaylistSongIds(songId).toSet()
    }

    fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrack>> =
        musicDao.getPlaylistPendingTracksFlow(playlistId).map { list ->
            list.map { it.toPendingTrack() }
        }

    suspend fun getPlaylistPendingTracks(playlistId: Long): List<PlaylistPendingTrack> =
        withContext(Dispatchers.IO) {
            musicDao.getPlaylistPendingTracks(playlistId).map { it.toPendingTrack() }
        }

    suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>) =
        withContext(Dispatchers.IO) {
            if (tracks.isEmpty()) return@withContext
            musicDao.insertPlaylistPendingTracks(tracks.map { it.toEntity() })
            enrichPlaylistPendingArtworks(tracks.first().playlistId)
        }

    suspend fun removePlaylistPendingTrack(playlistId: Long, artist: String, title: String) =
        withContext(Dispatchers.IO) {
            musicDao.deletePlaylistPendingTrackByArtistTitle(playlistId, artist, title)
        }

    suspend fun updatePlaylistPendingTrackArtwork(id: Long, artworkUri: String) =
        withContext(Dispatchers.IO) {
            musicDao.updatePlaylistPendingTrackArtwork(id, artworkUri)
        }

    suspend fun enrichPlaylistPendingArtworks(playlistId: Long) =
        withContext(Dispatchers.IO) {
            val allPending = musicDao.getPlaylistPendingTracks(playlistId)
            val firstExistingArt = allPending.firstNotNullOfOrNull {
                it.artworkUri?.takeIf { uri -> uri.isNotBlank() && !uri.equals("null", ignoreCase = true) }
            }
            if (firstExistingArt != null) {
                musicDao.updatePlaylistCoverIfEmpty(playlistId, firstExistingArt)
            }
            val pendingWithoutArtwork = allPending.filter {
                it.artworkUri.isNullOrBlank() || it.artworkUri.equals("null", ignoreCase = true)
            }
            if (pendingWithoutArtwork.isEmpty()) return@withContext

            coroutineScope {
                pendingWithoutArtwork.forEach { track ->
                    launch {
                        val artUrl = metadataSource.fetchTrackArtwork(track.toPendingTrack())
                        if (!artUrl.isNullOrBlank()) {
                            musicDao.updatePlaylistPendingTrackArtwork(track.id, artUrl)
                            musicDao.updatePlaylistCoverIfEmpty(playlistId, artUrl)
                        }
                    }
                }
            }
        }
}
