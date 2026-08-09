package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.LbPlaylistDetail
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.listenbrainz.MatchedLbTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.TrackMatchKeys

class MatchListenBrainzTracksUseCase {

    fun execute(detail: LbPlaylistDetail, library: List<Song>): MatchedLbPlaylist {
        val index = TrackMatchKeys.buildLibraryIndex(library)
        val matches = detail.tracks.map { track ->
            MatchedLbTrack(
                track = track,
                localSong = index[TrackMatchKeys.matchKey(track.artist, track.title)]
            )
        }
        return MatchedLbPlaylist(detail = detail, matches = matches)
    }
}
