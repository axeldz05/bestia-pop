package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    val stereoRightGain: Float = 1f
)

fun clampVolumeBoostAmount(amount: Float): Float = amount.coerceIn(0f, 1f)

fun clampStereoGain(gain: Float): Float = gain.coerceIn(0f, 1f)

class PlaybackPreferencesRepository(private val context: Context) {

    private object Keys {
        val VOLUME_BOOST_ENABLED = booleanPreferencesKey("volume_boost_enabled")
        val VOLUME_BOOST_AMOUNT = floatPreferencesKey("volume_boost_amount")
        val STEREO_LEFT_GAIN = floatPreferencesKey("stereo_left_gain")
        val STEREO_RIGHT_GAIN = floatPreferencesKey("stereo_right_gain")
    }

    val settingsFlow: Flow<PlaybackSettings> = context.playbackDataStore.data.map { prefs ->
        PlaybackSettings(
            volumeBoostEnabled = prefs[Keys.VOLUME_BOOST_ENABLED] ?: false,
            volumeBoostAmount = clampVolumeBoostAmount(prefs[Keys.VOLUME_BOOST_AMOUNT] ?: 0f),
            stereoLeftGain = clampStereoGain(prefs[Keys.STEREO_LEFT_GAIN] ?: 1f),
            stereoRightGain = clampStereoGain(prefs[Keys.STEREO_RIGHT_GAIN] ?: 1f)
        )
    }

    suspend fun setVolumeBoostEnabled(enabled: Boolean) {
        context.playbackDataStore.edit { prefs ->
            prefs[Keys.VOLUME_BOOST_ENABLED] = enabled
        }
    }

    suspend fun setVolumeBoostAmount(amount: Float) {
        context.playbackDataStore.edit { prefs ->
            prefs[Keys.VOLUME_BOOST_AMOUNT] = clampVolumeBoostAmount(amount)
        }
    }

    suspend fun setStereoLeftGain(gain: Float) {
        context.playbackDataStore.edit { prefs ->
            prefs[Keys.STEREO_LEFT_GAIN] = clampStereoGain(gain)
        }
    }

    suspend fun setStereoRightGain(gain: Float) {
        context.playbackDataStore.edit { prefs ->
            prefs[Keys.STEREO_RIGHT_GAIN] = clampStereoGain(gain)
        }
    }

    suspend fun resetStereoBalance() {
        context.playbackDataStore.edit { prefs ->
            prefs[Keys.STEREO_LEFT_GAIN] = 1f
            prefs[Keys.STEREO_RIGHT_GAIN] = 1f
        }
    }
}
