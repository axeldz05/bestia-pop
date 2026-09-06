package com.bestiapop.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.bestiapop.android.data.model.ColorSchemeData
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Standard WCAG 2.1 and Material 3 Color Harmonizer.
 * Provides luminance calculation, contrast validation, HSL conversions,
 * and comprehensive Material 3 ColorScheme generation.
 */
object ThemeHarmonizer {

    private const val WCAG_MIN_CONTRAST_NORMAL_TEXT = 4.5f
    private const val WCAG_MIN_CONTRAST_LARGE_UI = 3.0f

    /**
     * Calculates the relative luminance of a color based on WCAG 2.1 specs.
     * Returned value is in range [0.0, 1.0].
     */
    fun calculateLuminance(color: Color): Float {
        fun linearize(channel: Float): Float {
            return if (channel <= 0.04045f) {
                channel / 12.92f
            } else {
                ((channel + 0.055f) / 1.055f).pow(2.4f)
            }
        }
        val r = linearize(color.red)
        val g = linearize(color.green)
        val b = linearize(color.blue)
        return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
    }

    /**
     * Computes the WCAG 2.1 contrast ratio between two colors.
     * Value is in range [1.0, 21.0].
     */
    fun calculateContrastRatio(colorA: Color, colorB: Color): Float {
        val lumA = calculateLuminance(colorA)
        val lumB = calculateLuminance(colorB)
        val lighter = max(lumA, lumB)
        val darker = min(lumA, lumB)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /**
     * Converts an RGB color to HSL.
     * Returns FloatArray with [hue (0..360), saturation (0..1), lightness (0..1)].
     */
    fun rgbToHsl(color: Color): FloatArray {
        val r = color.red
        val g = color.green
        val b = color.blue

        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC

        val l = (maxC + minC) / 2f

        if (delta == 0f) {
            return floatArrayOf(0f, 0f, l)
        }

        val s = if (l > 0.5f) delta / (2f - maxC - minC) else delta / (maxC + minC)

        var h = when (maxC) {
            r -> ((g - b) / delta) + (if (g < b) 6f else 0f)
            g -> ((b - r) / delta) + 2f
            else -> ((r - g) / delta) + 4f
        } * 60f

        if (h < 0f) h += 360f

        return floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    /**
     * Converts HSL values to a Compose Color.
     */
    fun hslToColor(hue: Float, saturation: Float, lightness: Float, alpha: Float = 1f): Color {
        val h = (hue % 360f + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)

        if (s == 0f) {
            return Color(l, l, l, alpha)
        }

        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q

        fun hueToRgb(t: Float): Float {
            var tc = t
            if (tc < 0f) tc += 1f
            if (tc > 1f) tc -= 1f
            return when {
                tc < 1f / 6f -> p + (q - p) * 6f * tc
                tc < 1f / 2f -> q
                tc < 2f / 3f -> p + (q - p) * (2f / 3f - tc) * 6f
                else -> p
            }
        }

        val hk = h / 360f
        val r = hueToRgb(hk + 1f / 3f)
        val g = hueToRgb(hk)
        val b = hueToRgb(hk - 1f / 3f)

        return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha)
    }

    /**
     * Adjusts the lightness of [color] until it satisfies [minRatio] contrast against [background].
     */
    fun ensureContrast(
        color: Color,
        background: Color,
        minRatio: Float = WCAG_MIN_CONTRAST_NORMAL_TEXT,
        isDarkTheme: Boolean = true
    ): Color {
        var ratio = calculateContrastRatio(color, background)
        if (ratio >= minRatio) return color

        val hsl = rgbToHsl(color)
        val h = hsl[0]
        val s = hsl[1]
        var l = hsl[2]

        val step = if (isDarkTheme) 0.03f else -0.03f
        var iterations = 0

        while (ratio < minRatio && iterations < 25) {
            l = (l + step).coerceIn(0.05f, 0.95f)
            val candidate = hslToColor(h, s, l)
            ratio = calculateContrastRatio(candidate, background)
            if (ratio >= minRatio) {
                return candidate
            }
            iterations++
        }

        // If adjusting lightness wasn't enough (e.g. saturated pure hue against mid-gray),
        // fallback to high-contrast white or dark charcoal.
        return if (isDarkTheme) Color(0xFFE8EAED) else Color(0xFF202124)
    }

    /**
     * Returns high contrast on-color (either pure white or dark charcoal)
     * that maximizes contrast against [containerColor].
     */
    fun bestOnColor(containerColor: Color): Color {
        val whiteContrast = calculateContrastRatio(Color.White, containerColor)
        val blackContrast = calculateContrastRatio(Color(0xFF121212), containerColor)
        return if (whiteContrast >= blackContrast) Color.White else Color(0xFF121212)
    }

    /**
     * Builds a comprehensive, harmonized Material 3 ColorScheme from [data].
     */
    fun toMaterialColorScheme(data: ColorSchemeData, isDark: Boolean): ColorScheme {
        val rawPrimary = Color(data.primary)
        val rawSecondary = Color(data.secondary)
        val rawAccent = Color(data.accent)
        val rawBackground = Color(data.background)
        val rawSurface = Color(data.surface)
        val rawSurfaceVariant = Color(data.surfaceVariant)

        // Ensure primary has safe contrast against background
        val primary = ensureContrast(rawPrimary, rawBackground, minRatio = 4.5f, isDarkTheme = isDark)
        val onPrimary = bestOnColor(primary)

        val secondary = ensureContrast(rawSecondary, rawBackground, minRatio = 3.5f, isDarkTheme = isDark)
        val onSecondary = bestOnColor(secondary)

        val tertiary = ensureContrast(rawAccent, rawBackground, minRatio = 3.5f, isDarkTheme = isDark)
        val onTertiary = bestOnColor(tertiary)

        val background = rawBackground
        val onBackground = if (isDark) {
            ensureContrast(Color(0xFFF1F3F4), background, minRatio = 7.0f, isDarkTheme = true)
        } else {
            ensureContrast(Color(0xFF1F1F1F), background, minRatio = 7.0f, isDarkTheme = false)
        }

        val surface = rawSurface
        val onSurface = if (isDark) {
            ensureContrast(Color(0xFFE8EAED), surface, minRatio = 7.0f, isDarkTheme = true)
        } else {
            ensureContrast(Color(0xFF202124), surface, minRatio = 7.0f, isDarkTheme = false)
        }

        val surfaceVariant = rawSurfaceVariant
        val onSurfaceVariant = if (isDark) {
            ensureContrast(Color(0xFFC4C7C5), surfaceVariant, minRatio = 4.5f, isDarkTheme = true)
        } else {
            ensureContrast(Color(0xFF444746), surfaceVariant, minRatio = 4.5f, isDarkTheme = false)
        }

        // Tonal containers
        val primaryHsl = rgbToHsl(primary)
        val primaryContainer = if (isDark) {
            hslToColor(primaryHsl[0], (primaryHsl[1] * 0.45f).coerceIn(0.15f, 0.6f), 0.22f)
        } else {
            hslToColor(primaryHsl[0], (primaryHsl[1] * 0.4f).coerceIn(0.15f, 0.5f), 0.90f)
        }
        val onPrimaryContainer = bestOnColor(primaryContainer)

        val secondaryHsl = rgbToHsl(secondary)
        val secondaryContainer = if (isDark) {
            hslToColor(secondaryHsl[0], (secondaryHsl[1] * 0.4f).coerceIn(0.12f, 0.5f), 0.24f)
        } else {
            hslToColor(secondaryHsl[0], (secondaryHsl[1] * 0.35f).coerceIn(0.12f, 0.45f), 0.92f)
        }
        val onSecondaryContainer = bestOnColor(secondaryContainer)

        val tertiaryHsl = rgbToHsl(tertiary)
        val tertiaryContainer = if (isDark) {
            hslToColor(tertiaryHsl[0], (tertiaryHsl[1] * 0.4f).coerceIn(0.12f, 0.5f), 0.22f)
        } else {
            hslToColor(tertiaryHsl[0], (tertiaryHsl[1] * 0.35f).coerceIn(0.12f, 0.45f), 0.90f)
        }
        val onTertiaryContainer = bestOnColor(tertiaryContainer)

        // Surface elevation containers
        val surfaceHsl = rgbToHsl(surface)
        val surfaceContainerLowest = if (isDark) {
            hslToColor(surfaceHsl[0], surfaceHsl[1] * 0.8f, 0.05f)
        } else {
            Color.White
        }
        val surfaceContainerLow = if (isDark) {
            hslToColor(surfaceHsl[0], surfaceHsl[1] * 0.85f, 0.09f)
        } else {
            hslToColor(surfaceHsl[0], surfaceHsl[1] * 0.5f, 0.96f)
        }
        val surfaceContainer = if (isDark) {
            hslToColor(surfaceHsl[0], surfaceHsl[1] * 0.9f, 0.12f)
        } else {
            hslToColor(surfaceHsl[0], surfaceHsl[1] * 0.6f, 0.94f)
        }
        val surfaceContainerHigh = if (isDark) {
            hslToColor(surfaceHsl[0], surfaceHsl[1] * 0.95f, 0.16f)
        } else {
            hslToColor(surfaceHsl[0], surfaceHsl[1] * 0.7f, 0.92f)
        }
        val surfaceContainerHighest = if (isDark) {
            hslToColor(surfaceHsl[0], surfaceHsl[1], 0.20f)
        } else {
            hslToColor(surfaceHsl[0], surfaceHsl[1] * 0.8f, 0.90f)
        }

        val outline = if (isDark) {
            hslToColor(surfaceHsl[0], (surfaceHsl[1] * 0.3f), 0.45f)
        } else {
            hslToColor(surfaceHsl[0], (surfaceHsl[1] * 0.3f), 0.60f)
        }
        val outlineVariant = if (isDark) {
            hslToColor(surfaceHsl[0], (surfaceHsl[1] * 0.25f), 0.28f)
        } else {
            hslToColor(surfaceHsl[0], (surfaceHsl[1] * 0.25f), 0.80f)
        }

        return if (isDark) {
            darkColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = onSecondary,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary,
                onTertiary = onTertiary,
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                surfaceContainerLowest = surfaceContainerLowest,
                surfaceContainerLow = surfaceContainerLow,
                surfaceContainer = surfaceContainer,
                surfaceContainerHigh = surfaceContainerHigh,
                surfaceContainerHighest = surfaceContainerHighest,
                outline = outline,
                outlineVariant = outlineVariant
            )
        } else {
            lightColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = onSecondary,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary,
                onTertiary = onTertiary,
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                surfaceContainerLowest = surfaceContainerLowest,
                surfaceContainerLow = surfaceContainerLow,
                surfaceContainer = surfaceContainer,
                surfaceContainerHigh = surfaceContainerHigh,
                surfaceContainerHighest = surfaceContainerHighest,
                outline = outline,
                outlineVariant = outlineVariant
            )
        }
    }

    /**
     * Automatically calculates a harmonious [ColorSchemeData] from a single [primaryColor].
     * Derives balanced background, surface, secondary, and accent colors.
     */
    fun autoHarmonizePalette(primaryColor: Color, isDark: Boolean = true): ColorSchemeData {
        val hsl = rgbToHsl(primaryColor)
        val h = hsl[0]
        val s = hsl[1]

        // Analogous or complementary tones
        val secondaryHue = (h + 30f) % 360f
        val accentHue = (h + 180f) % 360f

        val primary = hslToColor(h, s.coerceIn(0.6f, 0.95f), if (isDark) 0.65f else 0.45f)
        val secondary = hslToColor(secondaryHue, s.coerceIn(0.45f, 0.85f), if (isDark) 0.60f else 0.50f)
        val accent = hslToColor(accentHue, s.coerceIn(0.55f, 0.95f), if (isDark) 0.70f else 0.45f)

        val background: Color
        val surface: Color
        val surfaceVariant: Color

        if (isDark) {
            // Ambient dark tint
            background = hslToColor(h, (s * 0.15f).coerceIn(0.04f, 0.12f), 0.05f)
            surface = hslToColor(h, (s * 0.18f).coerceIn(0.05f, 0.15f), 0.10f)
            surfaceVariant = hslToColor(h, (s * 0.22f).coerceIn(0.07f, 0.18f), 0.15f)
        } else {
            background = hslToColor(h, (s * 0.08f).coerceIn(0.02f, 0.08f), 0.97f)
            surface = Color.White
            surfaceVariant = hslToColor(h, (s * 0.12f).coerceIn(0.03f, 0.10f), 0.92f)
        }

        return ColorSchemeData(
            primary = primary.toArgb().toLong(),
            onPrimary = bestOnColor(primary).toArgb().toLong(),
            secondary = secondary.toArgb().toLong(),
            background = background.toArgb().toLong(),
            surface = surface.toArgb().toLong(),
            surfaceVariant = surfaceVariant.toArgb().toLong(),
            accent = accent.toArgb().toLong()
        )
    }
}
