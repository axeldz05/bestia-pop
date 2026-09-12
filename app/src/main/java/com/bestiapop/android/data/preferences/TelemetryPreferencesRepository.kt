package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bestiapop.android.BuildConfig
import com.bestiapop.android.data.util.CrashReporter
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.telemetryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "telemetry_settings"
)

class TelemetryPreferencesRepository(private val context: Context) {

    private val syncPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private object Keys {
        val TELEMETRY_ENABLED = booleanPreferencesKey(KEY_TELEMETRY_ENABLED)
    }

    val initialTelemetryEnabled: Boolean
        get() = syncPrefs.getBoolean(KEY_TELEMETRY_ENABLED, true)

    val telemetryEnabledFlow: Flow<Boolean> =
        context.telemetryDataStore.data.map { prefs ->
            prefs[Keys.TELEMETRY_ENABLED] ?: initialTelemetryEnabled
        }

    suspend fun setTelemetryEnabled(enabled: Boolean) {
        context.telemetryDataStore.put(Keys.TELEMETRY_ENABLED, enabled)
        syncPrefs.edit { putBoolean(KEY_TELEMETRY_ENABLED, enabled) }
        CrashReporter.isEnabled = enabled
        runCatching {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled && !BuildConfig.DEBUG)
        }
    }

    companion object {
        const val PREFS_NAME = "system_stability_prefs"
        const val KEY_TELEMETRY_ENABLED = "telemetry_enabled"

        fun isTelemetryEnabledSync(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_TELEMETRY_ENABLED, true)
        }
    }
}
