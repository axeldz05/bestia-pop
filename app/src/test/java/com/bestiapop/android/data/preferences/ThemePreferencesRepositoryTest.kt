package com.bestiapop.android.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.model.ColorSchemeData
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.TemporaryPreferencesDataStore
import com.bestiapop.android.ui.theme.ThemePresets
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
class ThemePreferencesRepositoryTest {

    @Test
    fun selectedPreset_reappearsWithColorsAndLightModeAfterColdStart() = runTest {
        val storage = temporaryDataStore()
        try {
            val repository = ThemePreferencesRepository(storage.dataStore)
            repository.selectPreset(ThemePresets.CleanLight.id)
            assertEquals(ThemePresets.CleanLight, repository.selectedThemeFlow.first())

            storage.restart()

            val restored = ThemePreferencesRepository(storage.dataStore)
                .selectedThemeFlow
                .first()
            assertEquals(ThemePresets.CleanLight.id, restored.id)
            assertEquals(ThemePresets.CleanLight.colors, restored.colors)
            assertEquals(false, restored.isDark)
        } finally {
            storage.close()
        }
    }

    @Test
    fun customTheme_editsAreEmittedAndReappearAfterColdStart() = runTest {
        val storage = temporaryDataStore()
        try {
            val colors = ColorSchemeData(
                primary = 0xFF010203,
                onPrimary = 0xFFFAFAFA,
                secondary = 0xFF112233,
                background = 0xFF040506,
                surface = 0xFF070809,
                surfaceVariant = 0xFF0A0B0C,
                accent = 0xFF445566
            )
            val repository = ThemePreferencesRepository(storage.dataStore)
            repository.saveCustomColors(colors)
            assertEquals(colors, repository.selectedThemeFlow.first().colors)

            storage.restart()

            val restored = ThemePreferencesRepository(storage.dataStore)
                .selectedThemeFlow
                .first()
            assertEquals("custom", restored.id)
            assertEquals(colors, restored.colors)
            assertEquals(true, restored.isDark)
        } finally {
            storage.close()
        }
    }

    private fun temporaryDataStore() = TemporaryPreferencesDataStore(
        ApplicationProvider.getApplicationContext(),
        "theme-preferences"
    )
}
