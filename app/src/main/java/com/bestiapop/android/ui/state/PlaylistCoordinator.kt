package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.repository.IMusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Coordinator for playlist CRUD operations, song assignments, reordering, and detail flows.
 * Keeps [com.bestiapop.android.ui.MusicPlayerViewModel] lean.
 */
class PlaylistCoordinator(
    private val scope: CoroutineScope,
    private val repository: IMusicRepository,
    private val onPlaylistDeleted: (Long) -> Unit = {}
) {
    fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> =
        repository.getPlaylistSongsFlow(playlistId)

    fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> =
        repository.getPlaylistDetailsFlow(playlistId)

    fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrack>> =
        repository.getPlaylistPendingTracksFlow(playlistId)

    fun createPlaylist(
        name: String,
        description: String? = null,
        coverUri: String? = null,
        initialSongIds: List<Long> = emptyList(),
        onCreated: ((Long) -> Unit)? = null
    ) {
        scope.launch {
            val id = repository.createPlaylist(name, description, coverUri)
            if (initialSongIds.isNotEmpty()) {
                repository.addSongsToPlaylist(id, initialSongIds)
            }
            onCreated?.invoke(id)
        }
    }

    fun updatePlaylist(
        id: Long,
        name: String,
        description: String? = null,
        coverUri: String? = null
    ) {
        scope.launch {
            repository.updatePlaylist(id, name, description, coverUri)
        }
    }

    fun deletePlaylist(id: Long) {
        onPlaylistDeleted(id)
        scope.launch {
            repository.deletePlaylist(id)
        }
    }

    fun addSongToPlaylist(playlistId: Long, song: Song) {
        scope.launch {
            repository.addSongToPlaylist(playlistId, song.id)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        scope.launch {
            repository.removeSongFromPlaylist(playlistId, songId)
        }
    }

    fun reorderPlaylistSongs(playlistId: Long, songIds: List<Long>) {
        scope.launch {
            repository.reorderPlaylistSongs(playlistId, songIds)
        }
    }

    fun runWithPlaylistSongs(playlistId: Long, action: (List<Song>) -> Unit) {
        scope.launch {
            val songs = repository.getPlaylistSongsOrdered(playlistId)
            if (songs.isNotEmpty()) {
                action(songs)
            }
        }
    }

    fun runWithPlaylistPlayables(playlistId: Long, action: (List<PlayableItem>) -> Unit) {
        scope.launch {
            val playables = repository.getPlaylistPlayables(playlistId)
            if (playables.isNotEmpty()) {
                action(playables)
            }
        }
    }

    fun enrichPlaylistPendingArtworks(playlistId: Long) {
        scope.launch {
            repository.enrichPlaylistPendingArtworks(playlistId)
        }
    }

    fun enrichAllPlaylistsPendingArtworks() {
        scope.launch {
            val playlists = repository.playlistsFlow.first()
            for (p in playlists) {
                repository.enrichPlaylistPendingArtworks(p.id)
            }
        }
    }
}
