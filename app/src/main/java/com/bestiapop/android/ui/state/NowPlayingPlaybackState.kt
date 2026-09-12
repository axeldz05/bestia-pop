package com.bestiapop.android.ui.state

import androidx.compose.runtime.Immutable
import com.bestiapop.android.domain.radio.RadioMode

/**
 * Bundled radio playback status for UI consumers like [com.bestiapop.android.ui.screens.NowPlayingScreen].
 */
@Immutable
data class RadioPlaybackState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val mode: RadioMode = RadioMode.KNOWN,
    val statusLabel: String? = null
)

/**
 * Bundled transport and queue playback actions avoiding prop-drilling in playback screens.
 */
@Immutable
data class NowPlayingTransportActions(
    val onTogglePlayPause: () -> Unit,
    val onSkipNext: () -> Unit,
    val onSkipPrevious: () -> Unit,
    val onToggleShuffle: () -> Unit,
    val onToggleRepeatMode: () -> Unit,
    val onSeek: (Long) -> Unit
)
