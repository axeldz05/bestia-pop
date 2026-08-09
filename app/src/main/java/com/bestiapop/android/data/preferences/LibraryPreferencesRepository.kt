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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.libraryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "library_settings"
)

/**
 * Library disk-import flags plus persisted display + UI navigation snapshot.
 * Survives app updates; cleared on uninstall / clear data.
 */
class LibraryPreferencesRepository(private val context: Context) {

    val displaySettingsFlow: Flow<LibraryDisplaySettings> = context.libraryDataStore.data.map { prefs ->
        LibraryDisplaySettings(
            sortOptionName = LibraryUiPreferencesCodec.sanitizeSortOptionName(
                prefs[Keys.SORT_OPTION]
            ),
            viewModeName = LibraryUiPreferencesCodec.sanitizeViewModeName(
                prefs[Keys.VIEW_MODE]
            )
        )
    }

    val navSnapshotFlow: Flow<UiNavSnapshot> = context.libraryDataStore.data.map { prefs ->
        LibraryUiPreferencesCodec.sanitizeNavSnapshot(
            navIndex = prefs[Keys.NAV_INDEX],
            libraryTab = prefs[Keys.LIBRARY_TAB],
            libraryArtistName = prefs[Keys.LIBRARY_ARTIST],
            libraryAlbumName = prefs[Keys.LIBRARY_ALBUM],
            playlistDetailKind = prefs[Keys.PLAYLIST_DETAIL_KIND],
            playlistLocalId = prefs[Keys.PLAYLIST_LOCAL_ID],
            playlistLbMbid = prefs[Keys.PLAYLIST_LB_MBID]
        )
    }

    suspend fun isInitialScanCompleted(): Boolean =
        context.libraryDataStore.data.map { prefs ->
            prefs[Keys.INITIAL_SCAN_COMPLETED] ?: false
        }.first()

    suspend fun setInitialScanCompleted(completed: Boolean = true) {
        context.libraryDataStore.edit { prefs ->
            prefs[Keys.INITIAL_SCAN_COMPLETED] = completed
        }
    }

    suspend fun setSortOptionName(name: String) {
        context.libraryDataStore.edit { prefs ->
            prefs[Keys.SORT_OPTION] = LibraryUiPreferencesCodec.sanitizeSortOptionName(name)
        }
    }

    suspend fun setViewModeName(name: String) {
        context.libraryDataStore.edit { prefs ->
            prefs[Keys.VIEW_MODE] = LibraryUiPreferencesCodec.sanitizeViewModeName(name)
        }
    }

    suspend fun setNavSnapshot(snapshot: UiNavSnapshot) {
        val clean = LibraryUiPreferencesCodec.sanitizeNavSnapshot(
            navIndex = snapshot.navIndex,
            libraryTab = snapshot.libraryTab,
            libraryArtistName = snapshot.libraryArtistName,
            libraryAlbumName = snapshot.libraryAlbumName,
            playlistDetailKind = snapshot.playlistDetailKind,
            playlistLocalId = snapshot.playlistLocalId,
            playlistLbMbid = snapshot.playlistLbMbid
        )
        context.libraryDataStore.edit { prefs ->
            prefs[Keys.NAV_INDEX] = clean.navIndex
            prefs[Keys.LIBRARY_TAB] = clean.libraryTab
            prefs[Keys.LIBRARY_ARTIST] = clean.libraryArtistName.orEmpty()
            prefs[Keys.LIBRARY_ALBUM] = clean.libraryAlbumName.orEmpty()
            prefs[Keys.PLAYLIST_DETAIL_KIND] = clean.playlistDetailKind
            val localId = clean.playlistLocalId
            if (localId != null) {
                prefs[Keys.PLAYLIST_LOCAL_ID] = localId
            } else {
                prefs.remove(Keys.PLAYLIST_LOCAL_ID)
            }
            prefs[Keys.PLAYLIST_LB_MBID] = clean.playlistLbMbid.orEmpty()
        }
    }

    private object Keys {
        val INITIAL_SCAN_COMPLETED = booleanPreferencesKey("initial_library_scan_completed")
        val SORT_OPTION = stringPreferencesKey("library_sort_option")
        val VIEW_MODE = stringPreferencesKey("library_view_mode")
        val NAV_INDEX = intPreferencesKey("ui_nav_index")
        val LIBRARY_TAB = intPreferencesKey("library_tab")
        val LIBRARY_ARTIST = stringPreferencesKey("library_artist_name")
        val LIBRARY_ALBUM = stringPreferencesKey("library_album_name")
        val PLAYLIST_DETAIL_KIND = stringPreferencesKey("playlist_detail_kind")
        val PLAYLIST_LOCAL_ID = longPreferencesKey("playlist_local_id")
        val PLAYLIST_LB_MBID = stringPreferencesKey("playlist_lb_mbid")
    }
}
