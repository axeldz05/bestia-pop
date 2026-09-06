package com.bestiapop.android.ui.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import com.bestiapop.android.testutil.MediumTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class DynamicThemeEngineTest {

    @Test
    fun deriveThemeFromBitmap_vibrantImageProducesConsistentTheme() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = AndroidColor.rgb(0, 245, 212) // Vibrant cyan
        }
        canvas.drawRect(0f, 0f, 64f, 64f, paint)

        val theme = DynamicThemeEngine.deriveThemeFromBitmap(bitmap, "mock://cyan", isDark = true)

        assertEquals(ThemePresets.DYNAMIC_THEME_ID, theme.id)
        val primaryColor = Color(theme.colors.primary)
        val bgColor = Color(theme.colors.background)

        val contrast = ThemeHarmonizer.calculateContrastRatio(primaryColor, bgColor)
        assertTrue("Primary contrast against background must be >= 4.5:1, was $contrast", contrast >= 4.5f)
    }

    @Test
    fun deriveThemeFromBitmap_monochromeImageTriggersSafeCongruentFallback() {
        // Completely grayscale bitmap (e.g. black and white album art)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = AndroidColor.rgb(128, 128, 128)
        }
        canvas.drawRect(0f, 0f, 64f, 64f, paint)

        val theme = DynamicThemeEngine.deriveThemeFromBitmap(bitmap, "mock://grayscale", isDark = true)

        val primaryColor = Color(theme.colors.primary)
        val hsl = ThemeHarmonizer.rgbToHsl(primaryColor)

        // The safe congruent fallback must inject a lively, saturated hue rather than dull gray
        assertTrue("Safe fallback must have sufficient saturation (>= 0.4), was ${hsl[1]}", hsl[1] >= 0.4f)

        val bgColor = Color(theme.colors.background)
        val contrast = ThemeHarmonizer.calculateContrastRatio(primaryColor, bgColor)
        assertTrue("Safe fallback contrast must be >= 4.5:1, was $contrast", contrast >= 4.5f)
    }

    @Test
    fun extractDynamicTheme_blankOrNullUriReturnsProvidedFallback() = kotlinx.coroutines.test.runTest {
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val customFallback = com.bestiapop.android.data.model.CustomTheme(
            id = ThemePresets.DYNAMIC_THEME_ID,
            name = "Custom Fallback",
            colors = com.bestiapop.android.data.model.ColorSchemeData(
                primary = 0xFF123456,
                onPrimary = 0xFFFFFFFF,
                secondary = 0xFF654321,
                background = 0xFF111111,
                surface = 0xFF222222,
                surfaceVariant = 0xFF333333,
                accent = 0xFFAABBCC
            ),
            isDark = true
        )

        val themeForNull = DynamicThemeEngine.extractDynamicTheme(
            context = context,
            artworkUri = null,
            fallback = customFallback
        )
        assertEquals(customFallback, themeForNull)

        val themeForBlank = DynamicThemeEngine.extractDynamicTheme(
            context = context,
            artworkUri = "   ",
            fallback = customFallback
        )
        assertEquals(customFallback, themeForBlank)
    }

    @Test
    fun resolveNextTheme_retainsCurrentStateWhenUriIsNullEmptyOrUnchanged() = kotlinx.coroutines.test.runTest {
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val customFallback = com.bestiapop.android.data.model.CustomTheme(
            id = ThemePresets.DYNAMIC_THEME_ID,
            name = "Saved Dynamic",
            colors = com.bestiapop.android.data.model.ColorSchemeData(
                primary = 0xFF990000,
                onPrimary = 0xFFFFFFFF,
                secondary = 0xFFCC0000,
                background = 0xFF050505,
                surface = 0xFF151515,
                surfaceVariant = 0xFF252525,
                accent = 0xFFFF0055
            ),
            isDark = true
        )
        val initialState = DynamicThemeEngine.DynamicThemeState(
            theme = customFallback,
            artworkUri = "mock://initial_album"
        )

        // Null uri (e.g. queue cleared) -> retains current state
        val stateAfterNull = DynamicThemeEngine.resolveNextTheme(
            context = context,
            artworkUri = null,
            currentState = initialState
        )
        assertEquals(initialState, stateAfterNull)

        // Same uri -> retains current state
        val stateAfterSame = DynamicThemeEngine.resolveNextTheme(
            context = context,
            artworkUri = "mock://initial_album",
            currentState = initialState
        )
        assertEquals(initialState, stateAfterSame)
    }

    @Test
    fun dynamicThemeFlow_preservesThemeWhenQueueIsCleared() = kotlinx.coroutines.test.runTest {
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val customColors = com.bestiapop.android.data.model.ColorSchemeData(
            primary = 0xFF445566,
            onPrimary = 0xFFFFFFFF,
            secondary = 0xFF778899,
            background = 0xFF010101,
            surface = 0xFF111111,
            surfaceVariant = 0xFF212121,
            accent = 0xFF00EEDD
        )
        val initialTheme = com.bestiapop.android.data.model.CustomTheme(
            id = ThemePresets.DYNAMIC_THEME_ID,
            name = "Initial Dynamic",
            colors = customColors,
            isDark = true
        )
        val initialState = DynamicThemeEngine.DynamicThemeState(
            theme = initialTheme,
            artworkUri = "mock://song1"
        )

        // Simulate playback events: queue cleared (null), empty item (blank), same song (mock://song1)
        val artworkUriFlow = kotlinx.coroutines.flow.flowOf(null, "", "mock://song1", null)
        val collectedThemes = mutableListOf<com.bestiapop.android.data.model.CustomTheme>()

        DynamicThemeEngine.dynamicThemeFlow(
            context = context,
            artworkUriFlow = artworkUriFlow,
            initialState = initialState
        ).collect { theme ->
            collectedThemes.add(theme)
        }

        // All emissions must preserve the initialTheme's colors and never revert to fallback
        assertTrue(collectedThemes.isNotEmpty())
        for (theme in collectedThemes) {
            assertEquals(initialTheme.colors, theme.colors)
        }
    }
}
