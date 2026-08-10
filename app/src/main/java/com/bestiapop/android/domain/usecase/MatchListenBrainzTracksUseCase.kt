package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.LbPlaylistDetail
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.TrackMatchKeys

class MatchListenBrainzTracksUseCase {

    fun execute(detail: LbPlaylistDetail, library: List<Song>): MatchedLbPlaylist {
        val index = TrackMatchKeys.buildLibraryIndex(library)
        val matches = detail.tracks.map { track ->
            track.toMatchedRemote(localSong = TrackMatchKeys.lookupLocalSong(index, track))
        }
        return MatchedLbPlaylist(detail = detail, matches = matches)
    }
}
