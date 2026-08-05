package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song

data class CfRecommendedRecording(
    val recordingMbid: String,
    val score: Double = 0.0
)

data class CfRecommendationsPayload(
    val userName: String,
    val recordings: List<CfRecommendedRecording>,
    val lastUpdatedEpochSec: Long? = null,
    val totalMbidCount: Int = 0,
    val artistType: String? = null
)

data class MatchedCfTrack(
    val recordingMbid: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val score: Double = 0.0,
    val localSong: Song?
) {
    fun toPlayableItem(): PlayableItem = PlayableItem.fromLibraryOrRemote(
        local = localSong,
        artist = artist,
        title = title,
        album = album,
        recordingMbid = recordingMbid
    )
}

data class MatchedCfRecommendations(
    val payload: CfRecommendationsPayload,
    val matches: List<MatchedCfTrack>
) {
    val matchedCount: Int get() = matches.count { it.localSong != null }
    val totalCount: Int get() = matches.size
    val streamCount: Int get() = totalCount - matchedCount

    fun toPlayableItems(): List<PlayableItem> = matches.map { it.toPlayableItem() }
}
