package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.model.PlayableItem

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

data class MatchedCfRecommendations(
    val payload: CfRecommendationsPayload,
    val matches: List<MatchedRemoteTrack>
) {
    val matchedCount: Int get() = matches.matchedCount()
    val totalCount: Int get() = matches.size
    val streamCount: Int get() = matches.streamCount()

    fun toPlayableItems(): List<PlayableItem> = matches.toPlayableItems()
}
