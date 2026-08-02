package com.bestiapop.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.bestiapop.android.data.model.ColorSchemeData
import com.bestiapop.android.data.model.CustomTheme

object ThemePresets {

    val MidnightDark = CustomTheme(
        id = "midnight",
        name = "Midnight Dark",
        colors = ColorSchemeData(
            primary = 0xFF9D4EDD,
            onPrimary = 0xFFFFFFFF,
            secondary = 0xFFC77DFF,
            background = 0xFF0F0C1B,
            surface = 0xFF1A162B,
            surfaceVariant = 0xFF2D1B4E,
            accent = 0xFFE0AFA0
        ),
        isDark = true
    )

    val AmoledBlack = CustomTheme(
        id = "amoled",
        name = "AMOLED Black",
        colors = ColorSchemeData(
            primary = 0xFFBB86FC,
            onPrimary = 0xFF000000,
            secondary = 0xFF03DAC6,
            background = 0xFF000000,
            surface = 0xFF121212,
            surfaceVariant = 0xFF1E1E1E,
            accent = 0xFFCF6679
        ),
        isDark = true
    )

    val SunsetGold = CustomTheme(
        id = "sunset",
        name = "Sunset Gold",
        colors = ColorSchemeData(
            primary = 0xFFFF9E00,
            onPrimary = 0xFF1D0047,
            secondary = 0xFFFF6B6B,
            background = 0xFF1A0B2E,
            surface = 0xFF281347,
            surfaceVariant = 0xFF3D1E6D,
            accent = 0xFFFFD166
        ),
        isDark = true
    )

    val CyberpunkNeon = CustomTheme(
        id = "cyberpunk",
        name = "Cyberpunk Neon",
        colors = ColorSchemeData(
            primary = 0xFF00F5D4,
            onPrimary = 0xFF050505,
            secondary = 0xFF7B2CBF,
            background = 0xFF05050A,
            surface = 0xFF0D0D1A,
            surfaceVariant = 0xFF1A1A33,
            accent = 0xFFF72585
        ),
        isDark = true
    )

    val CleanLight = CustomTheme(
        id = "clean_light",
        name = "Clean Light",
        colors = ColorSchemeData(
            primary = 0xFF6200EE,
            onPrimary = 0xFFFFFFFF,
            secondary = 0xFF03DAC6,
            background = 0xFFF6F8FA,
            surface = 0xFFFFFFFF,
            surfaceVariant = 0xFFEAEAEA,
            accent = 0xFF3700B3
        ),
        isDark = false
    )

    val allPresets = listOf(MidnightDark, AmoledBlack, SunsetGold, CyberpunkNeon, CleanLight)

    fun getById(id: String): CustomTheme {
        return allPresets.find { it.id == id } ?: MidnightDark
    }

    fun CustomTheme.toMaterialColorScheme(): ColorScheme {
        val p = Color(colors.primary)
        val onP = Color(colors.onPrimary)
        val sec = Color(colors.secondary)
        val bg = Color(colors.background)
        val surf = Color(colors.surface)
        val surfVar = Color(colors.surfaceVariant)

        return if (isDark) {
            darkColorScheme(
                primary = p,
                onPrimary = onP,
                secondary = sec,
                background = bg,
                surface = surf,
                surfaceVariant = surfVar,
                onBackground = Color(0xFFF0F0F0),
                onSurface = Color(0xFFE8E8E8)
            )
        } else {
            lightColorScheme(
                primary = p,
                onPrimary = onP,
                secondary = sec,
                background = bg,
                surface = surf,
                surfaceVariant = surfVar,
                onBackground = Color(0xFF1C1C1E),
                onSurface = Color(0xFF2C2C2E)
            )
        }
    }
}
