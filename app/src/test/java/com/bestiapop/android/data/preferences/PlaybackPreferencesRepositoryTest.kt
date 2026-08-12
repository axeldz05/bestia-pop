package com.bestiapop.android.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.TemporaryPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class PlaybackPreferencesRepositoryTest {

    @Test
    fun soundModesAndGrace_areEmittedAndRestoredTogetherAfterColdStart() = runTest {
        val storage = TemporaryPreferencesDataStore(
            ApplicationProvider.getApplicationContext(),
            "playback-preferences"
        )
        try {
            val repository = PlaybackPreferencesRepository(storage.dataStore)
            repository.setVolumeBoostEnabled(true)
            repository.setVolumeBoostAmount(0.65f)
            repository.setStereoLeftGain(0.25f)
            repository.setStereoRightGain(0.8f)
            repository.setRememberShuffleOnLaunch(false)
            repository.setRememberRepeatOnLaunch(false)
            repository.setAutoplayOnLaunch(true)
            repository.setLastShuffleEnabled(true)
            repository.setLastRepeatMode(RepeatMode.ALL)
            repository.setClearShuffleOnManualPlay(false)
            repository.setClearRepeatAllOnManualPlay(true)
            repository.setClearRepeatOneOnManualPlay(false)
            repository.setClearShuffleOnSkip(true)
            repository.setClearRepeatOneOnSkip(false)
            repository.setStreamSkipGraceSeconds(17)

            val expected = PlaybackSettings(
                volumeBoostEnabled = true,
                volumeBoostAmount = 0.65f,
                stereoLeftGain = 0.25f,
                stereoRightGain = 0.8f,
                rememberShuffleOnLaunch = false,
                rememberRepeatOnLaunch = false,
                autoplayOnLaunch = true,
                lastShuffleEnabled = true,
                lastRepeatMode = RepeatMode.ALL,
                clearShuffleOnManualPlay = false,
                clearRepeatAllOnManualPlay = true,
                clearRepeatOneOnManualPlay = false,
                clearShuffleOnSkip = true,
                clearRepeatOneOnSkip = false,
                streamSkipGraceSeconds = 17
            )
            assertEquals(expected, repository.settingsFlow.first())

            storage.restart()

            val restored = PlaybackPreferencesRepository(storage.dataStore)
                .settingsFlow
                .first()
            assertEquals(expected, restored)
        } finally {
            storage.close()
        }
    }
}
