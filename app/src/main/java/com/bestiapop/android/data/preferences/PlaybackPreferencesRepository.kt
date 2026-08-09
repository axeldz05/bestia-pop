package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bestiapop.android.data.model.RepeatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_settings"
)

/** Max LoudnessEnhancer target gain when slider is at 200% (+12 dB). */
const val MAX_VOLUME_BOOST_GAIN_MB = 1200

data class PlaybackSettings(
    val volumeBoostEnabled: Boolean = false,
    /** Fraction of the >100% range: 0 = no boost, 1 = full [MAX_VOLUME_BOOST_GAIN_MB]. */
    val volumeBoostAmount: Float = 0f,
    /** Independent left-channel attenuation (1 = full, 0 = mute). */
    val stereoLeftGain: Float = 1f,
    /** Independent right-channel attenuation (1 = full, 0 = mute). */
    val stereoRightGain: Float = 1f,
    val rememberShuffleOnLaunch: Boolean = true,
    val rememberRepeatOnLaunch: Boolean = true,
    /** Cold start: resume last queue/track. Local and remote use the same flag. Default off. */
    val autoplayOnLaunch: Boolean = false,
    val lastShuffleEnabled: Boolean = false,
    val lastRepeatMode: RepeatMode = RepeatMode.OFF
)

data class PlaybackModesSnapshot(
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val applyRepeatToPlayer: Boolean
)

/** Pure restore of Now Playing shuffle/repeat after MediaController connect. */
object PlaybackModeRestore {
    fun resolve(
        settings: PlaybackSettings,
        hasLiveSession: Boolean,
        liveRepeat: RepeatMode
    ): PlaybackModesSnapshot = resolve(
        rememberShuffle = settings.rememberShuffleOnLaunch,
        rememberRepeat = settings.rememberRepeatOnLaunch,
        lastShuffle = settings.lastShuffleEnabled,
        lastRepeat = settings.lastRepeatMode,
        hasLiveSession = hasLiveSession,
        liveRepeat = liveRepeat
    )

    fun resolve(
        rememberShuffle: Boolean,
        rememberRepeat: Boolean,
        lastShuffle: Boolean,
        lastRepeat: RepeatMode,
        hasLiveSession: Boolean,
        liveRepeat: RepeatMode
    ): PlaybackModesSnapshot {
        if (hasLiveSession) {
            return PlaybackModesSnapshot(
                shuffle = lastShuffle,
                repeat = liveRepeat,
                applyRepeatToPlayer = false
            )
        }
        return PlaybackModesSnapshot(
            shuffle = rememberShuffle && lastShuffle,
            repeat = if (rememberRepeat) lastRepeat else RepeatMode.OFF,
            applyRepeatToPlayer = true
        )
    }
}

fun parseRepeatModeName(name: String?): RepeatMode =
    name?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() } ?: RepeatMode.OFF

fun clampVolumeBoostAmount(amount: Float): Float = amount.coerceIn(0f, 1f)

fun clampStereoGain(gain: Float): Float = gain.coerceIn(0f, 1f)

class PlaybackPreferencesRepository(private val context: Context) {

    private object Keys {
        val VOLUME_BOOST_ENABLED = booleanPreferencesKey("volume_boost_enabled")
        val VOLUME_BOOST_AMOUNT = floatPreferencesKey("volume_boost_amount")
        val STEREO_LEFT_GAIN = floatPreferencesKey("stereo_left_gain")
        val STEREO_RIGHT_GAIN = floatPreferencesKey("stereo_right_gain")
        val REMEMBER_SHUFFLE_ON_LAUNCH = booleanPreferencesKey("remember_shuffle_on_launch")
        val REMEMBER_REPEAT_ON_LAUNCH = booleanPreferencesKey("remember_repeat_on_launch")
        val AUTOPLAY_ON_LAUNCH = booleanPreferencesKey("autoplay_on_launch")
        val LAST_SHUFFLE_ENABLED = booleanPreferencesKey("last_shuffle_enabled")
        val LAST_REPEAT_MODE = stringPreferencesKey("last_repeat_mode")
    }

    val settingsFlow: Flow<PlaybackSettings> = context.playbackDataStore.data.map { prefs ->
        PlaybackSettings(
            volumeBoostEnabled = prefs[Keys.VOLUME_BOOST_ENABLED] ?: false,
            volumeBoostAmount = clampVolumeBoostAmount(prefs[Keys.VOLUME_BOOST_AMOUNT] ?: 0f),
            stereoLeftGain = clampStereoGain(prefs[Keys.STEREO_LEFT_GAIN] ?: 1f),
            stereoRightGain = clampStereoGain(prefs[Keys.STEREO_RIGHT_GAIN] ?: 1f),
            rememberShuffleOnLaunch = prefs[Keys.REMEMBER_SHUFFLE_ON_LAUNCH] ?: true,
            rememberRepeatOnLaunch = prefs[Keys.REMEMBER_REPEAT_ON_LAUNCH] ?: true,
            autoplayOnLaunch = prefs[Keys.AUTOPLAY_ON_LAUNCH] ?: false,
            lastShuffleEnabled = prefs[Keys.LAST_SHUFFLE_ENABLED] ?: false,
            lastRepeatMode = parseRepeatModeName(prefs[Keys.LAST_REPEAT_MODE])
        )
    }

    suspend fun setVolumeBoostEnabled(enabled: Boolean) {
        context.playbackDataStore.put(Keys.VOLUME_BOOST_ENABLED, enabled)
    }

    suspend fun setVolumeBoostAmount(amount: Float) {
        context.playbackDataStore.put(Keys.VOLUME_BOOST_AMOUNT, clampVolumeBoostAmount(amount))
    }

    suspend fun setStereoLeftGain(gain: Float) {
        context.playbackDataStore.put(Keys.STEREO_LEFT_GAIN, clampStereoGain(gain))
    }

    suspend fun setStereoRightGain(gain: Float) {
        context.playbackDataStore.put(Keys.STEREO_RIGHT_GAIN, clampStereoGain(gain))
    }

    suspend fun resetStereoBalance() {
        context.playbackDataStore.edit { prefs ->
            prefs[Keys.STEREO_LEFT_GAIN] = 1f
            prefs[Keys.STEREO_RIGHT_GAIN] = 1f
        }
    }

    suspend fun setRememberShuffleOnLaunch(enabled: Boolean) {
        context.playbackDataStore.put(Keys.REMEMBER_SHUFFLE_ON_LAUNCH, enabled)
    }

    suspend fun setRememberRepeatOnLaunch(enabled: Boolean) {
        context.playbackDataStore.put(Keys.REMEMBER_REPEAT_ON_LAUNCH, enabled)
    }

    suspend fun setAutoplayOnLaunch(enabled: Boolean) {
        context.playbackDataStore.put(Keys.AUTOPLAY_ON_LAUNCH, enabled)
    }

    suspend fun setLastShuffleEnabled(enabled: Boolean) {
        context.playbackDataStore.put(Keys.LAST_SHUFFLE_ENABLED, enabled)
    }

    suspend fun setLastRepeatMode(mode: RepeatMode) {
        context.playbackDataStore.put(Keys.LAST_REPEAT_MODE, mode.name)
    }
}
