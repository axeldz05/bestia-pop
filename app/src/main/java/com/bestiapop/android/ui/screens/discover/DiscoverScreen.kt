package com.bestiapop.android.ui.screens.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.*
import com.bestiapop.android.domain.usecase.DiscoverFeed
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.CatalogCategoryChipsRow
import com.bestiapop.android.ui.components.CircleActionBox
import com.bestiapop.android.ui.components.HeaderActionIcon
import com.bestiapop.android.ui.components.preloadArtwork
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.ScreenBackHeader
import com.bestiapop.android.ui.components.SearchHistorySheet
import com.bestiapop.android.ui.components.SearchRecentChipsRow
import com.bestiapop.android.ui.components.TrackMetaRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.bestiapop.android.data.listenbrainz.MatchedCfRecommendations
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.model.DiscoverPlaybackOrigin
import com.bestiapop.android.data.preferences.DiscoverSourcePreference
import com.bestiapop.android.domain.usecase.RelatedAlbumItem
import com.bestiapop.android.domain.usecase.RelatedArtistItem
import com.bestiapop.android.domain.usecase.RelatedTrackItem
import com.bestiapop.android.domain.usecase.TopRelatedFeed
import com.bestiapop.android.ui.components.artistAlbumLabel
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs
import com.bestiapop.android.ui.components.SongItemActions
import com.bestiapop.android.data.preferences.SubmenuSwipeAction
import com.bestiapop.android.ui.components.SubmenuSwipeBox
import com.bestiapop.android.ui.components.SongQueueActions
import com.bestiapop.android.ui.components.rememberSongQueueActions
import com.bestiapop.android.ui.state.PlaylistDetailNav
import com.bestiapop.android.ui.state.CatalogCollectionKind
import com.bestiapop.android.ui.state.ItemLibraryStatus
import com.bestiapop.android.ui.components.PlayIconButton
import com.bestiapop.android.ui.components.isCurrentPlaying
import com.bestiapop.android.ui.components.ActiveAlbumDownloadProgress
import com.bestiapop.android.ui.components.AlbumDownloadStateButton
import com.bestiapop.android.ui.components.findAlbumDownloadProgress
import com.bestiapop.android.ui.components.isAlbumDownloading
import com.bestiapop.android.ui.components.MediaCardDownloadSpinner
import com.bestiapop.android.ui.components.findUiDownloadByTrack
import com.bestiapop.android.ui.components.DownloadStateTrailing
import com.bestiapop.android.ui.components.DownloadProgressPercent
import com.bestiapop.android.ui.components.DownloadQueuedLabel
import com.bestiapop.android.ui.components.DownloadSuccessReadyLabel
import com.bestiapop.android.ui.theme.ListDensity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier
) {
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
    val selectedLbPlaylist = lbPlaylistDetail.data
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

    Box(modifier = modifier.fillMaxSize()) {
        if (selectedCollectionTitle != null) {
            val gestureSettings by viewModel.submenuGestureSettings.collectAsStateWithLifecycle()
            SubmenuSwipeBox(
                settings = gestureSettings,
                onSwipeRight = { viewModel.clearSelectedCollection() },
                onSwipeLeft = {
                    viewModel.executeSubmenuActionForCandidates(
                        action = gestureSettings.swipeLeftAction,
                        candidates = activeCandidates
                    )
                },
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
                        currentItem = currentItem,
                        activeDownloads = activeDownloads
                    )
                } else {
                    val albumStatus = viewModel.getAlbumLibraryStatus(
                        albumTitle = selectedCollectionTitle,
                        artistName = activeCandidates.firstOrNull()?.artist.orEmpty()
                    )

                    val collectionActions = remember(
                        activeCandidates,
                        selectedCollectionTitle,
                        albumProgress,
                        activeDownloads
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
                            albumDownloadProgress = albumProgress,
                            activeDownloads = activeDownloads,
                            getTrackStatus = viewModel::getTrackLibraryStatus,
                            onAlreadyInLibrary = { viewModel.toast(it) }
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
            val gestureSettings by viewModel.submenuGestureSettings.collectAsStateWithLifecycle()
            val lbItems = lbPlaylistDetail.data?.toPlayableItems() ?: emptyList()
            val cfItems = cfRecommendationsState.data?.toPlayableItems() ?: emptyList()
            val currentItems = if (selectedLbPlaylistMbid != null) lbItems else cfItems
            SubmenuSwipeBox(
                settings = gestureSettings,
                onSwipeRight = { viewModel.closePlaylistDetail() },
                onSwipeLeft = {
                    viewModel.executeSubmenuActionForPlayables(
                        action = gestureSettings.swipeLeftAction,
                        items = currentItems,
                        onAddToPlaylist = {
                            if (selectedLbPlaylistMbid != null) {
                                viewModel.saveListenBrainzPlaylistAsLocal { newId ->
                                    viewModel.openLocalPlaylist(newId)
                                }
                            }
                        }
                    )
                },
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
                    songItemActions = songItemActions
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
                    val catalogActions = remember(viewModel, catalogSearch.isLoadingMore, catalogSearch.canLoadMore, activeDownloads) {
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
                            canLoadMore = catalogSearch.canLoadMore,
                            activeDownloads = activeDownloads,
                            getTrackStatus = viewModel::getTrackLibraryStatus,
                            getAlbumStatus = viewModel::getAlbumLibraryStatus,
                            onAlreadyInLibrary = { viewModel.toast(it) }
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

@Composable
fun DiscoverTopSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    showFilters: Boolean,
    onToggleFilters: () -> Unit,
    hasActiveFilters: Boolean,
    onRefreshFeed: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onOpenHistory: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            placeholder = {
                Text(
                    text = "Buscar canciones, álbumes, artistas…",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Buscar",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Limpiar"
                            )
                        }
                    } else if (onOpenHistory != null) {
                        IconButton(onClick = onOpenHistory) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Historial de búsqueda"
                            )
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(26.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onToggleFilters,
            modifier = Modifier.background(
                color = if (hasActiveFilters) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape
            )
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Filtros",
                tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = onRefreshFeed
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refrescar recomendaciones",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}



@Composable
fun DiscoverRecentSearchesView(
    recentSearches: List<String>,
    onSelectQuery: (String) -> Unit,
    onRemoveQuery: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenFullHistory: (() -> Unit)? = null
) {
    SearchRecentChipsRow(
        recentSearches = recentSearches,
        onSelectQuery = onSelectQuery,
        onRemoveQuery = onRemoveQuery,
        onClearAll = onClearAll,
        onOpenFullHistory = onOpenFullHistory,
        modifier = modifier
    )
}

/** Level 1: Reusable icon representation of an item's library status. */
@Composable
fun ItemLibraryStatusIcon(
    status: ItemLibraryStatus,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    when (status) {
        ItemLibraryStatus.DOWNLOADED -> {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "En la biblioteca",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier.size(size)
            )
        }
        ItemLibraryStatus.SAVED_REMOTE -> {
            Icon(
                imageVector = Icons.Default.BookmarkAdded,
                contentDescription = "Guardada en biblioteca",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = modifier.size(size)
            )
        }
        ItemLibraryStatus.NOT_IN_LIBRARY -> Unit
    }
}

@Composable
internal fun DiscoverActionIcon(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = 20.dp,
    boxSize: Dp = 36.dp
) {
    HeaderActionIcon(
        onClick = onClick,
        icon = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
        iconSize = iconSize,
        boxSize = boxSize
    )
}

@Composable
internal fun DiscoverStatusActionIcon(
    onClick: () -> Unit,
    status: ItemLibraryStatus,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    boxSize: Dp = 36.dp
) {
    CircleActionBox(
        onClick = onClick,
        modifier = modifier,
        boxSize = boxSize
    ) {
        ItemLibraryStatusIcon(status = status, size = iconSize)
    }
}

/** Level 2: Track library action buttons (Downloaded, Saved Remote, Download, In-Flight progress). */
@Composable
fun TrackLibraryActionButtons(
    status: ItemLibraryStatus,
    onDownload: () -> Unit,
    activeDownload: ActiveDownload? = null,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadyInLibrary: () -> Unit = { onNotifyStatus?.invoke(status.trackMessage) },
    modifier: Modifier = Modifier
) {
    if (activeDownload != null) {
        DownloadStateTrailing(
            state = activeDownload.state,
            percent = activeDownload.progressPercent,
            onRetry = onDownload,
            onDownload = onDownload,
            successContent = {
                DiscoverStatusActionIcon(
                    onClick = onAlreadyInLibrary,
                    status = ItemLibraryStatus.DOWNLOADED,
                    iconSize = 20.dp,
                    boxSize = 36.dp,
                    modifier = modifier
                )
            }
        )
        return
    }
    when (status) {
        ItemLibraryStatus.DOWNLOADED -> {
            DiscoverStatusActionIcon(
                onClick = onAlreadyInLibrary,
                status = status,
                iconSize = 20.dp,
                boxSize = 36.dp,
                modifier = modifier
            )
        }
        ItemLibraryStatus.SAVED_REMOTE -> {
            Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                DiscoverStatusActionIcon(
                    onClick = onAlreadyInLibrary,
                    status = status,
                    iconSize = 20.dp,
                    boxSize = 36.dp
                )
                DiscoverActionIcon(
                    onClick = onDownload,
                    icon = Icons.Default.Download,
                    contentDescription = "Descargar localmente",
                    tint = MaterialTheme.colorScheme.primary,
                    iconSize = 20.dp,
                    boxSize = 36.dp
                )
            }
        }
        ItemLibraryStatus.NOT_IN_LIBRARY -> {
            DiscoverActionIcon(
                onClick = onDownload,
                icon = Icons.Default.Download,
                contentDescription = "Descargar",
                tint = MaterialTheme.colorScheme.primary,
                iconSize = 20.dp,
                boxSize = 36.dp,
                modifier = modifier
            )
        }
    }
}

/** Level 2: Album save / saved icon button. */
@Composable
fun AlbumLibraryActionButton(
    status: ItemLibraryStatus,
    onSave: () -> Unit,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadySaved: () -> Unit = { onNotifyStatus?.invoke(status.albumMessage) },
    modifier: Modifier = Modifier
) {
    when (status) {
        ItemLibraryStatus.DOWNLOADED, ItemLibraryStatus.SAVED_REMOTE -> {
            DiscoverStatusActionIcon(
                onClick = onAlreadySaved,
                status = status,
                iconSize = 18.dp,
                boxSize = 32.dp,
                modifier = modifier
            )
        }
        ItemLibraryStatus.NOT_IN_LIBRARY -> {
            DiscoverActionIcon(
                onClick = onSave,
                icon = Icons.Default.BookmarkAdd,
                contentDescription = "Guardar álbum",
                tint = MaterialTheme.colorScheme.primary,
                iconSize = 18.dp,
                boxSize = 32.dp,
                modifier = modifier
            )
        }
    }
}

/** Level 2: Album save / status button for collection headers. */
@Composable
fun AlbumLibraryHeaderButton(
    status: ItemLibraryStatus,
    onSaveAlbum: () -> Unit,
    onAlreadyInLibrary: (String) -> Unit
) {
    when (status) {
        ItemLibraryStatus.DOWNLOADED -> {
            FilledTonalButton(
                onClick = { onAlreadyInLibrary(status.albumMessage) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("En biblioteca")
            }
        }
        ItemLibraryStatus.SAVED_REMOTE -> {
            OutlinedButton(
                onClick = { onAlreadyInLibrary(status.albumMessage) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.BookmarkAdded, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Guardado")
            }
        }
        ItemLibraryStatus.NOT_IN_LIBRARY -> {
            OutlinedButton(
                onClick = onSaveAlbum,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Guardar")
            }
        }
    }
}

/**
 * Level 1: Low-level primitive media card (Artwork + text + customizable overlay badges/actions).
 * Provides continuous granularity for all catalog/discover cards.
 */
@Composable
fun DiscoverMediaCard(
    title: String,
    subtitle: String,
    artworkUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp? = null,
    imageSize: Dp? = null,
    aspectRatio: Float = 1f,
    topEndBadge: @Composable (BoxScope.() -> Unit)? = null,
    bottomEndAction: @Composable (BoxScope.() -> Unit)? = null
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .then(if (cardWidth != null) Modifier.width(cardWidth) else Modifier.fillMaxWidth()),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            val imageBoxModifier = if (imageSize != null) {
                Modifier.size(imageSize)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
            }
            Box(modifier = imageBoxModifier) {
                ArtworkThumbnail(
                    artworkUri = artworkUri,
                    size = imageSize,
                    cornerRadius = 12.dp,
                    modifier = Modifier.fillMaxSize()
                )

                topEndBadge?.invoke(this)
                bottomEndAction?.invoke(this)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Level 1: Reusable top-end badge container for media cards. */
@Composable
fun BoxScope.MediaCardBadge(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(26.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Level 1: Reusable bottom-end circular action button for media cards. */
@Composable
fun BoxScope.MediaCardAction(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimary,
    size: Dp = 36.dp
) {
    Box(
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp)
            .size(size)
            .background(containerColor, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

/** Level 2: Track card with play overlay and library status badge. */
@Composable
fun DiscoverTrackCard(
    track: OnlineCatalogTrack,
    onPlay: () -> Unit,
    onDownload: () -> Unit = {},
    status: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadyInLibrary: () -> Unit = { onNotifyStatus?.invoke(status.trackMessage) },
    modifier: Modifier = Modifier
) {
    DiscoverMediaCard(
        title = track.title,
        subtitle = track.artist,
        artworkUri = track.artworkUri,
        cardWidth = 140.dp,
        imageSize = 124.dp,
        onClick = onPlay,
        modifier = modifier,
        topEndBadge = {
            if (status.isPresent) {
                MediaCardBadge(onClick = onAlreadyInLibrary) {
                    ItemLibraryStatusIcon(status = status, size = 18.dp)
                }
            }
        },
        bottomEndAction = {
            MediaCardAction(
                onClick = onPlay,
                icon = Icons.Default.PlayArrow,
                contentDescription = "Reproducir"
            )
        }
    )
}

/** Level 1: Low-level primitive album card with customizable badges and actions. */
@Composable
fun DiscoverAlbumCard(
    title: String,
    artist: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 150.dp,
    imageSize: Dp = 134.dp,
    topEndBadge: @Composable (BoxScope.() -> Unit)? = null,
    bottomEndAction: @Composable (BoxScope.() -> Unit)? = null
) {
    DiscoverMediaCard(
        title = title,
        subtitle = artist,
        artworkUri = coverUrl,
        cardWidth = cardWidth,
        imageSize = imageSize,
        onClick = onClick,
        modifier = modifier,
        topEndBadge = topEndBadge,
        bottomEndAction = bottomEndAction
    )
}

/** Level 2: Album card with save/status action button and download progress indicator. */
@Composable
fun DiscoverAlbumCard(
    album: CatalogAlbum,
    onClick: () -> Unit,
    onSave: () -> Unit,
    status: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    activeDownloads: List<ActiveDownload>? = null,
    isDownloading: Boolean = activeDownloads?.isAlbumDownloading(album) ?: false,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadySaved: () -> Unit = { onNotifyStatus?.invoke(status.albumMessage) },
    modifier: Modifier = Modifier
) {
    DiscoverAlbumCard(
        title = album.title,
        artist = album.artist,
        coverUrl = album.coverUrl,
        onClick = onClick,
        modifier = modifier,
        bottomEndAction = {
            if (isDownloading) {
                MediaCardDownloadSpinner()
            } else {
                AlbumLibraryActionButton(
                    status = status,
                    onSave = onSave,
                    onAlreadySaved = onAlreadySaved,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                )
            }
        }
    )
}

/** Level 2: Reusable Discover track list item using [TrackMetaRow], with continuous granularity slots. */
@Composable
fun DiscoverTrackListItem(
    track: TrackMeta,
    onPlay: () -> Unit,
    onDownload: () -> Unit = {},
    activeDownload: ActiveDownload? = null,
    status: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    highlighted: Boolean = false,
    subtitle: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadyInLibrary: () -> Unit = { onNotifyStatus?.invoke(status.trackMessage) },
    modifier: Modifier = Modifier
) {
    TrackMetaRow(
        artworkUri = track.artworkUri,
        title = track.title,
        subtitle = subtitle ?: track.artist,
        highlighted = highlighted,
        leading = leading,
        onClick = onPlay,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        trailing = trailing ?: {
            TrackLibraryActionButtons(
                status = status,
                onDownload = onDownload,
                activeDownload = activeDownload,
                onNotifyStatus = onNotifyStatus,
                onAlreadyInLibrary = onAlreadyInLibrary
            )
        }
    )
}

/**
 * Level 2: Shared stack frame bundling user interaction callbacks across Discover feed and search.
 */
@Immutable
data class DiscoverCatalogActions(
    val onPlayTrack: (OnlineCatalogTrack) -> Unit,
    val onDownloadTrack: (OnlineCatalogTrack) -> Unit,
    val onSelectAlbum: (CatalogAlbum) -> Unit,
    val onSaveAlbum: (CatalogAlbum) -> Unit,
    val onSelectPlaylist: (CatalogPlaylist) -> Unit = {},
    val onSelectGenre: (CatalogGenre) -> Unit = {},
    val onSearchMore: () -> Unit = {},
    val onSelectArtist: (String) -> Unit = {},
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val activeDownloads: List<ActiveDownload> = emptyList(),
    val getTrackStatus: (TrackMeta) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    val getAlbumStatus: (String, String) -> ItemLibraryStatus = { _, _ -> ItemLibraryStatus.NOT_IN_LIBRARY },
    val onAlreadyInLibrary: (String) -> Unit = {}
)

/**
 * Level 2: Actions for the top related section (artists, albums, tracks).
 */
@Immutable
data class DiscoverTopRelatedActions(
    val onSelectArtist: (String) -> Unit = {},
    val onStartRadioForArtist: (String) -> Unit = {},
    val onSelectAlbum: (RelatedAlbumItem) -> Unit = {},
    val onPlayTrack: (RelatedTrackItem) -> Unit = {},
    val onRefresh: () -> Unit = {}
)

/**
 * Level 2: Actions for ListenBrainz discover playlists and CF recommendations.
 */
@Immutable
data class DiscoverListenBrainzActions(
    val onOpenPlaylist: (String) -> Unit = {},
    val onOpenCfRecommendations: () -> Unit = {}
)

/** Level 1: Low-level section header with title and optional badge / action trailing. */
@Composable
fun DiscoverSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )
        if (badgeText != null) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor
            )
        } else if (trailing != null) {
            trailing()
        }
    }
}

/** Level 2: Shared horizontal section (header + spaced LazyRow) for Discover feed carousels. */
@Composable
fun DiscoverFeedHorizontalSection(
    title: String,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        DiscoverSectionHeader(
            title = title,
            badgeText = badgeText,
            badgeColor = badgeColor,
            trailing = trailing
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/** Level 2: Home feed view using bundled actions. */
@Composable
fun DiscoverHomeFeedView(
    feed: DiscoverFeed,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    source: DiscoverSourcePreference = DiscoverSourcePreference.BOTH,
    onSourceChange: (DiscoverSourcePreference) -> Unit = {},
    topRelatedFeed: TopRelatedFeed = TopRelatedFeed(),
    isLoadingTopRelated: Boolean = false,
    topRelatedActions: DiscoverTopRelatedActions = DiscoverTopRelatedActions(),
    lbDiscoverPlaylists: List<LbPlaylistSummary> = emptyList(),
    cfRecommendations: MatchedCfRecommendations? = null,
    lbActions: DiscoverListenBrainzActions = DiscoverListenBrainzActions(),
    showLbSections: Boolean = false,
    actions: DiscoverCatalogActions,
    scrollState: ScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) },
    modifier: Modifier = Modifier
) {
    DiscoverHomeFeedView(
        feed = feed,
        isLoading = isLoading,
        onRefresh = onRefresh,
        source = source,
        onSourceChange = onSourceChange,
        topRelatedFeed = topRelatedFeed,
        isLoadingTopRelated = isLoadingTopRelated,
        topRelatedActions = topRelatedActions,
        lbDiscoverPlaylists = lbDiscoverPlaylists,
        cfRecommendations = cfRecommendations,
        lbActions = lbActions,
        showLbSections = showLbSections,
        onPlayTrack = actions.onPlayTrack,
        onDownloadTrack = actions.onDownloadTrack,
        onSelectAlbum = actions.onSelectAlbum,
        onSaveAlbum = actions.onSaveAlbum,
        activeDownloads = actions.activeDownloads,
        getTrackStatus = actions.getTrackStatus,
        getAlbumStatus = actions.getAlbumStatus,
        onAlreadyInLibrary = actions.onAlreadyInLibrary,
        scrollState = scrollState,
        modifier = modifier
    )
}

/** Level 1: Low-level primitive home feed view with individual callbacks. */
@Composable
fun DiscoverHomeFeedView(
    feed: DiscoverFeed,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    source: DiscoverSourcePreference = DiscoverSourcePreference.BOTH,
    onSourceChange: (DiscoverSourcePreference) -> Unit = {},
    topRelatedFeed: TopRelatedFeed = TopRelatedFeed(),
    isLoadingTopRelated: Boolean = false,
    topRelatedActions: DiscoverTopRelatedActions = DiscoverTopRelatedActions(),
    lbDiscoverPlaylists: List<LbPlaylistSummary> = emptyList(),
    cfRecommendations: MatchedCfRecommendations? = null,
    lbActions: DiscoverListenBrainzActions = DiscoverListenBrainzActions(),
    showLbSections: Boolean = false,
    onPlayTrack: (OnlineCatalogTrack) -> Unit,
    onDownloadTrack: (OnlineCatalogTrack) -> Unit,
    onSelectAlbum: (CatalogAlbum) -> Unit,
    onSaveAlbum: (CatalogAlbum) -> Unit,
    activeDownloads: List<ActiveDownload> = emptyList(),
    getTrackStatus: (TrackMeta) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    getAlbumStatus: (String, String) -> ItemLibraryStatus = { _, _ -> ItemLibraryStatus.NOT_IN_LIBRARY },
    onAlreadyInLibrary: (String) -> Unit = {},
    scrollState: ScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) },
    modifier: Modifier = Modifier
) {
    val isFeedEmpty = feed.recommendedTracks.isEmpty() && feed.recommendedAlbums.isEmpty() && feed.chartTracks.isEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Section: Source Selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Fuente:",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilterChip(
                selected = source == DiscoverSourcePreference.BOTH,
                onClick = { onSourceChange(DiscoverSourcePreference.BOTH) },
                label = { Text("Ambos") },
                modifier = Modifier.height(ListDensity.filterChipHeight)
            )
            FilterChip(
                selected = source == DiscoverSourcePreference.DEEZER,
                onClick = { onSourceChange(DiscoverSourcePreference.DEEZER) },
                label = { Text("Deezer") },
                modifier = Modifier.height(ListDensity.filterChipHeight)
            )
            FilterChip(
                selected = source == DiscoverSourcePreference.LISTENBRAINZ,
                onClick = { onSourceChange(DiscoverSourcePreference.LISTENBRAINZ) },
                label = { Text("ListenBrainz") },
                modifier = Modifier.height(ListDensity.filterChipHeight)
            )
        }

        // Section: Buscar más relacionados (Local + ListenBrainz)
        DiscoverTopRelatedSection(
            feed = topRelatedFeed,
            isLoading = isLoadingTopRelated,
            actions = topRelatedActions,
            activeDownloads = activeDownloads
        )

        // Section: Recomendados para vos (CF)
        if (showLbSections && cfRecommendations != null && cfRecommendations.matches.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                DiscoverSectionHeader(
                    title = "Recomendados",
                    badgeText = "ListenBrainz CF",
                    badgeColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 0.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                CfRecommendationsCardItem(
                    matched = cfRecommendations,
                    onClick = lbActions.onOpenCfRecommendations
                )
            }
        }

        // Section: Playlists Para Ti (ListenBrainz Discover playlists)
        if (showLbSections && lbDiscoverPlaylists.isNotEmpty()) {
            DiscoverFeedHorizontalSection(
                title = "Playlists Para Ti",
                badgeText = "ListenBrainz",
                badgeColor = MaterialTheme.colorScheme.tertiary
            ) {
                items(
                    items = lbDiscoverPlaylists,
                    key = { "feed-lb-${it.mbid}" },
                    contentType = { "feed-lb-playlist-card" }
                ) { playlist ->
                    Box(modifier = Modifier.width(280.dp)) {
                        LbPlaylistCardItem(
                            playlist = playlist,
                            onClick = { lbActions.onOpenPlaylist(playlist.mbid) }
                        )
                    }
                }
            }
        }

        // Section: Canciones recomendadas (Deezer flow/recommendations)
        if (feed.recommendedTracks.isNotEmpty()) {
            DiscoverFeedHorizontalSection(
                title = "Canciones recomendadas"
            ) {
                items(
                    items = feed.recommendedTracks,
                    key = { "rec-track-${it.id.ifEmpty { "${it.artist}|${it.title}" }}" },
                    contentType = { "rec-track-card" }
                ) { track ->
                    val trackStatus = getTrackStatus(track.identity)
                    DiscoverTrackCard(
                        track = track,
                        onPlay = { onPlayTrack(track) },
                        onDownload = { onDownloadTrack(track) },
                        status = trackStatus,
                        onNotifyStatus = onAlreadyInLibrary
                    )
                }
            }
        }

        // Section: Recommended Albums
        if (feed.recommendedAlbums.isNotEmpty()) {
            DiscoverFeedHorizontalSection(
                title = "Álbumes recomendados"
            ) {
                items(
                    items = feed.recommendedAlbums,
                    key = { "rec-album-${it.id.ifEmpty { "${it.artist}|${it.title}" }}" },
                    contentType = { "rec-album-card" }
                ) { album ->
                    DiscoverAlbumCard(
                        album = album,
                        onClick = { onSelectAlbum(album) },
                        onSave = { onSaveAlbum(album) },
                        status = getAlbumStatus(album.title, album.artist),
                        activeDownloads = activeDownloads,
                        onNotifyStatus = onAlreadyInLibrary
                    )
                }
            }
        }

        // Section: Top Charts
        if (feed.chartTracks.isNotEmpty()) {
            Text(
                text = "Tendencias y Charts",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            feed.chartTracks.take(8).forEach { track ->
                key("chart-track-${track.id}") {
                    val trackStatus = getTrackStatus(track.identity)
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                        DiscoverTrackListItem(
                            track = track,
                            onPlay = { onPlayTrack(track) },
                            onDownload = { onDownloadTrack(track) },
                            status = trackStatus,
                            onNotifyStatus = onAlreadyInLibrary
                        )
                    }
                }
            }
        }

        if (isLoading && isFeedEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Text(
                        text = "Buscando recomendaciones…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Level 2: Top related section using bundled [DiscoverTopRelatedActions]. */
@Composable
fun DiscoverTopRelatedSection(
    feed: TopRelatedFeed,
    isLoading: Boolean,
    actions: DiscoverTopRelatedActions,
    activeDownloads: List<ActiveDownload> = emptyList(),
    modifier: Modifier = Modifier
) {
    DiscoverTopRelatedSection(
        feed = feed,
        isLoading = isLoading,
        onRefresh = actions.onRefresh,
        onSelectArtist = actions.onSelectArtist,
        onStartRadioForArtist = actions.onStartRadioForArtist,
        onSelectAlbum = actions.onSelectAlbum,
        onPlayTrack = actions.onPlayTrack,
        activeDownloads = activeDownloads,
        modifier = modifier
    )
}

/** Level 1: Primitive top related section with individual callbacks. */
@Composable
fun DiscoverTopRelatedSection(
    feed: TopRelatedFeed,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSelectArtist: (String) -> Unit,
    onStartRadioForArtist: (String) -> Unit,
    onSelectAlbum: (RelatedAlbumItem) -> Unit,
    onPlayTrack: (RelatedTrackItem) -> Unit,
    activeDownloads: List<ActiveDownload> = emptyList(),
    modifier: Modifier = Modifier
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "Buscar más relacionados",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Lo más escuchado localmente y en ListenBrainz",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Actualizar relacionados",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    label = { Text("Artistas (${feed.topArtists.size})") }
                )
                FilterChip(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    label = { Text("Álbumes (${feed.topAlbums.size})") }
                )
                FilterChip(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    label = { Text("Canciones (${feed.topTracks.size})") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (selectedTabIndex) {
                0 -> {
                    if (feed.topArtists.isEmpty()) {
                        EmptyListHint(
                            text = if (isLoading) "Cargando artistas más escuchados..." else "Sin estadísticas de artistas aún.",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        )
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = feed.topArtists,
                                key = { "rel-artist-${it.name}" },
                                contentType = { "rel-artist-card" }
                            ) { artist ->
                                RelatedArtistCard(
                                    artist = artist,
                                    onSelect = { onSelectArtist(artist.name) },
                                    onRadio = { onStartRadioForArtist(artist.name) }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    if (feed.topAlbums.isEmpty()) {
                        EmptyListHint(
                            text = if (isLoading) "Cargando álbumes más escuchados..." else "Sin estadísticas de álbumes aún.",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        )
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = feed.topAlbums,
                                key = { "rel-album-${it.artist}|${it.title}" },
                                contentType = { "rel-album-card" }
                            ) { album ->
                                RelatedAlbumCard(
                                    album = album,
                                    onSelect = { onSelectAlbum(album) },
                                    activeDownloads = activeDownloads
                                )
                            }
                        }
                    }
                }
                2 -> {
                    if (feed.topTracks.isEmpty()) {
                        EmptyListHint(
                            text = if (isLoading) "Cargando canciones más escuchadas..." else "Sin estadísticas de canciones aún.",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            feed.topTracks.take(8).forEach { track ->
                                RelatedTrackRow(
                                    track = track,
                                    onPlay = { onPlayTrack(track) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val (bgColor, textColor) = when {
        source.contains("+") -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        source.contains("ListenBrainz", ignoreCase = true) -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        Text(
            text = source,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = textColor,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            maxLines = 1
        )
    }
}

/** Level 2: Related artist card for discovery sections. */
@Composable
internal fun RelatedArtistCard(
    artist: RelatedArtistItem,
    onSelect: () -> Unit,
    onRadio: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier
            .width(136.dp)
            .clickable(onClick = onSelect)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!artist.artworkUri.isNullOrBlank()) {
                    ArtworkThumbnail(
                        artworkUri = artist.artworkUri,
                        contentDescription = artist.name,
                        size = 56.dp,
                        cornerRadius = 28.dp,
                        modifier = Modifier.clip(CircleShape)
                    )
                } else {
                    Text(
                        text = artist.name.firstOrNull()?.uppercase().orEmpty(),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            SourceBadge(source = artist.source)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onSelect,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("Buscar", style = MaterialTheme.typography.labelSmall)
                }
                DiscoverActionIcon(
                    onClick = onRadio,
                    icon = Icons.Default.Radio,
                    contentDescription = "Radio",
                    tint = MaterialTheme.colorScheme.primary,
                    iconSize = 16.dp,
                    boxSize = 26.dp
                )
            }
        }
    }
}

/** Level 2: Related album card composing Level 1 [DiscoverAlbumCard]. */
@Composable
internal fun RelatedAlbumCard(
    album: RelatedAlbumItem,
    onSelect: () -> Unit,
    activeDownloads: List<ActiveDownload>? = null,
    isDownloading: Boolean = activeDownloads?.isAlbumDownloading(album) ?: false,
    modifier: Modifier = Modifier
) {
    DiscoverAlbumCard(
        title = album.title,
        artist = album.artist,
        coverUrl = album.artworkUri,
        cardWidth = 136.dp,
        imageSize = 116.dp,
        onClick = onSelect,
        modifier = modifier,
        topEndBadge = {
            SourceBadge(
                source = album.source,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            )
        },
        bottomEndAction = if (isDownloading) {
            { MediaCardDownloadSpinner(size = 28.dp, indicatorSize = 14.dp) }
        } else null
    )
}

/** Level 2: Related track row composing [DiscoverTrackListItem]. */
@Composable
internal fun RelatedTrackRow(
    track: RelatedTrackItem,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    DiscoverTrackListItem(
        track = track,
        onPlay = onPlay,
        subtitle = track.artistAlbumLabel(" · "),
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SourceBadge(source = track.source)
                PlayIconButton(
                    onClick = onPlay,
                    contentDescription = "Reproducir",
                    modifier = Modifier.size(32.dp)
                )
            }
        },
        modifier = modifier
    )
}

/** Level 2: Search results view using bundled [DiscoverCatalogActions]. */
@Composable
fun DiscoverSearchResultsView(
    category: CatalogCategory,
    isSearching: Boolean,
    tracks: List<OnlineCatalogTrack>,
    albums: List<CatalogAlbum>,
    playlists: List<CatalogPlaylist>,
    genres: List<CatalogGenre>,
    actions: DiscoverCatalogActions,
    modifier: Modifier = Modifier
) {
    DiscoverSearchResultsView(
        category = category,
        isSearching = isSearching,
        isLoadingMore = actions.isLoadingMore,
        canLoadMore = actions.canLoadMore,
        tracks = tracks,
        albums = albums,
        playlists = playlists,
        genres = genres,
        onPlayTrack = actions.onPlayTrack,
        onDownloadTrack = actions.onDownloadTrack,
        onSelectAlbum = actions.onSelectAlbum,
        onSaveAlbum = actions.onSaveAlbum,
        onSelectPlaylist = actions.onSelectPlaylist,
        onSelectGenre = actions.onSelectGenre,
        onSearchMore = actions.onSearchMore,
        activeDownloads = actions.activeDownloads,
        getTrackStatus = actions.getTrackStatus,
        getAlbumStatus = actions.getAlbumStatus,
        onAlreadyInLibrary = actions.onAlreadyInLibrary,
        modifier = modifier
    )
}

/** Level 1: Low-level primitive search results view with individual callbacks. */
@Composable
fun DiscoverSearchResultsView(
    category: CatalogCategory,
    isSearching: Boolean,
    tracks: List<OnlineCatalogTrack>,
    albums: List<CatalogAlbum>,
    playlists: List<CatalogPlaylist>,
    genres: List<CatalogGenre>,
    onPlayTrack: (OnlineCatalogTrack) -> Unit,
    onDownloadTrack: (OnlineCatalogTrack) -> Unit,
    onSelectAlbum: (CatalogAlbum) -> Unit,
    onSaveAlbum: (CatalogAlbum) -> Unit,
    isLoadingMore: Boolean = false,
    canLoadMore: Boolean = true,
    onSelectPlaylist: (CatalogPlaylist) -> Unit = {},
    onSelectGenre: (CatalogGenre) -> Unit = {},
    onSearchMore: () -> Unit = {},
    activeDownloads: List<ActiveDownload> = emptyList(),
    getTrackStatus: (TrackMeta) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    getAlbumStatus: (String, String) -> ItemLibraryStatus = { _, _ -> ItemLibraryStatus.NOT_IN_LIBRARY },
    onAlreadyInLibrary: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (isSearching) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    when (category) {
        CatalogCategory.SONGS, CatalogCategory.CHARTS -> {
            if (tracks.isEmpty()) {
                Column(
                    modifier = modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EmptyListHint(text = "No se encontraron canciones")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onSearchMore,
                        enabled = !isLoadingMore
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscando en YouTube…")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscar en YouTube / búsqueda profunda")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = tracks,
                        key = { "search-track-${it.id}" },
                        contentType = { "search-track-item" }
                    ) { track ->
                        val trackStatus = getTrackStatus(track.identity)
                        val activeDownload = activeDownloads.findUiDownloadByTrack(track.artist, track.title)
                        DiscoverTrackListItem(
                            track = track,
                            onPlay = { onPlayTrack(track) },
                            onDownload = { onDownloadTrack(track) },
                            activeDownload = activeDownload,
                            status = trackStatus,
                            onNotifyStatus = onAlreadyInLibrary
                        )
                    }

                    if (canLoadMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = onSearchMore,
                                    enabled = !isLoadingMore
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Buscando más…")
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Buscar más resultados")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        CatalogCategory.ALBUMS -> {
            if (albums.isEmpty()) {
                Column(
                    modifier = modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EmptyListHint(text = "No se encontraron álbumes")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onSearchMore,
                        enabled = !isLoadingMore
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscando más…")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscar más álbumes")
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = albums,
                        key = { "search-album-${it.id.ifEmpty { "${it.artist}|${it.title}" }}" },
                        contentType = { "search-album-card" }
                    ) { album ->
                        DiscoverAlbumCard(
                            album = album,
                            onClick = { onSelectAlbum(album) },
                            onSave = { onSaveAlbum(album) },
                            status = getAlbumStatus(album.title, album.artist),
                            activeDownloads = activeDownloads,
                            onNotifyStatus = onAlreadyInLibrary
                        )
                    }

                    if (canLoadMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = onSearchMore,
                                    enabled = !isLoadingMore
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Buscando más…")
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Buscar más álbumes")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        CatalogCategory.PLAYLISTS -> {
            if (playlists.isEmpty()) {
                EmptyListHint(text = "No se encontraron playlists", modifier = modifier.fillMaxWidth().padding(32.dp))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = playlists,
                        key = { "search-playlist-${it.id}" },
                        contentType = { "search-playlist-card" }
                    ) { playlist ->
                        DiscoverMediaCard(
                            title = playlist.title,
                            subtitle = "",
                            artworkUri = playlist.coverUrl,
                            onClick = { onSelectPlaylist(playlist) }
                        )
                    }
                }
            }
        }
        CatalogCategory.GENRES -> {
            if (genres.isEmpty()) {
                EmptyListHint(text = "No se encontraron géneros", modifier = modifier.fillMaxWidth().padding(32.dp))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = genres,
                        key = { "search-genre-${it.id}" },
                        contentType = { "search-genre-card" }
                    ) { genre ->
                        Card(
                            onClick = { onSelectGenre(genre) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = genre.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Level 2: Shared stack frame bundling user interaction callbacks for collection detail views.
 */
data class DiscoverCollectionActions(
    val onBack: () -> Unit,
    val onPlayAll: () -> Unit,
    val onShuffle: () -> Unit,
    val onSaveAlbum: () -> Unit,
    val onDownloadAll: () -> Unit,
    val onPlayCandidate: (CatalogTrackCandidate) -> Unit,
    val onDownloadCandidate: (CatalogTrackCandidate) -> Unit,
    val onSelectArtist: (String) -> Unit = {},
    val albumDownloadProgress: ActiveAlbumDownloadProgress = ActiveAlbumDownloadProgress(),
    val activeDownloads: List<ActiveDownload> = emptyList(),
    val getTrackStatus: (TrackMeta) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    val onAlreadyInLibrary: (String) -> Unit = {}
)

/** Level 2: Collection drill-down view using bundled [DiscoverCollectionActions]. */
@Composable
fun DiscoverCollectionDetailView(
    title: String,
    kind: CatalogCollectionKind,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    isLoading: Boolean,
    albumStatus: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    currentItem: PlayableItem? = null,
    actions: DiscoverCollectionActions,
    modifier: Modifier = Modifier
) {
    DiscoverCollectionDetailView(
        title = title,
        kind = kind,
        coverUrl = coverUrl,
        candidates = candidates,
        isLoading = isLoading,
        albumStatus = albumStatus,
        albumDownloadProgress = actions.albumDownloadProgress,
        activeDownloads = actions.activeDownloads,
        currentItem = currentItem,
        onBack = actions.onBack,
        onPlayAll = actions.onPlayAll,
        onShuffle = actions.onShuffle,
        onSaveAlbum = actions.onSaveAlbum,
        onDownloadAll = actions.onDownloadAll,
        onPlayCandidate = actions.onPlayCandidate,
        onDownloadCandidate = actions.onDownloadCandidate,
        onSelectArtist = actions.onSelectArtist,
        getTrackStatus = actions.getTrackStatus,
        onAlreadyInLibrary = actions.onAlreadyInLibrary,
        modifier = modifier
    )
}

/** Level 1: Collection drill-down view with individual callbacks (continuous granularity). */
@Composable
fun DiscoverCollectionDetailView(
    title: String,
    kind: CatalogCollectionKind,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onSaveAlbum: () -> Unit,
    onDownloadAll: () -> Unit,
    onPlayCandidate: (CatalogTrackCandidate) -> Unit,
    onDownloadCandidate: (CatalogTrackCandidate) -> Unit,
    onSelectArtist: (String) -> Unit = {},
    albumStatus: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    albumDownloadProgress: ActiveAlbumDownloadProgress = ActiveAlbumDownloadProgress(),
    activeDownloads: List<ActiveDownload> = emptyList(),
    currentItem: PlayableItem? = null,
    getTrackStatus: (TrackMeta) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    onAlreadyInLibrary: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenBackHeader(
            title = title,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                // Header Info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkThumbnail(
                        artworkUri = coverUrl,
                        size = 100.dp,
                        cornerRadius = 16.dp
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        val artistName = candidates.firstOrNull()?.artist.orEmpty()
                        if (artistName.isNotBlank()) {
                            Text(
                                text = artistName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onSelectArtist(artistName) }
                                    .padding(vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "${candidates.size} canciones",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = onPlayAll,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play")
                            }

                            FilledTonalButton(
                                onClick = onShuffle,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            }

                            if (kind == CatalogCollectionKind.ALBUM) {
                                AlbumLibraryHeaderButton(
                                    status = albumStatus,
                                    onSaveAlbum = onSaveAlbum,
                                    onAlreadyInLibrary = onAlreadyInLibrary
                                )
                            }

                            if (albumStatus != ItemLibraryStatus.DOWNLOADED) {
                                AlbumDownloadStateButton(
                                    progress = albumDownloadProgress,
                                    onDownload = onDownloadAll
                                )
                            }
                        }
                    }
                }
            }

            items(
                items = candidates,
                key = { "candidate-${it.trackNumber}-${it.identity.artist}-${it.identity.title}" },
                contentType = { "candidate-track-item" }
            ) { candidate ->
                val trackStatus = getTrackStatus(candidate.identity)
                val activeDownload = activeDownloads.findUiDownloadByTrack(candidate.artist, candidate.title)
                val isPlaying = isCurrentPlaying(currentItem, candidate.identity.artist, candidate.identity.title)
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    DiscoverTrackListItem(
                        track = candidate,
                        onPlay = { onPlayCandidate(candidate) },
                        onDownload = { onDownloadCandidate(candidate) },
                        activeDownload = activeDownload,
                        status = trackStatus,
                        highlighted = isPlaying,
                        leading = {
                            val num = candidate.trackNumber.takeIf { it > 0 }
                            if (num != null) {
                                Text(
                                    text = "$num",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(28.dp)
                                )
                            }
                        },
                        onNotifyStatus = onAlreadyInLibrary
                    )
                }
            }
        }
    }
}

/**
 * Level 2: Shared stack frame bundling user interaction callbacks for artist discovery view.
 */
@Immutable
data class DiscoverArtistActions(
    val onBack: () -> Unit,
    val onPlayAll: () -> Unit,
    val onShuffle: () -> Unit,
    val onStartRadio: () -> Unit,
    val onSelectAlbum: (CatalogAlbum) -> Unit,
    val onSaveAlbum: (CatalogAlbum) -> Unit,
    val onPlayTrack: (CatalogTrackCandidate) -> Unit,
    val onDownloadTrack: (CatalogTrackCandidate) -> Unit,
    val onPlayLocalSong: (Song) -> Unit,
    val getTrackStatus: (TrackMeta) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    val getAlbumStatus: (String, String) -> ItemLibraryStatus = { _, _ -> ItemLibraryStatus.NOT_IN_LIBRARY },
    val onAlreadyInLibrary: (String) -> Unit = {}
)

/** Level 3: Stateful artist detail section that collects library songs and albums only when mounted. */
@Composable
private fun DiscoverArtistDetailSection(
    viewModel: MusicPlayerViewModel,
    artistName: String,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    albums: List<CatalogAlbum>,
    isLoading: Boolean,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    modifier: Modifier = Modifier
) {
    val librarySongs by viewModel.libraryProjection.songs.collectAsStateWithLifecycle()
    val libraryAlbums by viewModel.libraryProjection.albums.collectAsStateWithLifecycle()
    val artistLocalSongs = remember(librarySongs, artistName) {
        viewModel.songsForArtist(librarySongs, artistName)
    }
    val artistLocalAlbums = remember(libraryAlbums, artistLocalSongs, artistName) {
        val albumTitles = artistLocalSongs.map { it.album.lowercase().trim() }.toSet()
        libraryAlbums.filter { album ->
            album.artist.equals(artistName, ignoreCase = true) ||
                albumTitles.contains(album.name.lowercase().trim())
        }
    }

    val artistActions = remember(candidates, artistName, artistLocalSongs) {
        DiscoverArtistActions(
            onBack = { viewModel.clearSelectedCollection() },
            onPlayAll = {
                viewModel.playCatalogCandidates(candidates, startIndex = 0, startShuffled = false)
            },
            onShuffle = {
                viewModel.playCatalogCandidates(candidates, startIndex = 0, startShuffled = true)
            },
            onStartRadio = {
                val seed = artistLocalSongs.randomOrNull()
                if (seed != null) {
                    viewModel.startRadio(seedSong = seed)
                } else {
                    viewModel.startRadio()
                }
            },
            onSelectAlbum = { album ->
                viewModel.selectAlbumForInspection(album)
            },
            onSaveAlbum = { album ->
                viewModel.saveAlbumToLibrary(album)
            },
            onPlayTrack = { candidate ->
                val index = candidates.indexOf(candidate)
                if (index >= 0) {
                    viewModel.playCatalogCandidates(candidates, startIndex = index, startShuffled = false)
                } else {
                    viewModel.playCatalogCandidate(candidate)
                }
            },
            onDownloadTrack = { candidate ->
                viewModel.downloadCatalogCandidate(candidate)
            },
            onPlayLocalSong = { song ->
                viewModel.playSong(song)
            },
            getTrackStatus = viewModel::getTrackLibraryStatus,
            getAlbumStatus = viewModel::getAlbumLibraryStatus,
            onAlreadyInLibrary = { viewModel.toast(it) }
        )
    }

    DiscoverArtistDetailView(
        artistName = artistName,
        coverUrl = coverUrl,
        candidates = candidates,
        albums = albums,
        isLoading = isLoading,
        localSongs = artistLocalSongs,
        localAlbums = artistLocalAlbums,
        currentItem = currentItem,
        activeDownloads = activeDownloads,
        actions = artistActions,
        modifier = modifier
    )
}

/** Level 2: Artist detail view using bundled [DiscoverArtistActions]. */
@Composable
fun DiscoverArtistDetailView(
    artistName: String,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    albums: List<CatalogAlbum>,
    isLoading: Boolean,
    localSongs: List<Song>,
    localAlbums: List<Album>,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    actions: DiscoverArtistActions,
    modifier: Modifier = Modifier
) {
    DiscoverArtistDetailView(
        artistName = artistName,
        coverUrl = coverUrl,
        candidates = candidates,
        albums = albums,
        isLoading = isLoading,
        localSongs = localSongs,
        localAlbums = localAlbums,
        currentItem = currentItem,
        activeDownloads = activeDownloads,
        onBack = actions.onBack,
        onPlayAll = actions.onPlayAll,
        onShuffle = actions.onShuffle,
        onStartRadio = actions.onStartRadio,
        onSelectAlbum = actions.onSelectAlbum,
        onSaveAlbum = actions.onSaveAlbum,
        onPlayTrack = actions.onPlayTrack,
        onDownloadTrack = actions.onDownloadTrack,
        onPlayLocalSong = actions.onPlayLocalSong,
        getTrackStatus = actions.getTrackStatus,
        getAlbumStatus = actions.getAlbumStatus,
        onAlreadyInLibrary = actions.onAlreadyInLibrary,
        modifier = modifier
    )
}

/** Level 1: Artist detail view showing local library content vs online discography and top tracks. */
@Composable
fun DiscoverArtistDetailView(
    artistName: String,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    albums: List<CatalogAlbum>,
    isLoading: Boolean,
    localSongs: List<Song>,
    localAlbums: List<Album>,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onStartRadio: () -> Unit,
    onSelectAlbum: (CatalogAlbum) -> Unit,
    onSaveAlbum: (CatalogAlbum) -> Unit,
    onPlayTrack: (CatalogTrackCandidate) -> Unit,
    onDownloadTrack: (CatalogTrackCandidate) -> Unit,
    onPlayLocalSong: (Song) -> Unit,
    getTrackStatus: (TrackMeta) -> ItemLibraryStatus,
    getAlbumStatus: (String, String) -> ItemLibraryStatus,
    onAlreadyInLibrary: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenBackHeader(
            title = artistName,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // Hero
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!coverUrl.isNullOrBlank()) {
                            ArtworkThumbnail(
                                artworkUri = coverUrl,
                                contentDescription = artistName,
                                size = 88.dp,
                                cornerRadius = 44.dp,
                                modifier = Modifier.clip(CircleShape)
                            )
                        } else {
                            Text(
                                text = artistName.firstOrNull()?.uppercase().orEmpty(),
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artistName,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        val summaryText = buildString {
                            if (localSongs.isNotEmpty()) append("${localSongs.size} en biblioteca • ")
                            append("${albums.size} álbumes")
                        }
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = onPlayAll,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play")
                            }

                            FilledTonalButton(
                                onClick = onShuffle,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            }

                            OutlinedButton(
                                onClick = onStartRadio,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Radio")
                            }
                        }
                    }
                }
            }

            // Sección 1: En tu biblioteca / Guardados
            item {
                Text(
                    text = "En tu biblioteca",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (localSongs.isEmpty() && localAlbums.isEmpty()) {
                item {
                    EmptyListHint(
                        text = "No tienes canciones descargadas de este artista todavía",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                if (localAlbums.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = localAlbums,
                                key = { "local-alb-${it.name}" }
                            ) { album ->
                                val isDownloaded = localSongs.any { it.album == album.name && !it.isRemote }
                                DiscoverMediaCard(
                                    title = album.name,
                                    subtitle = "${album.songCount} canciones",
                                    artworkUri = album.artworkUri,
                                    cardWidth = 150.dp,
                                    imageSize = 134.dp,
                                    onClick = {
                                        onSelectAlbum(
                                            CatalogAlbum(
                                                id = "",
                                                title = album.name,
                                                artist = artistName,
                                                coverUrl = album.artworkUri,
                                                trackCount = album.songCount
                                            )
                                        )
                                    },
                                    topEndBadge = {
                                        Box(
                                            modifier = Modifier
                                                .padding(6.dp)
                                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                                .padding(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isDownloaded) Icons.Default.CheckCircle else Icons.Default.BookmarkAdded,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                if (localSongs.isNotEmpty()) {
                    items(
                        items = localSongs.take(5),
                        key = { "local-song-${it.id}" }
                    ) { song ->
                        val isPlaying = currentItem is PlayableItem.Local && currentItem.song.id == song.id
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                            DiscoverTrackListItem(
                                track = song,
                                onPlay = { onPlayLocalSong(song) },
                                status = if (song.isRemote) ItemLibraryStatus.SAVED_REMOTE else ItemLibraryStatus.DOWNLOADED,
                                highlighted = isPlaying,
                                subtitle = if (song.isRemote) "${song.album} • Guardado" else "${song.album} • Descargado"
                            )
                        }
                    }
                }
            }

            // Sección 2: Álbumes del artista (Catálogo online)
            if (albums.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Álbumes del artista (${albums.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = albums,
                            key = { "online-alb-${it.id.ifEmpty { it.title }}" }
                        ) { album ->
                            DiscoverAlbumCard(
                                album = album,
                                onClick = { onSelectAlbum(album) },
                                onSave = { onSaveAlbum(album) },
                                status = getAlbumStatus(album.title, album.artist),
                                activeDownloads = activeDownloads,
                                onNotifyStatus = onAlreadyInLibrary
                            )
                        }
                    }
                }
            }

            // Sección 3: Canciones populares (Top tracks streaming)
            if (candidates.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Canciones populares",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                items(
                    items = candidates,
                    key = { "top-cand-${it.trackNumber}-${it.identity.artist}-${it.identity.title}" }
                ) { candidate ->
                    val trackStatus = getTrackStatus(candidate.identity)
                    val activeDownload = activeDownloads.findUiDownloadByTrack(candidate.artist, candidate.title)
                    val isPlaying = isCurrentPlaying(currentItem, candidate.identity.artist, candidate.identity.title)
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                        DiscoverTrackListItem(
                            track = candidate,
                            onPlay = { onPlayTrack(candidate) },
                            onDownload = { onDownloadTrack(candidate) },
                            activeDownload = activeDownload,
                            status = trackStatus,
                            highlighted = isPlaying,
                            leading = {
                                val num = candidate.trackNumber.takeIf { it > 0 }
                                if (num != null) {
                                    Text(
                                        text = "$num",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(28.dp)
                                    )
                                }
                            },
                            onNotifyStatus = onAlreadyInLibrary
                        )
                    }
                }
            }
        }
    }
}

/** Level 2: Advanced filters panel accepting bundled [IdentifySearchFilters]. */
@Composable
fun DiscoverAdvancedFiltersPanel(
    filters: IdentifySearchFilters,
    onFiltersChange: (IdentifySearchFilters) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    com.bestiapop.android.ui.components.CatalogAdvancedFiltersPanel(
        filters = filters,
        onFiltersChange = onFiltersChange,
        onApply = onApply,
        onClear = onClear,
        modifier = modifier
    )
}

/** Level 1: Advanced filters panel with individual primitive fields and callbacks. */
@Composable
fun DiscoverAdvancedFiltersPanel(
    artist: String,
    onArtistChange: (String) -> Unit,
    album: String,
    onAlbumChange: (String) -> Unit,
    year: String,
    onYearChange: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    com.bestiapop.android.ui.components.CatalogAdvancedFiltersPanel(
        artist = artist,
        onArtistChange = onArtistChange,
        album = album,
        onAlbumChange = onAlbumChange,
        year = year,
        onYearChange = onYearChange,
        onApply = onApply,
        onClear = onClear,
        modifier = modifier
    )
}

