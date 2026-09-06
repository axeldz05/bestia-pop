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
}
