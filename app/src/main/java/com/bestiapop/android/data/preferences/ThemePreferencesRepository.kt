package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bestiapop.android.data.model.ColorSchemeData
import com.bestiapop.android.data.model.CustomTheme
import com.bestiapop.android.ui.theme.ThemePresets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "theme_settings"
)

class ThemePreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.themeDataStore)

    private object Keys {
        val SELECTED_THEME_ID = stringPreferencesKey("selected_theme_id")
        val CUSTOM_PRIMARY = longPreferencesKey("custom_primary")
        val CUSTOM_ON_PRIMARY = longPreferencesKey("custom_on_primary")
        val CUSTOM_SECONDARY = longPreferencesKey("custom_secondary")
        val CUSTOM_BACKGROUND = longPreferencesKey("custom_background")
        val CUSTOM_SURFACE = longPreferencesKey("custom_surface")
        val CUSTOM_SURFACE_VARIANT = longPreferencesKey("custom_surface_variant")
        val CUSTOM_ACCENT = longPreferencesKey("custom_accent")
    }

    val selectedThemeFlow: Flow<CustomTheme> = dataStore.data.map { prefs ->
        val themeId = prefs[Keys.SELECTED_THEME_ID] ?: ThemePresets.MidnightDark.id

        if (themeId == "custom") {
            val customColors = ColorSchemeData(
                primary = prefs[Keys.CUSTOM_PRIMARY] ?: ThemePresets.MidnightDark.colors.primary,
                onPrimary = prefs[Keys.CUSTOM_ON_PRIMARY] ?: ThemePresets.MidnightDark.colors.onPrimary,
                secondary = prefs[Keys.CUSTOM_SECONDARY] ?: ThemePresets.MidnightDark.colors.secondary,
                background = prefs[Keys.CUSTOM_BACKGROUND] ?: ThemePresets.MidnightDark.colors.background,
                surface = prefs[Keys.CUSTOM_SURFACE] ?: ThemePresets.MidnightDark.colors.surface,
                surfaceVariant = prefs[Keys.CUSTOM_SURFACE_VARIANT] ?: ThemePresets.MidnightDark.colors.surfaceVariant,
                accent = prefs[Keys.CUSTOM_ACCENT] ?: ThemePresets.MidnightDark.colors.accent
            )
            CustomTheme(
                id = "custom",
                name = "Custom Preset",
                colors = customColors,
                isDark = true
            )
        } else {
            ThemePresets.getById(themeId)
        }
    }

    suspend fun selectPreset(themeId: String) {
        dataStore.put(Keys.SELECTED_THEME_ID, themeId)
    }

    suspend fun saveCustomColors(colors: ColorSchemeData) {
        dataStore.edit { prefs ->
            prefs[Keys.SELECTED_THEME_ID] = "custom"
            prefs[Keys.CUSTOM_PRIMARY] = colors.primary
            prefs[Keys.CUSTOM_ON_PRIMARY] = colors.onPrimary
            prefs[Keys.CUSTOM_SECONDARY] = colors.secondary
            prefs[Keys.CUSTOM_BACKGROUND] = colors.background
            prefs[Keys.CUSTOM_SURFACE] = colors.surface
            prefs[Keys.CUSTOM_SURFACE_VARIANT] = colors.surfaceVariant
            prefs[Keys.CUSTOM_ACCENT] = colors.accent
        }
    }
}
