package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta

data class LbPlaylistSummary(
    val mbid: String,
    val title: String,
    val description: String?,
    val trackCount: Int
)

data class LbPlaylistTrack(
    val identity: TrackIdentity,
    val recordingMbid: String? = null
) : TrackMeta by identity {
    companion object {
        /** L2: flat LB playlist construction (identity is Level 1). */
        operator fun invoke(
            title: String,
            artist: String,
            album: String = "",
            recordingMbid: String? = null
        ) = LbPlaylistTrack(
            identity = TrackIdentity(title = title, artist = artist, album = album),
            recordingMbid = recordingMbid
        )
    }

    fun toMatchedRemote(localSong: Song?): MatchedRemoteTrack = MatchedRemoteTrack(
        identity = identity,
        recordingMbid = recordingMbid,
        localSong = localSong
    )
}

data class LbPlaylistDetail(
    val summary: LbPlaylistSummary,
    val tracks: List<LbPlaylistTrack>
)

data class MatchedLbPlaylist(
    val detail: LbPlaylistDetail,
    val matches: List<MatchedRemoteTrack>
) {
    val matchedCount: Int get() = matches.count { it.localSong != null }
    val totalCount: Int get() = matches.size
    val streamCount: Int get() = totalCount - matchedCount
    val matchedSongs: List<Song> get() = matches.mapNotNull { it.localSong }

    fun toPlayableItems(): List<PlayableItem> = matches.toPlayableItems()
}

sealed class LbApiResult<out T> {
    data class Success<T>(val data: T) : LbApiResult<T>()
    data class Failure(val message: String, val isNetworkError: Boolean = false) : LbApiResult<Nothing>()
}
