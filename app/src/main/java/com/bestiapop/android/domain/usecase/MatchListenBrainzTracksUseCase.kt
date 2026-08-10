package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.LbPlaylistDetail
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.listenbrainz.toMatchedRemote
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.TrackMatchKeys

class MatchListenBrainzTracksUseCase {

    fun execute(detail: LbPlaylistDetail, library: List<Song>): MatchedLbPlaylist {
        val matches = TrackMatchKeys.matchMetasAgainstLibrary(detail.tracks, library) { track, local ->
            track.identity.toMatchedRemote(
                localSong = local,
                recordingMbid = track.recordingMbid
            )
        }
        return MatchedLbPlaylist(detail = detail, matches = matches)
    }
}
