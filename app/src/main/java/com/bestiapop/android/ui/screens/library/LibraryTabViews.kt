package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.data.preferences.FastScrollSettings
import kotlinx.coroutines.flow.StateFlow

@Composable
fun LibraryAlbumsTab(
    viewModel: MusicPlayerViewModel,
    sortOption: SortOption,
    actions: AlbumBrowseActions,
    fastScrollSettings: FastScrollSettings,
    listState: LazyListState
) {
    val albums by viewModel.libraryProjection.albums.collectAsStateWithLifecycle()
    LibraryAlbumBrowseList(
        albums = albums,
        actions = actions,
        sortOption = sortOption,
        fastScrollSettings = fastScrollSettings,
        listState = listState
    )
}

@Composable
fun LibraryRecentTab(
    viewModel: MusicPlayerViewModel,
    currentSongIdFlow: StateFlow<Long?>,
    isSelectionMode: Boolean,
    selectedSongIds: Set<Long>,
    sortOption: SortOption,
    actions: LibrarySongListActions,
    searchQuery: String,
    listState: LazyListState,
    onToggleSelect: (Song) -> Unit,
    fastScrollSettings: FastScrollSettings
) {
    val recentSongs by viewModel.libraryProjection.recentSongs.collectAsStateWithLifecycle()
    val recentList by viewModel.libraryProjection.recentList.collectAsStateWithLifecycle()
    val recentEmptyFromSearch = searchQuery.isNotBlank()
    val onSongClick = remember(isSelectionMode, recentSongs, onToggleSelect) {
        { song: Song, index: Int ->
            if (isSelectionMode) onToggleSelect(song)
            else viewModel.playCollection(recentSongs, index)
        }
    }
    LibrarySongListHost(
        list = recentList,
        currentSongId = null,
        currentSongIdFlow = currentSongIdFlow,
        isSelectionMode = isSelectionMode,
        selectedSongIds = selectedSongIds,
        collapsedAlbumNames = emptySet(),
        sortOption = sortOption,
        emphasizeLastPlayed = true,
        emptyText = if (recentEmptyFromSearch) {
            "No se encontraron canciones"
        } else {
            "Todavía no hay recientes"
        },
        emptySubtitle = if (recentEmptyFromSearch) {
            "Ningún resultado para esta búsqueda"
        } else {
            "Reproducí canciones para verlas acá"
        },
        actions = actions,
        onSongClick = onSongClick,
        fastScrollSettings = fastScrollSettings,
        listState = listState
    )
}

@Composable
fun LibraryArtistsTab(
    viewModel: MusicPlayerViewModel,
    sortOption: SortOption,
    actions: AggregateBrowseActions<Artist>,
    fastScrollSettings: FastScrollSettings,
    listState: LazyListState
) {
    val artists by viewModel.libraryProjection.artists.collectAsStateWithLifecycle()
    LibraryArtistList(
        artists = artists,
        actions = actions,
        sortOption = sortOption,
        fastScrollSettings = fastScrollSettings,
        listState = listState
    )
}

@Composable
fun LibraryGenresTab(
    viewModel: MusicPlayerViewModel,
    sortOption: SortOption,
    actions: AggregateBrowseActions<GenreGroup>,
    fastScrollSettings: FastScrollSettings,
    listState: LazyListState
) {
    val genres by viewModel.libraryProjection.genres.collectAsStateWithLifecycle()
    LibraryGenreList(
        genres = genres,
        actions = actions,
        sortOption = sortOption,
        fastScrollSettings = fastScrollSettings,
        listState = listState
    )
}
