package com.bestiapop.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import com.bestiapop.android.data.model.CatalogCategory

import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.bestiapop.android.data.model.DownloadStatus
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.ui.MusicPlayerViewModel



@Composable
fun AddMusicDialog(
    viewModel: MusicPlayerViewModel,
    onSelectFolderClick: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var linkUrlInput by remember { mutableStateOf("") }
    var catalogSearchInput by remember { mutableStateOf("") }

    val catalogResults by viewModel.catalogSearchResults.collectAsState()
    val isSearchingCatalog by viewModel.isSearchingCatalog.collectAsState()
    val downloadStatus by viewModel.downloadStatus.collectAsState()

    val catalogCategory by viewModel.catalogCategory.collectAsState()
    val albumSearchResults by viewModel.albumSearchResults.collectAsState()
    val playlistSearchResults by viewModel.playlistSearchResults.collectAsState()
    val selectedCollectionTitle by viewModel.selectedCollectionTitle.collectAsState()
    val activeTrackCandidates by viewModel.activeTrackCandidates.collectAsState()
    val isLoadingCollection by viewModel.isLoadingCollection.collectAsState()

    Dialog(
        onDismissRequest = {
            viewModel.resetDownloadStatus()
            viewModel.clearSelectedCollection()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Agregar Música",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Local, Enlace Web o Catálogo Online",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            viewModel.resetDownloadStatus()
                            viewModel.clearSelectedCollection()
                            onDismiss()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tabs Navigation
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Local", fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Por Enlace", fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.Link, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Catálogo", fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.Public, contentDescription = null) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab Content
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> LocalImportTab(onSelectFolderClick = onSelectFolderClick)
                        1 -> LinkDownloaderTab(
                            urlInput = linkUrlInput,
                            onUrlInputChange = { linkUrlInput = it },
                            downloadStatus = downloadStatus,
                            onDownloadClick = { viewModel.downloadFromUrl(linkUrlInput) },
                            onResetStatus = { viewModel.resetDownloadStatus() },
                            onPlayDownloaded = { song ->
                                viewModel.playSong(song)
                                onDismiss()
                            }
                        )
                        2 -> OnlineCatalogTab(
                            searchInput = catalogSearchInput,
                            onSearchInputChange = { catalogSearchInput = it },
                            category = catalogCategory,
                            onCategorySelect = { viewModel.setCatalogCategory(it) },
                            isSearching = isSearchingCatalog,
                            songResults = catalogResults,
                            albumResults = albumSearchResults,
                            playlistResults = playlistSearchResults,
                            selectedCollectionTitle = selectedCollectionTitle,
                            activeCandidates = activeTrackCandidates,
                            isLoadingCollection = isLoadingCollection,
                            downloadStatus = downloadStatus,
                            onSearch = { viewModel.searchCatalog(catalogSearchInput) },
                            onAddTrack = { track -> viewModel.downloadOnlineTrack(track) },
                            onSelectAlbum = { album -> viewModel.selectAlbumForInspection(album) },
                            onSelectPlaylist = { playlist -> viewModel.selectPlaylistForInspection(playlist) },
                            onCycleCandidate = { index -> viewModel.cycleTrackCandidate(index) },
                            onToggleSelection = { index -> viewModel.toggleTrackSelection(index) },
                            onDownloadBatch = { viewModel.downloadSelectedCandidatesBatch() },
                            onClearCollection = { viewModel.clearSelectedCollection() },
                            onPlayDownloaded = { song ->
                                viewModel.playSong(song)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalImportTab(onSelectFolderClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Importar Carpeta de Música",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Selecciona cualquier carpeta en tu dispositivo o tarjeta SD para escanear y agregar canciones MP3, FLAC, M4A o WAV a tu biblioteca.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onSelectFolderClick,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Seleccionar Carpeta", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LinkDownloaderTab(
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    downloadStatus: DownloadStatus,
    onDownloadClick: () -> Unit,
    onResetStatus: () -> Unit,
    onPlayDownloaded: (com.bestiapop.android.data.model.Song) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Descargar por Enlace Web",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Pega un enlace de YouTube (youtube.com o youtu.be) o ingresa un ID de video para descargarlo e incorporarlo a tu reproductor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = urlInput,
            onValueChange = onUrlInputChange,
            placeholder = { Text("https://youtube.com/watch?v=... o https://youtu.be/...") },

            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
            trailingIcon = {
                if (urlInput.isNotEmpty()) {
                    IconButton(onClick = { onUrlInputChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Limpiar")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onDownloadClick,
            enabled = urlInput.isNotBlank() && downloadStatus !is DownloadStatus.Downloading,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Descargar MP3 y Agregar", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Download Status Feedback UI
        when (downloadStatus) {
            is DownloadStatus.Downloading -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = downloadStatus.message,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            is DownloadStatus.Success -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = downloadStatus.message,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { onPlayDownloaded(downloadStatus.song) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reproducir Ahora", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            is DownloadStatus.Error -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = downloadStatus.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            DownloadStatus.Idle -> { /* Nothing */ }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineCatalogTab(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    category: CatalogCategory,
    onCategorySelect: (CatalogCategory) -> Unit,
    isSearching: Boolean,
    songResults: List<OnlineCatalogTrack>,
    albumResults: List<com.bestiapop.android.data.model.CatalogAlbum>,
    playlistResults: List<com.bestiapop.android.data.model.CatalogPlaylist>,
    selectedCollectionTitle: String?,
    activeCandidates: List<com.bestiapop.android.data.model.CatalogTrackCandidate>,
    isLoadingCollection: Boolean,
    downloadStatus: DownloadStatus,
    onSearch: () -> Unit,
    onAddTrack: (OnlineCatalogTrack) -> Unit,
    onSelectAlbum: (com.bestiapop.android.data.model.CatalogAlbum) -> Unit,
    onSelectPlaylist: (com.bestiapop.android.data.model.CatalogPlaylist) -> Unit,
    onCycleCandidate: (Int) -> Unit,
    onToggleSelection: (Int) -> Unit,
    onDownloadBatch: () -> Unit,
    onClearCollection: () -> Unit,
    onPlayDownloaded: (com.bestiapop.android.data.model.Song) -> Unit
) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (songResults.isEmpty() && albumResults.isEmpty() && playlistResults.isEmpty()) {
            onSearch()
        }
    }

    // If inspecting an album/playlist, show the track candidate inspection view!
    if (selectedCollectionTitle != null) {
        CollectionTrackInspectionView(
            title = selectedCollectionTitle,
            isLoading = isLoadingCollection,
            candidates = activeCandidates,
            downloadStatus = downloadStatus,
            onBack = onClearCollection,
            onCycleCandidate = onCycleCandidate,
            onToggleSelection = onToggleSelection,
            onDownloadBatch = onDownloadBatch,
            onPlayDownloaded = onPlayDownloaded
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // Search Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchInput,
                onValueChange = onSearchInputChange,
                placeholder = { Text("Buscar en catálogo...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onSearch,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Search, contentDescription = "Buscar")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Category Selection Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = category == CatalogCategory.SONGS,
                onClick = { onCategorySelect(CatalogCategory.SONGS) },
                label = { Text("Canciones", fontWeight = FontWeight.Bold) },
                leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(20.dp)
            )
            FilterChip(
                selected = category == CatalogCategory.ALBUMS,
                onClick = { onCategorySelect(CatalogCategory.ALBUMS) },
                label = { Text("Álbumes", fontWeight = FontWeight.Bold) },
                leadingIcon = { Icon(Icons.Default.Album, contentDescription = null, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(20.dp)
            )
            FilterChip(
                selected = category == CatalogCategory.PLAYLISTS,
                onClick = { onCategorySelect(CatalogCategory.PLAYLISTS) },
                label = { Text("Playlists", fontWeight = FontWeight.Bold) },
                leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Download Status Indicator
        AnimatedVisibility(visible = downloadStatus !is DownloadStatus.Idle) {
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                when (downloadStatus) {
                    is DownloadStatus.Downloading -> {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = downloadStatus.message,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    is DownloadStatus.Success -> {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = downloadStatus.message,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { onPlayDownloaded(downloadStatus.song) }) {
                                    Text("Reproducir", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    is DownloadStatus.Error -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = downloadStatus.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    DownloadStatus.Idle -> {}
                }
            }
        }

        // Search Results List based on active category
        if (isSearching) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            when (category) {
                CatalogCategory.SONGS -> {
                    if (songResults.isEmpty()) {
                        EmptyResultText("No se encontraron canciones en el catálogo")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(songResults, key = { track -> track.id }) { track ->
                                CatalogTrackItem(
                                    track = track,
                                    onAddClick = { onAddTrack(track) }
                                )
                            }
                        }
                    }
                }
                CatalogCategory.ALBUMS -> {
                    if (albumResults.isEmpty()) {
                        EmptyResultText("No se encontraron álbumes")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(albumResults, key = { album -> album.id }) { album ->
                                CatalogAlbumItem(
                                    album = album,
                                    onClick = { onSelectAlbum(album) }
                                )
                            }
                        }
                    }
                }
                CatalogCategory.PLAYLISTS -> {
                    if (playlistResults.isEmpty()) {
                        EmptyResultText("No se encontraron playlists")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(playlistResults, key = { playlist -> playlist.id }) { playlist ->
                                CatalogPlaylistItem(
                                    playlist = playlist,
                                    onClick = { onSelectPlaylist(playlist) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyResultText(msg: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = msg,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun CollectionTrackInspectionView(
    title: String,
    isLoading: Boolean,
    candidates: List<com.bestiapop.android.data.model.CatalogTrackCandidate>,
    downloadStatus: DownloadStatus,
    onBack: () -> Unit,
    onCycleCandidate: (Int) -> Unit,
    onToggleSelection: (Int) -> Unit,
    onDownloadBatch: () -> Unit,
    onPlayDownloaded: (com.bestiapop.android.data.model.Song) -> Unit
) {
    val selectedCount = candidates.count { it.isSelected && it.currentTrack != null }

    Column(modifier = Modifier.fillMaxSize()) {
        // Back Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${candidates.size} canciones encontradas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Obteniendo pistas y buscando equivalentes en YouTube...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(candidates) { index, item ->
                    CandidateTrackCard(
                        item = item,
                        onToggleSelect = { onToggleSelection(index) },
                        onCycleCandidate = { onCycleCandidate(index) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onDownloadBatch,
                enabled = selectedCount > 0 && downloadStatus !is DownloadStatus.Downloading,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Descargar Selección ($selectedCount canciones)",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CandidateTrackCard(
    item: com.bestiapop.android.data.model.CatalogTrackCandidate,
    onToggleSelect: () -> Unit,
    onCycleCandidate: () -> Unit
) {
    val currentYt = item.currentTrack

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (item.isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = item.isSelected,
                onCheckedChange = { onToggleSelect() }
            )

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                val thumb = currentYt?.artworkUrl ?: item.coverUrl
                if (!thumb.isNullOrEmpty()) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = item.trackTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.MusicNote, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.trackTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val statusSubtext = when (item.downloadState) {
                    com.bestiapop.android.data.model.CandidateDownloadState.IDLE -> "${item.artist} • YouTube: ${currentYt?.title ?: "No encontrado"}"
                    com.bestiapop.android.data.model.CandidateDownloadState.DOWNLOADING -> "${item.artist} • Descargando audio..."
                    com.bestiapop.android.data.model.CandidateDownloadState.SUCCESS -> "${item.artist} • ¡Guardado en biblioteca!"
                    com.bestiapop.android.data.model.CandidateDownloadState.ERROR -> "${item.artist} • Error"
                }
                Text(
                    text = statusSubtext,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.downloadState == com.bestiapop.android.data.model.CandidateDownloadState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.downloadState == com.bestiapop.android.data.model.CandidateDownloadState.ERROR && !item.errorMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.errorMessage,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            when (item.downloadState) {
                com.bestiapop.android.data.model.CandidateDownloadState.IDLE -> {
                    OutlinedButton(
                        onClick = onCycleCandidate,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Buscar otro", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Buscar otro", style = MaterialTheme.typography.labelSmall)
                    }
                }
                com.bestiapop.android.data.model.CandidateDownloadState.DOWNLOADING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${item.downloadProgressPercent}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                com.bestiapop.android.data.model.CandidateDownloadState.SUCCESS -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Descargado",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Listo",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                com.bestiapop.android.data.model.CandidateDownloadState.ERROR -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun CatalogAlbumItem(
    album: com.bestiapop.android.data.model.CatalogAlbum,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!album.coverUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = album.coverUrl,
                        contentDescription = album.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Album, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${album.artist} • ${album.trackCount} canciones",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onClick,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Ver Canciones", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CatalogPlaylistItem(
    playlist: com.bestiapop.android.data.model.CatalogPlaylist,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!playlist.coverUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = playlist.coverUrl,
                        contentDescription = playlist.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.QueueMusic, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Por ${playlist.creator} • ${playlist.trackCount} canciones",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onClick,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Ver Canciones", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CatalogTrackItem(
    track: OnlineCatalogTrack,
    onAddClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!track.artworkUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${track.artist} • ${track.album}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onAddClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Agregar",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Agregar", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

