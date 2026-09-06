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
        val themeId = syncPrefs?.getString("selected_theme_id", null) ?: ThemePresets.DYNAMIC_THEME_ID
        when (themeId) {
            "custom" -> {
                val customColors = readColors(syncPrefs, "custom") ?: ThemePresets.MidnightDark.colors
                CustomTheme(
                    id = "custom",
                    name = "Custom Preset",
                    colors = customColors,
                    isDark = true
                )
            }
            ThemePresets.DYNAMIC_THEME_ID -> {
                val dynamicColors = readColors(syncPrefs, "dynamic") ?: ThemePresets.MidnightDark.colors
                CustomTheme(
                    id = ThemePresets.DYNAMIC_THEME_ID,
                    name = "Dinámico por Canción",
                    colors = dynamicColors,
                    isDark = true
                )
            }
            else -> ThemePresets.getById(themeId)
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

        val DYNAMIC_PRIMARY = longPreferencesKey("dynamic_primary")
        val DYNAMIC_ON_PRIMARY = longPreferencesKey("dynamic_on_primary")
        val DYNAMIC_SECONDARY = longPreferencesKey("dynamic_secondary")
        val DYNAMIC_BACKGROUND = longPreferencesKey("dynamic_background")
        val DYNAMIC_SURFACE = longPreferencesKey("dynamic_surface")
        val DYNAMIC_SURFACE_VARIANT = longPreferencesKey("dynamic_surface_variant")
        val DYNAMIC_ACCENT = longPreferencesKey("dynamic_accent")
        val DYNAMIC_ARTWORK_URI = stringPreferencesKey("dynamic_artwork_uri")
    }

    val lastDynamicArtworkUri: String?
        get() = syncPrefs?.getString("dynamic_artwork_uri", null)

    val dynamicArtworkUriFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.DYNAMIC_ARTWORK_URI]
    }

    val selectedThemeFlow: Flow<CustomTheme> = dataStore.data.map { prefs ->
        val themeId = prefs[Keys.SELECTED_THEME_ID] ?: ThemePresets.DYNAMIC_THEME_ID

        val resolved = when (themeId) {
            "custom" -> {
                val customColors = readColors(prefs, "custom") ?: ThemePresets.MidnightDark.colors
                CustomTheme(
                    id = "custom",
                    name = "Custom Preset",
                    colors = customColors,
                    isDark = true
                )
            }
            ThemePresets.DYNAMIC_THEME_ID -> {
                val dynamicColors = readColors(prefs, "dynamic") ?: ThemePresets.MidnightDark.colors
                CustomTheme(
                    id = ThemePresets.DYNAMIC_THEME_ID,
                    name = "Dinámico por Canción",
                    colors = dynamicColors,
                    isDark = true
                )
            }
            else -> ThemePresets.getById(themeId)
        }
        syncMirror(resolved)
        resolved
    }

    private fun readColors(prefs: Preferences, prefix: String): ColorSchemeData? {
        val primary = prefs[longPreferencesKey("${prefix}_primary")] ?: return null
        val onPrimary = prefs[longPreferencesKey("${prefix}_on_primary")] ?: return null
        val secondary = prefs[longPreferencesKey("${prefix}_secondary")] ?: return null
        val background = prefs[longPreferencesKey("${prefix}_background")] ?: return null
        val surface = prefs[longPreferencesKey("${prefix}_surface")] ?: return null
        val surfaceVariant = prefs[longPreferencesKey("${prefix}_surface_variant")] ?: return null
        val accent = prefs[longPreferencesKey("${prefix}_accent")] ?: return null
        return ColorSchemeData(
            primary = primary,
            onPrimary = onPrimary,
            secondary = secondary,
            background = background,
            surface = surface,
            surfaceVariant = surfaceVariant,
            accent = accent
        )
    }

    private fun readColors(sync: android.content.SharedPreferences?, prefix: String): ColorSchemeData? {
        if (sync == null || !sync.contains("${prefix}_primary")) return null
        return ColorSchemeData(
            primary = sync.getLong("${prefix}_primary", ThemePresets.MidnightDark.colors.primary),
            onPrimary = sync.getLong("${prefix}_on_primary", ThemePresets.MidnightDark.colors.onPrimary),
            secondary = sync.getLong("${prefix}_secondary", ThemePresets.MidnightDark.colors.secondary),
            background = sync.getLong("${prefix}_background", ThemePresets.MidnightDark.colors.background),
            surface = sync.getLong("${prefix}_surface", ThemePresets.MidnightDark.colors.surface),
            surfaceVariant = sync.getLong("${prefix}_surface_variant", ThemePresets.MidnightDark.colors.surfaceVariant),
            accent = sync.getLong("${prefix}_accent", ThemePresets.MidnightDark.colors.accent)
        )
    }

    private fun putColors(editor: android.content.SharedPreferences.Editor, prefix: String, colors: ColorSchemeData) {
        editor.putLong("${prefix}_primary", colors.primary)
        editor.putLong("${prefix}_on_primary", colors.onPrimary)
        editor.putLong("${prefix}_secondary", colors.secondary)
        editor.putLong("${prefix}_background", colors.background)
        editor.putLong("${prefix}_surface", colors.surface)
        editor.putLong("${prefix}_surface_variant", colors.surfaceVariant)
        editor.putLong("${prefix}_accent", colors.accent)
    }

    private fun putColors(prefs: androidx.datastore.preferences.core.MutablePreferences, prefix: String, colors: ColorSchemeData) {
        prefs[longPreferencesKey("${prefix}_primary")] = colors.primary
        prefs[longPreferencesKey("${prefix}_on_primary")] = colors.onPrimary
        prefs[longPreferencesKey("${prefix}_secondary")] = colors.secondary
        prefs[longPreferencesKey("${prefix}_background")] = colors.background
        prefs[longPreferencesKey("${prefix}_surface")] = colors.surface
        prefs[longPreferencesKey("${prefix}_surface_variant")] = colors.surfaceVariant
        prefs[longPreferencesKey("${prefix}_accent")] = colors.accent
    }

    private fun syncMirror(theme: CustomTheme) {
        syncPrefs?.edit()?.let { editor ->
            editor.putString("selected_theme_id", theme.id)
            if (theme.id == "custom") {
                putColors(editor, "custom", theme.colors)
            } else if (theme.id == ThemePresets.DYNAMIC_THEME_ID) {
                putColors(editor, "dynamic", theme.colors)
            }
            editor.apply()
        }
    }

    suspend fun selectPreset(themeId: String) {
        val theme = if (themeId == ThemePresets.DYNAMIC_THEME_ID) {
            val savedDynamic = readColors(syncPrefs, "dynamic")
            if (savedDynamic != null) {
                CustomTheme(
                    id = ThemePresets.DYNAMIC_THEME_ID,
                    name = "Dinámico por Canción",
                    colors = savedDynamic,
                    isDark = true
                )
            } else {
                ThemePresets.getById(themeId)
            }
        } else {
            ThemePresets.getById(themeId)
        }
        syncMirror(theme)
        dataStore.put(Keys.SELECTED_THEME_ID, themeId)
    }

    suspend fun enableDynamicTheme() {
        selectPreset(ThemePresets.DYNAMIC_THEME_ID)
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
            putColors(prefs, "custom", colors)
        }
    }

    suspend fun saveDynamicTheme(theme: CustomTheme, artworkUri: String? = null) {
        syncPrefs?.edit()?.let { editor ->
            putColors(editor, "dynamic", theme.colors)
            artworkUri?.let { editor.putString("dynamic_artwork_uri", it) }
            editor.apply()
        }
        dataStore.edit { prefs ->
            putColors(prefs, "dynamic", theme.colors)
            artworkUri?.let { prefs[Keys.DYNAMIC_ARTWORK_URI] = it }
        }
    }
}
