package com.bestiapop.android.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.listenbrainz.MatchedLbTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.LbDiscoverListUiState
import com.bestiapop.android.ui.LbPlaylistDetailUiState
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.SongListItem

import androidx.compose.runtime.LaunchedEffect

@Composable
fun PlaylistsScreen(
    viewModel: MusicPlayerViewModel,
    activeSelectedPlaylistId: Long? = null,
    onSelectPlaylistDetail: (Long?) -> Unit = {},
    onAddSongsRequest: (Playlist) -> Unit = {}
) {
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val allSongs by viewModel.songsState.collectAsState()
    val lbSettings by viewModel.listenBrainzSettings.collectAsState()
    val lbDiscoverPlaylists by viewModel.lbDiscoverPlaylists.collectAsState()
    val lbDiscoverListState by viewModel.lbDiscoverListState.collectAsState()
    val selectedLbPlaylist by viewModel.selectedLbPlaylist.collectAsState()
    val lbPlaylistDetailState by viewModel.lbPlaylistDetailState.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedPlaylistId by remember { mutableStateOf<Long?>(activeSelectedPlaylistId) }
    var selectedLbPlaylistMbid by remember { mutableStateOf<String?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    val showDiscover = lbSettings.showDiscoverPlaylists

    LaunchedEffect(activeSelectedPlaylistId) {
        if (activeSelectedPlaylistId != null) {
            selectedPlaylistId = activeSelectedPlaylistId
            selectedLbPlaylistMbid = null
            viewModel.closeListenBrainzPlaylist()
        }
    }

    LaunchedEffect(showDiscover) {
        if (showDiscover) {
            viewModel.refreshListenBrainzDiscoverPlaylists()
        } else {
            selectedLbPlaylistMbid = null
            viewModel.closeListenBrainzPlaylist()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            floatingActionButton = {
                if (selectedPlaylistId == null && selectedLbPlaylistMbid == null) {
                    FloatingActionButton(
                        onClick = { showCreateDialog = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Crear Playlist")
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showDiscover) {
                        item(key = "para-ti-header") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Para Ti",
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.refreshListenBrainzDiscoverPlaylists() }) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Actualizar Para Ti",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Playlists Discover de ListenBrainz",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        when (val state = lbDiscoverListState) {
                            is LbDiscoverListUiState.Loading -> {
                                item(key = "para-ti-loading") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                            is LbDiscoverListUiState.Error -> {
                                item(key = "para-ti-error") {
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                            is LbDiscoverListUiState.Success, is LbDiscoverListUiState.Idle -> {
                                if (lbDiscoverPlaylists.isEmpty() && state is LbDiscoverListUiState.Success) {
                                    item(key = "para-ti-empty") {
                                        Text(
                                            text = "Aún no hay playlists Discover en tu cuenta.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    }
                                } else {
                                    items(lbDiscoverPlaylists, key = { "lb-${it.mbid}" }) { playlist ->
                                        LbPlaylistCardItem(
                                            playlist = playlist,
                                            onClick = {
                                                selectedPlaylistId = null
                                                onSelectPlaylistDetail(null)
                                                selectedLbPlaylistMbid = playlist.mbid
                                                viewModel.openListenBrainzPlaylist(playlist.mbid)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "mis-playlists-header") {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Mis Playlists",
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    } else {
                        item(key = "mis-playlists-header-only") {
                            Text(
                                text = "Mis Playlists",
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    if (playlists.isEmpty()) {
                        item(key = "local-empty") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp).padding(8.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No tenés playlists creadas",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = "Tocá el botón '+' para crear tu primera lista personalizada.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    } else {
                        items(playlists, key = { it.id }) { playlist ->
                            PlaylistCardItem(
                                playlist = playlist,
                                onClick = {
                                    selectedLbPlaylistMbid = null
                                    viewModel.closeListenBrainzPlaylist()
                                    selectedPlaylistId = playlist.id
                                    onSelectPlaylistDetail(playlist.id)
                                },
                                onDelete = { playlistToDelete = playlist }
                            )
                        }
                    }
                }
            }
        }

        // Selected local Playlist Detail View Screen
        if (selectedPlaylistId != null) {
            val playlistId = selectedPlaylistId!!
            val detailsState by viewModel.getPlaylistDetailsFlow(playlistId).collectAsState(initial = null)

            detailsState?.let { pair ->
                val playlist = pair.first
                val songsInPlaylist = pair.second
                PlaylistDetailScreen(
                    playlist = playlist,
                    songs = songsInPlaylist,
                    allSongs = allSongs,
                    onBack = {
                        selectedPlaylistId = null
                        onSelectPlaylistDetail(null)
                    },
                    viewModel = viewModel,
                    onAddSongsRequest = { onAddSongsRequest(it) },
                    onDeletePlaylist = { playlistToDelete = playlist }
                )
            }
        }

        // ListenBrainz Discover playlist detail
        if (selectedLbPlaylistMbid != null) {
            val currentSong by viewModel.currentSong.collectAsState()
            LbPlaylistDetailScreen(
                detailState = lbPlaylistDetailState,
                matchedPlaylist = selectedLbPlaylist,
                onBack = {
                    selectedLbPlaylistMbid = null
                    viewModel.closeListenBrainzPlaylist()
                },
                onPlay = { viewModel.playListenBrainzPlaylist() },
                onShuffle = { viewModel.shuffleListenBrainzPlaylist() },
                onPlaySong = { song, queue -> viewModel.playSong(song, queue) },
                currentSongUri = currentSong?.uriString,
                onPlayNext = { viewModel.playNextInQueue(it) },
                onAddToQueue = { viewModel.addToQueue(it) },
                onStartRadio = { viewModel.startRadio(seedSong = it) }
            )
        }

        // Create Playlist Dialog
        if (showCreateDialog) {
            PlaylistFormDialog(
                title = "Nueva Playlist",
                initialName = "",
                initialDescription = "",
                initialCoverUri = null,
                confirmText = "Crear",
                onDismiss = { showCreateDialog = false },
                onSave = { name, desc, coverUri ->
                    viewModel.createPlaylist(name, desc, coverUri) { newId ->
                        selectedLbPlaylistMbid = null
                        viewModel.closeListenBrainzPlaylist()
                        selectedPlaylistId = newId
                        onSelectPlaylistDetail(newId)
                    }
                    showCreateDialog = false
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
                            if (selectedPlaylistId == target.id) {
                                selectedPlaylistId = null
                            }
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
private fun LbPlaylistCardItem(
    playlist: LbPlaylistSummary,
    onClick: () -> Unit
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
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!playlist.description.isNullOrBlank()) {
                    Text(
                        text = playlist.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (playlist.trackCount > 0) {
                        "${playlist.trackCount} tracks · ListenBrainz"
                    } else {
                        "ListenBrainz"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun LbPlaylistDetailScreen(
    detailState: LbPlaylistDetailUiState,
    matchedPlaylist: MatchedLbPlaylist?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    currentSongUri: String?,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onStartRadio: (Song) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            val title = matchedPlaylist?.detail?.summary?.title ?: "Para Ti"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (detailState) {
                is LbPlaylistDetailUiState.Loading, is LbPlaylistDetailUiState.Idle -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is LbPlaylistDetailUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = detailState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                is LbPlaylistDetailUiState.Success -> {
                    val matched = matchedPlaylist
                    if (matched == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No se pudo cargar la playlist",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val description = matched.detail.summary.description
                        val matchedSongs = matched.matchedSongs

                        if (!description.isNullOrBlank()) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Text(
                            text = "${matched.matchedCount} de ${matched.totalCount} disponibles en tu biblioteca",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onPlay,
                                enabled = matchedSongs.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reproducir")
                            }

                            OutlinedButton(
                                onClick = onShuffle,
                                enabled = matchedSongs.isNotEmpty(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.Shuffle, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Aleatorio")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (matched.matches.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Esta playlist no tiene tracks",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(
                                    items = matched.matches,
                                    key = { match ->
                                        "${match.track.recordingMbid ?: match.track.title}|${match.track.artist}|${match.localSong?.id}"
                                    }
                                ) { match ->
                                    LbMatchedTrackRow(
                                        match = match,
                                        matchedSongs = matchedSongs,
                                        isCurrentPlaying = match.localSong?.uriString == currentSongUri,
                                        onPlaySong = onPlaySong,
                                        onPlayNext = onPlayNext,
                                        onAddToQueue = onAddToQueue,
                                        onStartRadio = onStartRadio
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LbMatchedTrackRow(
    match: MatchedLbTrack,
    matchedSongs: List<Song>,
    isCurrentPlaying: Boolean,
    onPlaySong: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onStartRadio: (Song) -> Unit
) {
    val local = match.localSong
    if (local != null) {
        SongListItem(
            song = local,
            isCurrentPlaying = isCurrentPlaying,
            onClick = { onPlaySong(local, matchedSongs) },
            onPlayNext = { onPlayNext(local) },
            onAddToQueue = { onAddToQueue(local) },
            onStartRadio = { onStartRadio(local) }
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.45f)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = match.track.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = match.track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "No en biblioteca",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlaylistCardItem(
    playlist: Playlist,
    onClick: () -> Unit,
    onDelete: () -> Unit
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
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!playlist.coverUri.isNullOrEmpty()) {
                    AsyncImage(
                        model = playlist.coverUri,
                        contentDescription = playlist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!playlist.description.isNullOrBlank()) {
                    Text(
                        text = playlist.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Playlist personalizada",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Eliminar playlist",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PlaylistDetailScreen(
    playlist: Playlist,
    songs: List<Song>,
    allSongs: List<Song>,
    onBack: () -> Unit,
    viewModel: MusicPlayerViewModel,
    onAddSongsRequest: (Playlist) -> Unit,
    onDeletePlaylist: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { showEditDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Editar playlist",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDeletePlaylist) {
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
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (!playlist.coverUri.isNullOrEmpty()) {
                        AsyncImage(
                            model = playlist.coverUri,
                            contentDescription = playlist.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

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
                        text = "${songs.size} canciones",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (songs.isNotEmpty()) {
                            viewModel.playSong(songs.first(), songs)
                        }
                    },
                    enabled = songs.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reproducir")
                }

                OutlinedButton(
                    onClick = {
                        if (songs.isNotEmpty()) {
                            val shuffled = songs.shuffled()
                            viewModel.playSong(shuffled.first(), shuffled)
                        }
                    },
                    enabled = songs.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Shuffle, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Aleatorio")
                }

                Button(
                    onClick = { onAddSongsRequest(playlist) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Añadir")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Songs List in Playlist
            if (songs.isEmpty()) {
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
                            Icon(imageVector = Icons.Default.PlaylistAdd, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Añadir canciones ahora")
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = songs,
                        key = { it.id },
                        contentType = { "song" }
                    ) { song ->
                        SongListItem(
                            song = song,
                            isCurrentPlaying = viewModel.currentSong.collectAsState().value?.uriString == song.uriString,
                            onClick = { viewModel.playSong(song, songs) },
                            onPlayNext = { viewModel.playNextInQueue(song) },
                            onAddToQueue = { viewModel.addToQueue(song) },
                            onStartRadio = { viewModel.startRadio(seedSong = song) },
                            onDelete = { viewModel.removeSongFromPlaylist(playlist.id, song.id) }
                        )
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
private fun PlaylistFormDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    initialCoverUri: String?,
    confirmText: String,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String?, coverUri: String?) -> Unit
) {
    var nameInput by remember { mutableStateOf(initialName) }
    var descInput by remember { mutableStateOf(initialDescription) }
    var coverUriInput by remember { mutableStateOf(initialCoverUri) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { coverUriInput = it.toString() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Cover Image Picker
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { imagePickerLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (!coverUriInput.isNullOrEmpty()) {
                        AsyncImage(
                            model = coverUriInput,
                            contentDescription = "Portada de Playlist",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Elegir Portada",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Portada local",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Text(if (coverUriInput.isNullOrEmpty()) "Seleccionar imagen" else "Cambiar imagen")
                    }

                    if (!coverUriInput.isNullOrEmpty()) {
                        IconButton(onClick = { coverUriInput = null }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Quitar portada",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Nombre de la playlist *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = descInput,
                    onValueChange = { descInput = it },
                    label = { Text("Descripción (opcional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (nameInput.isNotBlank()) {
                        onSave(nameInput.trim(), descInput.trim(), coverUriInput)
                    }
                },
                enabled = nameInput.isNotBlank()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
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
    val availableSongs = remember(allSongs, existingSongIds) {
        allSongs.filter { !existingSongIds.contains(it.id) }
    }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir canciones a '$playlistName'", fontWeight = FontWeight.Bold) },
        text = {
            if (availableSongs.isEmpty()) {
                Text(
                    text = "Todas las canciones de la biblioteca ya están en esta playlist.",
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
                                            text = "${song.artist} • ${song.album}",
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
                    val selectedSongs = availableSongs.filter { selectedIds.contains(it.id) }
                    onAddSongs(selectedSongs)
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
