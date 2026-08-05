package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.util.TrackMatchKeys

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
        val hasMatched = matched.matchedCount > 0
        val hasUnmatched = matched.streamCount > 0
        if (!hasMatched && !hasUnmatched && !allowEmpty) return null

        val summary = matched.detail.summary
        val name = summary.title.ifBlank { "Para Ti" }
        val playlistId = repository.createPlaylist(
            name = name,
            description = summary.description,
            coverUri = null
        )

        matched.matches.forEach { row ->
            val local = row.localSong ?: return@forEach
            repository.addSongToPlaylist(playlistId, local.id)
        }

        val pending = matched.matches.mapIndexedNotNull { index, row ->
            if (row.localSong != null) return@mapIndexedNotNull null
            val track = row.track
            PlaylistPendingTrack(
                playlistId = playlistId,
                title = track.title,
                artist = track.artist,
                releaseName = track.releaseName,
                recordingMbid = track.recordingMbid,
                position = index
            )
        }
        repository.addPlaylistPendingTracks(pending)
        return playlistId
    }

    fun unmatchedCatalogTracks(matched: MatchedLbPlaylist): List<OnlineCatalogTrack> =
        matched.matches
            .filter { it.localSong == null }
            .map { it.track }
            .map { track ->
                PlayableItem.remoteFrom(
                    artist = track.artist,
                    title = track.title,
                    album = track.releaseName,
                    recordingMbid = track.recordingMbid
                ).toOnlineCatalogTrack(provider = "ListenBrainz")
            }

    companion object {
        fun downloadIdFor(artist: String, title: String): String =
            TrackMatchKeys.downloadIdFor(artist, title)
    }
}
