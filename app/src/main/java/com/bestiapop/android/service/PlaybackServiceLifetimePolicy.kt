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

    /**
     * When the user intentionally removes the task from the recent apps overview (task manager),
     * playback and the foreground service should stop cleanly.
     */
    fun shouldStopAfterTaskRemoved(
        playWhenReady: Boolean = false,
        mediaItemCount: Int = 0,
        playbackState: Int = Player.STATE_IDLE
    ): Boolean = true

    /**
     * Paused shade controls stay visible while a queue item is current, including Remote
     * placeholders that leave ExoPlayer in [Player.STATE_IDLE] during resolve.
     */
    fun shouldShowPlaybackNotification(
        mediaItemCount: Int,
        playbackState: Int
    ): Boolean =
        mediaItemCount > 0 && playbackState != Player.STATE_ENDED
}
