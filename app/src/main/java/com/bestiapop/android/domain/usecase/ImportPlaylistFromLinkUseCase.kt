package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.ImportedPlaylistData
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.PlaylistImportSummary
import com.bestiapop.android.data.model.isRemote
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.util.TrackMatchKeys
import kotlinx.coroutines.flow.first

/**
 * Recreates a public link playlist locally in Room:
 * links existing local songs (without re-downloading) and saves non-local tracks
 * as playable streaming pending rows.
 */
class ImportPlaylistFromLinkUseCase(
    private val repository: IMusicRepository,
) {
    suspend fun execute(
        playlist: ImportedPlaylistData,
        isDownloading: Boolean = false,
    ): PlaylistImportSummary {
        val library = repository.allSongsFlow.first().filter { !it.isRemote }
        var matchedLocalCount = 0
        var pendingStreamCount = 0

        val playables =
            TrackMatchKeys.matchMetasAgainstLibrary(
                items = playlist.tracks,
                library = library,
            ) { track, localSong ->
                if (localSong != null) {
                    matchedLocalCount++
                    PlayableItem.Local(localSong)
                } else {
                    pendingStreamCount++
                    PlayableItem.Remote(
                        identity = track,
                    )
                }
            }

        val effectiveCover = playlist.coverUrl ?: playlist.tracks.firstOrNull()?.artworkUri
        val playlistId =
            repository.createPlaylistWithPlayables(
                name = playlist.title.ifBlank { "Playlist de ${playlist.platform.displayName}" },
                items = playables,
                coverUri = effectiveCover,
                allowEmpty = true,
            ) ?: 0L

        return PlaylistImportSummary(
            playlistId = playlistId,
            playlistTitle = playlist.title,
            coverUrl = effectiveCover,
            totalTracks = playlist.tracks.size,
            matchedLocalCount = matchedLocalCount,
            pendingStreamCount = pendingStreamCount,
            isDownloading = isDownloading,
        )
    }
}
