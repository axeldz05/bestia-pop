package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogCategory
import com.bestiapop.android.data.model.CatalogGenre
import com.bestiapop.android.data.model.CatalogPlaylist
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.domain.usecase.DiscoverFeed

data class DiscoverUiState(
    val feed: DiscoverFeed = DiscoverFeed(),
    val isLoadingFeed: Boolean = false,
    val searchQueryDraft: String = "",
    val recentSearches: List<String> = emptyList(),
    val category: CatalogCategory = CatalogCategory.SONGS,
    val isSearching: Boolean = false,
    val tracks: List<OnlineCatalogTrack> = emptyList(),
    val albums: List<CatalogAlbum> = emptyList(),
    val playlists: List<CatalogPlaylist> = emptyList(),
    val genres: List<CatalogGenre> = emptyList(),
    val searchFilterArtist: String = "",
    val searchFilterAlbum: String = "",
    val searchFilterYear: String = "",
    val showSearchFilters: Boolean = false,
    val selectedCollection: CatalogCollectionUiState = CatalogCollectionUiState()
) {
    val isSearchActive: Boolean
        get() = searchQueryDraft.isNotBlank() || isSearching || hasActiveFilters

    val searchFilters: IdentifySearchFilters
        get() = IdentifySearchFilters(
            artist = searchFilterArtist,
            album = searchFilterAlbum,
            year = searchFilterYear.toIntOrNull() ?: 0
        )

    val hasActiveFilters: Boolean
        get() = searchFilters.hasAny
}

enum class ItemLibraryStatus {
    NOT_IN_LIBRARY,
    SAVED_REMOTE,
    DOWNLOADED
}
