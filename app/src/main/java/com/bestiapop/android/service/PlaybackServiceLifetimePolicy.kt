package com.bestiapop.android.service

import androidx.media3.common.Player

/**
 * Service lifetime is based on playback intent, not Activity/task visibility.
 *
 * A Remote placeholder temporarily leaves ExoPlayer IDLE while its CDN URI is being resolved. It is
 * still engaged when playWhenReady remains true and the queue is present.
 */
object PlaybackServiceLifetimePolicy {
    fun isPlaybackEngaged(
        playWhenReady: Boolean,
        mediaItemCount: Int,
        playbackState: Int
    ): Boolean =
        playWhenReady &&
            mediaItemCount > 0 &&
            playbackState != Player.STATE_ENDED

    fun shouldStopAfterTaskRemoved(
        playWhenReady: Boolean,
        mediaItemCount: Int,
        playbackState: Int
    ): Boolean = !isPlaybackEngaged(playWhenReady, mediaItemCount, playbackState)
}
