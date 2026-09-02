package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.PlaybackDiagnostics
import com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

internal const val LIBRARY_SEARCH_DEBOUNCE_MS = 75L

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryProjectionState internal constructor(
    scope: CoroutineScope,
    rawSongs: Flow<List<Song>>,
    albumOverrides: Flow<List<AlbumOverride>>,
    searchQuery: StateFlow<String>,
    sortOption: StateFlow<SortOption>,
    sortDirection: StateFlow<SortDirection>,
    artistPhotos: StateFlow<Map<String, String>>,
    private val useCase: GetLibrarySongsUseCase,
    overlayOpen: Flow<Boolean> = flowOf(false),
    viewMode: Flow<LibraryViewMode> = flowOf(LibraryViewMode.FLAT),
    browseFilter: Flow<LibraryBrowseFilter> = flowOf(LibraryBrowseFilter.SONGS),
    playStats: Flow<Map<Long, Long>> = flowOf(emptyMap()),
    prefsReady: Flow<Boolean> = flowOf(true),
    projectionDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private data class SortCriteria(
        val option: SortOption,
        val direction: SortDirection
    )

    private val sortCriteria: Flow<SortCriteria> = combine(sortOption, sortDirection, ::SortCriteria)
        .distinctUntilChanged()

    private data class DisplaySpec(
        val viewMode: LibraryViewMode,
        val browseFilter: LibraryBrowseFilter
    )

    private val displaySpec: Flow<DisplaySpec> = combine(viewMode, browseFilter, ::DisplaySpec)
        .distinctUntilChanged()

    private data class SongListSpec(
        val display: DisplaySpec,
        val sort: SortCriteria,
        val overrides: Map<String, AlbumOverride>
    )

    private val overridesByAlbum: StateFlow<Map<String, AlbumOverride>> = albumOverrides
        .map { overrides -> overrides.associateBy { it.albumKey } }
        .distinctUntilChanged()
        .stateInUi(scope, emptyMap())

    private val catalogSongs: Flow<List<Song>> = rawSongs
        .distinctUntilChanged(::sameLibraryCatalog)

    private val catalogQuery: Flow<String> = searchQuery
        .transformLatest { query ->
            if (query.isBlank()) {
                emit(query)
            } else {
                delay(LIBRARY_SEARCH_DEBOUNCE_MS)
                emit(query)
            }
        }
        .distinctUntilChanged()

    private data class CatalogFilter(
        val songs: List<Song>,
        val query: String,
        val sort: SortCriteria,
        val paused: Boolean
    )

    private data class CatalogSnapshot(
        val loaded: Boolean,
        val projection: GetLibrarySongsUseCase.CatalogProjection
    )

    private val filterFlow: Flow<CatalogFilter> = combine(
        catalogSongs,
        catalogQuery,
        sortCriteria,
        overlayOpen
    ) { songs, query, sort, paused ->
        CatalogFilter(songs, query, sort, paused)
    }.distinctUntilChanged()

    private val specFlow: Flow<SongListSpec> = combine(
        displaySpec,
        sortCriteria,
        overridesByAlbum,
        ::SongListSpec
    ).distinctUntilChanged()

    private val catalog: StateFlow<CatalogSnapshot> = combine(
        filterFlow,
        prefsReady,
        specFlow
    ) { filter, ready, spec ->
        if (filter.paused || !ready) {
            null
        } else {
            val listMode = if (spec.display.viewMode == LibraryViewMode.ALBUM_GROUPS &&
                spec.display.browseFilter != LibraryBrowseFilter.RECENT
            ) {
                LibraryViewMode.ALBUM_GROUPS
            } else {
                LibraryViewMode.FLAT
            }
            val haystack = if (filter.query.isBlank()) {
                null
            } else {
                useCase.getOrBuildHaystack(filter.songs)
            }
            val startedAt = System.nanoTime()
            val projection = useCase.projectCatalog(
                songs = filter.songs,
                query = filter.query,
                sortOption = filter.sort.option,
                sortDirection = filter.sort.direction,
                overrides = spec.overrides,
                listMode = listMode,
                haystackById = haystack
            )
            val ms = (System.nanoTime() - startedAt) / 1_000_000L
            PlaybackDiagnostics.log(
                PlaybackDiagnostics.TAG_LIFECYCLE,
                "projectCatalog n=${filter.songs.size} mode=$listMode ${ms}ms"
            )
            CatalogSnapshot(
                loaded = true,
                projection = projection
            )
        }
    }
        .filterNotNull()
        .flowOn(projectionDispatcher)
        .stateInUi(
            scope,
            CatalogSnapshot(
                loaded = false,
                projection = GetLibrarySongsUseCase.CatalogProjection.EMPTY
            )
        )

    val catalogLoaded: StateFlow<Boolean> = catalog
        .map { it.loaded }
        .distinctUntilChanged()
        .stateInUi(scope, false)

    val songs: StateFlow<List<Song>> = catalog
        .map { it.projection.songs }
        .stateInUi(scope, emptyList())

    val albums: StateFlow<List<Album>> = catalog
        .map { it.projection.albums }
        .stateInUi(scope, emptyList())

    val artists: StateFlow<List<Artist>> = combine(
        songs,
        artistPhotos,
        sortCriteria
    ) { projectedSongs, photos, sort ->
        useCase.extractArtists(projectedSongs, photos, sort.option, sort.direction)
    }
        .flowOn(projectionDispatcher)
        .stateInUi(scope, emptyList())

    val genres: StateFlow<List<GenreGroup>> = combine(
        songs,
        sortCriteria
    ) { projectedSongs, sort ->
        useCase.extractGenres(projectedSongs, sort.option, sort.direction)
    }
        .flowOn(projectionDispatcher)
        .stateInUi(scope, emptyList())

    val songList: StateFlow<LibraryListModel> = catalog
        .map { it.projection.list }
        .stateInUi(scope, LibraryListModel.EMPTY)

    val recentSongs: StateFlow<List<Song>> = combine(
        rawSongs,
        playStats,
        searchQuery,
        overlayOpen
    ) { list, stats, query, paused ->
        if (paused) null else useCase.recentSongs(list, query, stats)
    }
        .filterNotNull()
        .flowOn(projectionDispatcher)
        .stateInUi(scope, emptyList())

    val recentList: StateFlow<LibraryListModel> = recentSongs
        .map { useCase.buildListModel(it, LibraryViewMode.FLAT, emphasizeLastPlayed = true) }
        .flowOn(projectionDispatcher)
        .stateInUi(scope, LibraryListModel.EMPTY)

    fun buildListModel(
        songs: List<Song>,
        viewMode: LibraryViewMode,
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC
    ): LibraryListModel =
        useCase.buildListModel(
            songs,
            viewMode,
            overridesByAlbum.value,
            sortOption,
            sortDirection
        )

    fun buildListItems(
        songs: List<Song>,
        viewMode: LibraryViewMode,
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC
    ): List<LibraryListItem> =
        buildListModel(songs, viewMode, sortOption, sortDirection).toListItems()
}

internal fun sameLibraryCatalog(old: List<Song>, new: List<Song>): Boolean {
    if (old === new) return true
    if (old.size != new.size) return false
    for (i in old.indices) {
        if (!old[i].sameCatalogIdentity(new[i])) return false
    }
    return true
}

internal fun Song.sameCatalogIdentity(other: Song): Boolean =
    id == other.id &&
        uriString == other.uriString &&
        title == other.title &&
        artist == other.artist &&
        album == other.album &&
        genre == other.genre &&
        durationMs == other.durationMs &&
        year == other.year &&
        trackNumber == other.trackNumber &&
        artworkUri == other.artworkUri &&
        folderPath == other.folderPath &&
        dateAdded == other.dateAdded
