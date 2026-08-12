package com.bestiapop.android.data.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bestiapop.android.data.preferences.put
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appUpdateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_update"
)

class AppUpdateCheckStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.appUpdateDataStore)

    suspend fun lastCheckAtMs(): Long =
        dataStore.data.map { prefs ->
            prefs[Keys.LAST_CHECK_AT_MS] ?: 0L
        }.first()

    suspend fun setLastCheckAtMs(epochMs: Long) {
        dataStore.put(Keys.LAST_CHECK_AT_MS, epochMs)
    }

    /** Notes of the installed build, so Ajustes → Actualización shows something offline. */
    suspend fun cachedNotes(versionName: String): String? =
        dataStore.data.map { prefs ->
            if (prefs[Keys.NOTES_VERSION] == versionName) prefs[Keys.NOTES_BODY] else null
        }.first()

    suspend fun setCachedNotes(versionName: String, notes: String) {
        dataStore.put(Keys.NOTES_VERSION, versionName)
        dataStore.put(Keys.NOTES_BODY, notes)
    }

    private object Keys {
        val LAST_CHECK_AT_MS = longPreferencesKey("last_check_at_ms")
        val NOTES_VERSION = stringPreferencesKey("current_notes_version")
        val NOTES_BODY = stringPreferencesKey("current_notes_body")
    }
}
