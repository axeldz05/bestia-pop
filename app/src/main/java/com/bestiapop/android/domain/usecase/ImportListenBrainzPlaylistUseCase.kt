package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.listenbrainz.unmatchedCatalogTracks
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.domain.repository.IMusicRepository

/**
 * Creates a local Room playlist from an LB Discover playlist:
 * matched tracks as song cross-refs; unmatched as metadata-only pending rows.
 */
class ImportListenBrainzPlaylistUseCase(
    private val repository: IMusicRepository
) {

    /**
     * Creates a playlist with LB title/description, adds matched local songs,
     * and persists unmatched tracks as [PlaylistPendingTrack] (no CDN URLs).
     *
     * @return new playlist id, or null if there is nothing to save and [allowEmpty] is false
     */
    suspend fun createLocalFromMatched(
        matched: MatchedLbPlaylist,
        allowEmpty: Boolean = false
    ): Long? {
        val summary = matched.detail.summary
        val name = summary.title.ifBlank { "Para Ti" }
        return repository.createPlaylistWithPlayables(
            name = name,
            items = matched.toPlayableItems(),
            description = summary.description,
            allowEmpty = allowEmpty
        )
    }

    fun unmatchedCatalogTracks(matched: MatchedLbPlaylist): List<OnlineCatalogTrack> =
        matched.matches.unmatchedCatalogTracks()
}
