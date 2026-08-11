package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.libraryTagWriteDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "library_tag_write_settings"
)

data class LibraryTagWriteSettings(
    /** When true, Room metadata changes also write tags into the audio file (if writable). */
    val autoWriteTagsEnabled: Boolean = false
)

class LibraryTagWritePreferencesRepository(private val context: Context) {

    private object Keys {
        val AUTO_WRITE_TAGS = booleanPreferencesKey("auto_write_tags_enabled")
    }

    val settingsFlow: Flow<LibraryTagWriteSettings> =
        context.libraryTagWriteDataStore.data.map { prefs ->
            LibraryTagWriteSettings(
                autoWriteTagsEnabled = prefs[Keys.AUTO_WRITE_TAGS] ?: false
            )
        }

    suspend fun setAutoWriteTagsEnabled(enabled: Boolean) {
        context.libraryTagWriteDataStore.put(Keys.AUTO_WRITE_TAGS, enabled)
    }
}
