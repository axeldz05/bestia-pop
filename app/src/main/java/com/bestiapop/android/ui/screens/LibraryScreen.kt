package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.model.LibraryJobKind
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.albumNamesMatch
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.LocalSubmenuGestureSettings
import com.bestiapop.android.ui.components.MultiSelectActionBar
import com.bestiapop.android.ui.components.MultiSelectActions
import com.bestiapop.android.ui.components.PlaylistAdditionActionBar
import com.bestiapop.android.ui.components.SearchHistorySheet
import com.bestiapop.android.ui.components.SearchRecentChipsRow
import com.bestiapop.android.ui.components.SimilarPlaylistPreviewDialog
import com.bestiapop.android.ui.components.rememberSongQueueActions
import com.bestiapop.android.ui.screens.library.AggregateBrowseActions
import com.bestiapop.android.ui.screens.library.AlbumBrowseActions
import com.bestiapop.android.ui.screens.library.AlbumEditDialogsHost
import com.bestiapop.android.ui.screens.library.IdentifyPendingBanner
import com.bestiapop.android.ui.screens.library.LibraryBrowsePane
import com.bestiapop.android.ui.screens.library.LibraryBrowseSortSheet
import com.bestiapop.android.ui.screens.library.LibraryFilterChipRow
import com.bestiapop.android.ui.screens.library.LibraryProgressBanner
import com.bestiapop.android.ui.screens.library.LibrarySongListActions
import com.bestiapop.android.ui.screens.library.LibraryTopBar
import com.bestiapop.android.ui.screens.library.LibraryViewModeToggleRow
import com.bestiapop.android.ui.screens.library.NestedAlbumDisplayName
import com.bestiapop.android.ui.screens.library.SetAlbumArtworkDialog
import com.bestiapop.android.ui.screens.library.libraryFilterButtonLabel
import com.bestiapop.android.ui.screens.library.libraryNestedSongs
import com.bestiapop.android.ui.screens.library.libraryOrderSummary
import com.bestiapop.android.ui.screens.library.rememberLibraryBrowseListStates
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs
import com.bestiapop.android.ui.screens.library.songsForCurrentLibrarySelection
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import com.bestiapop.android.ui.state.LibraryViewMode
import com.bestiapop.android.ui.state.PlaylistDetailNav

