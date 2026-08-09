package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.libraryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "library_settings"
)

/**
 * One-shot flags for library disk import.
 * Survives app updates; cleared on uninstall / clear data (fresh install → scan again).
 */
class LibraryPreferencesRepository(private val context: Context) {

    suspend fun isInitialScanCompleted(): Boolean =
        context.libraryDataStore.data.map { prefs ->
            prefs[Keys.INITIAL_SCAN_COMPLETED] ?: false
        }.first()

    suspend fun setInitialScanCompleted(completed: Boolean = true) {
        context.libraryDataStore.edit { prefs ->
            prefs[Keys.INITIAL_SCAN_COMPLETED] = completed
        }
    }

    private object Keys {
        val INITIAL_SCAN_COMPLETED = booleanPreferencesKey("initial_library_scan_completed")
    }
}
