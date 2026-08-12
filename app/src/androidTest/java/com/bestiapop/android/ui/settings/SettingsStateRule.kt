package com.bestiapop.android.ui.settings

import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.data.model.CustomTheme
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.ThemePreferencesRepository
import com.bestiapop.android.ui.theme.ThemePresets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

internal class SettingsStateRule : ExternalResource() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val themePreferences = ThemePreferencesRepository(context)
    private val playbackPreferences = PlaybackPreferencesRepository(context)
    private lateinit var originalTheme: CustomTheme
    private lateinit var originalPlayback: PlaybackSettings

    override fun before() = runBlocking {
        originalTheme = themePreferences.selectedThemeFlow.first()
        originalPlayback = playbackPreferences.settingsFlow.first()
        themePreferences.selectPreset(ThemePresets.MidnightDark.id)
        writePlayback(
            PlaybackSettings(
                volumeBoostAmount = TEST_BOOST_AMOUNT
            )
        )
    }

    override fun after() = runBlocking {
        if (originalTheme.id == "custom") {
            themePreferences.saveCustomColors(originalTheme.colors)
        } else {
            themePreferences.selectPreset(originalTheme.id)
        }
        writePlayback(originalPlayback)
    }

    private suspend fun writePlayback(settings: PlaybackSettings) {
        playbackPreferences.setVolumeBoostEnabled(settings.volumeBoostEnabled)
        playbackPreferences.setVolumeBoostAmount(settings.volumeBoostAmount)
        playbackPreferences.setStereoLeftGain(settings.stereoLeftGain)
        playbackPreferences.setStereoRightGain(settings.stereoRightGain)
        playbackPreferences.setRememberShuffleOnLaunch(settings.rememberShuffleOnLaunch)
        playbackPreferences.setRememberRepeatOnLaunch(settings.rememberRepeatOnLaunch)
        playbackPreferences.setAutoplayOnLaunch(settings.autoplayOnLaunch)
        playbackPreferences.setLastShuffleEnabled(settings.lastShuffleEnabled)
        playbackPreferences.setLastRepeatMode(settings.lastRepeatMode)
        playbackPreferences.setClearShuffleOnManualPlay(settings.clearShuffleOnManualPlay)
        playbackPreferences.setClearRepeatAllOnManualPlay(settings.clearRepeatAllOnManualPlay)
        playbackPreferences.setClearRepeatOneOnManualPlay(settings.clearRepeatOneOnManualPlay)
        playbackPreferences.setClearShuffleOnSkip(settings.clearShuffleOnSkip)
        playbackPreferences.setClearRepeatOneOnSkip(settings.clearRepeatOneOnSkip)
        playbackPreferences.setStreamSkipGraceSeconds(settings.streamSkipGraceSeconds)
    }

    companion object {
        const val TEST_BOOST_AMOUNT = 0.5f
    }
}
