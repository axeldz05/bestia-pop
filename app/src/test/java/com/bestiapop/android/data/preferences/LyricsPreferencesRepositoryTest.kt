package com.bestiapop.android.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
class LyricsPreferencesRepositoryTest {

    @Test
    fun lyricsSettings_survivesColdStart() = runTest {
        val storage = TemporaryPreferencesDataStore(
            ApplicationProvider.getApplicationContext(),
            "lyrics-preferences"
        )
        try {
            val repository = LyricsPreferencesRepository(storage.dataStore)
            val initial = repository.settingsFlow.first()
            assertEquals(true, initial.phoneticGuideEnabled)
            assertEquals(JapanesePhoneticMode.ROMAJI, initial.japanesePhoneticMode)
            assertEquals(true, initial.askBeforeGoogleTranslate)

            repository.setPhoneticGuideEnabled(false)
            repository.setJapanesePhoneticMode(JapanesePhoneticMode.HIRAGANA)
            repository.setAskBeforeGoogleTranslate(false)

            val updated = repository.settingsFlow.first()
            val expected = LyricsSettings(
                phoneticGuideEnabled = false,
                japanesePhoneticMode = JapanesePhoneticMode.HIRAGANA,
                askBeforeGoogleTranslate = false
            )
            assertEquals(expected, updated)

            storage.restart()

            val restored = LyricsPreferencesRepository(storage.dataStore)
                .settingsFlow
                .first()
            assertEquals(expected, restored)
        } finally {
            storage.close()
        }
    }
}
