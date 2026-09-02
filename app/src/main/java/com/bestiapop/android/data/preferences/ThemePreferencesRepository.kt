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
    private val context: Context?,
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context, context.themeDataStore)
    internal constructor(dataStore: DataStore<Preferences>) : this(null, dataStore)

    private val syncPrefs = context?.getSharedPreferences("theme_settings_sync", Context.MODE_PRIVATE)

    val initialTheme: CustomTheme = run {
        val themeId = syncPrefs?.getString("selected_theme_id", null) ?: ThemePresets.MidnightDark.id
        if (themeId == "custom") {
            val customColors = ColorSchemeData(
                primary = syncPrefs?.getLong("custom_primary", ThemePresets.MidnightDark.colors.primary)
                    ?: ThemePresets.MidnightDark.colors.primary,
                onPrimary = syncPrefs?.getLong("custom_on_primary", ThemePresets.MidnightDark.colors.onPrimary)
                    ?: ThemePresets.MidnightDark.colors.onPrimary,
                secondary = syncPrefs?.getLong("custom_secondary", ThemePresets.MidnightDark.colors.secondary)
                    ?: ThemePresets.MidnightDark.colors.secondary,
                background = syncPrefs?.getLong("custom_background", ThemePresets.MidnightDark.colors.background)
                    ?: ThemePresets.MidnightDark.colors.background,
                surface = syncPrefs?.getLong("custom_surface", ThemePresets.MidnightDark.colors.surface)
                    ?: ThemePresets.MidnightDark.colors.surface,
                surfaceVariant = syncPrefs?.getLong("custom_surface_variant", ThemePresets.MidnightDark.colors.surfaceVariant)
                    ?: ThemePresets.MidnightDark.colors.surfaceVariant,
                accent = syncPrefs?.getLong("custom_accent", ThemePresets.MidnightDark.colors.accent)
                    ?: ThemePresets.MidnightDark.colors.accent
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

        val resolved = if (themeId == "custom") {
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
        syncMirror(resolved)
        resolved
    }

    private fun syncMirror(theme: CustomTheme) {
        syncPrefs?.edit()?.let { editor ->
            editor.putString("selected_theme_id", theme.id)
            if (theme.id == "custom") {
                editor.putLong("custom_primary", theme.colors.primary)
                editor.putLong("custom_on_primary", theme.colors.onPrimary)
                editor.putLong("custom_secondary", theme.colors.secondary)
                editor.putLong("custom_background", theme.colors.background)
                editor.putLong("custom_surface", theme.colors.surface)
                editor.putLong("custom_surface_variant", theme.colors.surfaceVariant)
                editor.putLong("custom_accent", theme.colors.accent)
            }
            editor.apply()
        }
    }

    suspend fun selectPreset(themeId: String) {
        syncMirror(ThemePresets.getById(themeId))
        dataStore.put(Keys.SELECTED_THEME_ID, themeId)
    }

    suspend fun saveCustomColors(colors: ColorSchemeData) {
        val customTheme = CustomTheme(
            id = "custom",
            name = "Custom Preset",
            colors = colors,
            isDark = true
        )
        syncMirror(customTheme)
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
