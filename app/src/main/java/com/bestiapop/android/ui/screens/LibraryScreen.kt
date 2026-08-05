package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.ViewAgenda
// UnfoldLess / UnfoldMore: collapse/expand all album groups
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.LabeledPlayShuffleButtons
import com.bestiapop.android.ui.components.MultiSelectActionBar
import com.bestiapop.android.ui.components.PlaylistAdditionActionBar
import com.bestiapop.android.ui.components.rememberSongQueueActions
import com.bestiapop.android.ui.screens.library.ConfirmMergeAlbumsDialog
import com.bestiapop.android.ui.screens.library.EditAlbumMetadataDialog
import com.bestiapop.android.ui.screens.library.LibraryAlbumGrid
import com.bestiapop.android.ui.screens.library.LibraryArtistList
import com.bestiapop.android.ui.screens.library.LibrarySongListActions
import com.bestiapop.android.ui.screens.library.LibrarySongListHost
import com.bestiapop.android.ui.screens.library.SetAlbumArtworkDialog
import com.bestiapop.android.ui.screens.library.SongActionDialogsHost
import com.bestiapop.android.ui.state.LibraryViewMode

@Composable
fun LibraryScreen(
    viewModel: MusicPlayerViewModel,
    targetPlaylistForAddition: Playlist? = null,
    onCompletePlaylistAddition: () -> Unit = {},
    onCancelPlaylistAddition: () -> Unit = {},
    onSelectFolderClick: () -> Unit,
    onSongSelect: (Song) -> Unit,
    onOpenDownloads: () -> Unit = {}
) {
    val songs by viewModel.songsState.collectAsState()
    val albums by viewModel.albumsState.collectAsState()
    val artists by viewModel.artistsState.collectAsState()
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val currentSong by viewModel.currentSong.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOption by viewModel.sortOption.collectAsState()
    val pendingAlbumMerge by viewModel.pendingAlbumMerge.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val isPlaylistAdditionMode = targetPlaylistForAddition != null

    LaunchedEffect(targetPlaylistForAddition) {
        if (targetPlaylistForAddition != null) {
            selectedTabIndex = 0
        }
    }

    var showAlbumHeaders by remember { mutableStateOf(true) }
    var selectedAlbumName by remember { mutableStateOf<String?>(null) }
    var selectedArtistName by remember { mutableStateOf<String?>(null) }
    var collapsedAlbumNames by remember { mutableStateOf(setOf<String>()) }

    // Multi-selection state
    var selectedSongIds by remember { mutableStateOf(setOf<Long>()) }
    val isMultiSelectMode = selectedSongIds.isNotEmpty()

    // Add Music dialog state
    var showAddMusicDialog by remember { mutableStateOf(false) }

    // Active Dialogs state
    var editingSong by remember { mutableStateOf<Song?>(null) }
    var albumForCoverChange by remember { mutableStateOf<Album?>(null) }
    var albumForEdit by remember { mutableStateOf<Album?>(null) }
    var songForPlaylistAddition by remember { mutableStateOf<Song?>(null) }
    var songsForDeletion by remember { mutableStateOf<List<Song>?>(null) }

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

    val currentSongId = currentSong?.id

    // Selection handlers (stable identities; read latest state via property delegates)
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

    val selectAllSongs = remember {
        { selectedSongIds = songs.map { it.id }.toSet() }
    }

    val clearSelection = remember {
        { selectedSongIds = emptySet() }
    }

    val hasNestedBack = isMultiSelectMode ||
        isPlaylistAdditionMode ||
        selectedAlbumName != null ||
        selectedArtistName != null ||
        searchQuery.isNotEmpty()

    BackHandler(enabled = hasNestedBack) {
        when {
            isMultiSelectMode -> clearSelection()
            isPlaylistAdditionMode -> onCancelPlaylistAddition()
            selectedAlbumName != null || selectedArtistName != null -> {
                selectedAlbumName = null
                selectedArtistName = null
            }
            searchQuery.isNotEmpty() -> viewModel.setSearchQuery("")
        }
    }

    val songActions = rememberSongQueueActions(viewModel)
    val onPlayNext = songActions.onPlayNext
    val onAddToQueue = songActions.onAddToQueue
    val onStartRadio = songActions.onStartRadio
    val onAddToPlaylist = remember<(Song) -> Unit> { { songForPlaylistAddition = it } }
    val onEditMetadata = remember<(Song) -> Unit> { { editingSong = it } }
    val onDeleteSong = remember<(Song) -> Unit> { { songsForDeletion = listOf(it) } }
    val onPlayAlbum = remember<(String, List<Song>) -> Unit> {
        { _, albumSongs -> viewModel.playCollection(albumSongs) }
    }
    val onShuffleAlbum = remember<(String, List<Song>) -> Unit> {
        { _, albumSongs -> viewModel.shuffleCollection(albumSongs) }
    }
    val songListActions = remember(
        onPlayNext, onAddToQueue, onStartRadio, onAddToPlaylist, onEditMetadata, onDeleteSong,
        onPlayAlbum, onShuffleAlbum, toggleSelectSong, toggleSelectAlbum, onAlbumLongClick,
        toggleCollapseAlbum, onEditAlbumByKey, onChangeAlbumCoverByKey
    ) {
        LibrarySongListActions(
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onStartRadio = onStartRadio,
            onAddToPlaylist = onAddToPlaylist,
            onEditMetadata = onEditMetadata,
            onDeleteSong = onDeleteSong,
            onPlayAlbum = onPlayAlbum,
            onShuffleAlbum = onShuffleAlbum,
            onToggleSelect = toggleSelectSong,
            onToggleSelectAlbum = toggleSelectAlbum,
            onAlbumLongClick = onAlbumLongClick,
            onToggleCollapseAlbum = toggleCollapseAlbum,
            onEditAlbum = onEditAlbumByKey,
            onChangeAlbumCover = onChangeAlbumCoverByKey
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {



        // Top Search Bar & Header Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectedAlbumName != null || selectedArtistName != null) {
                IconButton(onClick = {
                    selectedAlbumName = null
                    selectedArtistName = null
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Buscar canción, artista, álbum, género...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Limpiar")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            if (selectedAlbumName != null) {
                IconButton(onClick = { onEditAlbumByKey(selectedAlbumName!!) }) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar álbum")
                }
            }

            // Sort Menu Trigger
            Box {
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(Icons.Default.Sort, contentDescription = "Ordenar")
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    SortOption.values().forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = when (option) {
                                        SortOption.TITLE -> "Título"
                                        SortOption.ARTIST -> "Artista"
                                        SortOption.ALBUM -> "Álbum"
                                        SortOption.GENRE -> "Género"
                                        SortOption.DATE_ADDED -> "Fecha de adición"
                                    },
                                    fontWeight = if (sortOption == option) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                viewModel.setSortOption(option)
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }

        }

        // Section Tabs (Canciones, Álbumes, Artistas)
        if (selectedAlbumName == null && selectedArtistName == null && !isPlaylistAdditionMode) {
            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Canciones (${songs.size})") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Álbumes (${albums.size})") }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text("Artistas (${artists.size})") }
                )
            }
        }

        // Main Quick Play/Shuffle Action Header
        if (!isMultiSelectMode && !isPlaylistAdditionMode && selectedAlbumName == null && selectedArtistName == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LabeledPlayShuffleButtons(
                    onPlay = { viewModel.playCollection(songs) },
                    onShuffle = { viewModel.shuffleCollection(songs) },
                    enabled = songs.isNotEmpty(),
                    playLabel = "Reproducir todo",
                    shuffleLabel = "Mezclar",
                    modifier = Modifier.weight(1f)
                )

                if (selectedTabIndex == 0) {
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
                    IconButton(onClick = { showAlbumHeaders = !showAlbumHeaders }) {
                        Icon(
                            imageVector = Icons.Default.ViewAgenda,
                            contentDescription = "Cambiar vista",
                            tint = if (showAlbumHeaders) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Multi-Selection Action Bar (if active)
        if (isMultiSelectMode && !isPlaylistAdditionMode) {
            val selectedSongs = songs.filter { selectedSongIds.contains(it.id) }
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
                    songForPlaylistAddition = selectedSongs.firstOrNull()
                },
                onDeleteSelected = {
                    songsForDeletion = selectedSongs
                },
                onSelectAll = selectAllSongs,
                onClearSelection = clearSelection
            )
        }

        // Playlist Addition Action Bar at the Bottom
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

        // Main Content Switcher based on Selected Navigation
        Box(modifier = Modifier.weight(1f)) {
            when {
                selectedAlbumName != null -> {
                    val albumSongs = remember(songs, selectedAlbumName) {
                        songs.filter { it.album.equals(selectedAlbumName, ignoreCase = true) }
                    }
                    val albumListItems = remember(albumSongs) {
                        viewModel.buildLibraryListItems(albumSongs, LibraryViewMode.FLAT)
                    }
                    LibrarySongListHost(
                        items = albumListItems,
                        currentSongId = currentSongId,
                        isSelectionMode = isMultiSelectMode,
                        selectedSongIds = selectedSongIds,
                        collapsedAlbumNames = collapsedAlbumNames,
                        sortOption = sortOption,
                        actions = songListActions,
                        onSongClick = { song, index ->
                            if (isMultiSelectMode) toggleSelectSong(song) else viewModel.playCollection(albumSongs, index)
                        }
                    )
                }

                selectedArtistName != null -> {
                    val artistSongs = remember(songs, selectedArtistName) {
                        songs.filter { it.artist.equals(selectedArtistName, ignoreCase = true) }
                    }
                    val artistListItems = remember(artistSongs) {
                        viewModel.buildLibraryListItems(artistSongs, LibraryViewMode.ALBUM_GROUPS)
                    }
                    LibrarySongListHost(
                        items = artistListItems,
                        currentSongId = currentSongId,
                        isSelectionMode = isMultiSelectMode,
                        selectedSongIds = selectedSongIds,
                        collapsedAlbumNames = collapsedAlbumNames,
                        sortOption = sortOption,
                        actions = songListActions,
                        onSongClick = { song, index ->
                            if (isMultiSelectMode) toggleSelectSong(song) else viewModel.playCollection(artistSongs, index)
                        }
                    )
                }

                selectedTabIndex == 0 -> {
                    val songsViewMode = if (showAlbumHeaders) {
                        LibraryViewMode.ALBUM_GROUPS
                    } else {
                        LibraryViewMode.FLAT
                    }
                    val songListItems = remember(songs, songsViewMode) {
                        viewModel.buildLibraryListItems(songs, songsViewMode)
                    }
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
                                viewModel.playCollection(songs, index)
                            }
                        }
                    )
                }

                selectedTabIndex == 1 -> {
                    LibraryAlbumGrid(
                        albums = albums,
                        sortOption = sortOption,
                        onAlbumClick = { selectedAlbumName = it.name },
                        onPlayAlbum = { album ->
                            val albumSongs = songs.filter { it.album.equals(album.name, ignoreCase = true) }
                            viewModel.playCollection(albumSongs)
                        },
                        onShuffleAlbum = { album ->
                            val albumSongs = songs.filter { it.album.equals(album.name, ignoreCase = true) }
                            viewModel.shuffleCollection(albumSongs)
                        },
                        onChangeAlbumCover = { albumForCoverChange = it },
                        onEditAlbum = { albumForEdit = it }
                    )
                }

                selectedTabIndex == 2 -> {
                    LibraryArtistList(
                        artists = artists,
                        sortOption = sortOption,
                        onArtistClick = { selectedArtistName = it.name },
                        onPlayArtist = { artist ->
                            val artistSongs = songs.filter { it.artist.equals(artist.name, ignoreCase = true) }
                            viewModel.playCollection(artistSongs)
                        },
                        onShuffleArtist = { artist ->
                            val artistSongs = songs.filter { it.artist.equals(artist.name, ignoreCase = true) }
                            viewModel.shuffleCollection(artistSongs)
                        }
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

    // Modal Dialog Invocations
    SongActionDialogsHost(
        editingSong = editingSong,
        songForPlaylistAddition = songForPlaylistAddition,
        songsForDeletion = songsForDeletion,
        playlists = playlists,
        viewModel = viewModel,
        onDismissEdit = { editingSong = null },
        onDismissPlaylist = { songForPlaylistAddition = null },
        onDismissDelete = { songsForDeletion = null },
        onAfterPlaylistAdd = { clearSelection() },
        onAfterDelete = { clearSelection() },
        playlistSongIds = { song ->
            if (isMultiSelectMode) selectedSongIds.toList() else listOf(song.id)
        }
    )

    albumForEdit?.let { album ->
        if (pendingAlbumMerge == null) {
            EditAlbumMetadataDialog(
                album = album,
                onDismiss = { albumForEdit = null },
                onSaveAlbumOnly = { displayName, artist, genre, year, artworkUri ->
                    viewModel.requestSaveAlbumMetadata(
                        source = album,
                        displayName = displayName,
                        artist = artist,
                        genre = genre,
                        year = year,
                        artworkUri = artworkUri,
                        propagateToSongs = false
                    )
                    // Keep dialog open until merge prompt or successful save settles;
                    // close when no merge is pending after a short beat via collecting.
                    albumForEdit = null
                },
                onSaveAlbumAndSongs = { displayName, artist, genre, year, artworkUri ->
                    viewModel.requestSaveAlbumMetadata(
                        source = album,
                        displayName = displayName,
                        artist = artist,
                        genre = genre,
                        year = year,
                        artworkUri = artworkUri,
                        propagateToSongs = true
                    )
                    albumForEdit = null
                }
            )
        }
    }

    pendingAlbumMerge?.let { pending ->
        ConfirmMergeAlbumsDialog(
            source = pending.source,
            target = pending.target,
            onDismiss = { viewModel.dismissPendingAlbumMerge() },
            onConfirm = {
                val sourceKey = pending.source.name
                val targetKey = pending.target.name
                viewModel.confirmPendingAlbumMerge()
                if (selectedAlbumName.equals(sourceKey, ignoreCase = true)) {
                    selectedAlbumName = targetKey
                }
            }
        )
    }

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
}
