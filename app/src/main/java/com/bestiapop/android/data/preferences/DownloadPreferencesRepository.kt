package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.downloadDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "download_settings"
)

data class DownloadSettings(
    /** When false, downloads on metered networks are blocked. Default on (allow). */
    val downloadOnMeteredNetwork: Boolean = true,
    val totalMeteredBytes: Long = 0L,
    val totalUnmeteredBytes: Long = 0L
)

class DownloadPreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.downloadDataStore)

    private object Keys {
        val DOWNLOAD_ON_METERED = booleanPreferencesKey("download_on_metered_network")
        val TOTAL_METERED_BYTES = longPreferencesKey("total_metered_bytes")
        val TOTAL_UNMETERED_BYTES = longPreferencesKey("total_unmetered_bytes")
    }

    val settingsFlow: Flow<DownloadSettings> = dataStore.data.map { prefs ->
        DownloadSettings(
            downloadOnMeteredNetwork = prefs[Keys.DOWNLOAD_ON_METERED] ?: true,
            totalMeteredBytes = prefs[Keys.TOTAL_METERED_BYTES] ?: 0L,
            totalUnmeteredBytes = prefs[Keys.TOTAL_UNMETERED_BYTES] ?: 0L
        )
    }

    suspend fun setDownloadOnMeteredNetwork(enabled: Boolean) {
        dataStore.put(Keys.DOWNLOAD_ON_METERED, enabled)
    }

    suspend fun addDownloadedBytes(byteCount: Long, metered: Boolean) {
        if (byteCount <= 0) return
        dataStore.edit { prefs ->
            val key = if (metered) Keys.TOTAL_METERED_BYTES else Keys.TOTAL_UNMETERED_BYTES
            prefs[key] = (prefs[key] ?: 0L) + byteCount
        }
    }

    internal suspend fun restoreForTest(settings: DownloadSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.DOWNLOAD_ON_METERED] = settings.downloadOnMeteredNetwork
            prefs[Keys.TOTAL_METERED_BYTES] = settings.totalMeteredBytes
            prefs[Keys.TOTAL_UNMETERED_BYTES] = settings.totalUnmeteredBytes
        }
    }
}
