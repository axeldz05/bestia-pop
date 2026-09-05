package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.state.PlaylistDetailNav
import com.bestiapop.android.ui.components.ArtworkHero
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.PlayShuffleIconPair
import com.bestiapop.android.ui.components.ScreenBackHeader
import com.bestiapop.android.ui.components.SongListItem
import com.bestiapop.android.ui.components.SongQueueActions
import com.bestiapop.android.ui.components.isCurrentPlaying
import com.bestiapop.android.ui.components.rememberSongQueueActions
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs
import androidx.compose.foundation.lazy.itemsIndexed
import com.bestiapop.android.ui.components.PlaylistFormDialog
import com.bestiapop.android.ui.components.PlaylistHeader
import com.bestiapop.android.ui.components.RemoteTrackPlaceholderRow
import androidx.compose.runtime.LaunchedEffect

@Composable
fun PlaylistsScreen(
    viewModel: MusicPlayerViewModel,
    searchQuery: String = "",
    listState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    onAddSongsRequest: (Playlist) -> Unit = {}
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val visiblePlaylists = remember(playlists, searchQuery) {
        if (searchQuery.isBlank()) playlists
        else playlists.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }
    val allSongs by viewModel.libraryProjection.songs.collectAsStateWithLifecycle()
    val lbSettings by viewModel.listenBrainzSettings.collectAsStateWithLifecycle()
    val lbDiscover by viewModel.lbDiscover.collectAsStateWithLifecycle()
    val lbPlaylistDetail by viewModel.lbPlaylistDetail.collectAsStateWithLifecycle()
    val cfRecommendationsState by viewModel.cfRecommendations.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val playlistDetail = navigation.playlistDetail
    val lbDiscoverPlaylists = lbDiscover.data
    val selectedLbPlaylist = lbPlaylistDetail.data
    val cfRecommendations = cfRecommendationsState.data

    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var playlistToEdit by remember { mutableStateOf<Playlist?>(null) }

    val songActions = rememberSongQueueActions(viewModel)
    val songDialogs = rememberSongActionDialogs(viewModel = viewModel, playlists = playlists)

    val selectedPlaylistId = (playlistDetail as? PlaylistDetailNav.Local)?.id

    val hasNestedBack = selectedPlaylistId != null
    BackHandler(enabled = hasNestedBack) {
        viewModel.closePlaylistDetail()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Same pattern as LibraryScreen: FAB overlays content. Scaffold FAB slot would add
        // bottom content padding (~72dp) on top of MainScreen's bottomChromePadding → gap bar.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                    item(key = "mis-playlists-header") {
                        Text(
                            text = "Mis Playlists",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (visiblePlaylists.isEmpty()) {
                        item(key = "local-empty") {
                            EmptyListHint(
                                text = if (searchQuery.isBlank()) "No tenés playlists creadas" else "No se encontraron playlists",
                                subtitle = if (searchQuery.isBlank()) "Creá tu primera lista personalizada." else "Probá con otro término de búsqueda.",
                                icon = Icons.AutoMirrored.Filled.QueueMusic,
                                iconSize = 64.dp,
                                actionLabel = if (searchQuery.isBlank()) "Crear playlist" else null,
                                onAction = { showCreateDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 32.dp)
                            )
                        }
                    } else {
                        items(visiblePlaylists, key = { it.id }) { playlist ->
                            PlaylistHeader(
                                playlist = playlist,
                                onPlayPlaylist = { viewModel.playPlaylist(playlist.id, startShuffled = false) },
                                onShufflePlaylist = { viewModel.playPlaylist(playlist.id, startShuffled = true) },
                                onOpenPlaylist = {
                                    viewModel.openLocalPlaylist(playlist.id)
                                },
                                onEditPlaylist = { playlistToEdit = playlist },
                                onDeletePlaylist = { playlistToDelete = playlist },
                                onPlayNext = { viewModel.playPlaylistNext(playlist.id) },
                                onAddToQueue = { viewModel.enqueuePlaylist(playlist.id) }
                            )
                        }
                    }
                }
            }

        if (selectedPlaylistId == null) {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Crear Playlist")
            }
        }

        // Selected local Playlist Detail View Screen
        if (selectedPlaylistId != null) {
            val playlistId = selectedPlaylistId!!
            val detailsState by viewModel.getPlaylistDetailsFlow(playlistId).collectAsStateWithLifecycle(initialValue = null)

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                detailsState?.let { pair ->
                    val playlist = pair.first
                    val songsInPlaylist = pair.second
                    val pendingTracks by viewModel.getPlaylistPendingTracksFlow(playlistId)
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    PlaylistDetailScreen(
                        playlist = playlist,
                        songs = songsInPlaylist,
                        pendingTracks = pendingTracks,
                        allSongs = allSongs,
                        onBack = { viewModel.closePlaylistDetail() },
                        viewModel = viewModel,
                        onAddSongsRequest = { onAddSongsRequest(it) },
                        onDeletePlaylist = { playlistToDelete = playlist },
                        onDownloadPending = { viewModel.downloadPlaylistPendingTracks(playlistId) },
                        onEditLyrics = songDialogs.onEditLyrics
                    )
                } ?: Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        // Create Playlist Dialog
        if (showCreateDialog) {
            PlaylistFormDialog(
                title = "Nueva Playlist",
                initialName = "",
                initialDescription = "",
                initialCoverUri = null,
                confirmText = "Crear",
                confirmAndOpenText = "Crear y entrar",
                onDismiss = { showCreateDialog = false },
                onSave = { name, desc, coverUri ->
                    viewModel.createPlaylist(name, desc, coverUri)
                    showCreateDialog = false
                },
                onSaveAndOpen = { name, desc, coverUri ->
                    viewModel.createPlaylist(name, desc, coverUri) { newId ->
                        viewModel.openLocalPlaylist(newId)
                    }
                    showCreateDialog = false
                }
            )
        }

        // Edit Playlist Dialog from List
        if (playlistToEdit != null) {
            val target = playlistToEdit!!
            PlaylistFormDialog(
                title = "Editar Playlist",
                initialName = target.name,
                initialDescription = target.description ?: "",
                initialCoverUri = target.coverUri,
                confirmText = "Guardar",
                onDismiss = { playlistToEdit = null },
                onSave = { newName, newDesc, newCoverUri ->
                    viewModel.updatePlaylist(target.id, newName, newDesc, newCoverUri)
                    playlistToEdit = null
                }
            )
        }

        // Delete Playlist Confirmation Dialog
        if (playlistToDelete != null) {
            val target = playlistToDelete!!
            AlertDialog(
                onDismissRequest = { playlistToDelete = null },
                title = { Text("Eliminar Playlist", fontWeight = FontWeight.Bold) },
                text = { Text("¿Estás seguro de que deseas eliminar '${target.name}'? Esta acción no se puede deshacer.") },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deletePlaylist(target.id)
                            playlistToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Eliminar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { playlistToDelete = null }) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}

@Composable
fun PlaylistSurfaceCard(
    title: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    lines: @Composable ColumnScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading()
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                lines()
            }
            trailing()
        }
    }
}

