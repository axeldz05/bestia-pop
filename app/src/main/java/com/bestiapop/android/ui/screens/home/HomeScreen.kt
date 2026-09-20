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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.DiscoverSourcePreference
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

enum class HomeFilterMode {
    ALL,
    LOCAL,
    STREAMING,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val isOfflineMode by viewModel.isOfflineMode.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val catalogCollection by viewModel.catalogCollection.collectAsStateWithLifecycle()

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

    var isLibraryBrowseOpen by rememberSaveable { mutableStateOf(false) }

    if (hasLocalDetail || isLibraryBrowseOpen) {
        LibraryScreen(
            viewModel = viewModel,
            onBackToHome = {
                isLibraryBrowseOpen = false
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

    // Catalog search
    val catalogSearch by viewModel.catalogSearch.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filterMode by rememberSaveable { mutableStateOf(HomeFilterMode.ALL) }
    var showSearchHistorySheet by rememberSaveable { mutableStateOf(false) }

    val effectiveFilterMode = if (isOfflineMode) HomeFilterMode.LOCAL else filterMode

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

    // Prepare Speed Dial items
    val speedDialItems =
        remember(playlists, albums, lbDiscover, effectiveFilterMode) {
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
                // 3. Online playlists if not offline / local
                if (effectiveFilterMode != HomeFilterMode.LOCAL) {
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
                placeholder = { Text("Buscar canciones, artistas o streaming…") },
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

        // --- FILTER PILLS ROW [Todo | Local | Streaming] ---
        if (!isSearchActive) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                FilterChip(
                    selected = effectiveFilterMode == HomeFilterMode.ALL,
                    onClick = { filterMode = HomeFilterMode.ALL },
                    label = { Text("Todo") },
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isOfflineMode,
                )
                FilterChip(
                    selected = effectiveFilterMode == HomeFilterMode.LOCAL,
                    onClick = { filterMode = HomeFilterMode.LOCAL },
                    label = { Text("Música Local") },
                    shape = RoundedCornerShape(16.dp),
                )
                if (!isOfflineMode) {
                    FilterChip(
                        selected = effectiveFilterMode == HomeFilterMode.STREAMING,
                        onClick = { filterMode = HomeFilterMode.STREAMING },
                        label = { Text("Streaming") },
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
        }

        // --- CONTENT: UNIFIED SEARCH OR HOME FEED ---
        if (isSearchActive) {
            UnifiedSearchSection(
                searchQuery = searchQuery,
                localSongs = matchingLocalSongs,
                catalogTracks = catalogSearch.tracks,
                catalogAlbums = catalogSearch.albums,
                isSearchingOnline = catalogSearch.isSearching,
                currentSongUri = currentItem?.mediaId,
                songItemActions = songItemActions,
                onPlayLocalSong = { song ->
                    viewModel.playSong(song)
                },
                onPlayCatalogTrack = { track ->
                    viewModel.playCatalogOrLocalTrack(track)
                },
                onDownloadCatalogTrack = { track ->
                    viewModel.downloadOnlineTrack(track)
                },
                onSelectCatalogAlbum = { album ->
                    viewModel.selectAlbumForInspection(album)
                },
                onSearchMoreOnline = {
                    viewModel.searchMore()
                },
                activeDownloads = activeDownloads,
                canLoadMoreOnline = catalogSearch.canLoadMore,
                isLoadingMoreOnline = catalogSearch.isLoadingMore,
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

                // 2. "Vuelve a escuchar" Section (Frequent songs)
                if (effectiveFilterMode != HomeFilterMode.STREAMING) {
                    FrequentSongsCarousel(
                        songs = frequentSongs,
                        onPlaySong = { song ->
                            val index = frequentSongs.indexOf(song).coerceAtLeast(0)
                            viewModel.playCollection(frequentSongs, index)
                        },
                    )
                }

                // 3. "Tu Biblioteca" Shortcuts
                if (effectiveFilterMode != HomeFilterMode.STREAMING) {
                    LibraryCollectionsRow(
                        songCount = allSongs.size,
                        albumCount = albums.size,
                        artistCount = artists.size,
                        playlistCount = playlists.size,
                        onSelectFilter = { filter ->
                            viewModel.setLibraryBrowseFilter(filter)
                            isLibraryBrowseOpen = true
                        },
                    )
                }

                // 4. "Escuchado recientemente" Section
                if (effectiveFilterMode != HomeFilterMode.STREAMING) {
                    RecentSongsCarousel(
                        songs = recentSongs,
                        onPlaySong = { song ->
                            val index = recentSongs.indexOf(song).coerceAtLeast(0)
                            viewModel.playCollection(recentSongs, index)
                        },
                    )
                }

                // 5. Streaming Discovery Feeds (ListenBrainz, Deezer, Top Related)
                if (effectiveFilterMode != HomeFilterMode.LOCAL && !isOfflineMode) {
                    val catalogActions =
                        remember(viewModel, catalogSearch.isLoadingMore, catalogSearch.canLoadMore) {
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
                            )
                        }

                    val topRelatedActions =
                        remember(viewModel) {
                            DiscoverTopRelatedActions(
                                onSelectArtist = { artistName ->
                                    viewModel.selectArtistForInspection(artistName)
                                },
                                onStartRadioForArtist = { artistName ->
                                    val artistSongs = viewModel.songsForArtist(allSongs, artistName)
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
                                        searchQuery = item.title
                                        viewModel.setCatalogSearchDraft(item.title)
                                        viewModel.submitCatalogSearch(item.title)
                                    }
                                },
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
