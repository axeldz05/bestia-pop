package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogCategory
import com.bestiapop.android.data.model.CatalogGenre
import com.bestiapop.android.data.model.CatalogPlaylist
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.OnlineCatalogTrack

data class CatalogSearchUiState(
    val category: CatalogCategory = CatalogCategory.SONGS,
    val tracks: List<OnlineCatalogTrack> = emptyList(),
    val albums: List<CatalogAlbum> = emptyList(),
    val playlists: List<CatalogPlaylist> = emptyList(),
    val genres: List<CatalogGenre> = emptyList(),
    val isSearching: Boolean = false
) {
    fun currentResultsAreEmpty(): Boolean = when (category) {
        CatalogCategory.SONGS, CatalogCategory.CHARTS -> tracks.isEmpty()
        CatalogCategory.ALBUMS -> albums.isEmpty()
        CatalogCategory.PLAYLISTS -> playlists.isEmpty()
        CatalogCategory.GENRES -> genres.isEmpty()
    }
}

enum class CatalogCollectionKind {
    ALBUM,
    PLAYLIST,
    GENRE
}

data class CatalogCollectionUiState(
    val selectionKey: String? = null,
    val title: String? = null,
    val kind: CatalogCollectionKind? = null,
    val coverUrl: String? = null,
    val candidates: List<CatalogTrackCandidate> = emptyList(),
    val isLoading: Boolean = false
) {
    val isOpen: Boolean
        get() = selectionKey != null
}
