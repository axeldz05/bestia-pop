package com.bestiapop.android.data.model

import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist

/**
 * Process-session origin for a queue armed from Discover (Para Ti / Recomendados).
 *
 * This is deliberately not persisted across process death. Local playlist membership is resolved
 * separately through Room.
 */
sealed interface DiscoverPlaybackOrigin {
    data object None : DiscoverPlaybackOrigin
    data class ListenBrainz(val mbid: String, val title: String) : DiscoverPlaybackOrigin
    data object CfRecommendations : DiscoverPlaybackOrigin
}

fun MatchedLbPlaylist.toDiscoverOrigin(): DiscoverPlaybackOrigin =
    DiscoverPlaybackOrigin.ListenBrainz(
        mbid = detail.summary.mbid,
        title = detail.summary.title
    )
