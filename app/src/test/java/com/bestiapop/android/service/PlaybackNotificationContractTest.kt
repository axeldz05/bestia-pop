package com.bestiapop.android.service

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
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
    fun pausedPlaybackWithQueue_doesNotForceForegroundDemand() {
        assertFalse(
            playbackForegroundRequired(
                startInForegroundRequired = false,
                playWhenReady = false,
                mediaItemCount = 1,
                playbackState = Player.STATE_READY
            )
        )
    }

    @Test
    fun emptyQueueWithoutIntent_doesNotInventForegroundDemand() {
        assertFalse(
            playbackForegroundRequired(
                startInForegroundRequired = false,
                playWhenReady = false,
                mediaItemCount = 0,
                playbackState = Player.STATE_READY
            )
        )
    }

    @Test
    fun playIntentWithQueueDuringTransitionOrEnded_retainsForeground() {
        assertTrue(
            playbackForegroundRequired(
                startInForegroundRequired = false,
                playWhenReady = true,
                mediaItemCount = 1,
                playbackState = Player.STATE_ENDED
            )
        )
    }

    @Test
    fun localPlayback_usesNoJavaWakeLock() {
        assertEquals(C.WAKE_MODE_NONE, playbackWakeMode(currentIsRemote = false, nextIsRemote = false))
    }

    @Test
    fun remoteOrUpcomingRemote_usesNetworkWakeMode() {
        assertEquals(C.WAKE_MODE_NETWORK, playbackWakeMode(currentIsRemote = true, nextIsRemote = false))
        assertEquals(C.WAKE_MODE_NETWORK, playbackWakeMode(currentIsRemote = false, nextIsRemote = true))
    }

    @Test
    fun stickyRestart_resumesOnlyWhenIntentIsNullAndWasEngaged() {
        assertTrue(shouldResumeAfterStickyRestart(intentNull = true, wasEngaged = true))
        assertFalse(shouldResumeAfterStickyRestart(intentNull = true, wasEngaged = false))
        assertFalse(shouldResumeAfterStickyRestart(intentNull = false, wasEngaged = true))
        assertFalse(shouldResumeAfterStickyRestart(intentNull = false, wasEngaged = false))
    }

    @Test
    fun restrictionAlert_usesDistinctHighPriorityChannel() {
        assertEquals("playback_restricted_channel", MusicService.RESTRICTION_CHANNEL_ID)
        assertTrue(MusicService.RESTRICTION_CHANNEL_ID != MusicService.PLAYBACK_CHANNEL_ID)
    }
}
