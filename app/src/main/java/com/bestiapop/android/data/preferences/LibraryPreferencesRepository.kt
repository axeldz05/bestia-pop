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
        val sortOptionName = LibraryUiPreferencesCodec.sanitizeSortOptionName(
            prefs[Keys.SORT_OPTION]
        )
        LibraryDisplaySettings(
            sortOptionName = sortOptionName,
            sortDirectionName = LibraryUiPreferencesCodec.sanitizeSortDirectionName(
                prefs[Keys.SORT_DIRECTION],
                sortOptionName
            ),
            viewModeName = LibraryUiPreferencesCodec.sanitizeViewModeName(
                prefs[Keys.VIEW_MODE]
            )
        )
    }

    val navSnapshotFlow: Flow<UiNavSnapshot> = context.libraryDataStore.data.map { prefs ->
        LibraryUiPreferencesCodec.sanitizeNavSnapshot(
            navIndex = prefs[Keys.NAV_INDEX],
            browseFilterName = prefs[Keys.LIBRARY_BROWSE_FILTER],
            libraryTab = prefs[Keys.LIBRARY_TAB],
            libraryArtistName = prefs[Keys.LIBRARY_ARTIST],
            libraryAlbumName = prefs[Keys.LIBRARY_ALBUM],
            libraryGenreName = prefs[Keys.LIBRARY_GENRE],
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
        context.libraryDataStore.put(Keys.INITIAL_SCAN_COMPLETED, completed)
    }

    /**
     * Highest Room schema version this install has ever opened. Lives outside the database on purpose:
     * a destructive downgrade wipes Room, so the only way to tell the user why their playlists are
     * gone is a marker that survives it.
     */
    suspend fun highestDbVersionSeen(): Int =
        context.libraryDataStore.data.map { prefs ->
            prefs[Keys.HIGHEST_DB_VERSION] ?: 0
        }.first()

    suspend fun setHighestDbVersionSeen(version: Int) {
        context.libraryDataStore.put(Keys.HIGHEST_DB_VERSION, version)
    }

    suspend fun isLegacyYouTubeMusicMigrated(): Boolean =
        context.libraryDataStore.data.map { prefs ->
            prefs[Keys.LEGACY_YTM_MIGRATED] ?: false
        }.first()

    suspend fun setLegacyYouTubeMusicMigrated() {
        context.libraryDataStore.put(Keys.LEGACY_YTM_MIGRATED, true)
    }

    suspend fun setSortOptionName(name: String) {
        val clean = LibraryUiPreferencesCodec.sanitizeSortOptionName(name)
        context.libraryDataStore.edit { prefs ->
            prefs[Keys.SORT_OPTION] = clean
            prefs[Keys.SORT_DIRECTION] =
                LibraryUiPreferencesCodec.defaultSortDirectionName(clean)
        }
    }

    suspend fun setSortDirectionName(name: String, sortOptionName: String) {
        context.libraryDataStore.put(
            Keys.SORT_DIRECTION,
            LibraryUiPreferencesCodec.sanitizeSortDirectionName(name, sortOptionName)
        )
    }

    suspend fun setViewModeName(name: String) {
        context.libraryDataStore.put(
            Keys.VIEW_MODE,
            LibraryUiPreferencesCodec.sanitizeViewModeName(name)
        )
    }

    suspend fun setNavSnapshot(snapshot: UiNavSnapshot) {
        val clean = LibraryUiPreferencesCodec.sanitizeNavSnapshot(
            navIndex = snapshot.navIndex,
            browseFilterName = snapshot.browseFilterName,
            libraryArtistName = snapshot.libraryArtistName,
            libraryAlbumName = snapshot.libraryAlbumName,
            libraryGenreName = snapshot.libraryGenreName,
            playlistDetailKind = snapshot.playlistDetailKind,
            playlistLocalId = snapshot.playlistLocalId,
            playlistLbMbid = snapshot.playlistLbMbid
        )
        context.libraryDataStore.edit { prefs ->
            prefs[Keys.NAV_INDEX] = clean.navIndex
            prefs[Keys.LIBRARY_BROWSE_FILTER] = clean.browseFilterName
            // Keep legacy int in sync so older builds / mid-upgrade reads stay coherent.
            prefs[Keys.LIBRARY_TAB] =
                LibraryUiPreferencesCodec.browseFilterNameToLegacyTab(clean.browseFilterName)
            prefs[Keys.LIBRARY_ARTIST] = clean.libraryArtistName.orEmpty()
            prefs[Keys.LIBRARY_ALBUM] = clean.libraryAlbumName.orEmpty()
            prefs[Keys.LIBRARY_GENRE] = clean.libraryGenreName.orEmpty()
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
        val LEGACY_YTM_MIGRATED = booleanPreferencesKey("legacy_ytm_album_migrated")
        val HIGHEST_DB_VERSION = intPreferencesKey("highest_db_version_seen")
        val SORT_OPTION = stringPreferencesKey("library_sort_option")
        val SORT_DIRECTION = stringPreferencesKey("library_sort_direction")
        val VIEW_MODE = stringPreferencesKey("library_view_mode")
        val NAV_INDEX = intPreferencesKey("ui_nav_index")
        val LIBRARY_TAB = intPreferencesKey("library_tab")
        val LIBRARY_BROWSE_FILTER = stringPreferencesKey("library_browse_filter")
        val LIBRARY_ARTIST = stringPreferencesKey("library_artist_name")
        val LIBRARY_ALBUM = stringPreferencesKey("library_album_name")
        val LIBRARY_GENRE = stringPreferencesKey("library_genre_name")
        val PLAYLIST_DETAIL_KIND = stringPreferencesKey("playlist_detail_kind")
        val PLAYLIST_LOCAL_ID = longPreferencesKey("playlist_local_id")
        val PLAYLIST_LB_MBID = stringPreferencesKey("playlist_lb_mbid")
    }
}