@Composable
fun LibraryScreen(
    viewModel: MusicPlayerViewModel,
    targetPlaylistForAddition: Playlist? = null,
    onCompletePlaylistAddition: () -> Unit = {},
    onCancelPlaylistAddition: () -> Unit = {},
) {
    val identifyReview by viewModel.identifyReview.collectAsStateWithLifecycle()
    val catalogLoaded by viewModel.libraryProjection.catalogLoaded.collectAsStateWithLifecycle()
    val songList by viewModel.libraryProjection.songList.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val sortDirection by viewModel.sortDirection.collectAsStateWithLifecycle()
    val libraryViewMode by viewModel.libraryViewMode.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val browseFilter = navigation.libraryBrowseFilter
    val selectedAlbumName = navigation.libraryStack.albumName
    val selectedArtistName = navigation.libraryStack.artistName
    val selectedGenreName = navigation.libraryStack.genreName
    val libraryJobProgress by viewModel.libraryJobProgress.collectAsStateWithLifecycle()
    val similarPlaylistPreview by viewModel.similarPlaylistPreview.collectAsStateWithLifecycle()
    val fastScrollSettings by viewModel.fastScrollSettings.collectAsStateWithLifecycle()
    val libraryBlobsSettings by viewModel.libraryBlobsSettings.collectAsStateWithLifecycle()

    var localTargetPlaylistForAddition by remember { mutableStateOf<Playlist?>(null) }
    val effectiveTargetPlaylist = targetPlaylistForAddition ?: localTargetPlaylistForAddition
    val isPlaylistAdditionMode = effectiveTargetPlaylist != null

    LaunchedEffect(browseFilter, libraryBlobsSettings.enabledFilters, isPlaylistAdditionMode) {
        if (!isPlaylistAdditionMode && browseFilter !in libraryBlobsSettings.enabledFilters) {
            viewModel.setLibraryBrowseFilter(libraryBlobsSettings.primaryFilter)
        }
    }

    var showBrowseSortSheet by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    var showSearchHistorySheet by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val collapseSearch: () -> Unit = {
        viewModel.setSearchQuery("")
        searchExpanded = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val activeFilter = if (isPlaylistAdditionMode) LibraryBrowseFilter.SONGS else browseFilter
    val showAlbumHeaders =
        libraryViewMode == LibraryViewMode.ALBUM_GROUPS &&
            activeFilter == LibraryBrowseFilter.SONGS
    val songsViewMode =
        if (showAlbumHeaders) {
            LibraryViewMode.ALBUM_GROUPS
        } else {
            LibraryViewMode.FLAT
        }
    val orderSummary =
        remember(activeFilter, sortOption, sortDirection, showAlbumHeaders) {
            libraryOrderSummary(activeFilter, sortOption, sortDirection, showAlbumHeaders)
        }
    val filterButtonLabel =
        remember(activeFilter, sortOption, sortDirection, showAlbumHeaders) {
            libraryFilterButtonLabel(activeFilter, sortOption, sortDirection, showAlbumHeaders)
        }

    var collapsedAlbumNames by remember { mutableStateOf(setOf<String>()) }

    val browseListStates = rememberLibraryBrowseListStates()

    // Multi-selection state
    var selectedSongIds by remember { mutableStateOf(setOf<Long>()) }
    val isMultiSelectMode = selectedSongIds.isNotEmpty()

    var showAbortIdentifyDialog by remember { mutableStateOf(false) }

    // Active Dialogs state
    var albumForCoverChange by remember { mutableStateOf<Album?>(null) }
    var albumForEdit by remember { mutableStateOf<Album?>(null) }
    val songDialogs =
        rememberSongActionDialogs(
            viewModel = viewModel,
            playlists = playlists,
            onAfterPlaylistAdd = {
                selectedSongIds = emptySet()
            },
            onAfterDelete = {
                selectedSongIds = emptySet()
            },
            playlistSongIds = { song ->
                if (selectedSongIds.isNotEmpty()) selectedSongIds.toList() else listOf(song.id)
            },
        )

    val resolveAlbumByKey: (String) -> Album? =
        remember(viewModel) {
            { albumKey: String ->
                viewModel.libraryProjection.albums.value.firstOrNull {
                    albumNamesMatch(it.name, albumKey) || albumNamesMatch(it.displayName, albumKey)
                }
            }
        }

    val onEditAlbumByKey =
        remember(resolveAlbumByKey) {
            { albumKey: String ->
                resolveAlbumByKey(albumKey)?.let { albumForEdit = it }
                Unit
            }
        }
    val onChangeAlbumCoverByKey =
        remember(resolveAlbumByKey) {
            { albumKey: String ->
                resolveAlbumByKey(albumKey)?.let { albumForCoverChange = it }
                Unit
            }
        }
    val onIdentifyAlbumByKey =
        remember(resolveAlbumByKey, viewModel) {
            { albumKey: String ->
                resolveAlbumByKey(albumKey)?.let { album ->
                    val albumSongs = viewModel.songsForAlbum(viewModel.libraryProjection.songs.value, album.name)
                    if (albumSongs.isNotEmpty()) {
                        viewModel.openIdentifySetup(albumSongs, contextTitle = "Álbum: ${album.displayName}")
                    }
                }
                Unit
            }
        }

    val currentSongId = viewModel.currentSongId

    val toggleSelectSong =
        remember<(Song) -> Unit> {
            { song ->
                selectedSongIds = if (song.id in selectedSongIds) selectedSongIds - song.id else selectedSongIds + song.id
            }
        }

    val toggleSelectAlbum =
        remember {
            { albumIds: List<Long> ->
                val ids = albumIds.toSet()
                val removing = ids.isNotEmpty() && ids.all { it in selectedSongIds }
                selectedSongIds = if (removing) selectedSongIds - ids else selectedSongIds + ids
            }
        }

    val onAlbumLongClick =
        remember {
            { albumIds: List<Long> ->
                selectedSongIds = selectedSongIds + albumIds
            }
        }

    val toggleCollapseAlbum =
        remember<(String) -> Unit> {
            { albumName ->
                collapsedAlbumNames =
                    if (collapsedAlbumNames.contains(albumName)) {
                        collapsedAlbumNames - albumName
                    } else {
                        collapsedAlbumNames + albumName
                    }
            }
        }

    val libraryAlbumNames = songList.albumNames
    val allAlbumsCollapsed =
        libraryAlbumNames.isNotEmpty() &&
            libraryAlbumNames.all { collapsedAlbumNames.contains(it) }
    val toggleCollapseAllAlbums = {
        collapsedAlbumNames = if (allAlbumsCollapsed) emptySet() else libraryAlbumNames
    }

    val selectAllSongs = {
        val pool =
            songsForCurrentLibrarySelection(
                viewModel = viewModel,
                songList = songList,
                selectedAlbumName = selectedAlbumName,
                selectedArtistName = selectedArtistName,
                selectedGenreName = selectedGenreName,
                activeFilter = activeFilter,
                songsViewMode = songsViewMode,
            )
        selectedSongIds = pool.map { it.id }.toSet()
    }

    val clearSelection =
        remember {
            {
                selectedSongIds = emptySet()
            }
        }

    val completePlaylistAddition: () -> Unit = {
        val playlistId = effectiveTargetPlaylist?.id
        selectedSongIds = emptySet()
        if (targetPlaylistForAddition != null) {
            onCompletePlaylistAddition()
        } else {
            localTargetPlaylistForAddition = null
            if (playlistId != null) viewModel.openLocalPlaylist(playlistId)
            viewModel.setLibraryBrowseFilter(LibraryBrowseFilter.PLAYLISTS)
        }
    }

    val cancelPlaylistAddition: () -> Unit = {
        val playlistId = effectiveTargetPlaylist?.id
        selectedSongIds = emptySet()
        if (targetPlaylistForAddition != null) {
            onCancelPlaylistAddition()
        } else {
            localTargetPlaylistForAddition = null
            if (playlistId != null) viewModel.openLocalPlaylist(playlistId)
            viewModel.setLibraryBrowseFilter(LibraryBrowseFilter.PLAYLISTS)
        }
    }

    val hasPlaylistDetail =
        activeFilter == LibraryBrowseFilter.PLAYLISTS &&
            navigation.playlistDetail !is PlaylistDetailNav.None
    val hasNestedDetail =
        selectedAlbumName != null ||
            selectedArtistName != null ||
            selectedGenreName != null ||
            hasPlaylistDetail
    val hasNestedBack =
        isMultiSelectMode ||
            isPlaylistAdditionMode ||
            hasNestedDetail ||
            searchQuery.isNotEmpty() ||
            searchExpanded

    BackHandler(enabled = hasNestedBack) {
        when {
            // Addition first: isMultiSelectMode is just "something is ticked", so back used to wipe
            // the user's picks instead of cancelling, needing a second press to do what X does once.
            isPlaylistAdditionMode -> cancelPlaylistAddition()

            isMultiSelectMode -> clearSelection()

            hasPlaylistDetail -> viewModel.closePlaylistDetail()

            hasNestedDetail -> viewModel.popLibraryNested()

            searchQuery.isNotEmpty() -> collapseSearch()

            searchExpanded -> collapseSearch()
        }
    }

    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocusRequester.requestFocus()
    }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) searchExpanded = true
    }
    LaunchedEffect(navigation.playlistDetail) {
        if (navigation.playlistDetail is PlaylistDetailNav.Local) {
            collapseSearch()
        }
    }

    val gestureSettings by viewModel.submenuGestureSettings.collectAsStateWithLifecycle()

    val albumBrowseActions =
        remember(viewModel, searchQuery, gestureSettings) {
            AlbumBrowseActions(
                onAlbumClick = { album ->
                    if (searchQuery.isNotBlank()) viewModel.addRecentSearch(searchQuery)
                    viewModel.openLibraryAlbum(album.name, fromNestedParent = false)
                },
                onPlayAlbum = { album -> viewModel.playAlbum(album, startShuffled = false) },
                onShuffleAlbum = { album -> viewModel.playAlbum(album, startShuffled = true) },
                onEditAlbum = { album -> albumForEdit = album },
                onChangeAlbumCover = { album -> albumForCoverChange = album },
                onIdentifyAlbum = { album -> viewModel.identifyAlbum(album) },
                onSwipeAlbum = { album ->
                    val songs = viewModel.songsForAlbum(viewModel.libraryProjection.songs.value, album.name)
                    viewModel.executeSubmenuActionForSongs(gestureSettings.swipeLeftAction, songs)
                },
            )
        }
    val artistBrowseActions =
        remember(viewModel, searchQuery, gestureSettings) {
            AggregateBrowseActions<Artist>(
                onClick = { artist ->
                    if (searchQuery.isNotBlank()) viewModel.addRecentSearch(searchQuery)
                    viewModel.openLibraryArtist(artist.name)
                },
                onPlay = { artist -> viewModel.playArtist(artist.name, startShuffled = false) },
                onShuffle = { artist -> viewModel.playArtist(artist.name, startShuffled = true) },
                onSwipeAction = { artist ->
                    val songs = viewModel.songsForArtist(viewModel.libraryProjection.songs.value, artist.name)
                    viewModel.executeSubmenuActionForSongs(gestureSettings.swipeLeftAction, songs)
                },
            )
        }
    val genreBrowseActions =
        remember(viewModel, searchQuery, gestureSettings) {
            AggregateBrowseActions<GenreGroup>(
                onClick = { genre ->
                    if (searchQuery.isNotBlank()) viewModel.addRecentSearch(searchQuery)
                    viewModel.openLibraryGenre(genre.name)
                },
                onPlay = { genre -> viewModel.playGenre(genre.name, startShuffled = false) },
                onShuffle = { genre -> viewModel.playGenre(genre.name, startShuffled = true) },
                onSwipeAction = { genre ->
                    val songs = viewModel.songsForGenre(viewModel.libraryProjection.songs.value, genre.name)
                    viewModel.executeSubmenuActionForSongs(gestureSettings.swipeLeftAction, songs)
                },
            )
        }

    val songActions = rememberSongQueueActions(viewModel)
    val onPlayNext = songActions.onPlayNext
    val onAddToQueue = songActions.onAddToQueue
    val onStartRadio = songActions.onStartRadio
    val onAddToPlaylist = songDialogs.onAddToPlaylist
    val onEditMetadata = songDialogs.onEdit
    val onEditLyrics = songDialogs.onEditLyrics
    val onIdentify = songDialogs.onIdentify
    val onDeleteSong = songDialogs.onDelete
    val onPlayAlbum =
        remember<(String, List<Long>) -> Unit>(songList) {
            { _, albumIds ->
                viewModel.playCollection(songList.songsForIds(albumIds))
            }
        }
    val onShuffleAlbum =
        remember<(String, List<Long>) -> Unit>(songList) {
            { _, albumIds ->
                viewModel.shuffleCollection(songList.songsForIds(albumIds))
            }
        }
    val songListActions =
        remember(
            onPlayNext,
            onAddToQueue,
            onStartRadio,
            onAddToPlaylist,
            onEditMetadata,
            onEditLyrics,
            onIdentify,
            onDeleteSong,
            onPlayAlbum,
            onShuffleAlbum,
            toggleSelectSong,
            toggleSelectAlbum,
            onAlbumLongClick,
            toggleCollapseAlbum,
            onEditAlbumByKey,
            onChangeAlbumCoverByKey,
            onIdentifyAlbumByKey,
            selectedArtistName,
            selectedGenreName,
            gestureSettings,
        ) {
            LibrarySongListActions(
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onStartRadio = onStartRadio,
                onAddToPlaylist = onAddToPlaylist,
                onEditMetadata = onEditMetadata,
                onEditLyrics = onEditLyrics,
                onIdentify = onIdentify,
                onDeleteSong = onDeleteSong,
                onPlayAlbum = onPlayAlbum,
                onShuffleAlbum = onShuffleAlbum,
                onToggleSelect = toggleSelectSong,
                onToggleSelectAlbum = toggleSelectAlbum,
                onAlbumLongClick = onAlbumLongClick,
                onToggleCollapseAlbum = toggleCollapseAlbum,
                onEditAlbum = onEditAlbumByKey,
                onChangeAlbumCover = onChangeAlbumCoverByKey,
                onIdentifyAlbum = onIdentifyAlbumByKey,
                onOpenAlbum = { albumName ->
                    viewModel.openLibraryAlbum(
                        albumName,
                        fromNestedParent = selectedArtistName != null || selectedGenreName != null,
                    )
                },
                onSwipeAlbum = { _, albumIds ->
                    val songs = songList.songsForIds(albumIds)
                    viewModel.executeSubmenuActionForSongs(
                        action = gestureSettings.swipeLeftAction,
                        songs = songs,
                        onAddToPlaylist = { songDialogs.onAddManyToPlaylist(it) },
                    )
                },
            )
        }

    val sortEnabledInSheet = activeFilter != LibraryBrowseFilter.RECENT

    if (showBrowseSortSheet) {
        LibraryBrowseSortSheet(
            browseFilter = if (isPlaylistAdditionMode) LibraryBrowseFilter.SONGS else browseFilter,
            sortOption = sortOption,
            sortDirection = sortDirection,
            sortEnabled = sortEnabledInSheet,
            albumHeadersActive = showAlbumHeaders,
            filters = libraryBlobsSettings.enabledFilters,
            onBrowseFilterChange = { filter ->
                if (!isPlaylistAdditionMode) viewModel.setLibraryBrowseFilter(filter)
            },
            onSortOptionChange = { viewModel.setSortOption(it) },
            onToggleSortDirection = { viewModel.toggleSortDirection() },
            onDismiss = { showBrowseSortSheet = false },
        )
    }

    CompositionLocalProvider(LocalSubmenuGestureSettings provides gestureSettings) {
        Column(modifier = Modifier.fillMaxSize()) {
            val nestedTitle =
                when {
                    selectedAlbumName != null -> {
                        NestedAlbumDisplayName(
                            viewModel = viewModel,
                            albumKey = selectedAlbumName,
                        )
                    }

                    else -> {
                        selectedArtistName ?: selectedGenreName
                    }
                }

            val onPlayAll: () -> Unit = {
                if (hasNestedDetail) {
                    val nestedSongs =
                        libraryNestedSongs(
                            viewModel = viewModel,
                            songs = viewModel.libraryProjection.songs.value,
                            selectedAlbumName = selectedAlbumName,
                            selectedArtistName = selectedArtistName,
                            selectedGenreName = selectedGenreName,
                        )
                    viewModel.playCollection(nestedSongs, startShuffled = false)
                } else {
                    viewModel.playCurrentLibraryBrowse(shuffle = false)
                }
            }

            val onShuffleAll: () -> Unit = {
                if (hasNestedDetail) {
                    val nestedSongs =
                        libraryNestedSongs(
                            viewModel = viewModel,
                            songs = viewModel.libraryProjection.songs.value,
                            selectedAlbumName = selectedAlbumName,
                            selectedArtistName = selectedArtistName,
                            selectedGenreName = selectedGenreName,
                        )
                    viewModel.playCollection(nestedSongs, startShuffled = true)
                } else {
                    viewModel.playCurrentLibraryBrowse(shuffle = true)
                }
            }

            val showLibraryTopBar = !hasNestedDetail || isMultiSelectMode || isPlaylistAdditionMode
            if (showLibraryTopBar) {
                LibraryTopBar(
                    hasNestedDetail = hasNestedDetail,
                    onBackClick = {
                        if (hasPlaylistDetail) {
                            viewModel.closePlaylistDetail()
                        } else {
                            viewModel.popLibraryNested()
                        }
                    },
                    nestedTitle = nestedTitle,
                    isPlaylistAdditionMode = isPlaylistAdditionMode,
                    filterButtonLabel = filterButtonLabel,
                    orderSummary = orderSummary,
                    onOpenSortSheet = { showBrowseSortSheet = true },
                    searchExpanded = searchExpanded,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onSearchExpand = { searchExpanded = true },
                    onSearchCollapse = collapseSearch,
                    recentSearches = recentSearches,
                    onOpenSearchHistory = { showSearchHistorySheet = true },
                    searchFocusRequester = searchFocusRequester,
                    onSearchSubmit = { query ->
                        if (query.isNotBlank()) {
                            viewModel.addRecentSearch(query)
                        }
                    },
                    selectedAlbumName = selectedAlbumName,
                    onEditAlbum = { selectedAlbumName?.let { onEditAlbumByKey(it) } },
                    isMultiSelectMode = isMultiSelectMode,
                    onPlayAll = onPlayAll,
                    onShuffleAll = onShuffleAll,
                )

                if (searchExpanded && searchQuery.isBlank() && recentSearches.isNotEmpty()) {
                    SearchRecentChipsRow(
                        recentSearches = recentSearches,
                        onSelectQuery = { query ->
                            viewModel.setSearchQuery(query)
                            viewModel.addRecentSearch(query)
                        },
                        onRemoveQuery = { viewModel.removeRecentSearch(it) },
                        onClearAll = { viewModel.clearRecentSearches() },
                        onOpenFullHistory = { showSearchHistorySheet = true },
                    )
                }
            }

            // Hidden during multi-select: switching to Álbumes/Artistas/Géneros made "Seleccionar todo"
            // resolve against songsForBrowseProjection, i.e. the whole library, over rows the user cannot
            // see or untick.
            if (!hasNestedDetail && !isPlaylistAdditionMode && !isMultiSelectMode) {
                LibraryFilterChipRow(
                    selected = browseFilter,
                    onSelect = { viewModel.setLibraryBrowseFilter(it) },
                    filters = libraryBlobsSettings.enabledFilters,
                )
            }

            libraryJobProgress?.let { job ->
                LibraryProgressBanner(
                    progress = job,
                    onCancel =
                        if (job.kind == LibraryJobKind.IDENTIFY) {
                            { showAbortIdentifyDialog = true }
                        } else {
                            null
                        },
                )
            }
            if (identifyReview.pendingCount > 0 && !identifyReview.isVisible) {
                IdentifyPendingBanner(
                    pendingCount = identifyReview.pendingCount,
                    onReview = { viewModel.showIdentifyReview() },
                )
            }

            if (!isMultiSelectMode && !isPlaylistAdditionMode && !hasNestedDetail &&
                activeFilter == LibraryBrowseFilter.SONGS
            ) {
                LibraryViewModeToggleRow(
                    showAlbumHeaders = showAlbumHeaders,
                    hasAlbums = libraryAlbumNames.isNotEmpty(),
                    allAlbumsCollapsed = allAlbumsCollapsed,
                    onToggleCollapseAllAlbums = toggleCollapseAllAlbums,
                    onToggleLibraryViewMode = { viewModel.toggleLibraryViewMode() },
                )
            }

            if (isMultiSelectMode && !isPlaylistAdditionMode) {
                // Resolved against the *unfiltered* library, so searching narrows what you can tick
                // without losing what you already ticked, and the actions still cover all of it.
                val selectedSongs = viewModel.songsForIds(selectedSongIds)
                val multiSelectActions =
                    remember(viewModel, selectedSongs, songDialogs) {
                        MultiSelectActions(
                            onPlaySelected = {
                                viewModel.playCollection(selectedSongs)
                                clearSelection()
                            },
                            onEnqueueSelected = {
                                viewModel.enqueueCollection(selectedSongs)
                                clearSelection()
                            },
                            onAddToPlaylist = {
                                if (selectedSongs.isNotEmpty()) {
                                    songDialogs.onAddManyToPlaylist(selectedSongs)
                                }
                            },
                            onIdentifySelected = {
                                viewModel.openIdentifySetup(
                                    selectedSongs,
                                    contextTitle = "${selectedSongs.size} canciones seleccionadas",
                                )
                                clearSelection()
                            },
                            onSimilarSelected = {
                                viewModel.previewSimilarFromSelection(selectedSongs)
                                clearSelection()
                            },
                            onDeleteSelected = {
                                songDialogs.onDeleteMany(selectedSongs)
                            },
                            onSelectAll = selectAllSongs,
                            onClearSelection = clearSelection,
                        )
                    }
                MultiSelectActionBar(
                    selectedCount = selectedSongs.size,
                    actions = multiSelectActions,
                )
            }

            if (isPlaylistAdditionMode) {
                PlaylistAdditionActionBar(
                    playlistName = effectiveTargetPlaylist?.name ?: "Playlist",
                    selectedCount = selectedSongIds.size,
                    onConfirmAddition = {
                        val targetId = effectiveTargetPlaylist?.id ?: 0L
                        if (targetId != 0L && selectedSongIds.isNotEmpty()) {
                            viewModel.addSongsToPlaylist(
                                targetId,
                                selectedSongIds.toList(),
                            )
                        }
                        completePlaylistAddition()
                    },
                    onCancelAddition = cancelPlaylistAddition,
                    onSelectAll = selectAllSongs,
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                LibraryBrowsePane(
                    selectedAlbumName = selectedAlbumName,
                    selectedArtistName = selectedArtistName,
                    selectedGenreName = selectedGenreName,
                    activeFilter = activeFilter,
                    isPlaylistAdditionMode = isPlaylistAdditionMode,
                    isMultiSelectMode = isMultiSelectMode,
                    songList = songList,
                    catalogLoaded = catalogLoaded,
                    viewModel = viewModel,
                    currentSongIdFlow = currentSongId,
                    selectedSongIds = selectedSongIds,
                    collapsedAlbumNames = collapsedAlbumNames,
                    sortOption = sortOption,
                    sortDirection = sortDirection,
                    actions = songListActions,
                    onToggleSelect = toggleSelectSong,
                    searchQuery = searchQuery,
                    albumBrowseActions = albumBrowseActions,
                    artistBrowseActions = artistBrowseActions,
                    genreBrowseActions = genreBrowseActions,
                    fastScrollSettings = fastScrollSettings,
                    listStates = browseListStates,
                    onAddSongsToPlaylist = { localTargetPlaylistForAddition = it },
                    onAddManyToPlaylist = { songDialogs.onAddManyToPlaylist(it) },
                )
            }
        }

        AlbumEditDialogsHost(
            albumForEdit = albumForEdit,
            viewModel = viewModel,
            onDismissEdit = { albumForEdit = null },
        )

        albumForCoverChange?.let { album ->
            SetAlbumArtworkDialog(
                albumName = album.displayName,
                currentArtworkUri = album.artworkUri,
                onDismiss = { albumForCoverChange = null },
                onArtworkSelected = { newUri ->
                    viewModel.setAlbumArtwork(album.name, newUri)
                    albumForCoverChange = null
                },
            )
        }

        similarPlaylistPreview?.let { preview ->
            SimilarPlaylistPreviewDialog(
                state = preview,
                onDismiss = { viewModel.dismissSimilarPreview() },
                onToggleItem = { viewModel.toggleSimilarPreviewItem(it) },
                onModeChange = { viewModel.setSimilarPreviewMode(it) },
                onPlaylistNameChange = { viewModel.setSimilarPreviewPlaylistName(it) },
                onCreatePlaylist = { viewModel.confirmSimilarPreviewAsPlaylist() },
                onPlay = { viewModel.playSimilarPreview() },
                onEnqueue = { viewModel.enqueueSimilarPreview() },
            )
        }

        if (showAbortIdentifyDialog) {
            AlertDialog(
                onDismissRequest = { showAbortIdentifyDialog = false },
                title = { Text("¿Abortar identificación?") },
                text = {
                    Text("Se detendrá el proceso de identificación. Las canciones que ya fueron actualizadas conservarán sus cambios.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showAbortIdentifyDialog = false
                            viewModel.cancelIdentify()
                        },
                    ) {
                        Text(
                            text = "Abortar",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAbortIdentifyDialog = false }) {
                        Text("Continuar")
                    }
                },
            )
        }

        if (showSearchHistorySheet) {
            SearchHistorySheet(
                recentSearches = recentSearches,
                onSelectQuery = { query ->
                    viewModel.setSearchQuery(query)
                    viewModel.addRecentSearch(query)
                },
                onRemoveQuery = { viewModel.removeRecentSearch(it) },
                onClearAll = { viewModel.clearRecentSearches() },
                onDismiss = { showSearchHistorySheet = false },
            )
        }
    }
}
