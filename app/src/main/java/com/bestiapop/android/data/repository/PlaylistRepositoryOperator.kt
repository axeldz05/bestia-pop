package com.bestiapop.android.data.repository

import com.bestiapop.android.data.db.MusicDao
import com.bestiapop.android.data.db.PlaylistEntity
import com.bestiapop.android.data.db.PlaylistSongCrossRef
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class PlaylistRepositoryOperator(
    private val musicDao: MusicDao,
    private val savePlaylistCoverImage: (String?) -> String?
) {
    fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> =
        musicDao.getPlaylistSongsOrderedFlow(playlistId)

    fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> =
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

    suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>) =
        withContext(Dispatchers.IO) {
            if (tracks.isEmpty()) return@withContext
            musicDao.insertPlaylistPendingTracks(tracks.map { it.toEntity() })
        }

    suspend fun removePlaylistPendingTrack(playlistId: Long, artist: String, title: String) =
        withContext(Dispatchers.IO) {
            musicDao.deletePlaylistPendingTrackByArtistTitle(playlistId, artist, title)
        }
}
