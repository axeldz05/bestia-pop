package com.bestiapop.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.bestiapop.android.data.model.ColorSchemeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeHarmonizerTest {

    @Test
    fun calculateLuminance_pureBlackAndWhite() {
        val lumBlack = ThemeHarmonizer.calculateLuminance(Color.Black)
        val lumWhite = ThemeHarmonizer.calculateLuminance(Color.White)

        assertEquals(0.0f, lumBlack, 0.001f)
        assertEquals(1.0f, lumWhite, 0.001f)
    }

    @Test
    fun calculateContrastRatio_whiteAndBlackIs21() {
        val ratio = ThemeHarmonizer.calculateContrastRatio(Color.White, Color.Black)
        assertEquals(21.0f, ratio, 0.01f)
    }

    @Test
    fun calculateContrastRatio_identicalColorIs1() {
        val color = Color(0xFF9D4EDD)
        val ratio = ThemeHarmonizer.calculateContrastRatio(color, color)
        assertEquals(1.0f, ratio, 0.001f)
    }

    @Test
    fun ensureContrast_adjustsLowContrastColor() {
        val darkBg = Color(0xFF0F0C1B)
        val lowContrastPrimary = Color(0xFF2D1B4E) // Very dark purple, poor contrast against darkBg

        val initialRatio = ThemeHarmonizer.calculateContrastRatio(lowContrastPrimary, darkBg)
        assertTrue("Initial ratio should be low", initialRatio < 4.5f)

        val adjusted = ThemeHarmonizer.ensureContrast(lowContrastPrimary, darkBg, minRatio = 4.5f, isDarkTheme = true)
        val finalRatio = ThemeHarmonizer.calculateContrastRatio(adjusted, darkBg)

        assertTrue("Final ratio must meet WCAG 4.5:1, was $finalRatio", finalRatio >= 4.5f)
    }

    @Test
    fun bestOnColor_selectsWhiteOnDarkAndBlackOnLight() {
        val onDark = ThemeHarmonizer.bestOnColor(Color(0xFF101010))
        val onLight = ThemeHarmonizer.bestOnColor(Color(0xFFEEEEEE))

        assertEquals(Color.White, onDark)
        assertEquals(Color(0xFF121212), onLight)
    }

    @Test
    fun toMaterialColorScheme_generatesAllRequiredM3Containers() {
        val data = ColorSchemeData(
            primary = 0xFF9D4EDD,
            onPrimary = 0xFFFFFFFF,
            secondary = 0xFFC77DFF,
            background = 0xFF0F0C1B,
            surface = 0xFF1A162B,
            surfaceVariant = 0xFF2D1B4E,
            accent = 0xFFE0AFA0
        )

        val scheme = ThemeHarmonizer.toMaterialColorScheme(data, isDark = true)

        val primaryContrast = ThemeHarmonizer.calculateContrastRatio(scheme.primary, scheme.background)
        assertTrue("Primary on Background contrast must be >= 4.5:1, was $primaryContrast", primaryContrast >= 4.5f)

        val onPrimaryContrast = ThemeHarmonizer.calculateContrastRatio(scheme.onPrimary, scheme.primary)
        assertTrue("OnPrimary contrast must be >= 4.5:1, was $onPrimaryContrast", onPrimaryContrast >= 4.5f)

        val onSurfaceContrast = ThemeHarmonizer.calculateContrastRatio(scheme.onSurface, scheme.surface)
        assertTrue("OnSurface contrast must be >= 4.5:1, was $onSurfaceContrast", onSurfaceContrast >= 4.5f)
    }

    @Test
    fun autoHarmonizePalette_producesCohesiveColors() {
        val primary = Color(0xFF00F5D4)
        val harmonized = ThemeHarmonizer.autoHarmonizePalette(primary, isDark = true)

        val scheme = ThemeHarmonizer.toMaterialColorScheme(harmonized, isDark = true)
        val contrast = ThemeHarmonizer.calculateContrastRatio(scheme.primary, scheme.background)
        assertTrue("Harmonized primary contrast must be >= 4.5:1", contrast >= 4.5f)
    }
}
