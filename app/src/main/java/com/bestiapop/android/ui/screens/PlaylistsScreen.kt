package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.MatchedCfRecommendations
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.usecase.ImportListenBrainzPlaylistUseCase
import com.bestiapop.android.ui.CfRecommendationsUiState
import com.bestiapop.android.ui.LbDiscoverListUiState
import com.bestiapop.android.ui.LbPlaylistDetailUiState
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkPickerBlock
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.LabeledPlayShuffleButtons
import com.bestiapop.android.ui.components.MatchedTrackRow
import com.bestiapop.android.ui.components.RemoteTrackPlaceholderRow
import com.bestiapop.android.ui.components.SongListItem
import com.bestiapop.android.ui.components.SongQueueActions
import com.bestiapop.android.ui.components.isMatchedTrackPlaying
import com.bestiapop.android.ui.components.rememberImagePicker
import com.bestiapop.android.ui.components.rememberSongQueueActions

import androidx.compose.material.icons.filled.Recommend
import androidx.compose.runtime.LaunchedEffect
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun matchedStreamCountLabel(matched: Int, stream: Int): String =
    "$matched en biblioteca · $stream en stream"

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
    val cfRecommendations by viewModel.cfRecommendations.collectAsState()
    val cfListState by viewModel.cfListState.collectAsState()
    val cfDetailOpen by viewModel.cfDetailOpen.collectAsState()
    val cfDetailState by viewModel.cfDetailState.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedPlaylistId by remember { mutableStateOf<Long?>(activeSelectedPlaylistId) }
    var selectedLbPlaylistMbid by remember { mutableStateOf<String?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    val songActions = rememberSongQueueActions(viewModel)

    val showDiscover = lbSettings.showDiscoverPlaylists

    LaunchedEffect(activeSelectedPlaylistId) {
        if (activeSelectedPlaylistId != null) {
            selectedPlaylistId = activeSelectedPlaylistId
            selectedLbPlaylistMbid = null
            viewModel.closeListenBrainzPlaylist()
            viewModel.closeCfRecommendations()
        }
    }

    LaunchedEffect(showDiscover) {
        if (showDiscover) {
            viewModel.refreshListenBrainzDiscoverPlaylists()
        } else {
            selectedLbPlaylistMbid = null
            viewModel.closeListenBrainzPlaylist()
            viewModel.closeCfRecommendations()
        }
    }

    val hasNestedBack = cfDetailOpen || selectedLbPlaylistMbid != null || selectedPlaylistId != null
    BackHandler(enabled = hasNestedBack) {
        when {
            cfDetailOpen -> viewModel.closeCfRecommendations()
            selectedLbPlaylistMbid != null -> {
                selectedLbPlaylistMbid = null
                viewModel.closeListenBrainzPlaylist()
            }
            selectedPlaylistId != null -> {
                selectedPlaylistId = null
                onSelectPlaylistDetail(null)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Same pattern as LibraryScreen: FAB overlays content. Scaffold FAB slot would add
        // bottom content padding (~72dp) on top of MainScreen's bottomChromePadding → gap bar.
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                                                viewModel.closeCfRecommendations()
                                                selectedLbPlaylistMbid = playlist.mbid
                                                viewModel.openListenBrainzPlaylist(playlist.mbid)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "recomendados-header") {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recomendados",
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.refreshCfRecommendations() }) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Actualizar Recomendados",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Basado en tu historial ListenBrainz",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        when (val cfState = cfListState) {
                            is CfRecommendationsUiState.Loading -> {
                                item(key = "recomendados-loading") {
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
                            is CfRecommendationsUiState.Error -> {
                                item(key = "recomendados-error") {
                                    Text(
                                        text = cfState.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                            is CfRecommendationsUiState.Success, is CfRecommendationsUiState.Idle -> {
                                val matched = cfRecommendations
                                if (matched == null || matched.matches.isEmpty()) {
                                    if (cfState is CfRecommendationsUiState.Success) {
                                        item(key = "recomendados-empty") {
                                            Text(
                                                text = "Aún no hay recomendaciones CF para tu cuenta.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                modifier = Modifier.padding(vertical = 8.dp)
                                            )
                                        }
                                    }
                                } else {
                                    item(key = "recomendados-card") {
                                        CfRecommendationsCardItem(
                                            matched = matched,
                                            onClick = {
                                                selectedPlaylistId = null
                                                onSelectPlaylistDetail(null)
                                                selectedLbPlaylistMbid = null
                                                viewModel.closeListenBrainzPlaylist()
                                                viewModel.openCfRecommendations()
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
                                    viewModel.closeCfRecommendations()
                                    selectedPlaylistId = playlist.id
                                    onSelectPlaylistDetail(playlist.id)
                                },
                                onDelete = { playlistToDelete = playlist }
                            )
                        }
                    }
                }
            }

        if (selectedPlaylistId == null && selectedLbPlaylistMbid == null && !cfDetailOpen) {
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
            val detailsState by viewModel.getPlaylistDetailsFlow(playlistId).collectAsState(initial = null)

            detailsState?.let { pair ->
                val playlist = pair.first
                val songsInPlaylist = pair.second
                val pendingTracks by viewModel.getPlaylistPendingTracksFlow(playlistId)
                    .collectAsState(initial = emptyList())
                PlaylistDetailScreen(
                    playlist = playlist,
                    songs = songsInPlaylist,
                    pendingTracks = pendingTracks,
                    allSongs = allSongs,
                    onBack = {
                        selectedPlaylistId = null
                        onSelectPlaylistDetail(null)
                    },
                    viewModel = viewModel,
                    onAddSongsRequest = { onAddSongsRequest(it) },
                    onDeletePlaylist = { playlistToDelete = playlist },
                    onDownloadPending = { viewModel.downloadPlaylistPendingTracks(playlistId) }
                )
            }
        }

        // ListenBrainz Discover playlist detail
        if (selectedLbPlaylistMbid != null) {
            val currentItem by viewModel.currentItem.collectAsState()
            val activeDownloads by viewModel.activeDownloads.collectAsState()
            LbPlaylistDetailScreen(
                detailState = lbPlaylistDetailState,
                matchedPlaylist = selectedLbPlaylist,
                onBack = {
                    selectedLbPlaylistMbid = null
                    viewModel.closeListenBrainzPlaylist()
                },
                onPlay = { viewModel.playListenBrainzPlaylist() },
                onShuffle = { viewModel.shuffleListenBrainzPlaylist() },
                onPlayAt = { index -> viewModel.playListenBrainzPlaylistAt(index) },
                onSaveAsLocal = {
                    viewModel.saveListenBrainzPlaylistAsLocal { newId ->
                        selectedLbPlaylistMbid = null
                        viewModel.closeListenBrainzPlaylist()
                        selectedPlaylistId = newId
                        onSelectPlaylistDetail(newId)
                    }
                },
                onImportWithDownloads = {
                    viewModel.importListenBrainzPlaylistWithDownloads()
                },
                currentItem = currentItem,
                activeDownloads = activeDownloads,
                onDownloadRemote = { viewModel.downloadRemoteItem(it) },
                queueActions = songActions
            )
        }

        // CF Recommendations detail
        if (cfDetailOpen) {
            val currentItem by viewModel.currentItem.collectAsState()
            val activeDownloads by viewModel.activeDownloads.collectAsState()
            CfRecommendationsDetailScreen(
                detailState = cfDetailState,
                matched = cfRecommendations,
                onBack = { viewModel.closeCfRecommendations() },
                onPlay = { viewModel.playCfRecommendations() },
                onShuffle = { viewModel.shuffleCfRecommendations() },
                onPlayAt = { index -> viewModel.playCfAt(index) },
                currentItem = currentItem,
                activeDownloads = activeDownloads,
                onDownloadRemote = { viewModel.downloadRemoteItem(it) },
                queueActions = songActions
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
fun ScreenBackHeader(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {}
) {
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
        trailing()
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

private fun unmatchedRemote(localSong: Song?, playable: PlayableItem): PlayableItem.Remote? =
    if (localSong == null) playable as PlayableItem.Remote else null

private fun isRemoteDownloadBusy(
    artist: String,
    title: String,
    activeDownloads: List<ActiveDownload>
): Boolean {
    val key = ImportListenBrainzPlaylistUseCase.downloadIdFor(artist, title)
    if (key.isEmpty()) return false
    return activeDownloads.any { download ->
        download.id == key &&
            (download.state == CandidateDownloadState.QUEUED ||
                download.state == CandidateDownloadState.DOWNLOADING)
    }
}

@Composable
private fun MatchedPlaylistDetailScaffold(
    title: String,
    onBack: () -> Unit,
    loading: Boolean,
    errorMessage: String?,
    content: @Composable ColumnScope.() -> Unit
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
            ScreenBackHeader(title = title, onBack = onBack)
            Spacer(modifier = Modifier.height(12.dp))
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                errorMessage != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> content()
            }
        }
    }
}

@Composable
private fun CfRecommendationsCardItem(
    matched: MatchedCfRecommendations,
    onClick: () -> Unit
) {
    val lastUpdatedLabel = matched.payload.lastUpdatedEpochSec?.let { epochSec ->
        val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        " · actualizado ${formatter.format(Date(epochSec * 1000L))}"
    }.orEmpty()

    PlaylistSurfaceCard(
        title = "Recomendados para vos",
        onClick = onClick,
        leading = {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Recommend,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        lines = {
            Text(
                text = matchedStreamCountLabel(matched.matchedCount, matched.streamCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${matched.totalCount} tracks · CF$lastUpdatedLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun CfRecommendationsDetailScreen(
    detailState: CfRecommendationsUiState,
    matched: MatchedCfRecommendations?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayAt: (Int) -> Unit,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    onDownloadRemote: (PlayableItem.Remote) -> Unit,
    queueActions: SongQueueActions
) {
    MatchedPlaylistDetailScaffold(
        title = "Recomendados",
        onBack = onBack,
        loading = detailState is CfRecommendationsUiState.Loading ||
            detailState is CfRecommendationsUiState.Idle,
        errorMessage = (detailState as? CfRecommendationsUiState.Error)?.message
    ) {
        if (matched == null || matched.matches.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Aún no hay recomendaciones CF para tu cuenta.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Text(
                text = matchedStreamCountLabel(matched.matchedCount, matched.streamCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            LabeledPlayShuffleButtons(onPlay = onPlay, onShuffle = onShuffle)

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = matched.matches.withIndex().toList(),
                    key = { (index, match) ->
                        "${index}|${match.recordingMbid}|${match.title}|${match.artist}|${match.localSong?.id}"
                    }
                ) { (index, match) ->
                    MatchedTrackRow(
                        localSong = match.localSong,
                        title = match.title,
                        artist = match.artist,
                        remoteBadge = "Stream",
                        isCurrentPlaying = isMatchedTrackPlaying(
                            match.localSong,
                            match.artist,
                            match.title,
                            currentItem
                        ),
                        remote = unmatchedRemote(match.localSong, match.toPlayableItem()),
                        downloadBusy = isRemoteDownloadBusy(
                            match.artist,
                            match.title,
                            activeDownloads
                        ),
                        onPlayAt = { onPlayAt(index) },
                        onDownloadRemote = onDownloadRemote,
                        queueActions = queueActions
                    )
                }
            }
        }
    }
}

@Composable
private fun LbPlaylistCardItem(
    playlist: LbPlaylistSummary,
    onClick: () -> Unit
) {
    PlaylistSurfaceCard(
        title = playlist.title,
        onClick = onClick,
        leading = {
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
        },
        lines = {
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
    )
}

@Composable
private fun LbPlaylistDetailScreen(
    detailState: LbPlaylistDetailUiState,
    matchedPlaylist: MatchedLbPlaylist?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onSaveAsLocal: () -> Unit,
    onImportWithDownloads: () -> Unit,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    onDownloadRemote: (PlayableItem.Remote) -> Unit,
    queueActions: SongQueueActions
) {
    MatchedPlaylistDetailScaffold(
        title = matchedPlaylist?.detail?.summary?.title ?: "Para Ti",
        onBack = onBack,
        loading = detailState is LbPlaylistDetailUiState.Loading ||
            detailState is LbPlaylistDetailUiState.Idle,
        errorMessage = (detailState as? LbPlaylistDetailUiState.Error)?.message
    ) {
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
            return@MatchedPlaylistDetailScaffold
        }

        val description = matched.detail.summary.description
        val hasTracks = matched.matches.isNotEmpty()
        val hasMatched = matched.matchedCount > 0
        val hasUnmatched = matched.streamCount > 0

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
            text = matchedStreamCountLabel(matched.matchedCount, matched.streamCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(12.dp))
        LabeledPlayShuffleButtons(
            onPlay = onPlay,
            onShuffle = onShuffle,
            enabled = hasTracks
        )

        if (hasMatched || hasUnmatched) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSaveAsLocal,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.PlaylistAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Guardar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (hasUnmatched) {
                    OutlinedButton(
                        onClick = onImportWithDownloads,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Descargar faltantes",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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
                    items = matched.matches.withIndex().toList(),
                    key = { (index, match) ->
                        "${index}|${match.track.recordingMbid ?: match.track.title}|${match.track.artist}|${match.localSong?.id}"
                    }
                ) { (index, match) ->
                    MatchedTrackRow(
                        localSong = match.localSong,
                        title = match.track.title,
                        artist = match.track.artist,
                        remoteBadge = "No en biblioteca · stream",
                        isCurrentPlaying = isMatchedTrackPlaying(
                            match.localSong,
                            match.track.artist,
                            match.track.title,
                            currentItem
                        ),
                        remote = unmatchedRemote(match.localSong, match.toPlayableItem()),
                        downloadBusy = isRemoteDownloadBusy(
                            match.track.artist,
                            match.track.title,
                            activeDownloads
                        ),
                        onPlayAt = { onPlayAt(index) },
                        onDownloadRemote = onDownloadRemote,
                        queueActions = queueActions
                    )
                }
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
    PlaylistSurfaceCard(
        title = playlist.name,
        onClick = onClick,
        leading = {
            ArtworkThumbnail(
                artworkUri = playlist.coverUri,
                size = 60.dp,
                cornerRadius = 10.dp,
                fallbackIcon = Icons.Default.QueueMusic,
                contentDescription = playlist.name
            )
        },
        lines = {
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
        },
        trailing = {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Eliminar playlist",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

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
    onDownloadPending: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    val totalCount = songs.size + pendingTracks.size
    val songActions = rememberSongQueueActions(viewModel)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            ScreenBackHeader(title = playlist.name, onBack = onBack) {
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
                        text = if (pendingTracks.isEmpty()) {
                            "${songs.size} canciones"
                        } else {
                            "${songs.size} descargadas · ${pendingTracks.size} pendientes"
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LabeledPlayShuffleButtons(
                    onPlay = { viewModel.playCollection(songs) },
                    onShuffle = { viewModel.shuffleCollection(songs) },
                    enabled = songs.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                )

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
                        key = { "song-${it.id}" },
                        contentType = { "song" }
                    ) { song ->
                        SongListItem(
                            song = song,
                            isCurrentPlaying = viewModel.currentSong.collectAsState().value?.uriString == song.uriString,
                            onClick = { viewModel.playSong(song, songs) },
                            onPlayNext = { songActions.onPlayNext(song) },
                            onAddToQueue = { songActions.onAddToQueue(song) },
                            onStartRadio = { songActions.onStartRadio(song) },
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

    val imagePickerLauncher = rememberImagePicker { coverUriInput = it }

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
                ArtworkPickerBlock(
                    artworkUri = coverUriInput,
                    onPick = { imagePickerLauncher.launch("image/*") },
                    buttonText = if (coverUriInput.isNullOrEmpty()) {
                        "Seleccionar imagen"
                    } else {
                        "Cambiar imagen"
                    },
                    spacing = 12.dp,
                    preview = { uri ->
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { imagePickerLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (!uri.isNullOrEmpty()) {
                                AsyncImage(
                                    model = uri,
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
                    },
                    buttonLeading = {},
                    trailing = {
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
                )

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
