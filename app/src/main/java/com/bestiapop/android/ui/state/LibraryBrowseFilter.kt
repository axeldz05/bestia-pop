package com.bestiapop.android.ui.state

/**
 * Exclusive library browse chip: projects the filtered song pool into one list shape.
 * Persisted as name via [com.bestiapop.android.data.preferences.LibraryUiPreferencesCodec].
 */
enum class LibraryBrowseFilter {
    SONGS,
    ALBUMS,
    ARTISTS,
    GENRES,
    /** dateAdded DESC (“Añadidas”); not last-played until Room lastPlayedAt exists. */
    RECENT
}
