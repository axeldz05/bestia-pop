package com.bestiapop.android.ui.screens.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.toPlayableItems
import com.bestiapop.android.ui.state.CatalogCollectionKind
import com.bestiapop.android.data.preferences.DiscoverSourcePreference
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.CatalogCategoryChipsRow
import com.bestiapop.android.ui.components.LocalSubmenuGestureSettings
import com.bestiapop.android.ui.components.SearchHistorySheet
import com.bestiapop.android.ui.components.SongItemActions
import com.bestiapop.android.ui.components.SubmenuSwipeBox
import com.bestiapop.android.ui.components.findAlbumDownloadProgress
import com.bestiapop.android.ui.components.preloadArtwork
import com.bestiapop.android.ui.components.rememberSongQueueActions
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs
import com.bestiapop.android.ui.state.PlaylistDetailNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier
) {
    val gestureSettings by viewModel.submenuGestureSettings.collectAsStateWithLifecycle()
    val catalogSearch by viewModel.catalogSearch.collectAsStateWithLifecycle()
    val catalogCollection by viewModel.catalogCollection.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val discoverFeed by viewModel.discoverFeed.collectAsStateWithLifecycle()
    val isLoadingFeed by viewModel.isLoadingDiscoverFeed.collectAsStateWithLifecycle()
    val discoverSource by viewModel.discoverSource.collectAsStateWithLifecycle()
    val topRelatedFeed by viewModel.topRelatedFeed.collectAsStateWithLifecycle()
    val isLoadingTopRelated by viewModel.isLoadingTopRelatedFeed.collectAsStateWithLifecycle()

    val lbDiscover by viewModel.lbDiscover.collectAsStateWithLifecycle()
    val lbPlaylistDetail by viewModel.lbPlaylistDetail.collectAsStateWithLifecycle()
    val cfRecommendationsState by viewModel.cfRecommendations.collectAsStateWithLifecycle()
    val cfRecommendations = cfRecommendationsState.data
    val lbSettings by viewModel.listenBrainzSettings.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val playlistDetail = navigation.playlistDetail
    val selectedLbPlaylistMbid = (playlistDetail as? PlaylistDetailNav.ListenBrainz)?.mbid
    val cfDetailOpen = playlistDetail is PlaylistDetailNav.CfRecommendations

    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(emptyList())
    val songActions = rememberSongQueueActions(viewModel)
    val songDialogs = rememberSongActionDialogs(viewModel = viewModel, playlists = playlists)
    val songItemActions = remember(songActions, songDialogs) {
        SongItemActions.from(songActions, songDialogs)
    }

    var searchInput by remember { mutableStateOf(catalogSearch.searchQueryDraft) }
    var isSearchFocused by remember { mutableStateOf(false) }
    var showSearchHistorySheet by remember { mutableStateOf(false) }

    val feedScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val context = LocalContext.current
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        if (discoverFeed.recommendedTracks.isEmpty() && discoverFeed.recommendedAlbums.isEmpty()) {
            viewModel.refreshDiscoverFeed()
        }
        if (topRelatedFeed.topArtists.isEmpty() && topRelatedFeed.topAlbums.isEmpty()) {
            viewModel.refreshTopRelatedFeed()
        }
    }

    LaunchedEffect(discoverFeed) {
        val trackSizePx = with(density) { 124.dp.roundToPx().coerceAtLeast(1) }
        val albumSizePx = with(density) { 134.dp.roundToPx().coerceAtLeast(1) }
        val listItemSizePx = with(density) { 48.dp.roundToPx().coerceAtLeast(1) }

        discoverFeed.recommendedTracks.take(12).forEach { track ->
            track.artworkUri?.let { preloadArtwork(context, it, trackSizePx) }
        }
        discoverFeed.recommendedAlbums.forEach { album ->
            album.coverUrl?.let { preloadArtwork(context, it, albumSizePx) }
        }
        discoverFeed.chartTracks.take(8).forEach { track ->
            track.artworkUri?.let { preloadArtwork(context, it, listItemSizePx) }
        }
    }

    LaunchedEffect(topRelatedFeed) {
        val artistSizePx = with(density) { 56.dp.roundToPx().coerceAtLeast(1) }
        val albumSizePx = with(density) { 116.dp.roundToPx().coerceAtLeast(1) }
        val trackSizePx = with(density) { 48.dp.roundToPx().coerceAtLeast(1) }

        topRelatedFeed.topArtists.forEach { artist ->
            artist.artworkUri?.let { preloadArtwork(context, it, artistSizePx) }
        }
        topRelatedFeed.topAlbums.forEach { album ->
            album.artworkUri?.let { preloadArtwork(context, it, albumSizePx) }
        }
        topRelatedFeed.topTracks.forEach { track ->
            track.artworkUri?.let { preloadArtwork(context, it, trackSizePx) }
        }
    }

    val selectedCollectionTitle = catalogCollection.title
    val activeCandidates = catalogCollection.candidates
    val isLoadingCollection = catalogCollection.isLoading

    val hasNestedBack = selectedCollectionTitle != null || selectedLbPlaylistMbid != null || cfDetailOpen
    BackHandler(enabled = hasNestedBack) {
        if (selectedCollectionTitle != null) {
            viewModel.clearSelectedCollection()
        } else if (selectedLbPlaylistMbid != null || cfDetailOpen) {
            viewModel.closePlaylistDetail()
        }
    }

    val swipeActions = remember(viewModel, gestureSettings.swipeLeftAction) {
        DiscoverSwipeActions(
            onSwipeTrack = { track ->
                viewModel.executeSubmenuActionForTrack(
                    gestureSettings.swipeLeftAction,
                    track,
                    onAddToPlaylist = { song -> songItemActions.onAddToPlaylist?.invoke(song) }
                )
            },
            onSwipeAlbum = { album ->
                viewModel.executeSubmenuActionForAlbum(
                    gestureSettings.swipeLeftAction,
                    album
                )
            },
            onSwipeArtist = { artistName ->
                viewModel.executeSubmenuActionForArtist(
                    gestureSettings.swipeLeftAction,
                    artistName = artistName
                )
            }
        )
    }

    val discoverContext = remember(swipeActions, activeDownloads, viewModel) {
        DiscoverContext(
            swipeActions = swipeActions,
            activeDownloads = activeDownloads,
            getTrackStatus = viewModel::getTrackLibraryStatus,
            getAlbumStatus = viewModel::getAlbumLibraryStatus,
            onNotifyStatus = { viewModel.toast(it) }
        )
    }

    CompositionLocalProvider(
        LocalSubmenuGestureSettings provides gestureSettings,
        LocalDiscoverContext provides discoverContext
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            if (selectedCollectionTitle != null) {
                SubmenuSwipeBox(
                    settings = gestureSettings,
                    onSwipeRight = { viewModel.clearSelectedCollection() },
                    onSwipeLeft = {
                        if (catalogCollection.kind == CatalogCollectionKind.ARTIST) {
                            swipeActions.onSwipeArtist?.invoke(selectedCollectionTitle)
                        } else {
                            viewModel.executeSubmenuActionForAlbum(
                                gestureSettings.swipeLeftAction,
                                albumTitle = selectedCollectionTitle,
                                artistName = activeCandidates.firstOrNull()?.artist.orEmpty(),
                                albumId = catalogCollection.selectionKey.orEmpty(),
                                coverUrl = catalogCollection.coverUrl
                            )
                        }
                    },
                    canSwipeBack = true,
                    canExecuteAction = activeCandidates.isNotEmpty()
                ) {
                    val albumProgress = activeDownloads.findAlbumDownloadProgress(
                        albumTitle = selectedCollectionTitle,
                        artistName = activeCandidates.firstOrNull()?.artist.orEmpty()
                    )

                    if (catalogCollection.kind == CatalogCollectionKind.ARTIST) {
                        DiscoverArtistDetailSection(
                            viewModel = viewModel,
                            artistName = selectedCollectionTitle,
                            coverUrl = catalogCollection.coverUrl,
                            candidates = activeCandidates,
                            albums = catalogCollection.albums,
                            isLoading = isLoadingCollection,
                            currentItem = currentItem
                        )
                    } else {
                        val albumStatus = viewModel.getAlbumLibraryStatus(
                            albumTitle = selectedCollectionTitle,
                            artistName = activeCandidates.firstOrNull()?.artist.orEmpty()
                        )

                        val collectionActions = remember(
                            activeCandidates,
                            selectedCollectionTitle,
                            albumProgress
                        ) {
                            DiscoverCollectionActions(
                                onBack = { viewModel.clearSelectedCollection() },
                                onPlayAll = {
                                    viewModel.playCatalogCandidates(activeCandidates, startIndex = 0, startShuffled = false)
                                },
                                onShuffle = {
                                    viewModel.playCatalogCandidates(activeCandidates, startIndex = 0, startShuffled = true)
                                },
                                onSaveAlbum = {
                                    viewModel.saveAlbumToLibrary(
                                        albumTitle = selectedCollectionTitle,
                                        artistName = activeCandidates.firstOrNull()?.artist.orEmpty(),
                                        coverUrl = catalogCollection.coverUrl,
                                        candidates = activeCandidates,
                                        albumId = catalogCollection.selectionKey.orEmpty()
                                    )
                                },
                                onDownloadAll = {
                                    viewModel.downloadSelectedCandidatesBatch()
                                },
                                onPlayCandidate = { candidate ->
                                    val index = activeCandidates.indexOf(candidate)
                                    if (index >= 0) {
                                        viewModel.playCatalogCandidates(activeCandidates, startIndex = index, startShuffled = false)
                                    } else {
                                        viewModel.playCatalogCandidate(candidate)
                                    }
                                },
                                onDownloadCandidate = { candidate ->
                                    viewModel.downloadCatalogCandidate(candidate)
                                },
                                onSelectArtist = { artistName ->
                                    viewModel.selectArtistForInspection(artistName)
                                },
                                albumDownloadProgress = albumProgress
                            )
                        }

                        // Collection Drill-down view (Album / Playlist / Genre)
                        DiscoverCollectionDetailView(
                            title = selectedCollectionTitle,
                            kind = catalogCollection.kind ?: CatalogCollectionKind.ALBUM,
                            coverUrl = catalogCollection.coverUrl,
                            candidates = activeCandidates,
                            isLoading = isLoadingCollection,
                            albumStatus = albumStatus,
                            currentItem = currentItem,
                            actions = collectionActions
                        )
                    }
                }
            } else if (selectedLbPlaylistMbid != null || cfDetailOpen) {
                val lbItems = lbPlaylistDetail.data?.toPlayableItems() ?: emptyList()
                val cfItems = cfRecommendationsState.data?.toPlayableItems() ?: emptyList()
                val currentItems = if (selectedLbPlaylistMbid != null) lbItems else cfItems
                SubmenuSwipeBox(
                    settings = gestureSettings,
                    onSwipeRight = { viewModel.closePlaylistDetail() },
                    onSwipeLeft = { viewModel.executeSubmenuActionForPlayables(gestureSettings.swipeLeftAction, currentItems) },
                    canSwipeBack = true,
                    canExecuteAction = currentItems.isNotEmpty()
                ) {
                    DiscoverPlaylistDetailHost(
                        selectedLbPlaylistMbid = selectedLbPlaylistMbid,
                        cfDetailOpen = cfDetailOpen,
                        lbPlaylistDetail = lbPlaylistDetail,
                        cfRecommendationsState = cfRecommendationsState,
                        currentItem = currentItem,
                        activeDownloads = activeDownloads,
                        songActions = songActions,
                        onEditLyrics = songDialogs.onEditLyrics,
                        onBack = { viewModel.closePlaylistDetail() },
                        onDownloadRemote = { viewModel.downloadRemoteItem(it) },
                        onRetryDownload = viewModel::retryActiveDownload,
                        onCancelDownload = viewModel::dismissActiveDownload,
                        onPlayMatched = { items, origin, startIndex ->
                            viewModel.playMatchedTracks(items, origin, startIndex = startIndex)
                        },
                        onShuffleMatched = { items, origin ->
                            viewModel.shuffleMatchedTracks(items, origin)
                        },
                        onSaveLbAsLocal = { onComplete ->
                            viewModel.saveListenBrainzPlaylistAsLocal(onComplete)
                        },
                        onOpenLocalPlaylist = { newId ->
                            viewModel.openLocalPlaylist(newId)
                        },
                        onImportLbWithDownloads = {
                            viewModel.importListenBrainzPlaylistWithDownloads()
                        },
                        songItemActions = songItemActions,
                        onSwipeRemote = { remote ->
                            viewModel.executeSubmenuActionForPlayables(gestureSettings.swipeLeftAction, listOf(remote))
                        }
                    )
                }
            } else {
                val isSearchActive = searchInput.isNotBlank() || catalogSearch.hasActiveFilters || catalogSearch.isSearching

                Column(modifier = Modifier.fillMaxSize()) {
                    DiscoverTopSearchBar(
                        query = searchInput,
                        onQueryChange = {
                            searchInput = it
                            viewModel.setCatalogSearchDraft(it)
                            viewModel.searchCatalogDebounced(query = it)
                        },
                        onSearch = { query ->
                            if (query.isNotBlank()) {
                                viewModel.submitCatalogSearch(query = query)
                                isSearchFocused = false
                            }
                        },
                        onClear = {
                            searchInput = ""
                            viewModel.setCatalogSearchDraft("")
                            viewModel.searchCatalog(query = "", saveToRecent = false)
                        },
                        showFilters = catalogSearch.showSearchFilters,
                        onToggleFilters = { viewModel.toggleCatalogSearchFilters() },
                        hasActiveFilters = catalogSearch.hasActiveFilters,
                        onRefreshFeed = {
                            viewModel.refreshDiscoverFeed(forceRefresh = true)
                            viewModel.refreshTopRelatedFeed(forceRefresh = true)
                        },
                        isLoading = isLoadingFeed || catalogSearch.isSearching,
                        onOpenHistory = { showSearchHistorySheet = true }
                    )

                    // Category Chips (when searching or active)
                    if (isSearchActive) {
                        CatalogCategoryChipsRow(
                            selectedCategory = catalogSearch.category,
                            onSelectCategory = { category ->
                                viewModel.setCatalogCategory(category)
                            },
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    // Advanced Search Filters Panel
                    AnimatedVisibility(visible = catalogSearch.showSearchFilters) {
                        DiscoverAdvancedFiltersPanel(
                            filters = catalogSearch.searchFilters,
                            onFiltersChange = viewModel::setCatalogSearchFilters,
                            onApply = { viewModel.searchCatalog(query = searchInput, saveToRecent = false) },
                            onClear = {
                                viewModel.clearCatalogSearchFilters()
                                viewModel.searchCatalog(query = searchInput, saveToRecent = false)
                            }
                        )
                    }

                    // Recent Searches Suggestions
                    if (searchInput.isBlank() && recentSearches.isNotEmpty() && isSearchFocused) {
                        DiscoverRecentSearchesView(
                            recentSearches = recentSearches,
                            onSelectQuery = { query ->
                                searchInput = query
                                viewModel.setCatalogSearchDraft(query)
                                viewModel.submitCatalogSearch(query = query)
                                isSearchFocused = false
                            },
                            onRemoveQuery = { viewModel.removeRecentSearch(it) },
                            onClearAll = { viewModel.clearRecentSearches() },
                            onOpenFullHistory = { showSearchHistorySheet = true }
                        )
                    } else {
                        val catalogActions = remember(viewModel, catalogSearch.isLoadingMore, catalogSearch.canLoadMore) {
                            DiscoverCatalogActions(
                                onPlayTrack = viewModel::playCatalogOrLocalTrack,
                                onDownloadTrack = viewModel::downloadOnlineTrack,
                                onSelectAlbum = viewModel::selectAlbumForInspection,
                                onSaveAlbum = { album -> viewModel.saveAlbumToLibrary(album) },
                                onSelectPlaylist = viewModel::selectPlaylistForInspection,
                                onSelectGenre = viewModel::selectGenreForInspection,
                                onSearchMore = viewModel::searchMore,
                                onSelectArtist = viewModel::selectArtistForInspection,
                                isLoadingMore = catalogSearch.isLoadingMore,
                                canLoadMore = catalogSearch.canLoadMore
                            )
                        }

                        val topRelatedActions = remember(viewModel) {
                            DiscoverTopRelatedActions(
                                onSelectArtist = { artistName ->
                                    viewModel.selectArtistForInspection(artistName)
                                },
                                onStartRadioForArtist = { artistName ->
                                    val artistSongs = viewModel.songsForArtist(viewModel.libraryProjection.songs.value, artistName)
                                    if (artistSongs.isNotEmpty()) {
                                        viewModel.startRadio(seedSong = artistSongs.random())
                                    } else {
                                        viewModel.selectArtistForInspection(artistName)
                                    }
                                },
                                onSelectAlbum = viewModel::selectAlbumForInspection,
                                onPlayTrack = { item ->
                                    if (item.localSong != null) {
                                        viewModel.playSong(item.localSong)
                                    } else {
                                        searchInput = item.title
                                        viewModel.setCatalogSearchDraft(item.title)
                                        viewModel.submitCatalogSearch(item.title)
                                    }
                                },
                                onRefresh = { viewModel.refreshTopRelatedFeed(forceRefresh = true) }
                            )
                        }

                        val lbActions = remember(viewModel) {
                            DiscoverListenBrainzActions(
                                onOpenPlaylist = { viewModel.openListenBrainzPlaylistDetail(it) },
                                onOpenCfRecommendations = { viewModel.openCfRecommendationsDetail() }
                            )
                        }
                        val onRefreshFeed = remember(viewModel) { { viewModel.refreshDiscoverFeed(forceRefresh = true) } }
                        val onSourceChange = remember(viewModel) { { src: DiscoverSourcePreference -> viewModel.setDiscoverSource(src) } }

                        if (isSearchActive) {
                            // Search Results View
                            DiscoverSearchResultsView(
                                category = catalogSearch.category,
                                isSearching = catalogSearch.isSearching,
                                tracks = catalogSearch.tracks,
                                albums = catalogSearch.albums,
                                playlists = catalogSearch.playlists,
                                genres = catalogSearch.genres,
                                actions = catalogActions
                            )
                        } else {
                            // Home Discover Feed View
                            DiscoverHomeFeedView(
                                feed = discoverFeed,
                                isLoading = isLoadingFeed,
                                onRefresh = onRefreshFeed,
                                source = discoverSource,
                                onSourceChange = onSourceChange,
                                topRelatedFeed = topRelatedFeed,
                                isLoadingTopRelated = isLoadingTopRelated,
                                topRelatedActions = topRelatedActions,
                                lbDiscoverPlaylists = lbDiscover.data ?: emptyList(),
                                cfRecommendations = cfRecommendations,
                                lbActions = lbActions,
                                showLbSections = discoverSource != DiscoverSourcePreference.DEEZER && lbSettings.enabled,
                                actions = catalogActions,
                                scrollState = feedScrollState
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSearchHistorySheet) {
        SearchHistorySheet(
            recentSearches = recentSearches,
            onSelectQuery = { query ->
                searchInput = query
                viewModel.setCatalogSearchDraft(query)
                viewModel.submitCatalogSearch(query = query)
                isSearchFocused = false
            },
            onRemoveQuery = { viewModel.removeRecentSearch(it) },
            onClearAll = { viewModel.clearRecentSearches() },
            onDismiss = { showSearchHistorySheet = false }
        )
    }
}
