package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.listenBrainzDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "listenbrainz_settings"
)

data class ListenBrainzSettings(
    val enabled: Boolean = false,
    val discoverEnabled: Boolean = false,
    val saveWhileListening: Boolean = false,
    /** Percent of track duration (1–100) before background save starts. */
    val saveWhileListeningPercent: Int = DEFAULT_SAVE_WHILE_LISTENING_PERCENT,
    val userToken: String = "",
    val username: String? = null,
    val lastSyncAt: Long? = null
) {
    val showDiscoverPlaylists: Boolean
        get() = enabled && discoverEnabled && !username.isNullOrBlank()
}

const val DEFAULT_SAVE_WHILE_LISTENING_PERCENT = 25
const val MIN_SAVE_WHILE_LISTENING_PERCENT = 5
const val MAX_SAVE_WHILE_LISTENING_PERCENT = 100

fun clampSaveWhileListeningPercent(percent: Int): Int =
    percent.coerceIn(MIN_SAVE_WHILE_LISTENING_PERCENT, MAX_SAVE_WHILE_LISTENING_PERCENT)

class ListenBrainzPreferencesRepository(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val DISCOVER_ENABLED = booleanPreferencesKey("discover_enabled")
        val SAVE_WHILE_LISTENING = booleanPreferencesKey("save_while_listening")
        val SAVE_WHILE_LISTENING_PERCENT = intPreferencesKey("save_while_listening_percent")
        val USER_TOKEN = stringPreferencesKey("user_token")
        val USERNAME = stringPreferencesKey("username")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
    }

    val settingsFlow: Flow<ListenBrainzSettings> = context.listenBrainzDataStore.data.map { prefs ->
        ListenBrainzSettings(
            enabled = prefs[Keys.ENABLED] ?: false,
            discoverEnabled = prefs[Keys.DISCOVER_ENABLED] ?: false,
            saveWhileListening = prefs[Keys.SAVE_WHILE_LISTENING] ?: false,
            saveWhileListeningPercent = clampSaveWhileListeningPercent(
                prefs[Keys.SAVE_WHILE_LISTENING_PERCENT] ?: DEFAULT_SAVE_WHILE_LISTENING_PERCENT
            ),
            userToken = prefs[Keys.USER_TOKEN].orEmpty(),
            username = prefs[Keys.USERNAME],
            lastSyncAt = prefs[Keys.LAST_SYNC_AT]
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.listenBrainzDataStore.put(Keys.ENABLED, enabled)
    }

    suspend fun setDiscoverEnabled(enabled: Boolean) {
        context.listenBrainzDataStore.put(Keys.DISCOVER_ENABLED, enabled)
    }

    suspend fun setSaveWhileListening(enabled: Boolean) {
        context.listenBrainzDataStore.put(Keys.SAVE_WHILE_LISTENING, enabled)
    }

    suspend fun setSaveWhileListeningPercent(percent: Int) {
        context.listenBrainzDataStore.put(
            Keys.SAVE_WHILE_LISTENING_PERCENT,
            clampSaveWhileListeningPercent(percent)
        )
    }

    suspend fun setToken(token: String) {
        context.listenBrainzDataStore.edit { prefs ->
            prefs[Keys.USER_TOKEN] = token.trim()
            // Clear username until re-validated with the new token.
            prefs.remove(Keys.USERNAME)
        }
    }

    suspend fun setUsername(username: String?) {
        context.listenBrainzDataStore.edit { prefs ->
            if (username.isNullOrBlank()) {
                prefs.remove(Keys.USERNAME)
            } else {
                prefs[Keys.USERNAME] = username
            }
        }
    }

    suspend fun setLastSyncAt(epochMs: Long) {
        context.listenBrainzDataStore.put(Keys.LAST_SYNC_AT, epochMs)
    }

    suspend fun clear() {
        context.listenBrainzDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
