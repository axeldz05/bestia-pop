package com.bestiapop.android.ui.state

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.TemporaryPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class AudioVolumeCoordinatorTest {

    @Test
    fun setVolume_whenBoostDisabled_clampsToMax1() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val storage = TemporaryPreferencesDataStore(context, "audio-volume-test-1")
        val preferences = PlaybackPreferencesRepository(storage.dataStore)

        var boostEnabled = false
        var currentSettings = PlaybackSettings(volumeBoostEnabled = false, volumeBoostAmount = 0f)

        val coordinator = AudioVolumeCoordinator(
            audioManager = audioManager,
            playbackPreferences = preferences,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isBoostPrefEnabled = { boostEnabled },
            getPlaybackSettings = { currentSettings }
        )

        coordinator.setVolume(1.5f)
        assertEquals(1.0f, coordinator.volumeLevel.value, 0.01f)
    }

    @Test
    fun setVolume_whenBoostEnabled_allowsBoostUpTo2() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val storage = TemporaryPreferencesDataStore(context, "audio-volume-test-2")
        val preferences = PlaybackPreferencesRepository(storage.dataStore)

        var boostEnabled = true
        var currentSettings = PlaybackSettings(volumeBoostEnabled = true, volumeBoostAmount = 0.5f)

        val coordinator = AudioVolumeCoordinator(
            audioManager = audioManager,
            playbackPreferences = preferences,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isBoostPrefEnabled = { boostEnabled },
            getPlaybackSettings = { currentSettings }
        )

        coordinator.setVolume(1.5f)
        assertEquals(1.5f, coordinator.volumeLevel.value, 0.01f)
        assertTrue(coordinator.isVolumeBoostActive())
    }

    @Test
    fun handleVolumeUp_whenSystemVolumeAtMax_incrementsBoost() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)

        val storage = TemporaryPreferencesDataStore(context, "audio-volume-test-3")
        val preferences = PlaybackPreferencesRepository(storage.dataStore)

        var currentSettings = PlaybackSettings(volumeBoostEnabled = true, volumeBoostAmount = 0.2f)

        val coordinator = AudioVolumeCoordinator(
            audioManager = audioManager,
            playbackPreferences = preferences,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isBoostPrefEnabled = { true },
            getPlaybackSettings = { currentSettings }
        )

        val handled = coordinator.handleVolumeUp()
        assertTrue(handled)
        assertEquals(1.3f, coordinator.volumeLevel.value, 0.01f)
        assertTrue(coordinator.volumeBoostHudVisible.value)
    }

    @Test
    fun handleVolumeDown_whenBoostActive_decrementsBoost() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)

        val storage = TemporaryPreferencesDataStore(context, "audio-volume-test-4")
        val preferences = PlaybackPreferencesRepository(storage.dataStore)

        var currentSettings = PlaybackSettings(volumeBoostEnabled = true, volumeBoostAmount = 0.3f)

        val coordinator = AudioVolumeCoordinator(
            audioManager = audioManager,
            playbackPreferences = preferences,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isBoostPrefEnabled = { true },
            getPlaybackSettings = { currentSettings }
        )

        val handled = coordinator.handleVolumeDown()
        assertTrue(handled)
        assertEquals(1.2f, coordinator.volumeLevel.value, 0.01f)
        assertTrue(coordinator.consumeVolumeDownUpAction())
        assertFalse(coordinator.consumeVolumeDownUpAction())
    }

    @Test
    fun restoreVolumeBoost_restoresLevelCorrectly() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val storage = TemporaryPreferencesDataStore(context, "audio-volume-test-5")
        val preferences = PlaybackPreferencesRepository(storage.dataStore)

        val coordinator = AudioVolumeCoordinator(
            audioManager = audioManager,
            playbackPreferences = preferences,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isBoostPrefEnabled = { true },
            getPlaybackSettings = { PlaybackSettings() }
        )

        val settings = PlaybackSettings(volumeBoostEnabled = true, volumeBoostAmount = 0.4f)
        coordinator.restoreVolumeBoost(settings)
        assertEquals(1.4f, coordinator.volumeLevel.value, 0.01f)
    }
}
