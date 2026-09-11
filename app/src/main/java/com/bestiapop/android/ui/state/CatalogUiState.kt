package com.bestiapop.android.ui.state

import androidx.compose.runtime.Immutable
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogCategory
import com.bestiapop.android.data.model.CatalogGenre
import com.bestiapop.android.data.model.CatalogPlaylist
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.OnlineCatalogTrack

import com.bestiapop.android.data.model.IdentifySearchFilters

@Immutable
data class CatalogSearchUiState(
    val category: CatalogCategory = CatalogCategory.SONGS,
    val tracks: List<OnlineCatalogTrack> = emptyList(),
    val albums: List<CatalogAlbum> = emptyList(),
    val playlists: List<CatalogPlaylist> = emptyList(),
    val genres: List<CatalogGenre> = emptyList(),
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val searchQueryDraft: String = "",
    val searchFilterArtist: String = "",
    val searchFilterAlbum: String = "",
    val searchFilterYear: String = "",
    val showSearchFilters: Boolean = false
) {
    val searchFilters: IdentifySearchFilters
        get() = IdentifySearchFilters(
            artist = searchFilterArtist,
            album = searchFilterAlbum,
            year = searchFilterYear.toIntOrNull() ?: 0
        )

    val hasActiveFilters: Boolean
        get() = searchFilters.hasAny

    fun withSearchFilters(filters: IdentifySearchFilters): CatalogSearchUiState = copy(
        searchFilterArtist = filters.artist,
        searchFilterAlbum = filters.album,
        searchFilterYear = if (filters.year > 0) filters.year.toString() else ""
    )

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
    GENRE,
    ARTIST
}

@Immutable
data class CatalogCollectionUiState(
    val selectionKey: String? = null,
    val title: String? = null,
    val kind: CatalogCollectionKind? = null,
    val coverUrl: String? = null,
    val candidates: List<CatalogTrackCandidate> = emptyList(),
    val albums: List<CatalogAlbum> = emptyList(),
    val parent: CatalogCollectionUiState? = null,
    val isLoading: Boolean = false
) {
    val isOpen: Boolean
        get() = selectionKey != null
}

/** Level 2: translates candidate list to PlayableItems, resolving available local tracks against the index. */
fun List<CatalogTrackCandidate>.toPlayableItems(localLibraryIndex: Map<String, com.bestiapop.android.data.model.Song>): List<com.bestiapop.android.data.model.PlayableItem> =
    map { candidate ->
        val track = candidate.effectiveTrack
        val ytQuery = track.audioUrl.takeIf { it.isNotBlank() }
            ?: track.id.takeIf { it.isNotBlank() }
            ?: "${candidate.artist} ${candidate.title}".trim()
        com.bestiapop.android.data.model.PlayableItem.fromLibraryOrRemote(
            local = com.bestiapop.android.domain.util.TrackMatchKeys.lookupLocalSong(localLibraryIndex, candidate.identity),
            identity = candidate.identity,
            youtubeQueryOrId = ytQuery.takeIf { it.isNotBlank() }
        )
    }
