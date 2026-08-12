package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
    val lastRepeatMode: RepeatMode = RepeatMode.OFF,
    val clearShuffleOnManualPlay: Boolean = true,
    val clearRepeatAllOnManualPlay: Boolean = false,
    val clearRepeatOneOnManualPlay: Boolean = true,
    val clearShuffleOnSkip: Boolean = false,
    val clearRepeatOneOnSkip: Boolean = true,
    /**
     * How long a failing online track keeps being retried before the queue moves on. 0 = skip on the
     * first error (the old behaviour); anything above that re-extracts the stream and re-prepares
     * until the window closes, so a track is not dropped over one bad CDN response.
     */
    val streamSkipGraceSeconds: Int = DEFAULT_STREAM_SKIP_GRACE_SECONDS
)

const val DEFAULT_STREAM_SKIP_GRACE_SECONDS = 3
const val MAX_STREAM_SKIP_GRACE_SECONDS = 30

fun clampStreamSkipGraceSeconds(seconds: Int): Int =
    seconds.coerceIn(0, MAX_STREAM_SKIP_GRACE_SECONDS)

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

/** Pure shuffle/repeat clears after a manual play or in-app skip. */
object PlaybackModeClear {
    /** Starting radio always disables shuffle and Repeat One; Repeat All remains intentional. */
    @Suppress("UNUSED_PARAMETER")
    fun afterRadioStart(
        shuffle: Boolean,
        repeat: RepeatMode
    ): Pair<Boolean, RepeatMode> =
        false to if (repeat == RepeatMode.ONE) RepeatMode.OFF else repeat

    fun afterManualPlay(
        shuffle: Boolean,
        repeat: RepeatMode,
        settings: PlaybackSettings
    ): Pair<Boolean, RepeatMode> {
        val nextShuffle = if (settings.clearShuffleOnManualPlay) false else shuffle
        val nextRepeat = when (repeat) {
            RepeatMode.ALL -> if (settings.clearRepeatAllOnManualPlay) RepeatMode.OFF else repeat
            RepeatMode.ONE -> if (settings.clearRepeatOneOnManualPlay) RepeatMode.OFF else repeat
            RepeatMode.OFF -> RepeatMode.OFF
        }
        return nextShuffle to nextRepeat
    }

    fun afterSkip(
        shuffle: Boolean,
        repeat: RepeatMode,
        settings: PlaybackSettings
    ): Pair<Boolean, RepeatMode> {
        val nextShuffle = if (settings.clearShuffleOnSkip) false else shuffle
        val nextRepeat =
            if (settings.clearRepeatOneOnSkip && repeat == RepeatMode.ONE) RepeatMode.OFF else repeat
        return nextShuffle to nextRepeat
    }
}

fun parseRepeatModeName(name: String?): RepeatMode =
    name?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() } ?: RepeatMode.OFF

fun clampVolumeBoostAmount(amount: Float): Float = amount.coerceIn(0f, 1f)

fun clampStereoGain(gain: Float): Float = gain.coerceIn(0f, 1f)

class PlaybackPreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.playbackDataStore)

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
        val CLEAR_SHUFFLE_ON_MANUAL_PLAY = booleanPreferencesKey("clear_shuffle_on_manual_play")
        val CLEAR_REPEAT_ALL_ON_MANUAL_PLAY = booleanPreferencesKey("clear_repeat_all_on_manual_play")
        val CLEAR_REPEAT_ONE_ON_MANUAL_PLAY = booleanPreferencesKey("clear_repeat_one_on_manual_play")
        val CLEAR_SHUFFLE_ON_SKIP = booleanPreferencesKey("clear_shuffle_on_skip")
        val CLEAR_REPEAT_ONE_ON_SKIP = booleanPreferencesKey("clear_repeat_one_on_skip")
        val STREAM_SKIP_GRACE_SECONDS = intPreferencesKey("stream_skip_grace_seconds")
    }

    val settingsFlow: Flow<PlaybackSettings> = dataStore.data.map { prefs ->
        PlaybackSettings(
            volumeBoostEnabled = prefs[Keys.VOLUME_BOOST_ENABLED] ?: false,
            volumeBoostAmount = clampVolumeBoostAmount(prefs[Keys.VOLUME_BOOST_AMOUNT] ?: 0f),
            stereoLeftGain = clampStereoGain(prefs[Keys.STEREO_LEFT_GAIN] ?: 1f),
            stereoRightGain = clampStereoGain(prefs[Keys.STEREO_RIGHT_GAIN] ?: 1f),
            rememberShuffleOnLaunch = prefs[Keys.REMEMBER_SHUFFLE_ON_LAUNCH] ?: true,
            rememberRepeatOnLaunch = prefs[Keys.REMEMBER_REPEAT_ON_LAUNCH] ?: true,
            autoplayOnLaunch = prefs[Keys.AUTOPLAY_ON_LAUNCH] ?: false,
            lastShuffleEnabled = prefs[Keys.LAST_SHUFFLE_ENABLED] ?: false,
            lastRepeatMode = parseRepeatModeName(prefs[Keys.LAST_REPEAT_MODE]),
            clearShuffleOnManualPlay = prefs[Keys.CLEAR_SHUFFLE_ON_MANUAL_PLAY] ?: true,
            clearRepeatAllOnManualPlay = prefs[Keys.CLEAR_REPEAT_ALL_ON_MANUAL_PLAY] ?: false,
            clearRepeatOneOnManualPlay = prefs[Keys.CLEAR_REPEAT_ONE_ON_MANUAL_PLAY] ?: true,
            clearShuffleOnSkip = prefs[Keys.CLEAR_SHUFFLE_ON_SKIP] ?: false,
            clearRepeatOneOnSkip = prefs[Keys.CLEAR_REPEAT_ONE_ON_SKIP] ?: true,
            streamSkipGraceSeconds = clampStreamSkipGraceSeconds(
                prefs[Keys.STREAM_SKIP_GRACE_SECONDS] ?: DEFAULT_STREAM_SKIP_GRACE_SECONDS
            )
        )
    }

    suspend fun setVolumeBoostEnabled(enabled: Boolean) {
        dataStore.put(Keys.VOLUME_BOOST_ENABLED, enabled)
    }

    suspend fun setVolumeBoostAmount(amount: Float) {
        dataStore.put(Keys.VOLUME_BOOST_AMOUNT, clampVolumeBoostAmount(amount))
    }

    suspend fun setStereoLeftGain(gain: Float) {
        dataStore.put(Keys.STEREO_LEFT_GAIN, clampStereoGain(gain))
    }

    suspend fun setStereoRightGain(gain: Float) {
        dataStore.put(Keys.STEREO_RIGHT_GAIN, clampStereoGain(gain))
    }

    suspend fun resetStereoBalance() {
        dataStore.edit { prefs ->
            prefs[Keys.STEREO_LEFT_GAIN] = 1f
            prefs[Keys.STEREO_RIGHT_GAIN] = 1f
        }
    }

    suspend fun setRememberShuffleOnLaunch(enabled: Boolean) {
        dataStore.put(Keys.REMEMBER_SHUFFLE_ON_LAUNCH, enabled)
    }

    suspend fun setRememberRepeatOnLaunch(enabled: Boolean) {
        dataStore.put(Keys.REMEMBER_REPEAT_ON_LAUNCH, enabled)
    }

    suspend fun setAutoplayOnLaunch(enabled: Boolean) {
        dataStore.put(Keys.AUTOPLAY_ON_LAUNCH, enabled)
    }

    suspend fun setLastShuffleEnabled(enabled: Boolean) {
        dataStore.put(Keys.LAST_SHUFFLE_ENABLED, enabled)
    }

    suspend fun setLastRepeatMode(mode: RepeatMode) {
        dataStore.put(Keys.LAST_REPEAT_MODE, mode.name)
    }

    suspend fun setClearShuffleOnManualPlay(enabled: Boolean) {
        dataStore.put(Keys.CLEAR_SHUFFLE_ON_MANUAL_PLAY, enabled)
    }

    suspend fun setClearRepeatAllOnManualPlay(enabled: Boolean) {
        dataStore.put(Keys.CLEAR_REPEAT_ALL_ON_MANUAL_PLAY, enabled)
    }

    suspend fun setClearRepeatOneOnManualPlay(enabled: Boolean) {
        dataStore.put(Keys.CLEAR_REPEAT_ONE_ON_MANUAL_PLAY, enabled)
    }

    suspend fun setClearShuffleOnSkip(enabled: Boolean) {
        dataStore.put(Keys.CLEAR_SHUFFLE_ON_SKIP, enabled)
    }

    suspend fun setClearRepeatOneOnSkip(enabled: Boolean) {
        dataStore.put(Keys.CLEAR_REPEAT_ONE_ON_SKIP, enabled)
    }

    suspend fun setStreamSkipGraceSeconds(seconds: Int) {
        dataStore.put(
            Keys.STREAM_SKIP_GRACE_SECONDS,
            clampStreamSkipGraceSeconds(seconds)
        )
    }
}
