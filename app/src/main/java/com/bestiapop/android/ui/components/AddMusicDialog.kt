package com.bestiapop.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Whatshot
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
import com.bestiapop.android.data.model.CatalogGenre

import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.isFailed
import com.bestiapop.android.data.model.isInFlight
import com.bestiapop.android.ui.MusicPlayerViewModel



@Composable
fun AddMusicDialog(
    viewModel: MusicPlayerViewModel,
    onSelectFolderClick: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDownloads: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var linkUrlInput by remember { mutableStateOf("") }
    var catalogSearchInput by remember { mutableStateOf("") }

    val catalogResults by viewModel.catalogSearchResults.collectAsState()
    val isSearchingCatalog by viewModel.isSearchingCatalog.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()

    val catalogCategory by viewModel.catalogCategory.collectAsState()
    val albumSearchResults by viewModel.albumSearchResults.collectAsState()
    val playlistSearchResults by viewModel.playlistSearchResults.collectAsState()
    val catalogGenres by viewModel.catalogGenres.collectAsState()
    val selectedCollectionTitle by viewModel.selectedCollectionTitle.collectAsState()
    val activeTrackCandidates by viewModel.activeTrackCandidates.collectAsState()
    val isLoadingCollection by viewModel.isLoadingCollection.collectAsState()

    val catalogPreviewKey by viewModel.catalogPreviewKey.collectAsState()
    val currentItem by viewModel.currentItem.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val resolvingRemote by viewModel.resolvingRemote.collectAsState()
    val playbackPositionMs by viewModel.playbackPositionMs.collectAsState()

    fun dismissDialog() {
        viewModel.clearSelectedCollection()
        viewModel.clearCatalogPreview()
        onDismiss()
    }

    Dialog(
        onDismissRequest = {
            if (selectedCollectionTitle != null) {
                viewModel.clearSelectedCollection()
            } else {
                dismissDialog()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler(enabled = selectedCollectionTitle != null) {
            viewModel.clearSelectedCollection()
        }

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

                    IconButton(onClick = { dismissDialog() }) {
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
                            linkDownloads = activeDownloads.filter {
                                it.source == ActiveDownloadSource.LINK &&
                                    (it.state.isInFlight || it.state.isFailed)
                            },
                            onDownloadClick = { viewModel.downloadFromUrl(linkUrlInput) },
                            onRetry = { id -> viewModel.retryActiveDownload(id) },
                            onOpenDownloads = onOpenDownloads
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
                            genreResults = catalogGenres,
                            selectedCollectionTitle = selectedCollectionTitle,
                            activeCandidates = activeTrackCandidates,
                            isLoadingCollection = isLoadingCollection,
                            catalogDownloads = activeDownloads.filter {
                                it.source == ActiveDownloadSource.CATALOG ||
                                    it.source == ActiveDownloadSource.BATCH
                            },
                            catalogPreviewKey = catalogPreviewKey,
                            previewItem = (currentItem as? PlayableItem.Remote)?.takeIf { catalogPreviewKey != null },
                            isPreviewPlaying = isPlaying && catalogPreviewKey != null,
                            isPreviewResolving = resolvingRemote && catalogPreviewKey != null,
                            previewPositionMs = playbackPositionMs,
                            previewKeyFor = { track -> viewModel.catalogPreviewKeyFor(track) },
                            onSearch = { viewModel.searchCatalog(catalogSearchInput) },
                            onAddTrack = { track -> viewModel.downloadOnlineTrack(track) },
                            onStreamTrack = { track -> viewModel.playOnlineCatalogTrackAsStream(track) },
                            onCycleSong = { index -> viewModel.cycleSongCatalogResult(index) },
                            onTogglePreviewPlayPause = { viewModel.togglePlayPause() },
                            onStopPreview = {
                                if (isPlaying) viewModel.togglePlayPause()
                                viewModel.clearCatalogPreview()
                            },
                            onSelectAlbum = { album -> viewModel.selectAlbumForInspection(album) },
                            onSelectPlaylist = { playlist -> viewModel.selectPlaylistForInspection(playlist) },
                            onSelectGenre = { genre -> viewModel.selectGenreForInspection(genre) },
                            onCycleCandidate = { index -> viewModel.cycleTrackCandidate(index) },
                            onStreamCandidate = { candidate ->
                                candidate.currentTrack?.let { viewModel.playOnlineCatalogTrackAsStream(it) }
                            },
                            onToggleSelection = { index -> viewModel.toggleTrackSelection(index) },
                            onRetryCandidate = { index -> viewModel.downloadSingleCandidate(index) },
                            onDownloadBatch = { viewModel.downloadSelectedCandidatesBatch() },
                            onClearCollection = { viewModel.clearSelectedCollection() },
                            onRetryDownload = { id -> viewModel.retryActiveDownload(id) },
                            onOpenDownloads = onOpenDownloads
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
    linkDownloads: List<ActiveDownload>,
    onDownloadClick: () -> Unit,
    onRetry: (String) -> Unit,
    onOpenDownloads: () -> Unit
) {
    val isDownloading = linkDownloads.any { it.state.isInFlight }
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
            enabled = urlInput.isNotBlank() && !isDownloading,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Descargar MP3 y Agregar", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        ActiveDownloadsSummaryBanner(
            downloads = linkDownloads,
            onRetry = onRetry,
            onOpenDownloads = onOpenDownloads
        )
    }
}

@Composable
private fun ActiveDownloadsSummaryBanner(
    downloads: List<ActiveDownload>,
    onRetry: (String) -> Unit,
    onOpenDownloads: () -> Unit
) {
    if (downloads.isEmpty()) return
    val downloading = downloads.filter { it.state.isInFlight }
    val failed = downloads.filter { it.state.isFailed }
    if (downloading.isEmpty() && failed.isEmpty()) return

    AnimatedVisibility(visible = true) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (downloading.isNotEmpty()) {
                val latest = downloading.first()
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
                            text = if (downloading.size == 1) {
                                latest.progressMessage
                                    ?: DownloadMessages.downloadingQuoted(latest.displayLabel)
                            } else {
                                DownloadMessages.downloadingCount(downloading.size)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            if (failed.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (failed.size == 1) {
                                failed.first().errorMessage
                                    ?: DownloadMessages.failedQuoted(failed.first().displayLabel)
                            } else {
                                DownloadMessages.downloadsFailed(failed.size)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onRetry(failed.first().id) }) {
                                Text("Reintentar", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = onOpenDownloads) {
                                Text("Ver descargas", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
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
    genreResults: List<CatalogGenre>,
    selectedCollectionTitle: String?,
    activeCandidates: List<com.bestiapop.android.data.model.CatalogTrackCandidate>,
    isLoadingCollection: Boolean,
    catalogDownloads: List<ActiveDownload>,
    catalogPreviewKey: String?,
    previewItem: PlayableItem?,
    isPreviewPlaying: Boolean,
    isPreviewResolving: Boolean,
    previewPositionMs: Long,
    previewKeyFor: (OnlineCatalogTrack) -> String,
    onSearch: () -> Unit,
    onAddTrack: (OnlineCatalogTrack) -> Unit,
    onStreamTrack: (OnlineCatalogTrack) -> Unit,
    onCycleSong: (Int) -> Unit,
    onTogglePreviewPlayPause: () -> Unit,
    onStopPreview: () -> Unit,
    onSelectAlbum: (com.bestiapop.android.data.model.CatalogAlbum) -> Unit,
    onSelectPlaylist: (com.bestiapop.android.data.model.CatalogPlaylist) -> Unit,
    onSelectGenre: (CatalogGenre) -> Unit,
    onCycleCandidate: (Int) -> Unit,
    onStreamCandidate: (com.bestiapop.android.data.model.CatalogTrackCandidate) -> Unit,
    onToggleSelection: (Int) -> Unit,
    onRetryCandidate: (Int) -> Unit,
    onDownloadBatch: () -> Unit,
    onClearCollection: () -> Unit,
    onRetryDownload: (String) -> Unit,
    onOpenDownloads: () -> Unit
) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (songResults.isEmpty() && albumResults.isEmpty() && playlistResults.isEmpty() &&
            genreResults.isEmpty()
        ) {
            onSearch()
        }
    }

    // If inspecting an album/playlist/genre, show the track candidate inspection view!
    if (selectedCollectionTitle != null) {
        CollectionTrackInspectionView(
            title = selectedCollectionTitle,
            isLoading = isLoadingCollection,
            candidates = activeCandidates,
            batchDownloads = catalogDownloads.filter { it.source == ActiveDownloadSource.BATCH },
            catalogPreviewKey = catalogPreviewKey,
            previewItem = previewItem,
            isPreviewPlaying = isPreviewPlaying,
            isPreviewResolving = isPreviewResolving,
            previewPositionMs = previewPositionMs,
            previewKeyFor = previewKeyFor,
            onBack = onClearCollection,
            onCycleCandidate = onCycleCandidate,
            onStreamCandidate = onStreamCandidate,
            onToggleSelection = onToggleSelection,
            onRetryCandidate = onRetryCandidate,
            onDownloadBatch = onDownloadBatch,
            onRetryDownload = onRetryDownload,
            onOpenDownloads = onOpenDownloads,
            onTogglePreviewPlayPause = onTogglePreviewPlayPause,
            onStopPreview = onStopPreview
        )
        return
    }

    val searchPlaceholder = when (category) {
        CatalogCategory.GENRES -> "Filtrar géneros..."
        CatalogCategory.CHARTS -> "Charts globales (Deezer)"
        else -> "Buscar en catálogo..."
    }
    val showSearchField = category != CatalogCategory.CHARTS

    Column(modifier = Modifier.fillMaxSize()) {

        // Search Input Bar (hidden for Charts — no query needed)
        if (showSearchField) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchInput,
                    onValueChange = onSearchInputChange,
                    placeholder = { Text(searchPlaceholder) },
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
                    modifier = Modifier.testTag("catalog-search-submit"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Buscar")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Category Selection Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CatalogCategoryChip(
                selected = category == CatalogCategory.SONGS,
                label = "Canciones",
                icon = Icons.Default.MusicNote,
                onClick = { onCategorySelect(CatalogCategory.SONGS) }
            )
            CatalogCategoryChip(
                selected = category == CatalogCategory.ALBUMS,
                label = "Álbumes",
                icon = Icons.Default.Album,
                onClick = { onCategorySelect(CatalogCategory.ALBUMS) }
            )
            CatalogCategoryChip(
                selected = category == CatalogCategory.PLAYLISTS,
                label = "Playlists",
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                onClick = { onCategorySelect(CatalogCategory.PLAYLISTS) }
            )
            CatalogCategoryChip(
                selected = category == CatalogCategory.GENRES,
                label = "Géneros",
                icon = Icons.Default.Category,
                onClick = { onCategorySelect(CatalogCategory.GENRES) }
            )
            CatalogCategoryChip(
                selected = category == CatalogCategory.CHARTS,
                label = "Charts",
                icon = Icons.Default.Whatshot,
                onClick = { onCategorySelect(CatalogCategory.CHARTS) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        ActiveDownloadsSummaryBanner(
            downloads = catalogDownloads.filter { it.source == ActiveDownloadSource.CATALOG },
            onRetry = onRetryDownload,
            onOpenDownloads = onOpenDownloads
        )

        // Search Results List based on active category
        Box(modifier = Modifier.weight(1f)) {
            if (isSearching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                when (category) {
                    CatalogCategory.SONGS, CatalogCategory.CHARTS -> {
                        if (songResults.isEmpty()) {
                            EmptyResultText(
                                if (category == CatalogCategory.CHARTS) {
                                    "No se pudieron cargar los charts"
                                } else {
                                    "No se encontraron canciones en el catálogo"
                                }
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(
                                    items = songResults,
                                    key = { index, track -> "${track.provider}:${track.id}:$index" }
                                ) { index, track ->
                                    val flags = previewFlags(
                                        catalogPreviewKey,
                                        previewKeyFor(track),
                                        isPreviewPlaying,
                                        isPreviewResolving
                                    )
                                    CatalogTrackItem(
                                        track = track,
                                        isPreviewing = flags.isThisPreview,
                                        isPlaying = flags.isPlaying,
                                        isResolving = flags.isResolving,
                                        progressMs = if (flags.isThisPreview) previewPositionMs else 0L,
                                        onStreamClick = { onStreamTrack(track) },
                                        onCycleClick = { onCycleSong(index) },
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
                    CatalogCategory.GENRES -> {
                        if (genreResults.isEmpty()) {
                            EmptyResultText("No se encontraron géneros")
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(genreResults, key = { genre -> genre.id }) { genre ->
                                    CatalogGenreItem(
                                        genre = genre,
                                        onClick = { onSelectGenre(genre) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val previewTrack = remember(catalogPreviewKey, songResults) {
            songResults.find { previewKeyFor(it) == catalogPreviewKey }
        }
        AnimatedVisibility(visible = catalogPreviewKey != null) {
            CatalogPreviewBar(
                title = previewItem?.title ?: previewTrack?.title ?: "Preview",
                artist = previewItem?.artist ?: previewTrack?.artist ?: "",
                artworkUri = previewItem?.artworkUri ?: previewTrack?.artworkUri,
                durationMs = previewItem?.durationMs?.takeIf { it > 0 }
                    ?: previewTrack?.durationMs
                    ?: 0L,
                positionMs = previewPositionMs,
                isPlaying = isPreviewPlaying,
                isResolving = isPreviewResolving,
                onPlayPause = onTogglePreviewPlayPause,
                onStop = onStopPreview
            )
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
private fun CatalogCategoryChip(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.Bold) },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun CollectionTrackInspectionView(
    title: String,
    isLoading: Boolean,
    candidates: List<com.bestiapop.android.data.model.CatalogTrackCandidate>,
    batchDownloads: List<ActiveDownload>,
    catalogPreviewKey: String?,
    previewItem: PlayableItem?,
    isPreviewPlaying: Boolean,
    isPreviewResolving: Boolean,
    previewPositionMs: Long,
    previewKeyFor: (OnlineCatalogTrack) -> String,
    onBack: () -> Unit,
    onCycleCandidate: (Int) -> Unit,
    onStreamCandidate: (com.bestiapop.android.data.model.CatalogTrackCandidate) -> Unit,
    onToggleSelection: (Int) -> Unit,
    onRetryCandidate: (Int) -> Unit,
    onDownloadBatch: () -> Unit,
    onRetryDownload: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onTogglePreviewPlayPause: () -> Unit,
    onStopPreview: () -> Unit
) {
    val selectedCount = candidates.count { it.isSelected && it.currentTrack != null }
    val isBatchDownloading = batchDownloads.any { it.state.isInFlight }

    Column(modifier = Modifier.fillMaxSize()) {
        // Back Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
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

        ActiveDownloadsSummaryBanner(
            downloads = batchDownloads,
            onRetry = onRetryDownload,
            onOpenDownloads = onOpenDownloads
        )

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
                itemsIndexed(
                    candidates,
                    key = { index, item -> "${item.title}:${item.artist}:$index" }
                ) { index, item ->
                    val currentYt = item.currentTrack
                    val flags = previewFlags(
                        catalogPreviewKey,
                        currentYt?.let(previewKeyFor),
                        isPreviewPlaying,
                        isPreviewResolving
                    )
                    CandidateTrackCard(
                        item = item,
                        trackedDownload = batchDownloads.findUiDownloadByTrack(
                            item.artist,
                            item.title
                        ),
                        isPreviewing = flags.isThisPreview,
                        isPlaying = flags.isPlaying,
                        isResolving = flags.isResolving,
                        progressMs = if (flags.isThisPreview) previewPositionMs else 0L,
                        onToggleSelect = { onToggleSelection(index) },
                        onCycleCandidate = { onCycleCandidate(index) },
                        onStreamClick = { onStreamCandidate(item) },
                        onRetryDownload = { onRetryCandidate(index) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onDownloadBatch,
                enabled = selectedCount > 0 && !isBatchDownloading,
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

            val previewCandidate = remember(catalogPreviewKey, candidates) {
                candidates.find { c ->
                    val t = c.currentTrack ?: return@find false
                    previewKeyFor(t) == catalogPreviewKey
                }
            }
            AnimatedVisibility(visible = catalogPreviewKey != null) {
                CatalogPreviewBar(
                    title = previewItem?.title ?: previewCandidate?.title ?: "Preview",
                    artist = previewItem?.artist ?: previewCandidate?.artist ?: "",
                    artworkUri = previewItem?.artworkUri
                        ?: previewCandidate?.artworkUri,
                    durationMs = previewItem?.durationMs?.takeIf { it > 0 }
                        ?: previewCandidate?.currentTrack?.durationMs
                        ?: 0L,
                    positionMs = previewPositionMs,
                    isPlaying = isPreviewPlaying,
                    isResolving = isPreviewResolving,
                    onPlayPause = onTogglePreviewPlayPause,
                    onStop = onStopPreview
                )
            }
        }
    }
}

@Composable
private fun CatalogPreviewableRow(
    title: String,
    subtitle: String,
    artworkUri: String?,
    isPreviewing: Boolean,
    progressFraction: Float,
    isResolving: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    subtitleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    leading: @Composable (RowScope.() -> Unit)? = null,
    extraBelowTitle: @Composable (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                isPreviewing -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            if (isPreviewing) {
                LinearProgressIndicator(
                    progress = { if (isResolving) 0f else progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
            }
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                leading?.invoke(this)
                ArtworkThumbnail(
                    artworkUri = artworkUri,
                    size = 50.dp,
                    cornerRadius = 10.dp,
                    contentDescription = title
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    TrackTextColumn(
                        title = title,
                        subtitle = subtitle,
                        titleWeight = FontWeight.Bold,
                        titleColor = if (isPreviewing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        subtitleColor = subtitleColor
                    )
                    extraBelowTitle?.invoke()
                }
                Spacer(modifier = Modifier.width(4.dp))
                trailing()
            }
        }
    }
}

@Composable
private fun CandidateTrackCard(
    item: com.bestiapop.android.data.model.CatalogTrackCandidate,
    trackedDownload: ActiveDownload?,
    isPreviewing: Boolean,
    isPlaying: Boolean,
    isResolving: Boolean,
    progressMs: Long,
    onToggleSelect: () -> Unit,
    onCycleCandidate: () -> Unit,
    onStreamClick: () -> Unit,
    onRetryDownload: () -> Unit
) {
    val currentYt = item.currentTrack
    val durationMs = currentYt?.durationMs ?: 0L
    val progressFraction = previewProgressFraction(progressMs, durationMs)
    val downloadState = trackedDownload?.state ?: CandidateDownloadState.IDLE
    val downloadPercent = trackedDownload?.progressPercent ?: 0
    val downloadError = trackedDownload?.errorMessage
    val statusSubtext = when {
        isResolving -> "${item.artist} • Resolviendo stream…"
        isPreviewing && isPlaying -> "${item.artist} • Reproduciendo preview"
        isPreviewing -> "${item.artist} • Preview en pausa"
        downloadState == CandidateDownloadState.IDLE ->
            "${item.artist} • YouTube: ${currentYt?.title ?: "No encontrado"}"
        else -> {
            val status = downloadStateStatusLabel(
                state = downloadState,
                successLabel = DownloadMessages.savedInLibrary,
                queuedLabel = DownloadMessages.queuedEllipsis,
                downloadingFallback = DownloadMessages.downloadingAudio,
                errorMessage = "Error"
            ) ?: "Error"
            "${item.artist} • $status"
        }
    }

    CatalogPreviewableRow(
        title = item.title,
        subtitle = statusSubtext,
        artworkUri = currentYt?.artworkUri ?: item.artworkUri,
        isPreviewing = isPreviewing,
        selected = item.isSelected,
        progressFraction = progressFraction,
        isResolving = isResolving,
        subtitleColor = if (downloadState.isFailed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        },
        leading = {
            Checkbox(
                checked = item.isSelected,
                onCheckedChange = { onToggleSelect() }
            )
            Spacer(modifier = Modifier.width(6.dp))
        },
        extraBelowTitle = {
            if (downloadState.isFailed &&
                !downloadError.isNullOrEmpty()
            ) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = downloadError,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    ) {
        if (downloadState == CandidateDownloadState.IDLE ||
            downloadState.isFailed
        ) {
            PreviewPlayPauseButton(
                isResolving = isResolving,
                isPlaying = isPlaying,
                onClick = onStreamClick,
                enabled = currentYt != null
            )
        }
        DownloadStateTrailing(
            state = downloadState,
            percent = downloadPercent,
            onRetry = onRetryDownload,
            onCycle = onCycleCandidate,
            successContent = { DownloadSuccessReadyLabel() },
            idleContent = {
                DownloadOutlinedActionButton(
                    label = "Buscar otro",
                    onClick = onCycleCandidate,
                    contentDescription = "Buscar otro",
                    horizontalPadding = 10
                )
            }
        )
    }
}


@Composable
private fun CatalogCollectionRow(
    coverUrl: String?,
    title: String,
    subtitle: String,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
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
            ArtworkThumbnail(
                artworkUri = coverUrl,
                size = 56.dp,
                cornerRadius = 10.dp,
                fallbackIcon = fallbackIcon,
                contentDescription = title
            )

            Spacer(modifier = Modifier.width(12.dp))

            TrackTextColumn(
                title = title,
                subtitle = subtitle,
                modifier = Modifier.weight(1f),
                titleWeight = FontWeight.Bold
            )

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
private fun CatalogAlbumItem(
    album: com.bestiapop.android.data.model.CatalogAlbum,
    onClick: () -> Unit
) {
    CatalogCollectionRow(
        coverUrl = album.coverUrl,
        title = album.title,
        subtitle = "${album.artist} • ${album.trackCount} canciones",
        fallbackIcon = Icons.Default.Album,
        onClick = onClick
    )
}

@Composable
private fun CatalogGenreItem(
    genre: CatalogGenre,
    onClick: () -> Unit
) {
    CatalogCollectionRow(
        coverUrl = genre.pictureUrl,
        title = genre.name,
        subtitle = "Género",
        fallbackIcon = Icons.Default.Category,
        onClick = onClick
    )
}

@Composable
private fun CatalogPlaylistItem(
    playlist: com.bestiapop.android.data.model.CatalogPlaylist,
    onClick: () -> Unit
) {
    CatalogCollectionRow(
        coverUrl = playlist.coverUrl,
        title = playlist.title,
        subtitle = "Por ${playlist.creator} • ${playlist.trackCount} canciones",
        fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
        onClick = onClick
    )
}

@Composable
private fun CatalogTrackItem(
    track: OnlineCatalogTrack,
    isPreviewing: Boolean,
    isPlaying: Boolean,
    isResolving: Boolean,
    progressMs: Long,
    onStreamClick: () -> Unit,
    onCycleClick: () -> Unit,
    onAddClick: () -> Unit
) {
    val progressFraction = previewProgressFraction(progressMs, track.durationMs)
    val subtitle = when {
        isResolving -> "Resolviendo stream…"
        isPreviewing && isPlaying -> "Reproduciendo preview"
        isPreviewing -> "Preview en pausa"
        else -> joinMeta(track.artist, track.album)
    }
    CatalogPreviewableRow(
        title = track.title,
        subtitle = subtitle,
        artworkUri = track.artworkUri,
        isPreviewing = isPreviewing,
        progressFraction = progressFraction,
        isResolving = isResolving,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        PreviewPlayPauseButton(
            isResolving = isResolving,
            isPlaying = isPlaying,
            onClick = onStreamClick
        )
        IconButton(onClick = onCycleClick) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Buscar otro",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
        Button(
            onClick = onAddClick,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 10.dp,
                vertical = 6.dp
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

@Composable
private fun CatalogPreviewBar(
    title: String,
    artist: String,
    artworkUri: String?,
    durationMs: Long,
    positionMs: Long,
    isPlaying: Boolean,
    isResolving: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit
) {
    val progressFraction = previewProgressFraction(positionMs, durationMs)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Column {
            LinearProgressIndicator(
                progress = { if (isResolving) 0f else progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkThumbnail(
                    artworkUri = artworkUri,
                    size = 44.dp,
                    cornerRadius = 8.dp,
                    contentDescription = title
                )

                Spacer(modifier = Modifier.width(10.dp))

                TrackTextColumn(
                    title = if (isResolving) "Resolviendo…" else title,
                    subtitle = joinMeta(
                        artist,
                        if (isResolving) "stream" else buildString {
                            append(formatDuration(positionMs))
                            if (durationMs > 0) {
                                append(" / ")
                                append(formatDuration(durationMs))
                            }
                        },
                        sep = " · "
                    ),
                    modifier = Modifier.weight(1f),
                    titleStyle = MaterialTheme.typography.titleSmall,
                    titleWeight = FontWeight.Bold
                )

                PreviewPlayPauseButton(
                    isResolving = isResolving,
                    isPlaying = isPlaying,
                    onClick = onPlayPause
                )

                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Detener preview",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

