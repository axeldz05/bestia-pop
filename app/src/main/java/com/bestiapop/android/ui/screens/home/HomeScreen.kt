package com.bestiapop.android.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.DiscoverSourcePreference
import com.bestiapop.android.domain.util.MetadataSplitter
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.SearchHistorySheet
import com.bestiapop.android.ui.components.SongItemActions
import com.bestiapop.android.ui.components.rememberSongQueueActions
import com.bestiapop.android.ui.screens.LibraryScreen
import com.bestiapop.android.ui.screens.discover.DiscoverCatalogActions
import com.bestiapop.android.ui.screens.discover.DiscoverHomeFeedView
import com.bestiapop.android.ui.screens.discover.DiscoverListenBrainzActions
import com.bestiapop.android.ui.screens.discover.DiscoverScreen
import com.bestiapop.android.ui.screens.discover.DiscoverTopRelatedActions
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import com.bestiapop.android.ui.state.PlaylistDetailNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val isOfflineMode by viewModel.isOfflineMode.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val catalogCollection by viewModel.catalogCollection.collectAsStateWithLifecycle()
    val catalogSearch by viewModel.catalogSearch.collectAsStateWithLifecycle()

    var searchQuery by rememberSaveable { mutableStateOf(catalogSearch.searchQueryDraft) }
    val searchListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var isLibraryBrowseOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(catalogSearch.searchQueryDraft) {
        if (catalogSearch.searchQueryDraft != searchQuery) {
            searchQuery = catalogSearch.searchQueryDraft
        }
    }

    // Inspecting a local album/artist/genre or local playlist -> delegate directly to LibraryScreen
    val hasLocalDetail =
        navigation.libraryStack.albumName != null ||
            navigation.libraryStack.artistName != null ||
            navigation.libraryStack.genreName != null ||
            (navigation.playlistDetail is PlaylistDetailNav.Local)

    // Inspecting an online album/artist collection or ListenBrainz/CF detail -> delegate to DiscoverScreen
    val hasRemoteDetail =
        catalogCollection.title != null ||
            navigation.playlistDetail is PlaylistDetailNav.ListenBrainz ||
            navigation.playlistDetail is PlaylistDetailNav.CfRecommendations

    if (hasLocalDetail || isLibraryBrowseOpen) {
        LibraryScreen(
            viewModel = viewModel,
            onBackToHome = {
                isLibraryBrowseOpen = false
                viewModel.clearSelectedCollection()
                viewModel.popLibraryNested()
                viewModel.closePlaylistDetail()
                viewModel.setLibraryBrowseFilter(LibraryBrowseFilter.SONGS)
            },
        )
        return
    }

    if (hasRemoteDetail) {
        DiscoverScreen(viewModel = viewModel)
        return
    }

    // --- HOME FEED & SEARCH ---
    val allSongs by viewModel.libraryProjection.songs.collectAsStateWithLifecycle()
    val albums by viewModel.libraryProjection.albums.collectAsStateWithLifecycle()
    val artists by viewModel.libraryProjection.artists.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(emptyList())
    val frequentSongs by viewModel.frequentSongs.collectAsStateWithLifecycle()
    val recentSongs by viewModel.libraryProjection.recentSongs.collectAsStateWithLifecycle()
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val libraryBlobsSettings by viewModel.libraryBlobsSettings.collectAsStateWithLifecycle()

    // Streaming discovery feeds
    val discoverFeed by viewModel.discoverFeed.collectAsStateWithLifecycle()
    val isLoadingDiscoverFeed by viewModel.isLoadingDiscoverFeed.collectAsStateWithLifecycle()
    val discoverSource by viewModel.discoverSource.collectAsStateWithLifecycle()
    val topRelatedFeed by viewModel.topRelatedFeed.collectAsStateWithLifecycle()
    val isLoadingTopRelated by viewModel.isLoadingTopRelatedFeed.collectAsStateWithLifecycle()
    val lbDiscover by viewModel.lbDiscover.collectAsStateWithLifecycle()
    val cfRecommendationsState by viewModel.cfRecommendations.collectAsStateWithLifecycle()
    val cfRecommendations = cfRecommendationsState.data
    val lbSettings by viewModel.listenBrainzSettings.collectAsStateWithLifecycle()

    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    var showSearchHistorySheet by rememberSaveable { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val songActions = rememberSongQueueActions(viewModel)
    val songDialogs = rememberSongActionDialogs(viewModel = viewModel, playlists = playlists)
    val songItemActions =
        remember(songActions, songDialogs) {
            SongItemActions.from(songActions, songDialogs)
        }

    val isSearchActive = searchQuery.isNotBlank()

    // Filter local songs matching query
    val matchingLocalSongs =
        remember(searchQuery, allSongs) {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                val q = searchQuery.trim().lowercase()
                allSongs.filter {
                    it.title.lowercase().contains(q) ||
                        it.artist.lowercase().contains(q) ||
                        it.album.lowercase().contains(q)
                }
            }
        }

    // Filter local albums matching query
    val matchingLocalAlbums =
        remember(searchQuery, albums) {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                val q = searchQuery.trim().lowercase()
                albums.filter {
                    it.name.lowercase().contains(q) ||
                        it.artist.lowercase().contains(q)
                }
            }
        }

    // Filter local artists matching query
    val matchingLocalArtists =
        remember(searchQuery, artists) {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                val q = searchQuery.trim().lowercase()
                artists.filter { it.name.lowercase().contains(q) }
            }
        }

    val unifiedArtists =
        remember(matchingLocalArtists, catalogSearch.artists) {
            MetadataSplitter.deduplicateArtists(matchingLocalArtists + catalogSearch.artists)
        }

    // Prepare Speed Dial items
    val speedDialItems =
        remember(playlists, albums, lbDiscover, isOfflineMode) {
            buildList {
                // 1. Playlists
                playlists.take(4).forEach { pl ->
                    add(
                        HomeSpeedDialItem(
                            id = "pl-${pl.id}",
                            title = pl.name,
                            subtitle = "Playlist",
                            artworkUri = pl.coverUri,
                            isRemote = false,
                            onClick = {
                                viewModel.openLocalPlaylist(pl.id)
                                isLibraryBrowseOpen = true
                            },
                        ),
                    )
                }
                // 2. Albums
                albums.take(4).forEach { alb ->
                    add(
                        HomeSpeedDialItem(
                            id = "alb-${alb.name}",
                            title = alb.displayName,
                            subtitle = alb.artist,
                            artworkUri = alb.artworkUri,
                            isRemote = false,
                            onClick = {
                                val albumSongs = viewModel.songsForAlbum(allSongs, alb.name)
                                viewModel.playCollection(albumSongs, 0)
                            },
                        ),
                    )
                }
                // 3. Online playlists if not offline
                if (!isOfflineMode) {
                    lbDiscover.data?.take(2)?.forEach { lbPl ->
                        add(
                            HomeSpeedDialItem(
                                id = "lb-${lbPl.mbid}",
                                title = lbPl.title,
                                subtitle = "ListenBrainz",
                                artworkUri = null,
                                isRemote = true,
                                onClick = {
                                    viewModel.openListenBrainzPlaylistDetail(lbPl.mbid)
                                },
                            ),
                        )
                    }
                }
            }
        }

    // Refresh feeds if empty
    LaunchedEffect(Unit) {
        if (!isOfflineMode) {
            if (discoverFeed.recommendedTracks.isEmpty() && discoverFeed.recommendedAlbums.isEmpty()) {
                viewModel.refreshDiscoverFeed()
            }
            if (topRelatedFeed.topArtists.isEmpty() && topRelatedFeed.topAlbums.isEmpty()) {
                viewModel.refreshTopRelatedFeed()
            }
        }
    }

    BackHandler(enabled = isSearchActive) {
        searchQuery = ""
        viewModel.setCatalogSearchDraft("")
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // --- TOP SEARCH BAR ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { query ->
                    searchQuery = query
                    viewModel.setCatalogSearchDraft(query)
                    if (!isOfflineMode) {
                        viewModel.searchCatalogDebounced(query = query)
                    }
                },
                placeholder = { Text("Buscar…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    searchQuery = ""
                                    viewModel.setCatalogSearchDraft("")
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                },
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Borrar")
                            }
                        }
                        IconButton(onClick = { showSearchHistorySheet = true }) {
                            Icon(Icons.Default.History, contentDescription = "Historial")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    ),
                modifier = Modifier.weight(1f),
            )
        }

        // --- TU BIBLIOTECA SHORTCUTS ROW ---
        if (!isSearchActive) {
            LibraryCollectionsRow(
                songCount = allSongs.size,
                albumCount = albums.size,
                artistCount = artists.size,
                playlistCount = playlists.size,
                onSelectFilter = { filter ->
                    viewModel.setLibraryBrowseFilter(filter)
                    isLibraryBrowseOpen = true
                },
                filters = libraryBlobsSettings.enabledFilters,
                showTitle = false,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // --- CONTENT: UNIFIED SEARCH OR HOME FEED ---
        if (isSearchActive) {
            UnifiedSearchSection(
                searchQuery = searchQuery,
                lazyListState = searchListState,
                localSongs = matchingLocalSongs,
                localAlbums = matchingLocalAlbums,
                catalogTracks = catalogSearch.tracks,
                catalogAlbums = catalogSearch.albums,
                isSearchingOnline = catalogSearch.isSearching,
                currentSongUri = currentItem?.mediaId,
                songItemActions = songItemActions,
                onPlayLocalSong = { song ->
                    viewModel.playSong(song)
                },
                onSelectLocalAlbum = { album ->
                    viewModel.openLibraryAlbum(album.name)
                },
                onPlayCatalogTrack = { track ->
                    viewModel.playCatalogOrLocalTrack(track)
                },
                onDownloadCatalogTrack = { track ->
                    viewModel.downloadOnlineTrack(track)
                },
                onSelectCatalogAlbum = { album ->
                    viewModel.openAlbum(album)
                },
                onSearchMoreOnline = {
                    viewModel.searchMore()
                },
                activeDownloads = activeDownloads,
                canLoadMoreOnline = catalogSearch.canLoadMore,
                isLoadingMoreOnline = catalogSearch.isLoadingMore,
                artists = unifiedArtists,
                onSelectArtist = { artist ->
                    viewModel.selectArtistForInspection(artist.name)
                },
                onEnqueueCatalogTrack = { track ->
                    viewModel.enqueueCatalogOrLocalTrack(track)
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            val homeScrollState = rememberScrollState()
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(homeScrollState)
                        .padding(bottom = 80.dp),
            ) {
                // 1. Speed Dial Section
                HomeSpeedDialSection(items = speedDialItems)

                // 2. "Escuchado recientemente" Section
                RecentSongsCarousel(
                    songs = recentSongs,
                    onPlaySong = { song ->
                        viewModel.playCollection(recentSongs, song)
                    },
                )

                // 3. "Vuelve a escuchar" Section (Frequent songs)
                FrequentSongsCarousel(
                    songs = frequentSongs,
                    onPlaySong = { song ->
                        viewModel.playCollection(frequentSongs, song)
                    },
                )

                // 4. Streaming Discovery Feeds (ListenBrainz, Deezer, Top Related)
                if (!isOfflineMode) {
                    val catalogActions =
                        remember(viewModel, catalogSearch.isLoadingMore, catalogSearch.canLoadMore) {
                            DiscoverCatalogActions(
                                onPlayTrack = viewModel::playCatalogOrLocalTrack,
                                onDownloadTrack = viewModel::downloadOnlineTrack,
                                onSelectAlbum = viewModel::openAlbum,
                                onSaveAlbum = { album -> viewModel.saveAlbumToLibrary(album) },
                                onSelectPlaylist = viewModel::selectPlaylistForInspection,
                                onSelectGenre = viewModel::selectGenreForInspection,
                                onSearchMore = viewModel::searchMore,
                                onSelectArtist = viewModel::selectArtistForInspection,
                                isLoadingMore = catalogSearch.isLoadingMore,
                                canLoadMore = catalogSearch.canLoadMore,
                                onPlayTrackInCollection = viewModel::playCatalogOrLocalTrack,
                            )
                        }

                    val topRelatedActions =
                        remember(viewModel) {
                            DiscoverTopRelatedActions(
                                onSelectArtist = viewModel::selectArtistForInspection,
                                onStartRadioForArtist = viewModel::startRadioForArtist,
                                onSelectAlbum = viewModel::openAlbum,
                                onStartRadioForAlbum = viewModel::startRadioForAlbum,
                                onPlayTrack = { item ->
                                    if (item.localSong != null) {
                                        viewModel.playSong(item.localSong)
                                    } else {
                                        searchQuery = item.title
                                        viewModel.setCatalogSearchDraft(item.title)
                                        viewModel.submitCatalogSearch(item.title)
                                    }
                                },
                                onStartRadioForTrack = viewModel::startRadioForTrack,
                                onRefresh = { viewModel.refreshTopRelatedFeed(forceRefresh = true) },
                            )
                        }

                    val lbActions =
                        remember(viewModel) {
                            DiscoverListenBrainzActions(
                                onOpenPlaylist = { viewModel.openListenBrainzPlaylistDetail(it) },
                                onOpenCfRecommendations = { viewModel.openCfRecommendationsDetail() },
                            )
                        }

                    DiscoverHomeFeedView(
                        feed = discoverFeed,
                        isLoading = isLoadingDiscoverFeed,
                        onRefresh = { viewModel.refreshDiscoverFeed(forceRefresh = true) },
                        source = discoverSource,
                        onSourceChange = { src -> viewModel.setDiscoverSource(src) },
                        topRelatedFeed = topRelatedFeed,
                        isLoadingTopRelated = isLoadingTopRelated,
                        topRelatedActions = topRelatedActions,
                        lbDiscoverPlaylists = lbDiscover.data ?: emptyList(),
                        cfRecommendations = cfRecommendations,
                        lbActions = lbActions,
                        showLbSections = discoverSource != DiscoverSourcePreference.DEEZER && lbSettings.enabled,
                        actions = catalogActions,
                        scrollState = null,
                    )
                }
            }
        }
    }

    if (showSearchHistorySheet) {
        SearchHistorySheet(
            recentSearches = recentSearches,
            onSelectQuery = { query ->
                searchQuery = query
                viewModel.setCatalogSearchDraft(query)
                if (!isOfflineMode) {
                    viewModel.submitCatalogSearch(query)
                }
                showSearchHistorySheet = false
            },
            onRemoveQuery = { viewModel.removeRecentSearch(it) },
            onClearAll = { viewModel.clearRecentSearches() },
            onDismiss = { showSearchHistorySheet = false },
        )
    }
}
