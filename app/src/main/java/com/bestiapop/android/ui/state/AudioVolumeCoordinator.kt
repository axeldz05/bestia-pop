package com.bestiapop.android.ui.state

import android.media.AudioManager
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinator for audio volume calculations, volume boost HUD auto-hide timeouts,
 * hardware volume key interception, and stereo balance controls.
 * Keeps [com.bestiapop.android.ui.MusicPlayerViewModel] lean.
 */
class AudioVolumeCoordinator(
    private val audioManager: AudioManager,
    private val playbackPreferences: PlaybackPreferencesRepository,
    private val scope: CoroutineScope,
    private val isBoostPrefEnabled: () -> Boolean,
    private val getPlaybackSettings: () -> PlaybackSettings
) {
    companion object {
        const val VOLUME_BOOST_STEP = 0.10f
        const val VOLUME_BOOST_HUD_DURATION_MS = 2000L
    }

    private val _volumeLevel = MutableStateFlow(getDeviceVolumeRatio())
    val volumeLevel: StateFlow<Float> = _volumeLevel.asStateFlow()

    private val _volumeBoostHudVisible = MutableStateFlow(false)
    val volumeBoostHudVisible: StateFlow<Boolean> = _volumeBoostHudVisible.asStateFlow()

    private var hudHideJob: Job? = null
    private var handledVolumeDownAction = false

    fun getDeviceVolumeRatio(): Float {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    fun setSystemVolumeRatio(systemRatio: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVolume = (systemRatio.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        } catch (_: Exception) {}
    }

    fun setVolume(ratio: Float) {
        val boostEnabled = isBoostPrefEnabled()
        val clamped = if (boostEnabled) ratio.coerceIn(0f, 2f) else ratio.coerceIn(0f, 1f)
        val systemRatio = clamped.coerceAtMost(1f)
        setSystemVolumeRatio(systemRatio)

        val boostAmount = if (boostEnabled && clamped > 1f) (clamped - 1f).coerceIn(0f, 1f) else 0f
        if (boostEnabled) {
            _volumeLevel.value = clamped
            scope.launch { playbackPreferences.setVolumeBoostAmount(boostAmount) }
        } else {
            _volumeLevel.value = systemRatio
        }
    }

    fun setVolumeBoostEnabled(enabled: Boolean) {
        scope.launch {
            playbackPreferences.setVolumeBoostEnabled(enabled)
            if (enabled) {
                val amount = getPlaybackSettings().volumeBoostAmount.coerceIn(0f, 1f)
                if (amount > 0f) {
                    setSystemVolumeRatio(1f)
                    _volumeLevel.value = 1f + amount
                } else {
                    _volumeLevel.value = getDeviceVolumeRatio().coerceAtMost(1f)
                }
            } else {
                _volumeLevel.value = getDeviceVolumeRatio().coerceAtMost(1f)
            }
        }
    }

    fun restoreVolumeBoost(settings: PlaybackSettings) {
        if (!settings.volumeBoostEnabled) {
            if (_volumeLevel.value > 1f) {
                _volumeLevel.value = getDeviceVolumeRatio().coerceAtMost(1f)
            }
            return
        }
        val amount = settings.volumeBoostAmount.coerceIn(0f, 1f)
        if (amount > 0f) {
            setSystemVolumeRatio(1f)
            _volumeLevel.value = 1f + amount
        } else {
            _volumeLevel.value = getDeviceVolumeRatio().coerceAtMost(1f)
        }
    }

    fun showVolumeBoostHud() {
        hudHideJob?.cancel()
        _volumeBoostHudVisible.value = true
        hudHideJob = scope.launch {
            delay(VOLUME_BOOST_HUD_DURATION_MS)
            _volumeBoostHudVisible.value = false
        }
    }

    fun hideVolumeBoostHud() {
        hudHideJob?.cancel()
        _volumeBoostHudVisible.value = false
    }

    fun handleVolumeUp(): Boolean {
        val boostEnabled = isBoostPrefEnabled()
        if (!boostEnabled) return false

        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        if (currentVol < maxVol) {
            if (getPlaybackSettings().volumeBoostAmount > 0f) {
                scope.launch { playbackPreferences.setVolumeBoostAmount(0f) }
            }
            return false
        }

        // System volume is at 100%. Increase boost in steps of 10% (0.10f).
        val currentBoost = getPlaybackSettings().volumeBoostAmount.coerceIn(0f, 1f)
        val newBoost = (currentBoost + VOLUME_BOOST_STEP).coerceIn(0f, 1f)
        val newRatio = 1f + newBoost
        _volumeLevel.value = newRatio
        scope.launch { playbackPreferences.setVolumeBoostAmount(newBoost) }
        showVolumeBoostHud()
        return true
    }

    fun handleVolumeDown(): Boolean {
        val boostEnabled = isBoostPrefEnabled()
        val currentBoost = if (boostEnabled) getPlaybackSettings().volumeBoostAmount.coerceIn(0f, 1f) else 0f
        if (boostEnabled && currentBoost > 0.001f) {
            handledVolumeDownAction = true
            val newBoost = (currentBoost - VOLUME_BOOST_STEP).coerceAtLeast(0f)
            scope.launch { playbackPreferences.setVolumeBoostAmount(newBoost) }
            val newRatio = 1f + newBoost
            _volumeLevel.value = newRatio
            showVolumeBoostHud()
            return true
        }

        hideVolumeBoostHud()
        handledVolumeDownAction = false
        return false
    }

    fun consumeVolumeDownUpAction(): Boolean {
        val wasHandled = handledVolumeDownAction
        handledVolumeDownAction = false
        return wasHandled
    }

    fun isVolumeBoostActive(): Boolean {
        return isBoostPrefEnabled() && _volumeLevel.value > 1.0f
    }

    fun setStereoLeftGain(gain: Float) {
        scope.launch { playbackPreferences.setStereoLeftGain(gain) }
    }

    fun setStereoRightGain(gain: Float) {
        scope.launch { playbackPreferences.setStereoRightGain(gain) }
    }

    fun resetStereoBalance() {
        scope.launch {
            playbackPreferences.setStereoLeftGain(1f)
            playbackPreferences.setStereoRightGain(1f)
        }
    }
}