private data class DisplayPlaylistTrack(
    val entryId: String,
    val song: Song
)

@Composable
private fun PlaylistDetailScreen(
    playlist: Playlist,
    songs: List<Song>,
    pendingTracks: List<PlaylistPendingTrack>,
    allSongs: List<Song>,
    onBack: () -> Unit,
    viewModel: MusicPlayerViewModel,
    onAddSongsRequest: (Playlist) -> Unit,
    onDeletePlaylist: () -> Unit,
    onDownloadPending: () -> Unit,
    onEditLyrics: (Song) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var isReorderMode by remember { mutableStateOf(false) }
    var localSongs by remember {
        mutableStateOf(songs.mapIndexed { idx, s -> DisplayPlaylistTrack("${s.id}_$idx", s) })
    }

    LaunchedEffect(songs) {
        if (localSongs.map { it.song } != songs) {
            localSongs = songs.mapIndexed { idx, s -> DisplayPlaylistTrack("${s.id}_$idx", s) }
        }
    }

    BackHandler(enabled = isReorderMode) {
        isReorderMode = false
    }

    val onReorder: (Int, Int) -> Unit = { from, to ->
        if (from in localSongs.indices && to in localSongs.indices && from != to) {
            val updated = localSongs.toMutableList()
            val moved = updated.removeAt(from)
            updated.add(to, moved)
            localSongs = updated
            viewModel.reorderPlaylistSongs(playlist.id, updated.map { it.song.id })
        }
    }

    val totalCount = localSongs.size + pendingTracks.size
    val songActions = rememberSongQueueActions(viewModel)
    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val detailListState = rememberSaveable(playlist.id, saver = LazyListState.Saver) { LazyListState() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            ScreenBackHeader(
                title = playlist.name,
                onBack = {
                    if (isReorderMode) {
                        isReorderMode = false
                    } else {
                        onBack()
                    }
                }
            ) {
                if (localSongs.size > 1) {
                    IconButton(onClick = { isReorderMode = !isReorderMode }) {
                        Icon(
                            imageVector = if (isReorderMode) Icons.Default.Check else Icons.Default.SwapVert,
                            contentDescription = if (isReorderMode) "Listo" else "Mover canciones",
                            tint = if (isReorderMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                IconButton(onClick = { showEditDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Editar playlist",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onDeletePlaylist,
                    modifier = Modifier.testTag("playlist-detail-delete")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Eliminar playlist",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info Card
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkHero(
                    uri = playlist.coverUri,
                    contentDescription = playlist.name,
                    fallback = Icons.AutoMirrored.Filled.QueueMusic,
                    fallbackTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    cornerRadius = 14.dp,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(100.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!playlist.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = playlist.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (pendingTracks.isEmpty()) {
                            "${localSongs.size} canciones"
                        } else {
                            "${localSongs.size} descargadas · ${pendingTracks.size} pendientes"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PlayShuffleIconPair(
                    onPlay = {
                        if (localSongs.isNotEmpty()) viewModel.playCollection(localSongs.map { it.song })
                    },
                    onShuffle = {
                        if (localSongs.isNotEmpty()) viewModel.shuffleCollection(localSongs.map { it.song })
                    },
                    playDescription = "Reproducir",
                    shuffleDescription = "Aleatorio",
                    modifier = Modifier.weight(1f)
                )
                if (localSongs.size > 1) {
                    Button(
                        onClick = { isReorderMode = !isReorderMode },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isReorderMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (isReorderMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(
                            horizontal = 10.dp,
                            vertical = 8.dp
                        )
                    ) {
                        Icon(
                            imageVector = if (isReorderMode) Icons.Default.Check else Icons.Default.SwapVert,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isReorderMode) "Listo" else "Mover canciones", maxLines = 1, softWrap = false)
                    }
                }
                Button(
                    onClick = { onAddSongsRequest(playlist) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    )
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Añadir", maxLines = 1, softWrap = false)
                }
            }

            if (pendingTracks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDownloadPending,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Descargar ${pendingTracks.size} pendientes")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Songs List in Playlist
            if (totalCount == 0) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Esta playlist está vacía",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { onAddSongsRequest(playlist) }) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Añadir canciones ahora")
                        }
                    }
                }
            } else {
                LazyColumn(state = detailListState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(
                        items = localSongs,
                        key = { _, item -> item.entryId },
                        contentType = { _, _ -> "song" }
                    ) { index, item ->
                        val song = item.song
                        SongListItem(
                            song = song,
                            artworkUri = viewModel.resolveAlbumArtwork(song),
                            isCurrentPlaying = isCurrentPlaying(currentItem ?: currentSong?.toPlayable(), song),
                            isReorderMode = isReorderMode,
                            index = index,
                            reorderCount = localSongs.size,
                            onReorder = onReorder,
                            onClick = { viewModel.playSong(song, localSongs.map { it.song }) },
                            onPlayNext = { songActions.onPlayNext(song) },
                            onAddToQueue = { songActions.onAddToQueue(song) },
                            onStartRadio = { songActions.onStartRadio(song) },
                            onEditLyrics = { onEditLyrics(song) },
                            onDelete = { viewModel.removeSongFromPlaylist(playlist.id, song.id) }
                        )
                    }
                    items(
                        items = pendingTracks,
                        key = { "pending-${it.id}" },
                        contentType = { "pending" }
                    ) { pending ->
                        PlaylistPendingTrackRow(pending = pending)
                    }
                }
            }
        }

        // Edit Playlist Dialog
        if (showEditDialog) {
            PlaylistFormDialog(
                title = "Editar Playlist",
                initialName = playlist.name,
                initialDescription = playlist.description ?: "",
                initialCoverUri = playlist.coverUri,
                confirmText = "Guardar",
                onDismiss = { showEditDialog = false },
                onSave = { newName, newDesc, newCoverUri ->
                    viewModel.updatePlaylist(playlist.id, newName, newDesc, newCoverUri)
                    showEditDialog = false
                }
            )
        }
    }
}

@Composable
private fun PlaylistPendingTrackRow(pending: PlaylistPendingTrack) {
    RemoteTrackPlaceholderRow(
        title = pending.title,
        artist = pending.artist,
        badge = "Pendiente de descarga",
        leadingIcon = Icons.Default.Download,
        highlighted = false
    )
}

@Composable
private fun AddSongsToPlaylistDialog(
    playlistName: String,
    allSongs: List<Song>,
    existingSongIds: Set<Long>,
    onDismiss: () -> Unit,
    onAddSongs: (List<Song>) -> Unit
) {
    val availableSongs = allSongs
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir canciones a '$playlistName'", fontWeight = FontWeight.Bold) },
        text = {
            if (availableSongs.isEmpty()) {
                Text(
                    text = "No hay canciones disponibles en la biblioteca.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Seleccionadas: ${selectedIds.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyColumn(modifier = Modifier.height(280.dp)) {
                        items(availableSongs, key = { it.id }) { song ->
                            val isChecked = selectedIds.contains(song.id)
                            val inPlaylistLabel = if (existingSongIds.contains(song.id)) " • En playlist" else ""
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isChecked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable {
                                        selectedIds = if (isChecked) selectedIds - song.id else selectedIds + song.id
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            selectedIds = if (checked == true) selectedIds + song.id else selectedIds - song.id
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${song.artist} • ${song.album}$inPlaylistLabel",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
        },
        confirmButton = {
            Button(
                onClick = {
                    val toAdd = allSongs.filter { selectedIds.contains(it.id) }
                    onAddSongs(toAdd)
                },
                enabled = selectedIds.isNotEmpty()
            ) {
                Text("Añadir (${selectedIds.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
