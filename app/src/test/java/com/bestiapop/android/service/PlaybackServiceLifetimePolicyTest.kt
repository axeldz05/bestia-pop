package com.bestiapop.android.service

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceLifetimePolicyTest {

    @Test
    fun taskRemoved_keepsRemotePlaceholderWhilePlayIntentIsActive() {
        assertTrue(
            PlaybackServiceLifetimePolicy.isPlaybackEngaged(
                playWhenReady = true,
                mediaItemCount = 4,
                playbackState = Player.STATE_IDLE
            )
        )
        assertFalse(
            PlaybackServiceLifetimePolicy.shouldStopAfterTaskRemoved(
                playWhenReady = true,
                mediaItemCount = 4,
                playbackState = Player.STATE_IDLE
            )
        )
    }

    @Test
    fun taskRemoved_stopsPausedOrEndedPlayback() {
        assertTrue(
            PlaybackServiceLifetimePolicy.shouldStopAfterTaskRemoved(
                playWhenReady = false,
                mediaItemCount = 4,
                playbackState = Player.STATE_READY
            )
        )
        assertTrue(
            PlaybackServiceLifetimePolicy.shouldStopAfterTaskRemoved(
                playWhenReady = true,
                mediaItemCount = 4,
                playbackState = Player.STATE_ENDED
            )
        )
    }

    @Test
    fun pausedNotification_staysVisibleForIdleRemotePlaceholder() {
        assertTrue(
            PlaybackServiceLifetimePolicy.shouldShowPlaybackNotification(
                mediaItemCount = 2,
                playbackState = Player.STATE_IDLE
            )
        )
        assertTrue(
            PlaybackServiceLifetimePolicy.shouldShowPlaybackNotification(
                mediaItemCount = 2,
                playbackState = Player.STATE_READY
            )
        )
        assertFalse(
            PlaybackServiceLifetimePolicy.shouldShowPlaybackNotification(
                mediaItemCount = 2,
                playbackState = Player.STATE_ENDED
            )
        )
        assertFalse(
            PlaybackServiceLifetimePolicy.shouldShowPlaybackNotification(
                mediaItemCount = 0,
                playbackState = Player.STATE_IDLE
            )
        )
    }
}
