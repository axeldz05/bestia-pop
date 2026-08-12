package com.bestiapop.android.service

import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.session.R as Media3SessionR
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNotificationContractTest {

    @Test
    fun actions_arePreviousPlayPauseNext_withAllThreeCompact() {
        assertArrayEquals(
            intArrayOf(0, 1, 2),
            PlaybackNotificationFactory.compactActionIndices()
        )

        val actions = PlaybackNotificationFactory.actionSpecs(showPauseAction = true)
        assertEquals(
            listOf(
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            ),
            actions.map { it.command }
        )
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_NEXT
            ),
            actions.map { it.keyCode }
        )
        assertEquals(
            listOf(
                Media3SessionR.drawable.media3_icon_previous,
                Media3SessionR.drawable.media3_icon_pause,
                Media3SessionR.drawable.media3_icon_next
            ),
            actions.map { it.iconResId }
        )
    }

    @Test
    fun centerAction_reflectsPlayIntentAndOnlyPlayStartsForegroundService() {
        val pause = PlaybackNotificationFactory.actionSpecs(showPauseAction = true)[1]
        val play = PlaybackNotificationFactory.actionSpecs(showPauseAction = false)[1]

        assertEquals(Media3SessionR.drawable.media3_icon_pause, pause.iconResId)
        assertEquals(Media3SessionR.string.media3_controls_pause_description, pause.titleResId)
        assertFalse(pause.startsForegroundService)

        assertEquals(Media3SessionR.drawable.media3_icon_play, play.iconResId)
        assertEquals(Media3SessionR.string.media3_controls_play_description, play.titleResId)
        assertTrue(play.startsForegroundService)
    }

    @Test
    fun pauseIcon_tracksPlayIntentThroughBufferingAndRemoteIdle_butNotEnded() {
        assertTrue(
            PlaybackNotificationFactory.shouldShowPauseAction(
                playWhenReady = true,
                playbackState = Player.STATE_BUFFERING
            )
        )
        assertTrue(
            PlaybackNotificationFactory.shouldShowPauseAction(
                playWhenReady = true,
                playbackState = Player.STATE_IDLE
            )
        )
        assertFalse(
            PlaybackNotificationFactory.shouldShowPauseAction(
                playWhenReady = false,
                playbackState = Player.STATE_READY
            )
        )
        assertFalse(
            PlaybackNotificationFactory.shouldShowPauseAction(
                playWhenReady = true,
                playbackState = Player.STATE_ENDED
            )
        )
    }

    @Test
    fun compactIndices_areDefensivelyCopied() {
        val first = PlaybackNotificationFactory.compactActionIndices()
        first[0] = 99

        assertArrayEquals(
            intArrayOf(0, 1, 2),
            PlaybackNotificationFactory.compactActionIndices()
        )
    }
}
