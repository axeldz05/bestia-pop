package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.LbPlaylistDetail
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.model.firstArtworkUri
import com.bestiapop.android.data.listenbrainz.toMatchedRemote
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.TrackMatchKeys

class MatchListenBrainzTracksUseCase {

    fun execute(detail: LbPlaylistDetail, library: List<Song>): MatchedLbPlaylist {
        val matches = TrackMatchKeys.matchMetasAgainstLibrary(detail.tracks, library) { track, local ->
            val resolvedArtwork = track.identity.artworkUri?.takeIf { it.isNotBlank() }
                ?: local?.artworkUri?.takeIf { it.isNotBlank() }
                ?: track.identity.album.takeIf { it.isNotBlank() && !isGenericAlbum(it) }?.let { albumName ->
                    library.firstOrNull { it.album.equals(albumName, ignoreCase = true) && !it.artworkUri.isNullOrBlank() }?.artworkUri
                }
            val identity = if (resolvedArtwork != null && resolvedArtwork != track.identity.artworkUri) {
                track.identity.copy(artworkUri = resolvedArtwork)
            } else {
                track.identity
            }
            identity.toMatchedRemote(
                localSong = local,
                recordingMbid = track.recordingMbid
            )
        }
        val resolvedCover = detail.summary.coverUrl ?: matches.firstArtworkUri()
        val updatedDetail = if (resolvedCover != null && resolvedCover != detail.summary.coverUrl) {
            detail.copy(summary = detail.summary.copy(coverUrl = resolvedCover))
        } else {
            detail
        }
        return MatchedLbPlaylist(detail = updatedDetail, matches = matches)
    }

    private fun isGenericAlbum(name: String): Boolean =
        name.equals("Single", ignoreCase = true) ||
            name.equals("Unknown Album", ignoreCase = true) ||
            name.equals("Stream", ignoreCase = true)
}
