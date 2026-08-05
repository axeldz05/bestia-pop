package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.model.PlayableItem
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
) {
    fun toPlayableItem(): PlayableItem = PlayableItem.fromLibraryOrRemote(
        local = localSong,
        artist = track.artist,
        title = track.title,
        album = track.releaseName,
        recordingMbid = track.recordingMbid
    )
}

data class MatchedLbPlaylist(
    val detail: LbPlaylistDetail,
    val matches: List<MatchedLbTrack>
) {
    val matchedCount: Int get() = matches.count { it.localSong != null }
    val totalCount: Int get() = matches.size
    val streamCount: Int get() = totalCount - matchedCount
    val matchedSongs: List<Song> get() = matches.mapNotNull { it.localSong }

    fun toPlayableItems(): List<PlayableItem> = matches.map { it.toPlayableItem() }
}

sealed class LbApiResult<out T> {
    data class Success<T>(val data: T) : LbApiResult<T>()
    data class Failure(val message: String, val isNetworkError: Boolean = false) : LbApiResult<Nothing>()
}
