package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

class DownloadPreferencesRepository(private val context: Context) {

    private object Keys {
        val DOWNLOAD_ON_METERED = booleanPreferencesKey("download_on_metered_network")
        val TOTAL_METERED_BYTES = longPreferencesKey("total_metered_bytes")
        val TOTAL_UNMETERED_BYTES = longPreferencesKey("total_unmetered_bytes")
    }

    val settingsFlow: Flow<DownloadSettings> = context.downloadDataStore.data.map { prefs ->
        DownloadSettings(
            downloadOnMeteredNetwork = prefs[Keys.DOWNLOAD_ON_METERED] ?: true,
            totalMeteredBytes = prefs[Keys.TOTAL_METERED_BYTES] ?: 0L,
            totalUnmeteredBytes = prefs[Keys.TOTAL_UNMETERED_BYTES] ?: 0L
        )
    }

    suspend fun setDownloadOnMeteredNetwork(enabled: Boolean) {
        context.downloadDataStore.put(Keys.DOWNLOAD_ON_METERED, enabled)
    }
}
