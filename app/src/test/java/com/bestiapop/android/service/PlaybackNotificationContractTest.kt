package com.bestiapop.android.service

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNotificationContractTest {

    @Test
    fun media3ForegroundRequest_isAlwaysHonored() {
        assertTrue(
            playbackForegroundRequired(
                startInForegroundRequired = true,
                playWhenReady = false,
                mediaItemCount = 0,
                playbackState = Player.STATE_IDLE
            )
        )
    }

    @Test
    fun remoteIdleWithPlayIntent_forcesMedia3Foreground() {
        assertTrue(
            playbackForegroundRequired(
                startInForegroundRequired = false,
                playWhenReady = true,
                mediaItemCount = 1,
                playbackState = Player.STATE_IDLE
            )
        )
    }

    @Test
    fun pausedOrEndedPlayback_doesNotInventForegroundDemand() {
        assertFalse(
            playbackForegroundRequired(
                startInForegroundRequired = false,
                playWhenReady = false,
                mediaItemCount = 1,
                playbackState = Player.STATE_READY
            )
        )
        assertFalse(
            playbackForegroundRequired(
                startInForegroundRequired = false,
                playWhenReady = true,
                mediaItemCount = 1,
                playbackState = Player.STATE_ENDED
            )
        )
    }
}
