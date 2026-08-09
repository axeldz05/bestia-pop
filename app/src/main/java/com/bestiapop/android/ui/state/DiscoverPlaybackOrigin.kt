package com.bestiapop.android.ui.state

/**
 * Session-only origin when the queue was armed from Discover (Para Ti / Recomendados).
 * Not persisted; local playlist membership is resolved separately via Room.
 */
sealed interface DiscoverPlaybackOrigin {
    data object None : DiscoverPlaybackOrigin
    data class ListenBrainz(val mbid: String, val title: String) : DiscoverPlaybackOrigin
    data object CfRecommendations : DiscoverPlaybackOrigin
}
