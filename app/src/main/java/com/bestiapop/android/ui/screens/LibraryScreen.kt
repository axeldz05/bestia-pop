package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.PlayShuffleIconPair
import com.bestiapop.android.ui.components.MultiSelectActionBar
import com.bestiapop.android.ui.components.PlaylistAdditionActionBar
import com.bestiapop.android.ui.components.SimilarPlaylistPreviewDialog
import com.bestiapop.android.ui.components.rememberSongQueueActions
import com.bestiapop.android.ui.screens.library.AlbumEditDialogsHost
import com.bestiapop.android.ui.screens.library.IdentifyPendingBanner
import com.bestiapop.android.ui.screens.library.LibraryAlbumBrowseList
import com.bestiapop.android.ui.screens.library.LibraryArtistList
import com.bestiapop.android.ui.screens.library.LibraryBrowseSortSheet
import com.bestiapop.android.ui.screens.library.LibraryFilterChipRow
import com.bestiapop.android.ui.screens.library.LibraryGenreList
import com.bestiapop.android.ui.screens.library.LibraryProgressBanner
import com.bestiapop.android.ui.screens.library.LibrarySongListActions
import com.bestiapop.android.ui.screens.library.LibrarySongListHost
import com.bestiapop.android.ui.screens.library.SetAlbumArtworkDialog
import com.bestiapop.android.ui.screens.library.libraryOrderSummary
import com.bestiapop.android.ui.screens.library.libraryTuneContentDescription
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import com.bestiapop.android.ui.state.LibraryViewMode

