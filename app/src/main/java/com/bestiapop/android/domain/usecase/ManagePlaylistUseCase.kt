package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.domain.repository.IMusicRepository

class ManagePlaylistUseCase(private val repository: IMusicRepository) {

    suspend fun createPlaylist(name: String, description: String? = null, coverUri: String? = null): Long {
        require(name.isNotBlank()) { "El nombre de la playlist no puede estar vacío" }
        return repository.createPlaylist(name.trim(), description?.trim(), coverUri)
    }

    suspend fun updatePlaylist(id: Long, name: String, description: String? = null, coverUri: String? = null) {
        require(name.isNotBlank()) { "El nombre de la playlist no puede estar vacío" }
        repository.updatePlaylist(id, name.trim(), description?.trim(), coverUri)
    }

    suspend fun deletePlaylist(playlistId: Long) {
        repository.deletePlaylist(playlistId)
    }

    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        songIds.forEach { songId ->
            repository.addSongToPlaylist(playlistId, songId)
        }
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        repository.removeSongFromPlaylist(playlistId, songId)
    }
}
