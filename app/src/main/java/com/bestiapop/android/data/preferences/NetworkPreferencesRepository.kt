package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bestiapop.android.BuildConfig
import com.bestiapop.android.data.network.HttpClients
import com.bestiapop.android.data.util.CrashReporter
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.networkDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "network_settings"
)

/**
 * Manages the global offline mode / disable internet connection preference.
 * Synchronizes with SharedPreferences for synchronous read at process start.
 */
class NetworkPreferencesRepository(private val context: Context) {

    private val syncPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private object Keys {
        val OFFLINE_MODE = booleanPreferencesKey(KEY_OFFLINE_MODE)
    }

    val initialOfflineMode: Boolean
        get() = syncPrefs.getBoolean(KEY_OFFLINE_MODE, false)

    val offlineModeFlow: Flow<Boolean> =
        context.networkDataStore.data.map { prefs ->
            prefs[Keys.OFFLINE_MODE] ?: initialOfflineMode
        }

    suspend fun setOfflineMode(enabled: Boolean) {
        context.networkDataStore.put(Keys.OFFLINE_MODE, enabled)
        syncPrefs.edit { putBoolean(KEY_OFFLINE_MODE, enabled) }

        // Update HttpClients in-memory guard immediately
        HttpClients.isOfflineMode = { enabled }

        // Telemetry is suspended while offline mode is active
        val telemetryPermitted = !enabled && TelemetryPreferencesRepository.isTelemetryEnabledSync(context)
        CrashReporter.isEnabled = telemetryPermitted
        runCatching {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(telemetryPermitted && !BuildConfig.DEBUG)
        }
    }

    companion object {
        const val PREFS_NAME = "network_prefs"
        const val KEY_OFFLINE_MODE = "offline_mode"

        fun isOfflineModeSync(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_OFFLINE_MODE, false)
        }
    }
}
