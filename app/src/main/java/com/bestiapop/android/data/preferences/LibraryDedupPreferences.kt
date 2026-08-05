package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.libraryDedupDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "library_dedup"
)

class LibraryDedupPreferences(private val context: Context) {

    private object Keys {
        val DEDUP_V1_DONE = booleanPreferencesKey("library_dedup_v1_done")
    }

    suspend fun isDedupV1Done(): Boolean {
        return context.libraryDedupDataStore.data.map { prefs ->
            prefs[Keys.DEDUP_V1_DONE] == true
        }.first()
    }

    suspend fun setDedupV1Done(done: Boolean = true) {
        context.libraryDedupDataStore.edit { prefs ->
            prefs[Keys.DEDUP_V1_DONE] = done
        }
    }
}
