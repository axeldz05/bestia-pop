package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LibraryProjectionState internal constructor(
    scope: CoroutineScope,
    rawSongs: Flow<List<Song>>,
    albumOverrides: Flow<List<AlbumOverride>>,
    searchQuery: StateFlow<String>,
    sortOption: StateFlow<SortOption>,
    sortDirection: StateFlow<SortDirection>,
    artistPhotos: StateFlow<Map<String, String>>,
    private val useCase: GetLibrarySongsUseCase,
    projectionDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val overridesByAlbum: StateFlow<Map<String, AlbumOverride>> = albumOverrides
        .map { overrides -> overrides.associateBy { it.albumKey } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val songs: StateFlow<List<Song>> = combine(
        rawSongs,
        searchQuery,
        sortOption,
        sortDirection
    ) { list, query, sort, direction ->
        useCase.execute(list, query, sort, direction)
    }
        .flowOn(projectionDispatcher)
        .stateInUi(scope, emptyList())

    val albums: StateFlow<List<Album>> = combine(
        songs,
        overridesByAlbum,
        sortOption,
        sortDirection
    ) { projectedSongs, overrides, sort, direction ->
        useCase.extractAlbums(projectedSongs, overrides, sort, direction)
    }
        .flowOn(projectionDispatcher)
        .stateInUi(scope, emptyList())

    val artists: StateFlow<List<Artist>> = combine(
        songs,
        artistPhotos,
        sortOption,
        sortDirection
    ) { projectedSongs, photos, sort, direction ->
        useCase.extractArtists(projectedSongs, photos, sort, direction)
    }
        .flowOn(projectionDispatcher)
        .stateInUi(scope, emptyList())

    val genres: StateFlow<List<GenreGroup>> = combine(
        songs,
        sortOption,
        sortDirection
    ) { projectedSongs, sort, direction ->
        useCase.extractGenres(projectedSongs, sort, direction)
    }
        .flowOn(projectionDispatcher)
        .stateInUi(scope, emptyList())

    fun buildListItems(
        songs: List<Song>,
        viewMode: LibraryViewMode
    ): List<LibraryListItem> =
        useCase.buildListItems(songs, viewMode, overridesByAlbum.value)
}
