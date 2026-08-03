package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.model.Song

data class LbPlaylistSummary(
    val mbid: String,
    val title: String,
    val description: String?,
    val trackCount: Int
)

data class LbPlaylistTrack(
    val title: String,
    val artist: String,
    val recordingMbid: String? = null,
    val releaseName: String? = null
)

data class LbPlaylistDetail(
    val summary: LbPlaylistSummary,
    val tracks: List<LbPlaylistTrack>
)

data class MatchedLbTrack(
    val track: LbPlaylistTrack,
    val localSong: Song?
)

data class MatchedLbPlaylist(
    val detail: LbPlaylistDetail,
    val matches: List<MatchedLbTrack>
) {
    val matchedCount: Int get() = matches.count { it.localSong != null }
    val totalCount: Int get() = matches.size
    val matchedSongs: List<Song> get() = matches.mapNotNull { it.localSong }
}

sealed class LbApiResult<out T> {
    data class Success<T>(val data: T) : LbApiResult<T>()
    data class Failure(val message: String, val isNetworkError: Boolean = false) : LbApiResult<Nothing>()
}
