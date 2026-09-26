package com.bestiapop.android.ui.screens.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.CatalogAlbum
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
import kotlinx.coroutines.delay

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

    var debouncedLocalQuery by remember { mutableStateOf(searchQuery) }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedLocalQuery = ""
        } else {
            delay(60)
            debouncedLocalQuery = searchQuery
        }
    }

    val cleanSearchQuery = remember(debouncedLocalQuery) { debouncedLocalQuery.trim() }
    val normalizedSearchQuery = remember(cleanSearchQuery) { TrackMatchKeys.normalize(cleanSearchQuery) }
    val searchTokens = remember(normalizedSearchQuery) { normalizedSearchQuery.split(' ').filter { it.isNotEmpty() } }

    // Filter local songs matching query
    val matchingLocalSongs =
        remember(normalizedSearchQuery, allSongs) {
            if (normalizedSearchQuery.isEmpty()) {
                emptyList()
            } else {
                allSongs.filter { song ->
                    TrackMatchKeys.matchesQuery(
                        "${song.title} ${song.artist} ${song.album}",
                        normalizedSearchQuery,
                        searchTokens,
                    )
                }
            }
        }

    // Filter local albums matching query
    val matchingLocalAlbums =
        remember(normalizedSearchQuery, albums) {
            if (normalizedSearchQuery.isEmpty()) {
                emptyList()
            } else {
                albums.filter { album ->
                    TrackMatchKeys.matchesQuery(
                        "${album.name} ${album.artist}",
                        normalizedSearchQuery,
                        searchTokens,
                    )
                }
            }
        }

    // Filter local artists matching query
    val matchingLocalArtists =
        remember(normalizedSearchQuery, artists) {
            if (normalizedSearchQuery.isEmpty()) {
                emptyList()
            } else {
                artists.filter { artist ->
                    TrackMatchKeys.matchesQuery(
                        artist.name,
                        normalizedSearchQuery,
                        searchTokens,
                    )
                }
            }
        }

    // Filter local playlists matching query
    val matchingLocalPlaylists =
        remember(normalizedSearchQuery, playlists) {
            if (normalizedSearchQuery.isEmpty()) {
                emptyList()
            } else {
                playlists.filter { playlist ->
                    TrackMatchKeys.matchesQuery(
                        playlist.name,
                        normalizedSearchQuery,
                        searchTokens,
                    )
                }
            }
        }

    val unifiedArtists =
        remember(matchingLocalArtists, catalogSearch.artists) {
            MetadataSplitter.deduplicateArtists(matchingLocalArtists + catalogSearch.artists)
        }

    // Prepare Speed Dial items
    val speedDialItems =
        remember(playlists, albums, lbDiscover, isOfflineMode) {
            buildHomeSpeedDialItems(
                playlists = playlists,
                albums = albums,
                onlinePlaylists = lbDiscover.data ?: emptyList(),
                isOfflineMode = isOfflineMode,
                onOpenPlaylist = { pl ->
                    viewModel.openLocalPlaylist(pl.id)
                    isLibraryBrowseOpen = true
                },
                onPlayAlbum = { alb ->
                    val albumSongs = viewModel.songsForAlbum(allSongs, alb.name)
                    viewModel.playCollection(albumSongs, 0)
                },
                onOpenOnlinePlaylist = { lbPl ->
                    viewModel.openListenBrainzPlaylistDetail(lbPl.mbid)
                },
            )
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            viewModel.setCatalogSearchDraft(searchQuery)
                            viewModel.commitActiveSearchToHistory(searchQuery)
                            if (!isOfflineMode && searchQuery.isNotBlank()) {
                                viewModel.submitCatalogSearch(searchQuery.trim())
                            }
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                        },
                    ),
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
                localPlaylists = matchingLocalPlaylists,
                catalogTracks = catalogSearch.tracks,
                catalogAlbums = catalogSearch.albums,
                catalogPlaylists = catalogSearch.playlists,
                isSearchingOnline = catalogSearch.isSearching,
                currentSongUri = currentItem?.mediaId,
                songItemActions = songItemActions,
                onPlayLocalSong = { song ->
                    viewModel.playSong(song, playlistOrQueue = matchingLocalSongs)
                },
                onSelectLocalAlbum = { album ->
                    viewModel.openLibraryAlbum(album.name)
                },
                onSelectLocalPlaylist = { playlist ->
                    viewModel.openLocalPlaylist(playlist.id)
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
                onSelectCatalogPlaylist = { playlist ->
                    viewModel.selectPlaylistForInspection(playlist)
                },
                onSearchMoreOnline = {
                    viewModel.searchMore()
                },
                activeDownloads = activeDownloads,
                canLoadMoreOnline = catalogSearch.canLoadMore,
                isLoadingMoreOnline = catalogSearch.isLoadingMore,
                artists = unifiedArtists,
                onSelectArtist = { artist ->
                    val isLocal = matchingLocalArtists.any { it.name.equals(artist.name, ignoreCase = true) }
                    if (isLocal || isOfflineMode) {
                        viewModel.openLibraryArtist(artist.name)
                    } else {
                        viewModel.selectArtistForInspection(artist.name)
                    }
                },
                onEnqueueCatalogTrack = { track ->
                    viewModel.enqueueCatalogOrLocalTrack(track)
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            HomeFeedContent(
                speedDialItems = speedDialItems,
                recentSongs = recentSongs,
                frequentSongs = frequentSongs,
                onPlayRecentSong = { song -> viewModel.playCollection(recentSongs, song) },
                onPlayFrequentSong = { song -> viewModel.playCollection(frequentSongs, song) },
                modifier = Modifier.weight(1f),
                discoveryContent = {
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
                },
            )
        }
    }

    if (showSearchHistorySheet) {
        SearchHistorySheet(
            recentSearches = recentSearches,
            onSelectQuery = { query ->
                searchQuery = query
                viewModel.setCatalogSearchDraft(query)
                viewModel.addRecentSearch(query)
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
