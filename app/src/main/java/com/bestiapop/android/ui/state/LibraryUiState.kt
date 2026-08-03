package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption

enum class LibraryViewMode {
    FLAT,
    ALBUM_GROUPS
}

sealed interface LibraryUiState {
    object Loading : LibraryUiState

    data class Success(
        val songs: List<Song> = emptyList(),
        val albums: List<Album> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val searchQuery: String = "",
        val sortOption: SortOption = SortOption.TITLE,
        val viewMode: LibraryViewMode = LibraryViewMode.FLAT,
        val selectedTab: Int = 0,
        val selectedSongIds: Set<Long> = emptySet(),
        val isMultiSelectMode: Boolean = false
    ) : LibraryUiState {
        val selectedSongs: List<Song>
            get() = songs.filter { selectedSongIds.contains(it.id) }
    }

    data class Error(val message: String) : LibraryUiState
}
