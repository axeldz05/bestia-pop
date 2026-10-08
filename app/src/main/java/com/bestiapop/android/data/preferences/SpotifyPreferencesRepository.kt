package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.spotifyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "spotify_settings",
)

data class SpotifyAuthData(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtMs: Long = 0L,
    val userDisplayName: String? = null,
    val userId: String? = null,
    val customClientId: String? = null,
    val pendingCodeVerifier: String? = null,
    val pendingState: String? = null,
) {
    val isConnected: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    val isTokenExpired: Boolean
        get() = System.currentTimeMillis() >= (expiresAtMs - 60_000L)
}

class SpotifyPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.spotifyDataStore)

    private object Keys {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val EXPIRES_AT = longPreferencesKey("expires_at_ms")
        val USER_DISPLAY_NAME = stringPreferencesKey("user_display_name")
        val USER_ID = stringPreferencesKey("user_id")
        val CUSTOM_CLIENT_ID = stringPreferencesKey("custom_client_id")
        val PENDING_CODE_VERIFIER = stringPreferencesKey("pending_code_verifier")
        val PENDING_STATE = stringPreferencesKey("pending_state")
    }

    val authDataFlow: Flow<SpotifyAuthData> =
        dataStore.data.map { prefs ->
            SpotifyAuthData(
                accessToken = prefs[Keys.ACCESS_TOKEN],
                refreshToken = prefs[Keys.REFRESH_TOKEN],
                expiresAtMs = prefs[Keys.EXPIRES_AT] ?: 0L,
                userDisplayName = prefs[Keys.USER_DISPLAY_NAME],
                userId = prefs[Keys.USER_ID],
                customClientId = prefs[Keys.CUSTOM_CLIENT_ID],
                pendingCodeVerifier = prefs[Keys.PENDING_CODE_VERIFIER],
                pendingState = prefs[Keys.PENDING_STATE],
            )
        }

    suspend fun getAuthData(): SpotifyAuthData = authDataFlow.first()

    suspend fun saveTokens(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long,
    ) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L)
        dataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = accessToken
            if (!refreshToken.isNullOrBlank()) {
                prefs[Keys.REFRESH_TOKEN] = refreshToken
            }
            prefs[Keys.EXPIRES_AT] = expiresAt
        }
    }

    suspend fun saveUserProfile(
        displayName: String?,
        userId: String?,
    ) {
        dataStore.edit { prefs ->
            if (displayName != null) {
                prefs[Keys.USER_DISPLAY_NAME] = displayName
            } else {
                prefs.remove(Keys.USER_DISPLAY_NAME)
            }
            if (userId != null) {
                prefs[Keys.USER_ID] = userId
            } else {
                prefs.remove(Keys.USER_ID)
            }
        }
    }

    suspend fun saveCustomClientId(clientId: String?) {
        dataStore.edit { prefs ->
            if (!clientId.isNullOrBlank()) {
                prefs[Keys.CUSTOM_CLIENT_ID] = clientId.trim()
            } else {
                prefs.remove(Keys.CUSTOM_CLIENT_ID)
            }
        }
    }

    suspend fun savePendingAuthSession(
        verifier: String?,
        state: String?,
    ) {
        dataStore.edit { prefs ->
            if (verifier != null) {
                prefs[Keys.PENDING_CODE_VERIFIER] = verifier
            } else {
                prefs.remove(Keys.PENDING_CODE_VERIFIER)
            }
            if (state != null) {
                prefs[Keys.PENDING_STATE] = state
            } else {
                prefs.remove(Keys.PENDING_STATE)
            }
        }
    }

    suspend fun clearPendingAuthSession() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.PENDING_CODE_VERIFIER)
            prefs.remove(Keys.PENDING_STATE)
        }
    }

    suspend fun getPendingCodeVerifier(): String? = dataStore.data.first()[Keys.PENDING_CODE_VERIFIER]

    suspend fun getPendingState(): String? = dataStore.data.first()[Keys.PENDING_STATE]

    suspend fun clearAuth() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.REFRESH_TOKEN)
            prefs.remove(Keys.EXPIRES_AT)
            prefs.remove(Keys.USER_DISPLAY_NAME)
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.PENDING_CODE_VERIFIER)
            prefs.remove(Keys.PENDING_STATE)
        }
    }
}
