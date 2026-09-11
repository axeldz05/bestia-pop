package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lyricsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lyrics_settings"
)

enum class JapanesePhoneticMode {
    ROMAJI,
    HIRAGANA
}

data class LyricsSettings(
    val phoneticGuideEnabled: Boolean = true,
    val japanesePhoneticMode: JapanesePhoneticMode = JapanesePhoneticMode.ROMAJI,
    val askBeforeGoogleTranslate: Boolean = true
)

class LyricsPreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.lyricsDataStore)

    private object Keys {
        val PHONETIC_GUIDE_ENABLED = booleanPreferencesKey("phonetic_guide_enabled")
        val JAPANESE_PHONETIC_MODE = stringPreferencesKey("japanese_phonetic_mode")
        val ASK_BEFORE_GOOGLE_TRANSLATE = booleanPreferencesKey("ask_before_google_translate")
    }

    val settingsFlow: Flow<LyricsSettings> = dataStore.data.map { prefs ->
        val phoneticEnabled = prefs[Keys.PHONETIC_GUIDE_ENABLED] ?: true
        val japModeStr = prefs[Keys.JAPANESE_PHONETIC_MODE]
        val japMode = if (japModeStr != null) {
            try {
                JapanesePhoneticMode.valueOf(japModeStr)
            } catch (_: IllegalArgumentException) {
                JapanesePhoneticMode.ROMAJI
            }
        } else {
            JapanesePhoneticMode.ROMAJI
        }
        val askGTranslate = prefs[Keys.ASK_BEFORE_GOOGLE_TRANSLATE] ?: true
        LyricsSettings(
            phoneticGuideEnabled = phoneticEnabled,
            japanesePhoneticMode = japMode,
            askBeforeGoogleTranslate = askGTranslate
        )
    }

    suspend fun setPhoneticGuideEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.PHONETIC_GUIDE_ENABLED] = enabled
        }
    }

    suspend fun setJapanesePhoneticMode(mode: JapanesePhoneticMode) {
        dataStore.edit { prefs ->
            prefs[Keys.JAPANESE_PHONETIC_MODE] = mode.name
        }
    }

    suspend fun setAskBeforeGoogleTranslate(ask: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ASK_BEFORE_GOOGLE_TRANSLATE] = ask
        }
    }
}
