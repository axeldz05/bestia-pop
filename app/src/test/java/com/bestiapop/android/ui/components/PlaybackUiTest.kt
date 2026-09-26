package com.bestiapop.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackUiTest {
    @Test
    fun resolvePlayPauseVisualState_pausedWhenIdle() {
        assertEquals(
            PlayPauseVisualState.Paused,
            resolvePlayPauseVisualState(isPlaying = false, isPlaybackLoading = false),
        )
    }

    @Test
    fun resolvePlayPauseVisualState_loadingTakesPrecedenceWhenResolvingOrBuffering() {
        assertEquals(
            PlayPauseVisualState.Loading,
            resolvePlayPauseVisualState(isPlaying = false, isPlaybackLoading = true),
        )
        assertEquals(
            PlayPauseVisualState.Loading,
            resolvePlayPauseVisualState(isPlaying = true, isPlaybackLoading = true),
        )
    }

    @Test
    fun resolvePlayPauseVisualState_playingWhenActiveWithoutLoading() {
        assertEquals(
            PlayPauseVisualState.Playing,
            resolvePlayPauseVisualState(isPlaying = true, isPlaybackLoading = false),
        )
    }
}
