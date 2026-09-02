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
import androidx.compose.runtime.produceState
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
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.albumNamesMatch
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.SortDirection
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
import com.bestiapop.android.ui.state.LibraryListModel
import com.bestiapop.android.ui.state.LibraryViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    viewModel: MusicPlayerViewModel,
    targetPlaylistForAddition: Playlist? = null,
    onCompletePlaylistAddition: () -> Unit = {},
    onCancelPlaylistAddition: () -> Unit = {},
    onSelectFolderClick: () -> Unit,
    onOpenDownloads: () -> Unit = {}
) {
    val identifyReview by viewModel.identifyReview.collectAsState()
    val catalogLoaded by viewModel.libraryProjection.catalogLoaded.collectAsState()
    val songList by viewModel.libraryProjection.songList.collectAsState()
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
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
    val orderSummary = remember(activeFilter, sortOption, sortDirection, showAlbumHeaders) {
        libraryOrderSummary(activeFilter, sortOption, sortDirection, showAlbumHeaders)
    }

    var collapsedAlbumNames by remember { mutableStateOf(setOf<String>()) }

    // Multi-selection state
    var selectedSongIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedSongsById by remember { mutableStateOf(mapOf<Long, Song>()) }
    val isMultiSelectMode = selectedSongIds.isNotEmpty()


    // Add Music dialog state
    var showAddMusicDialog by remember { mutableStateOf(false) }

    // Active Dialogs state
    var albumForCoverChange by remember { mutableStateOf<Album?>(null) }
    var albumForEdit by remember { mutableStateOf<Album?>(null) }
    val songDialogs = rememberSongActionDialogs(
        viewModel = viewModel,
        playlists = playlists,
        onAfterPlaylistAdd = {
            selectedSongIds = emptySet()
            selectedSongsById = emptyMap()
        },
        onAfterDelete = {
            selectedSongIds = emptySet()
            selectedSongsById = emptyMap()
        },
        playlistSongIds = { song ->
            if (selectedSongIds.isNotEmpty()) selectedSongIds.toList() else listOf(song.id)
        }
    )

    val resolveAlbumByKey: (String) -> Album? = remember(viewModel) {
        { albumKey: String ->
            viewModel.libraryProjection.albums.value.firstOrNull {
                albumNamesMatch(it.name, albumKey) || albumNamesMatch(it.displayName, albumKey)
            }
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
    val onIdentifyAlbumByKey = remember(resolveAlbumByKey, viewModel) {
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

    val toggleSelectSong = remember<(Song) -> Unit> {
        { song ->
            if (selectedSongIds.contains(song.id)) {
                selectedSongIds = selectedSongIds - song.id
                selectedSongsById = selectedSongsById - song.id
            } else {
                selectedSongIds = selectedSongIds + song.id
                selectedSongsById = selectedSongsById + (song.id to song)
            }
        }
    }

    val toggleSelectAlbum = remember(songList) {
        { albumIds: List<Long> ->
            val ids = albumIds.toSet()
            val removing = ids.isNotEmpty() && ids.all { selectedSongIds.contains(it) }
            if (removing) {
                selectedSongIds = selectedSongIds - ids
                selectedSongsById = selectedSongsById - ids
            } else {
                selectedSongIds = selectedSongIds + ids
                selectedSongsById = selectedSongsById + ids.mapNotNull { id ->
                    songList.songsById[id]?.let { id to it }
                }
            }
        }
    }

    val onAlbumLongClick = remember(songList) {
        { albumIds: List<Long> ->
            selectedSongIds = selectedSongIds + albumIds
            selectedSongsById = selectedSongsById + albumIds.mapNotNull { id ->
                songList.songsById[id]?.let { id to it }
            }
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

    val libraryAlbumNames = songList.albumNames
    val allAlbumsCollapsed = libraryAlbumNames.isNotEmpty() &&
        libraryAlbumNames.all { collapsedAlbumNames.contains(it) }
    val toggleCollapseAllAlbums = {
        collapsedAlbumNames = if (allAlbumsCollapsed) emptySet() else libraryAlbumNames
    }

    val selectAllSongs = {
        val pool = songsForCurrentLibrarySelection(
            viewModel = viewModel,
            songList = songList,
            selectedAlbumName = selectedAlbumName,
            selectedArtistName = selectedArtistName,
            selectedGenreName = selectedGenreName,
            activeFilter = activeFilter,
            songsViewMode = songsViewMode
        )
        selectedSongIds = pool.map { it.id }.toSet()
        selectedSongsById = selectedSongsById + pool.associateBy { it.id }
    }

    val clearSelection = remember {
        {
            selectedSongIds = emptySet()
            selectedSongsById = emptyMap()
        }
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

    val playOrShuffleAlbum: (Album, Boolean) -> Unit = remember(viewModel) {
        { album, shuffle ->
            val albumSongs = viewModel.songsForAlbum(viewModel.libraryProjection.songs.value, album.name)
            if (shuffle) viewModel.shuffleCollection(albumSongs)
            else viewModel.playCollection(albumSongs)
        }
    }
    val playOrShuffleArtist: (String, Boolean) -> Unit = remember(viewModel) {
        { artistName, shuffle ->
            val artistSongs = viewModel.songsForArtist(viewModel.libraryProjection.songs.value, artistName)
            if (shuffle) viewModel.shuffleCollection(artistSongs)
            else viewModel.playCollection(artistSongs)
        }
    }
    val playOrShuffleGenre: (String, Boolean) -> Unit = remember(viewModel) {
        { genreName, shuffle ->
            val genreSongs = viewModel.songsForGenre(viewModel.libraryProjection.songs.value, genreName)
            if (shuffle) viewModel.shuffleCollection(genreSongs)
            else viewModel.playCollection(genreSongs)
        }
    }
    val onShuffleAlbumBrowse = remember(playOrShuffleAlbum) {
        { album: Album -> playOrShuffleAlbum(album, true) }
    }
    val onOpenAlbumBrowse = remember {
        { album: Album -> viewModel.openLibraryAlbum(album.name, fromNestedParent = false) }
    }
    val onEditAlbumBrowse = remember {
        { album: Album -> albumForEdit = album }
    }
    val onChangeAlbumCoverBrowse = remember {
        { album: Album -> albumForCoverChange = album }
    }
    val onIdentifyAlbumBrowse = remember(viewModel) {
        { album: Album ->
            val albumSongs = viewModel.songsForAlbum(viewModel.libraryProjection.songs.value, album.name)
            if (albumSongs.isNotEmpty()) {
                viewModel.openIdentifySetup(
                    albumSongs,
                    contextTitle = "Álbum: ${album.displayName}"
                )
            }
        }
    }
    val onArtistClickBrowse = remember {
        { artist: Artist -> viewModel.openLibraryArtist(artist.name) }
    }
    val onPlayArtistBrowse = remember(playOrShuffleArtist) {
        { artist: Artist -> playOrShuffleArtist(artist.name, false) }
    }
    val onShuffleArtistBrowse = remember(playOrShuffleArtist) {
        { artist: Artist -> playOrShuffleArtist(artist.name, true) }
    }
    val onGenreClickBrowse = remember {
        { genre: GenreGroup -> viewModel.openLibraryGenre(genre.name) }
    }
    val onPlayGenreBrowse = remember(playOrShuffleGenre) {
        { genre: GenreGroup -> playOrShuffleGenre(genre.name, false) }
    }
    val onShuffleGenreBrowse = remember(playOrShuffleGenre) {
        { genre: GenreGroup -> playOrShuffleGenre(genre.name, true) }
    }

    val songActions = rememberSongQueueActions(viewModel)
    val onPlayNext = songActions.onPlayNext
    val onAddToQueue = songActions.onAddToQueue
    val onStartRadio = songActions.onStartRadio
    val onAddToPlaylist = songDialogs.onAddToPlaylist
    val onEditMetadata = songDialogs.onEdit
    val onEditLyrics = songDialogs.onEditLyrics
    val onIdentify = remember<(Song) -> Unit> { { viewModel.identifySongForReview(it) } }
    val onDeleteSong = songDialogs.onDelete
    val onPlayAlbum = remember<(String, List<Long>) -> Unit>(songList) {
        { _, albumIds ->
            viewModel.playCollection(albumIds.mapNotNull { songList.songsById[it] })
        }
    }
    val onShuffleAlbum = remember<(String, List<Long>) -> Unit>(songList) {
        { _, albumIds ->
            viewModel.shuffleCollection(albumIds.mapNotNull { songList.songsById[it] })
        }
    }
    val songListActions = remember(
        onPlayNext, onAddToQueue, onStartRadio, onAddToPlaylist, onEditMetadata, onEditLyrics, onIdentify, onDeleteSong,
        onPlayAlbum, onShuffleAlbum, toggleSelectSong, toggleSelectAlbum, onAlbumLongClick,
        toggleCollapseAlbum, onEditAlbumByKey, onChangeAlbumCoverByKey, onIdentifyAlbumByKey, selectedArtistName, selectedGenreName
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
            albumHeadersActive = showAlbumHeaders,
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
            val nestedTitle = when {
                selectedAlbumName != null -> NestedAlbumDisplayName(
                    viewModel = viewModel,
                    albumKey = selectedAlbumName!!
                )
                else -> selectedArtistName ?: selectedGenreName
            }

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
                    onPlay = { viewModel.playCurrentLibraryBrowse(shuffle = false) },
                    onShuffle = { viewModel.playCurrentLibraryBrowse(shuffle = true) },
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
            val selectedSongs = selectedSongIds.mapNotNull { selectedSongsById[it] }
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
                onPlayAlbum = playOrShuffleAlbum,
                onShuffleAlbum = onShuffleAlbumBrowse,
                onOpenAlbum = onOpenAlbumBrowse,
                onEditAlbum = onEditAlbumBrowse,
                onChangeAlbumCover = onChangeAlbumCoverBrowse,
                onIdentifyAlbum = onIdentifyAlbumBrowse,
                onArtistClick = onArtistClickBrowse,
                onPlayArtist = onPlayArtistBrowse,
                onShuffleArtist = onShuffleArtistBrowse,
                onGenreClick = onGenreClickBrowse,
                onPlayGenre = onPlayGenreBrowse,
                onShuffleGenre = onShuffleGenreBrowse
            )

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

@Composable
private fun LibraryBrowsePane(
    selectedAlbumName: String?,
    selectedArtistName: String?,
    selectedGenreName: String?,
    activeFilter: LibraryBrowseFilter,
    isPlaylistAdditionMode: Boolean,
    isMultiSelectMode: Boolean,
    songList: LibraryListModel,
    catalogLoaded: Boolean,
    viewModel: MusicPlayerViewModel,
    currentSongIdFlow: StateFlow<Long?>,
    selectedSongIds: Set<Long>,
    collapsedAlbumNames: Set<String>,
    sortOption: SortOption,
    sortDirection: SortDirection,
    actions: LibrarySongListActions,
    onToggleSelect: (Song) -> Unit,
    searchQuery: String,
    onPlayAlbum: (Album, Boolean) -> Unit,
    onShuffleAlbum: (Album) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onEditAlbum: (Album) -> Unit,
    onChangeAlbumCover: (Album) -> Unit,
    onIdentifyAlbum: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onPlayArtist: (Artist) -> Unit,
    onShuffleArtist: (Artist) -> Unit,
    onGenreClick: (GenreGroup) -> Unit,
    onPlayGenre: (GenreGroup) -> Unit,
    onShuffleGenre: (GenreGroup) -> Unit
) {
    when {
        selectedAlbumName != null -> {
            NestedLibraryBrowse(
                selectedAlbumName = selectedAlbumName,
                selectedArtistName = null,
                selectedGenreName = null,
                viewMode = LibraryViewMode.FLAT,
                viewModel = viewModel,
                currentSongIdFlow = currentSongIdFlow,
                isSelectionMode = isMultiSelectMode,
                selectedSongIds = selectedSongIds,
                collapsedAlbumNames = collapsedAlbumNames,
                sortOption = sortOption,
                sortDirection = sortDirection,
                actions = actions,
                onToggleSelect = onToggleSelect
            )
        }

        selectedArtistName != null -> {
            NestedLibraryBrowse(
                selectedAlbumName = null,
                selectedArtistName = selectedArtistName,
                selectedGenreName = null,
                viewMode = LibraryViewMode.ALBUM_GROUPS,
                viewModel = viewModel,
                currentSongIdFlow = currentSongIdFlow,
                isSelectionMode = isMultiSelectMode,
                selectedSongIds = selectedSongIds,
                collapsedAlbumNames = collapsedAlbumNames,
                sortOption = sortOption,
                sortDirection = sortDirection,
                actions = actions,
                onToggleSelect = onToggleSelect
            )
        }

        selectedGenreName != null -> {
            NestedLibraryBrowse(
                selectedAlbumName = null,
                selectedArtistName = null,
                selectedGenreName = selectedGenreName,
                viewMode = LibraryViewMode.ALBUM_GROUPS,
                viewModel = viewModel,
                currentSongIdFlow = currentSongIdFlow,
                isSelectionMode = isMultiSelectMode,
                selectedSongIds = selectedSongIds,
                collapsedAlbumNames = collapsedAlbumNames,
                sortOption = sortOption,
                sortDirection = sortDirection,
                actions = actions,
                onToggleSelect = onToggleSelect
            )
        }

        activeFilter == LibraryBrowseFilter.SONGS || isPlaylistAdditionMode -> {
            val onLibrarySongsClick = remember(
                isPlaylistAdditionMode,
                isMultiSelectMode,
                songList,
                onToggleSelect
            ) {
                { song: Song, index: Int ->
                    if (isPlaylistAdditionMode || isMultiSelectMode) {
                        onToggleSelect(song)
                    } else {
                        viewModel.playCollection(songList.songsVisual, index)
                    }
                }
            }
            LibrarySongListHost(
                list = songList,
                currentSongId = null,
                currentSongIdFlow = currentSongIdFlow,
                isSelectionMode = isMultiSelectMode || isPlaylistAdditionMode,
                selectedSongIds = selectedSongIds,
                collapsedAlbumNames = collapsedAlbumNames,
                sortOption = sortOption,
                actions = actions,
                onSongClick = onLibrarySongsClick,
                loading = !catalogLoaded
            )
        }

        activeFilter == LibraryBrowseFilter.RECENT -> {
            LibraryRecentTab(
                viewModel = viewModel,
                currentSongIdFlow = currentSongIdFlow,
                isSelectionMode = isMultiSelectMode,
                selectedSongIds = selectedSongIds,
                sortOption = SortOption.DATE_ADDED,
                actions = actions,
                searchQuery = searchQuery,
                onToggleSelect = onToggleSelect
            )
        }

        activeFilter == LibraryBrowseFilter.ALBUMS -> {
            LibraryAlbumsTab(
                viewModel = viewModel,
                sortOption = sortOption,
                onAlbumClick = onOpenAlbum,
                onPlayAlbum = { onPlayAlbum(it, false) },
                onShuffleAlbum = onShuffleAlbum,
                onEditAlbum = onEditAlbum,
                onChangeAlbumCover = onChangeAlbumCover,
                onIdentifyAlbum = onIdentifyAlbum
            )
        }

        activeFilter == LibraryBrowseFilter.ARTISTS -> {
            LibraryArtistsTab(
                viewModel = viewModel,
                sortOption = sortOption,
                onArtistClick = onArtistClick,
                onPlayArtist = onPlayArtist,
                onShuffleArtist = onShuffleArtist
            )
        }

        activeFilter == LibraryBrowseFilter.GENRES -> {
            LibraryGenresTab(
                viewModel = viewModel,
                sortOption = sortOption,
                onGenreClick = onGenreClick,
                onPlayGenre = onPlayGenre,
                onShuffleGenre = onShuffleGenre
            )
        }
    }
}

@Composable
private fun NestedAlbumDisplayName(
    viewModel: MusicPlayerViewModel,
    albumKey: String
): String {
    val albums by viewModel.libraryProjection.albums.collectAsState()
    return albums.firstOrNull {
        albumNamesMatch(it.name, albumKey) || albumNamesMatch(it.displayName, albumKey)
    }?.displayName ?: albumKey
}

private fun songsForCurrentLibrarySelection(
    viewModel: MusicPlayerViewModel,
    songList: LibraryListModel,
    selectedAlbumName: String?,
    selectedArtistName: String?,
    selectedGenreName: String?,
    activeFilter: LibraryBrowseFilter,
    songsViewMode: LibraryViewMode
): List<Song> {
    val songs = viewModel.libraryProjection.songs.value
    return when {
        selectedAlbumName != null || selectedArtistName != null || selectedGenreName != null ->
            libraryNestedSongs(viewModel, songs, selectedAlbumName, selectedArtistName, selectedGenreName)
        activeFilter == LibraryBrowseFilter.RECENT -> viewModel.libraryProjection.recentSongs.value
        activeFilter == LibraryBrowseFilter.SONGS -> songList.songsVisual
        else -> viewModel.songsForBrowseProjection(
            filter = activeFilter,
            songs = songs,
            viewMode = songsViewMode,
            albums = viewModel.libraryProjection.albums.value,
            artists = viewModel.libraryProjection.artists.value,
            genres = viewModel.libraryProjection.genres.value
        )
    }
}

private fun libraryNestedSongs(
    viewModel: MusicPlayerViewModel,
    songs: List<Song>,
    selectedAlbumName: String?,
    selectedArtistName: String?,
    selectedGenreName: String?
): List<Song> = when {
    selectedAlbumName != null -> viewModel.songsForAlbum(songs, selectedAlbumName)
    selectedArtistName != null -> viewModel.songsForArtist(songs, selectedArtistName)
    selectedGenreName != null -> viewModel.songsForGenre(songs, selectedGenreName)
    else -> emptyList()
}

/** Nested album/artist/genre detail: build list items + play in view order. */
@Composable
private fun NestedLibraryBrowse(
    selectedAlbumName: String?,
    selectedArtistName: String?,
    selectedGenreName: String?,
    viewMode: LibraryViewMode,
    viewModel: MusicPlayerViewModel,
    currentSongIdFlow: StateFlow<Long?>,
    isSelectionMode: Boolean,
    selectedSongIds: Set<Long>,
    collapsedAlbumNames: Set<String>,
    sortOption: SortOption,
    sortDirection: SortDirection,
    actions: LibrarySongListActions,
    onToggleSelect: (Song) -> Unit
) {
    val songs by viewModel.libraryProjection.songs.collectAsState()
    val browseSongs = remember(songs, selectedAlbumName, selectedArtistName, selectedGenreName) {
        libraryNestedSongs(viewModel, songs, selectedAlbumName, selectedArtistName, selectedGenreName)
    }
    // Keyed on albums so an album rename refreshes the group headers, which read the override name.
    val albums by viewModel.libraryProjection.albums.collectAsState()
    val list by produceState(
        initialValue = LibraryListModel.EMPTY,
        browseSongs,
        viewMode,
        albums,
        sortOption,
        sortDirection
    ) {
        value = withContext(Dispatchers.Default) {
            viewModel.buildLibraryListModel(browseSongs, viewMode, sortOption, sortDirection)
        }
    }
    val playQueue = remember(list, viewMode, browseSongs) {
        if (viewMode == LibraryViewMode.ALBUM_GROUPS) {
            list.songsVisual
        } else {
            browseSongs
        }
    }
    val onSongClick = remember(isSelectionMode, playQueue, onToggleSelect) {
        { song: Song, index: Int ->
            if (isSelectionMode) onToggleSelect(song)
            else viewModel.playCollection(playQueue, index)
        }
    }
    LibrarySongListHost(
        list = list,
        currentSongId = null,
        currentSongIdFlow = currentSongIdFlow,
        isSelectionMode = isSelectionMode,
        selectedSongIds = selectedSongIds,
        collapsedAlbumNames = collapsedAlbumNames,
        sortOption = sortOption,
        actions = actions,
        onSongClick = onSongClick
    )
}

@Composable
private fun LibraryAlbumsTab(
    viewModel: MusicPlayerViewModel,
    sortOption: SortOption,
    onAlbumClick: (Album) -> Unit,
    onPlayAlbum: (Album) -> Unit,
    onShuffleAlbum: (Album) -> Unit,
    onEditAlbum: (Album) -> Unit,
    onChangeAlbumCover: (Album) -> Unit,
    onIdentifyAlbum: (Album) -> Unit
) {
    val albums by viewModel.libraryProjection.albums.collectAsState()
    LibraryAlbumBrowseList(
        albums = albums,
        sortOption = sortOption,
        onAlbumClick = onAlbumClick,
        onPlayAlbum = onPlayAlbum,
        onShuffleAlbum = onShuffleAlbum,
        onEditAlbum = onEditAlbum,
        onChangeAlbumCover = onChangeAlbumCover,
        onIdentifyAlbum = onIdentifyAlbum
    )
}

@Composable
private fun LibraryRecentTab(
    viewModel: MusicPlayerViewModel,
    currentSongIdFlow: StateFlow<Long?>,
    isSelectionMode: Boolean,
    selectedSongIds: Set<Long>,
    sortOption: SortOption,
    actions: LibrarySongListActions,
    searchQuery: String,
    onToggleSelect: (Song) -> Unit
) {
    val recentSongs by viewModel.libraryProjection.recentSongs.collectAsState()
    val recentList by viewModel.libraryProjection.recentList.collectAsState()
    val recentEmptyFromSearch = searchQuery.isNotBlank()
    val onSongClick = remember(isSelectionMode, recentSongs, onToggleSelect) {
        { song: Song, index: Int ->
            if (isSelectionMode) onToggleSelect(song)
            else viewModel.playCollection(recentSongs, index)
        }
    }
    LibrarySongListHost(
        list = recentList,
        currentSongId = null,
        currentSongIdFlow = currentSongIdFlow,
        isSelectionMode = isSelectionMode,
        selectedSongIds = selectedSongIds,
        collapsedAlbumNames = emptySet(),
        sortOption = sortOption,
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
        actions = actions,
        onSongClick = onSongClick
    )
}

@Composable
private fun LibraryArtistsTab(
    viewModel: MusicPlayerViewModel,
    sortOption: SortOption,
    onArtistClick: (Artist) -> Unit,
    onPlayArtist: (Artist) -> Unit,
    onShuffleArtist: (Artist) -> Unit
) {
    val artists by viewModel.libraryProjection.artists.collectAsState()
    LibraryArtistList(
        artists = artists,
        sortOption = sortOption,
        onArtistClick = onArtistClick,
        onPlayArtist = onPlayArtist,
        onShuffleArtist = onShuffleArtist
    )
}

@Composable
private fun LibraryGenresTab(
    viewModel: MusicPlayerViewModel,
    sortOption: SortOption,
    onGenreClick: (GenreGroup) -> Unit,
    onPlayGenre: (GenreGroup) -> Unit,
    onShuffleGenre: (GenreGroup) -> Unit
) {
    val genres by viewModel.libraryProjection.genres.collectAsState()
    LibraryGenreList(
        genres = genres,
        sortOption = sortOption,
        onGenreClick = onGenreClick,
        onPlayGenre = onPlayGenre,
        onShuffleGenre = onShuffleGenre
    )
}