@Composable
fun LibraryScreen(
    viewModel: MusicPlayerViewModel,
    targetPlaylistForAddition: Playlist? = null,
    onCompletePlaylistAddition: () -> Unit = {},
    onCancelPlaylistAddition: () -> Unit = {},
    onSelectFolderClick: () -> Unit,
    onOpenDownloads: () -> Unit = {}
) {
    val songs by viewModel.libraryProjection.songs.collectAsState()
    /** Unfiltered: multi-select keeps ids picked before a search narrowed the visible list. */
    val allSongs by viewModel.rawSongs.collectAsState(initial = emptyList())
    val albums by viewModel.libraryProjection.albums.collectAsState()
    val artists by viewModel.libraryProjection.artists.collectAsState()
    val genres by viewModel.libraryProjection.genres.collectAsState()
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val currentSong by viewModel.currentSong.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOption by viewModel.sortOption.collectAsState()
    val sortDirection by viewModel.sortDirection.collectAsState()
    val libraryViewMode by viewModel.libraryViewMode.collectAsState()
    val navigation by viewModel.navigation.collectAsState()
    val browseFilter = navigation.libraryBrowseFilter
    val selectedAlbumName = navigation.libraryStack.albumName
    val selectedArtistName = navigation.libraryStack.artistName
    val selectedGenreName = navigation.libraryStack.genreName
    val libraryJobProgress by viewModel.libraryJobProgress.collectAsState()
    val identifyReview by viewModel.identifyReview.collectAsState()
    val similarPlaylistPreview by viewModel.similarPlaylistPreview.collectAsState()

    var showBrowseSortSheet by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val collapseSearch: () -> Unit = {
        viewModel.setSearchQuery("")
        searchExpanded = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val isPlaylistAdditionMode = targetPlaylistForAddition != null
    val activeFilter = if (isPlaylistAdditionMode) LibraryBrowseFilter.SONGS else browseFilter
    val showAlbumHeaders = libraryViewMode == LibraryViewMode.ALBUM_GROUPS &&
        activeFilter == LibraryBrowseFilter.SONGS
    val songsViewMode = if (showAlbumHeaders) {
        LibraryViewMode.ALBUM_GROUPS
    } else {
        LibraryViewMode.FLAT
    }
    // `albums` is keyed in because headers now read album overrides: a rename has to refresh them.
    val songListItems = remember(songs, songsViewMode, albums) {
        viewModel.buildLibraryListItems(songs, songsViewMode)
    }
    val recentSongs = remember(songs) {
        songs.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }
    }
    val recentListItems = remember(recentSongs) {
        viewModel.buildLibraryListItems(recentSongs, LibraryViewMode.FLAT)
    }
    val songsForPlayAll = remember(
        activeFilter, songs, songsViewMode, albums, artists, genres, songListItems, recentSongs
    ) {
        when (activeFilter) {
            LibraryBrowseFilter.SONGS -> viewModel.songsFromLibraryListItems(songListItems)
            LibraryBrowseFilter.RECENT -> recentSongs
            else -> viewModel.songsForBrowseProjection(
                filter = activeFilter,
                songs = songs,
                viewMode = songsViewMode,
                albums = albums,
                artists = artists,
                genres = genres
            )
        }
    }
    val orderSummary = remember(activeFilter, sortOption, sortDirection) {
        libraryOrderSummary(activeFilter, sortOption, sortDirection)
    }

    var collapsedAlbumNames by remember { mutableStateOf(setOf<String>()) }

    // Multi-selection state
    var selectedSongIds by remember { mutableStateOf(setOf<Long>()) }
    val isMultiSelectMode = selectedSongIds.isNotEmpty()


    // Add Music dialog state
    var showAddMusicDialog by remember { mutableStateOf(false) }

    // Active Dialogs state
    var albumForCoverChange by remember { mutableStateOf<Album?>(null) }
    var albumForEdit by remember { mutableStateOf<Album?>(null) }
    val songDialogs = rememberSongActionDialogs(
        viewModel = viewModel,
        playlists = playlists,
        onAfterPlaylistAdd = { selectedSongIds = emptySet() },
        onAfterDelete = { selectedSongIds = emptySet() },
        playlistSongIds = { song ->
            if (selectedSongIds.isNotEmpty()) selectedSongIds.toList() else listOf(song.id)
        }
    )

    val resolveAlbumByKey = remember(albums) {
        { albumKey: String ->
            albums.firstOrNull { it.name.equals(albumKey, ignoreCase = true) }
        }
    }

    val onEditAlbumByKey = remember(resolveAlbumByKey) {
        { albumKey: String ->
            resolveAlbumByKey(albumKey)?.let { albumForEdit = it }
            Unit
        }
    }
    val onChangeAlbumCoverByKey = remember(resolveAlbumByKey) {
        { albumKey: String ->
            resolveAlbumByKey(albumKey)?.let { albumForCoverChange = it }
            Unit
        }
    }
    val onIdentifyAlbumByKey = remember(resolveAlbumByKey, songs) {
        { albumKey: String ->
            resolveAlbumByKey(albumKey)?.let { album ->
                val albumSongs = viewModel.songsForAlbum(songs, album.name)
                if (albumSongs.isNotEmpty()) {
                    viewModel.openIdentifySetup(albumSongs, contextTitle = "Álbum: ${album.displayName}")
                }
            }
            Unit
        }
    }

    val currentSongId = currentSong?.id

    val toggleSelectSong = remember<(Song) -> Unit> {
        { song ->
            selectedSongIds = if (selectedSongIds.contains(song.id)) {
                selectedSongIds - song.id
            } else {
                selectedSongIds + song.id
            }
        }
    }

    val toggleSelectAlbum = remember<(List<Song>) -> Unit> {
        { albumSongs ->
            val ids = albumSongs.map { it.id }.toSet()
            selectedSongIds = if (ids.isNotEmpty() && ids.all { selectedSongIds.contains(it) }) {
                selectedSongIds - ids
            } else {
                selectedSongIds + ids
            }
        }
    }

    val onAlbumLongClick = remember<(List<Song>) -> Unit> {
        { albumSongs ->
            selectedSongIds = selectedSongIds + albumSongs.map { it.id }
        }
    }

    val toggleCollapseAlbum = remember<(String) -> Unit> {
        { albumName ->
            collapsedAlbumNames = if (collapsedAlbumNames.contains(albumName)) {
                collapsedAlbumNames - albumName
            } else {
                collapsedAlbumNames + albumName
            }
        }
    }

    val libraryAlbumNames = remember(songs) {
        songs.map { it.album }.filter { it.isNotBlank() }.toSet()
    }
    val allAlbumsCollapsed = libraryAlbumNames.isNotEmpty() &&
        libraryAlbumNames.all { collapsedAlbumNames.contains(it) }
    val toggleCollapseAllAlbums = {
        collapsedAlbumNames = if (allAlbumsCollapsed) emptySet() else libraryAlbumNames
    }

    val songsForSelection = remember(
        selectedAlbumName, selectedArtistName, selectedGenreName,
        activeFilter, songs, recentSongs, songsForPlayAll
    ) {
        when {
            selectedAlbumName != null -> viewModel.songsForAlbum(songs, selectedAlbumName!!)
            selectedArtistName != null -> viewModel.songsForArtist(songs, selectedArtistName!!)
            selectedGenreName != null -> viewModel.songsForGenre(songs, selectedGenreName!!)
            activeFilter == LibraryBrowseFilter.RECENT -> recentSongs
            else -> songsForPlayAll
        }
    }

    val selectAllSongs = {
        selectedSongIds = songsForSelection.map { it.id }.toSet()
    }

    val clearSelection = remember {
        { selectedSongIds = emptySet() }
    }

    val hasNestedDetail = selectedAlbumName != null ||
        selectedArtistName != null ||
        selectedGenreName != null
    val hasNestedBack = isMultiSelectMode ||
        isPlaylistAdditionMode ||
        hasNestedDetail ||
        searchQuery.isNotEmpty() ||
        searchExpanded

    BackHandler(enabled = hasNestedBack) {
        when {
            // Addition first: isMultiSelectMode is just "something is ticked", so back used to wipe
            // the user's picks instead of cancelling, needing a second press to do what X does once.
            isPlaylistAdditionMode -> onCancelPlaylistAddition()
            isMultiSelectMode -> clearSelection()
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

    val playOrShuffleAlbum: (Album, Boolean) -> Unit = remember(songs) {
        { album, shuffle ->
            val albumSongs = viewModel.songsForAlbum(songs, album.name)
            if (shuffle) viewModel.shuffleCollection(albumSongs)
            else viewModel.playCollection(albumSongs)
        }
    }
    val playOrShuffleArtist: (String, Boolean) -> Unit = remember(songs) {
        { artistName, shuffle ->
            val artistSongs = viewModel.songsForArtist(songs, artistName)
            if (shuffle) viewModel.shuffleCollection(artistSongs)
            else viewModel.playCollection(artistSongs)
        }
    }
    val playOrShuffleGenre: (String, Boolean) -> Unit = remember(songs) {
        { genreName, shuffle ->
            val genreSongs = viewModel.songsForGenre(songs, genreName)
            if (shuffle) viewModel.shuffleCollection(genreSongs)
            else viewModel.playCollection(genreSongs)
        }
    }

    val songActions = rememberSongQueueActions(viewModel)
    val onPlayNext = songActions.onPlayNext
    val onAddToQueue = songActions.onAddToQueue
    val onStartRadio = songActions.onStartRadio
    val onAddToPlaylist = songDialogs.onAddToPlaylist
    val onEditMetadata = songDialogs.onEdit
    val onIdentify = remember<(Song) -> Unit> { { viewModel.identifySongForReview(it) } }
    val onDeleteSong = songDialogs.onDelete
    val onPlayAlbum = remember<(String, List<Song>) -> Unit> {
        { _, albumSongs -> viewModel.playCollection(albumSongs) }
    }
    val onShuffleAlbum = remember<(String, List<Song>) -> Unit> {
        { _, albumSongs -> viewModel.shuffleCollection(albumSongs) }
    }
    val songListActions = remember(
        onPlayNext, onAddToQueue, onStartRadio, onAddToPlaylist, onEditMetadata, onIdentify, onDeleteSong,
        onPlayAlbum, onShuffleAlbum, toggleSelectSong, toggleSelectAlbum, onAlbumLongClick,
        toggleCollapseAlbum, onEditAlbumByKey, onChangeAlbumCoverByKey, onIdentifyAlbumByKey, selectedArtistName, selectedGenreName
    ) {
        LibrarySongListActions(
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onStartRadio = onStartRadio,
            onAddToPlaylist = onAddToPlaylist,
            onEditMetadata = onEditMetadata,
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
                    fromNestedParent = selectedArtistName != null || selectedGenreName != null
                )
            }
        )
    }

    val sortEnabledInSheet = activeFilter != LibraryBrowseFilter.RECENT

    if (showBrowseSortSheet) {
        LibraryBrowseSortSheet(
            browseFilter = if (isPlaylistAdditionMode) LibraryBrowseFilter.SONGS else browseFilter,
            sortOption = sortOption,
            sortDirection = sortDirection,
            sortEnabled = sortEnabledInSheet,
            onBrowseFilterChange = { filter ->
                if (!isPlaylistAdditionMode) viewModel.setLibraryBrowseFilter(filter)
            },
            onSortOptionChange = { viewModel.setSortOption(it) },
            onToggleSortDirection = { viewModel.toggleSortDirection() },
            onDismiss = { showBrowseSortSheet = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasNestedDetail) {
                IconButton(onClick = { viewModel.popLibraryNested() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
            }

            // Nested album detail renders a FLAT list with no group header, so without this the user
            // saw a bare song list with nothing naming the album they opened.
            val nestedTitle = selectedAlbumName?.let { key ->
                resolveAlbumByKey(key)?.displayName ?: key
            } ?: selectedArtistName ?: selectedGenreName

            if (searchExpanded) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Buscar…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = collapseSearch) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar búsqueda")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            } else {
                if (nestedTitle != null) {
                    Text(
                        text = nestedTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                IconButton(onClick = { searchExpanded = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Buscar")
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            if (selectedAlbumName != null) {
                IconButton(onClick = { onEditAlbumByKey(selectedAlbumName!!) }) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar álbum")
                }
            }

            if (!searchExpanded && !isPlaylistAdditionMode) {
                IconButton(onClick = { showBrowseSortSheet = true }) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = libraryTuneContentDescription(orderSummary)
                    )
                }
            }

            if (!searchExpanded && !isMultiSelectMode && !isPlaylistAdditionMode && !hasNestedDetail) {
                PlayShuffleIconPair(
                    onPlay = {
                        if (songsForPlayAll.isNotEmpty()) viewModel.playCollection(songsForPlayAll)
                    },
                    onShuffle = {
                        if (songsForPlayAll.isNotEmpty()) viewModel.shuffleCollection(songsForPlayAll)
                    },
                    playDescription = "Reproducir todo",
                    shuffleDescription = "Mezclar"
                )
            }
        }

        // Hidden during multi-select: switching to Álbumes/Artistas/Géneros made "Seleccionar todo"
        // resolve against songsForBrowseProjection, i.e. the whole library, over rows the user cannot
        // see or untick.
        if (!hasNestedDetail && !isPlaylistAdditionMode && !isMultiSelectMode) {
            LibraryFilterChipRow(
                selected = browseFilter,
                onSelect = { viewModel.setLibraryBrowseFilter(it) }
            )
        }

        libraryJobProgress?.let { job ->
            LibraryProgressBanner(progress = job)
        }
        if (identifyReview.pendingCount > 0 && !identifyReview.isVisible) {
            IdentifyPendingBanner(
                pendingCount = identifyReview.pendingCount,
                onReview = { viewModel.showIdentifyReview() }
            )
        }

        if (!isMultiSelectMode && !isPlaylistAdditionMode && !hasNestedDetail &&
            activeFilter == LibraryBrowseFilter.SONGS
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showAlbumHeaders && libraryAlbumNames.isNotEmpty()) {
                    IconButton(onClick = toggleCollapseAllAlbums) {
                        Icon(
                            imageVector = if (allAlbumsCollapsed) {
                                Icons.Default.UnfoldMore
                            } else {
                                Icons.Default.UnfoldLess
                            },
                            contentDescription = if (allAlbumsCollapsed) {
                                "Expandir todos los álbumes"
                            } else {
                                "Colapsar todos los álbumes"
                            }
                        )
                    }
                }
                IconButton(onClick = { viewModel.toggleLibraryViewMode() }) {
                    Icon(
                        imageVector = Icons.Default.ViewAgenda,
                        contentDescription = "Cambiar vista",
                        tint = if (showAlbumHeaders) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }

        if (isMultiSelectMode && !isPlaylistAdditionMode) {
            // Resolved against the *unfiltered* library, so searching narrows what you can tick
            // without losing what you already ticked, and the actions still cover all of it.
            val selectedSongs = allSongs.filter { selectedSongIds.contains(it.id) }
            MultiSelectActionBar(
                selectedCount = selectedSongs.size,
                onPlaySelected = {
                    viewModel.playCollection(selectedSongs)
                    clearSelection()
                },
                onEnqueueSelected = {
                    viewModel.enqueueCollection(selectedSongs)
                    clearSelection()
                },
                onAddToPlaylist = {
                    selectedSongs.firstOrNull()?.let(songDialogs.onAddToPlaylist)
                },
                onIdentifySelected = {
                    viewModel.openIdentifySetup(
                        selectedSongs,
                        contextTitle = "${selectedSongs.size} canciones seleccionadas"
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
                onClearSelection = clearSelection
            )
        }

        if (isPlaylistAdditionMode) {
            PlaylistAdditionActionBar(
                playlistName = targetPlaylistForAddition?.name ?: "Playlist",
                selectedCount = selectedSongIds.size,
                onConfirmAddition = {
                    viewModel.addSongsToPlaylist(
                        targetPlaylistForAddition?.id ?: 0L,
                        selectedSongIds.toList()
                    )
                    onCompletePlaylistAddition()
                },
                onCancelAddition = onCancelPlaylistAddition
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                selectedAlbumName != null -> {
                    val albumName = selectedAlbumName!!
                    val albumSongs = remember(songs, albumName) {
                        viewModel.songsForAlbum(songs, albumName)
                    }
                    NestedLibraryBrowse(
                        browseSongs = albumSongs,
                        viewMode = LibraryViewMode.FLAT,
                        viewModel = viewModel,
                        currentSongId = currentSongId,
                        isSelectionMode = isMultiSelectMode,
                        selectedSongIds = selectedSongIds,
                        collapsedAlbumNames = collapsedAlbumNames,
                        sortOption = sortOption,
                        actions = songListActions,
                        onToggleSelect = toggleSelectSong
                    )
                }

                selectedArtistName != null -> {
                    val artistSongs = remember(songs, selectedArtistName) {
                        viewModel.songsForArtist(songs, selectedArtistName!!)
                    }
                    NestedLibraryBrowse(
                        browseSongs = artistSongs,
                        viewMode = LibraryViewMode.ALBUM_GROUPS,
                        viewModel = viewModel,
                        currentSongId = currentSongId,
                        isSelectionMode = isMultiSelectMode,
                        selectedSongIds = selectedSongIds,
                        collapsedAlbumNames = collapsedAlbumNames,
                        sortOption = sortOption,
                        actions = songListActions,
                        onToggleSelect = toggleSelectSong
                    )
                }

                selectedGenreName != null -> {
                    val genreName = selectedGenreName!!
                    val genreSongs = remember(songs, genreName) {
                        viewModel.songsForGenre(songs, genreName)
                    }
                    NestedLibraryBrowse(
                        browseSongs = genreSongs,
                        viewMode = LibraryViewMode.ALBUM_GROUPS,
                        viewModel = viewModel,
                        currentSongId = currentSongId,
                        isSelectionMode = isMultiSelectMode,
                        selectedSongIds = selectedSongIds,
                        collapsedAlbumNames = collapsedAlbumNames,
                        sortOption = sortOption,
                        actions = songListActions,
                        onToggleSelect = toggleSelectSong
                    )
                }

                activeFilter == LibraryBrowseFilter.SONGS || isPlaylistAdditionMode -> {
                    LibrarySongListHost(
                        items = songListItems,
                        currentSongId = currentSongId,
                        isSelectionMode = isMultiSelectMode || isPlaylistAdditionMode,
                        selectedSongIds = selectedSongIds,
                        collapsedAlbumNames = collapsedAlbumNames,
                        sortOption = sortOption,
                        actions = songListActions,
                        onSongClick = { song, index ->
                            if (isPlaylistAdditionMode || isMultiSelectMode) {
                                toggleSelectSong(song)
                            } else {
                                viewModel.playCollection(songsForPlayAll, index)
                            }
                        }
                    )
                }

                activeFilter == LibraryBrowseFilter.RECENT -> {
                    val recentEmptyFromSearch = searchQuery.isNotBlank()
                    LibrarySongListHost(
                        items = recentListItems,
                        currentSongId = currentSongId,
                        isSelectionMode = isMultiSelectMode,
                        selectedSongIds = selectedSongIds,
                        collapsedAlbumNames = emptySet(),
                        sortOption = SortOption.DATE_ADDED,
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
                        actions = songListActions,
                        onSongClick = { song, index ->
                            if (isMultiSelectMode) {
                                toggleSelectSong(song)
                            } else {
                                viewModel.playCollection(recentSongs, index)
                            }
                        }
                    )
                }

                activeFilter == LibraryBrowseFilter.ALBUMS -> {
                    LibraryAlbumBrowseList(
                        albums = albums,
                        sortOption = sortOption,
                        onAlbumClick = { viewModel.openLibraryAlbum(it.name, fromNestedParent = false) },
                        onPlayAlbum = { playOrShuffleAlbum(it, false) },
                        onShuffleAlbum = { playOrShuffleAlbum(it, true) },
                        onEditAlbum = { albumForEdit = it },
                        onChangeAlbumCover = { albumForCoverChange = it },
                        onIdentifyAlbum = { album ->
                            val albumSongs = viewModel.songsForAlbum(songs, album.name)
                            if (albumSongs.isNotEmpty()) {
                                viewModel.openIdentifySetup(
                                    albumSongs,
                                    contextTitle = "Álbum: ${album.displayName}"
                                )
                            }
                        }
                    )
                }

                activeFilter == LibraryBrowseFilter.ARTISTS -> {
                    LibraryArtistList(
                        artists = artists,
                        sortOption = sortOption,
                        onArtistClick = { viewModel.openLibraryArtist(it.name) },
                        onPlayArtist = { playOrShuffleArtist(it.name, false) },
                        onShuffleArtist = { playOrShuffleArtist(it.name, true) }
                    )
                }

                activeFilter == LibraryBrowseFilter.GENRES -> {
                    LibraryGenreList(
                        genres = genres,
                        sortOption = sortOption,
                        onGenreClick = { viewModel.openLibraryGenre(it.name) },
                        onPlayGenre = { playOrShuffleGenre(it.name, false) },
                        onShuffleGenre = { playOrShuffleGenre(it.name, true) }
                    )
                }
            }

            if (!isPlaylistAdditionMode) {
                FloatingActionButton(
                    onClick = { showAddMusicDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Agregar", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    AlbumEditDialogsHost(
        albumForEdit = albumForEdit,
        viewModel = viewModel,
        onDismissEdit = { albumForEdit = null }
    )

    albumForCoverChange?.let { album ->
        SetAlbumArtworkDialog(
            albumName = album.displayName,
            currentArtworkUri = album.artworkUri,
            onDismiss = { albumForCoverChange = null },
            onArtworkSelected = { newUri ->
                viewModel.setAlbumArtwork(album.name, newUri)
                albumForCoverChange = null
            }
        )
    }

    if (showAddMusicDialog) {
        com.bestiapop.android.ui.components.AddMusicDialog(
            viewModel = viewModel,
            onSelectFolderClick = {
                showAddMusicDialog = false
                onSelectFolderClick()
            },
            onDismiss = { showAddMusicDialog = false },
            onOpenDownloads = {
                showAddMusicDialog = false
                onOpenDownloads()
            }
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
            onEnqueue = { viewModel.enqueueSimilarPreview() }
        )
    }
}

/** Nested album/artist/genre detail: build list items + play in view order. */
@Composable
private fun NestedLibraryBrowse(
    browseSongs: List<Song>,
    viewMode: LibraryViewMode,
    viewModel: MusicPlayerViewModel,
    currentSongId: Long?,
    isSelectionMode: Boolean,
    selectedSongIds: Set<Long>,
    collapsedAlbumNames: Set<String>,
    sortOption: SortOption,
    actions: LibrarySongListActions,
    onToggleSelect: (Song) -> Unit
) {
    // Keyed on albums so an album rename refreshes the group headers, which read the override name.
    val albums by viewModel.libraryProjection.albums.collectAsState()
    val listItems = remember(browseSongs, viewMode, albums) {
        viewModel.buildLibraryListItems(browseSongs, viewMode)
    }
    val playQueue = remember(listItems, viewMode, browseSongs) {
        if (viewMode == LibraryViewMode.ALBUM_GROUPS) {
            viewModel.songsFromLibraryListItems(listItems)
        } else {
            browseSongs
        }
    }
    LibrarySongListHost(
        items = listItems,
        currentSongId = currentSongId,
        isSelectionMode = isSelectionMode,
        selectedSongIds = selectedSongIds,
        collapsedAlbumNames = collapsedAlbumNames,
        sortOption = sortOption,
        actions = actions,
        onSongClick = { song, index ->
            if (isSelectionMode) onToggleSelect(song)
            else viewModel.playCollection(playQueue, index)
        }
    )
}
