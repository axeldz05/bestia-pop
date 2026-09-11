package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.CatalogCategory
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.domain.util.IdentifyCatalogQuery
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.matchKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinator for online catalog searches, debouncing, draft filtering, and category navigation.
 * Keeps [com.bestiapop.android.ui.MusicPlayerViewModel] lean while preserving UI facade API.
 */
class CatalogSearchCoordinator(
    private val scope: CoroutineScope,
    private val isOnline: () -> Boolean,
    private val onNotifyToast: (String) -> Unit,
    private val onSaveRecentSearch: (String) -> Unit
) {
    private val _state = MutableStateFlow(CatalogSearchUiState())
    val state: StateFlow<CatalogSearchUiState> = _state.asStateFlow()

    private var catalogSearchJob: Job? = null
    private var catalogDebounceJob: Job? = null
    private var catalogSearchGeneration: Long = 0L

    var lastQuery: String = ""
        private set
    var lastFilters: IdentifySearchFilters = IdentifySearchFilters()
        private set

    fun setCategory(category: CatalogCategory) {
        _state.update { it.copy(category = category) }
        search(lastQuery, lastFilters, saveToRecent = false)
    }

    fun setDraft(query: String) {
        _state.update { it.copy(searchQueryDraft = query) }
    }

    fun setFilterArtist(artist: String) {
        _state.update { it.copy(searchFilterArtist = artist) }
    }

    fun setFilterAlbum(album: String) {
        _state.update { it.copy(searchFilterAlbum = album) }
    }

    fun setFilterYear(year: String) {
        _state.update { it.copy(searchFilterYear = year) }
    }

    fun setFilters(filters: IdentifySearchFilters) {
        _state.update { it.withSearchFilters(filters) }
    }

    fun toggleFilters(show: Boolean? = null) {
        _state.update { current ->
            val next = show ?: !current.showSearchFilters
            current.copy(showSearchFilters = next)
        }
    }

    fun clearFilters() {
        _state.update {
            it.copy(
                searchFilterArtist = "",
                searchFilterAlbum = "",
                searchFilterYear = ""
            )
        }
    }

    fun updateTracks(transform: (List<OnlineCatalogTrack>) -> List<OnlineCatalogTrack>) {
        _state.update { it.copy(tracks = transform(it.tracks)) }
    }

    fun searchDebounced(
        query: String = _state.value.searchQueryDraft,
        filters: IdentifySearchFilters = _state.value.searchFilters,
        debounceMs: Long = 350L
    ) {
        catalogDebounceJob?.cancel()
        val cleanQ = query.trim()
        if (cleanQ.isEmpty()) {
            search(query = "", filters = filters, saveToRecent = false)
            return
        }
        catalogDebounceJob = scope.launch {
            delay(debounceMs)
            search(query = cleanQ, filters = filters, saveToRecent = false)
        }
    }

    fun submitSearch(
        query: String = _state.value.searchQueryDraft,
        filters: IdentifySearchFilters = _state.value.searchFilters
    ) {
        search(query = query, filters = filters, saveToRecent = true)
    }

    fun search(
        query: String = _state.value.searchQueryDraft,
        filters: IdentifySearchFilters = _state.value.searchFilters,
        saveToRecent: Boolean = false
    ) {
        catalogDebounceJob?.cancel()
        lastQuery = query
        lastFilters = filters
        val cleanQ = query.trim()
        if (saveToRecent && cleanQ.isNotBlank()) {
            onSaveRecentSearch(cleanQ)
        }
        val normalizedFilters = filters.normalized()
        val effectiveQuery = IdentifyCatalogQuery.build(cleanQ, normalizedFilters)
        val generation = ++catalogSearchGeneration
        val category = _state.value.category
        catalogSearchJob?.cancel()
        catalogSearchJob = scope.launch {
            _state.update { it.copy(isSearching = true, canLoadMore = true, isLoadingMore = false) }
            when (category) {
                CatalogCategory.SONGS -> {
                    val results = if (effectiveQuery.isEmpty() && !normalizedFilters.hasAny) {
                        MetadataFetcher.getFeaturedDemoCatalog()
                    } else {
                        MetadataFetcher.searchOnlineCatalog(effectiveQuery)
                    }
                    updateIfCurrent(generation) { it.copy(tracks = results, canLoadMore = results.isNotEmpty()) }
                }

                CatalogCategory.ALBUMS -> {
                    val albumQuery = if (effectiveQuery.isNotEmpty()) effectiveQuery else cleanQ
                    val results = MetadataFetcher.searchAlbums(albumQuery)
                    updateIfCurrent(generation) { it.copy(albums = results, canLoadMore = results.isNotEmpty()) }
                }

                CatalogCategory.PLAYLISTS -> {
                    val playlistQuery = if (cleanQ.isNotEmpty()) cleanQ else effectiveQuery
                    val results = MetadataFetcher.searchPlaylists(playlistQuery)
                    updateIfCurrent(generation) { it.copy(playlists = results) }
                }

                CatalogCategory.GENRES -> {
                    val genres = MetadataFetcher.listGenres()
                    val results = if (cleanQ.isEmpty()) {
                        genres
                    } else {
                        genres.filter { TrackMatchKeys.containsNormalized(it.name, cleanQ) }
                    }
                    updateIfCurrent(generation) { it.copy(genres = results) }
                }

                CatalogCategory.CHARTS -> {
                    val results = MetadataFetcher.fetchChartTracks()
                    updateIfCurrent(generation) { it.copy(tracks = results) }
                }
            }
            updateIfCurrent(generation) { it.copy(isSearching = false) }
            if (_state.value.currentResultsAreEmpty() && !isOnline()) {
                onNotifyToast("Sin conexión: no se pudo buscar en el catálogo")
            }
        }
    }

    fun searchMore() {
        val current = _state.value
        if (current.isSearching || current.isLoadingMore || !current.canLoadMore) return
        val cleanQ = lastQuery.trim()
        val normalizedFilters = lastFilters.normalized()
        val effectiveQuery = IdentifyCatalogQuery.build(cleanQ, normalizedFilters)
        if (effectiveQuery.isBlank() && cleanQ.isBlank()) return

        val category = current.category
        val generation = catalogSearchGeneration
        scope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (category) {
                CatalogCategory.SONGS -> {
                    val existingTracks = _state.value.tracks
                    val existingKeys = existingTracks.map { it.identity.matchKey() }.toMutableSet()
                    val newTracks = mutableListOf<OnlineCatalogTrack>()

                    fun appendDeduplicated(source: Iterable<OnlineCatalogTrack>) {
                        for (track in source) {
                            val key = track.identity.matchKey()
                            if (key.isNotBlank() && existingKeys.add(key)) {
                                newTracks.add(track)
                            }
                        }
                    }

                    // 1. Next page from Deezer
                    val nextPage = MetadataFetcher.searchOnlineCatalog(effectiveQuery, limit = 25, index = existingTracks.size)
                    appendDeduplicated(nextPage)

                    // 2. Also search YouTube and iTunes for deep search if Deezer provided few or none
                    if (newTracks.size < 10) {
                        val ytTracks = com.bestiapop.android.data.network.YouTubeExtractor.searchYouTube(effectiveQuery)
                        appendDeduplicated(ytTracks)

                        val itunesTracks = MetadataFetcher.searchItunesSongs(effectiveQuery, limit = 20)
                        appendDeduplicated(itunesTracks)
                    }

                    updateIfCurrent(generation) { s ->
                        s.copy(
                            tracks = s.tracks + newTracks,
                            isLoadingMore = false,
                            canLoadMore = newTracks.isNotEmpty()
                        )
                    }
                }

                CatalogCategory.ALBUMS -> {
                    val albumQuery = if (effectiveQuery.isNotEmpty()) effectiveQuery else cleanQ
                    val existingAlbums = _state.value.albums
                    val existingKeys = existingAlbums.map { it.matchKey() }.toMutableSet()
                    val nextAlbums = MetadataFetcher.searchAlbums(albumQuery, limit = 15, index = existingAlbums.size)
                    val newAlbums = nextAlbums.filter { existingKeys.add(it.matchKey()) }
                    updateIfCurrent(generation) { s ->
                        s.copy(
                            albums = s.albums + newAlbums,
                            isLoadingMore = false,
                            canLoadMore = newAlbums.isNotEmpty()
                        )
                    }
                }

                else -> {
                    updateIfCurrent(generation) { it.copy(isLoadingMore = false, canLoadMore = false) }
                }
            }
        }
    }

    private inline fun updateIfCurrent(
        generation: Long,
        crossinline transform: (CatalogSearchUiState) -> CatalogSearchUiState
    ) {
        if (generation == catalogSearchGeneration) {
            _state.update { transform(it) }
        }
    }
}
