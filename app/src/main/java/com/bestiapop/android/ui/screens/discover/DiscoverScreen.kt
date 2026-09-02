package com.bestiapop.android.ui.screens.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.*
import com.bestiapop.android.domain.usecase.DiscoverFeed
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.ScreenBackHeader
import com.bestiapop.android.ui.components.TrackMetaRow
import com.bestiapop.android.ui.state.CatalogCollectionKind
import com.bestiapop.android.ui.state.ItemLibraryStatus
import com.bestiapop.android.ui.theme.ListDensity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier
) {
    val catalogSearch by viewModel.catalogSearch.collectAsStateWithLifecycle()
    val catalogCollection by viewModel.catalogCollection.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val discoverFeed by viewModel.discoverFeed.collectAsStateWithLifecycle()
    val isLoadingFeed by viewModel.isLoadingDiscoverFeed.collectAsStateWithLifecycle()

    var searchInput by remember { mutableStateOf(catalogSearch.searchQueryDraft) }
    var isSearchFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (discoverFeed.recommendedTracks.isEmpty() && discoverFeed.recommendedAlbums.isEmpty()) {
            viewModel.refreshDiscoverFeed()
        }
    }

    val selectedCollectionTitle = catalogCollection.title
    val activeCandidates = catalogCollection.candidates
    val isLoadingCollection = catalogCollection.isLoading

    BackHandler(enabled = selectedCollectionTitle != null) {
        viewModel.clearSelectedCollection()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (selectedCollectionTitle != null) {
            val albumStatus = viewModel.getAlbumLibraryStatus(
                albumTitle = selectedCollectionTitle,
                artistName = activeCandidates.firstOrNull()?.artist.orEmpty()
            )

            // Collection Drill-down view (Album / Playlist / Genre)
            DiscoverCollectionDetailView(
                title = selectedCollectionTitle,
                kind = catalogCollection.kind ?: CatalogCollectionKind.ALBUM,
                coverUrl = catalogCollection.coverUrl,
                candidates = activeCandidates,
                isLoading = isLoadingCollection,
                onBack = { viewModel.clearSelectedCollection() },
                onPlayAll = {
                    viewModel.playCatalogCandidates(activeCandidates, startIndex = 0, startShuffled = false)
                },
                onShuffle = {
                    viewModel.playCatalogCandidates(activeCandidates, startIndex = 0, startShuffled = true)
                },
                onSaveAlbum = {
                    val album = CatalogAlbum(
                        id = catalogCollection.selectionKey.orEmpty(),
                        title = selectedCollectionTitle,
                        artist = activeCandidates.firstOrNull()?.artist.orEmpty(),
                        coverUrl = catalogCollection.coverUrl,
                        trackCount = activeCandidates.size
                    )
                    viewModel.saveAlbumToLibrary(album, activeCandidates)
                },
                onDownloadAll = {
                    viewModel.downloadSelectedCandidatesBatch()
                },
                onPlayCandidate = { candidate ->
                    viewModel.playCatalogCandidate(candidate)
                },
                onDownloadCandidate = { candidate ->
                    viewModel.downloadCatalogCandidate(candidate)
                },
                albumStatus = albumStatus,
                getTrackStatus = viewModel::getTrackLibraryStatus,
                onAlreadyInLibrary = { viewModel.toast(it) }
            )
        } else {
            val isSearchActive = searchInput.isNotBlank() || catalogSearch.hasActiveFilters || catalogSearch.isSearching

            Column(modifier = Modifier.fillMaxSize()) {
                DiscoverTopSearchBar(
                    query = searchInput,
                    onQueryChange = {
                        searchInput = it
                        viewModel.setCatalogSearchDraft(it)
                        if (it.isNotBlank()) {
                            viewModel.searchCatalog(query = it)
                        }
                    },
                    onSearch = { query ->
                        if (query.isNotBlank()) {
                            viewModel.searchCatalog(query = query)
                            isSearchFocused = false
                        }
                    },
                    onClear = {
                        searchInput = ""
                        viewModel.setCatalogSearchDraft("")
                        viewModel.searchCatalog(query = "")
                    },
                    showFilters = catalogSearch.showSearchFilters,
                    onToggleFilters = { viewModel.toggleCatalogSearchFilters() },
                    hasActiveFilters = catalogSearch.hasActiveFilters,
                    onRefreshFeed = { viewModel.refreshDiscoverFeed() },
                    isLoading = isLoadingFeed || catalogSearch.isSearching
                )

                // Category Chips (when searching or active)
                if (isSearchActive) {
                    DiscoverCategoryChipsRow(
                        selectedCategory = catalogSearch.category,
                        onSelectCategory = { category ->
                            viewModel.setCatalogCategory(category)
                            viewModel.searchCatalog(query = searchInput)
                        }
                    )
                }

                // Advanced Search Filters Panel
                AnimatedVisibility(visible = catalogSearch.showSearchFilters) {
                    DiscoverAdvancedFiltersPanel(
                        artist = catalogSearch.searchFilterArtist,
                        onArtistChange = viewModel::setCatalogSearchFilterArtist,
                        album = catalogSearch.searchFilterAlbum,
                        onAlbumChange = viewModel::setCatalogSearchFilterAlbum,
                        year = catalogSearch.searchFilterYear,
                        onYearChange = viewModel::setCatalogSearchFilterYear,
                        onApply = { viewModel.searchCatalog(query = searchInput) },
                        onClear = {
                            viewModel.clearCatalogSearchFilters()
                            viewModel.searchCatalog(query = searchInput)
                        }
                    )
                }

                // Recent Searches Suggestions
                if (searchInput.isBlank() && recentSearches.isNotEmpty() && isSearchFocused) {
                    DiscoverRecentSearchesView(
                        recentSearches = recentSearches,
                        onSelectQuery = { query ->
                            searchInput = query
                            viewModel.setCatalogSearchDraft(query)
                            viewModel.searchCatalog(query = query)
                            isSearchFocused = false
                        },
                        onRemoveQuery = { viewModel.removeRecentSearch(it) },
                        onClearAll = { viewModel.clearRecentSearches() }
                    )
                } else {
                    val catalogActions = remember(viewModel) {
                        DiscoverCatalogActions(
                            onPlayTrack = viewModel::playCatalogOrLocalTrack,
                            onDownloadTrack = viewModel::downloadOnlineTrack,
                            onSelectAlbum = viewModel::selectAlbumForInspection,
                            onSaveAlbum = { album -> viewModel.saveAlbumToLibrary(album) },
                            onSelectPlaylist = viewModel::selectPlaylistForInspection,
                            onSelectGenre = viewModel::selectGenreForInspection,
                            getTrackStatus = viewModel::getTrackLibraryStatus,
                            getAlbumStatus = viewModel::getAlbumLibraryStatus,
                            onAlreadyInLibrary = { viewModel.toast(it) }
                        )
                    }

                    if (isSearchActive) {
                        // Search Results View
                        DiscoverSearchResultsView(
                            category = catalogSearch.category,
                            isSearching = catalogSearch.isSearching,
                            tracks = catalogSearch.tracks,
                            albums = catalogSearch.albums,
                            playlists = catalogSearch.playlists,
                            genres = catalogSearch.genres,
                            actions = catalogActions
                        )
                    } else {
                        // Home Discover Feed View
                        DiscoverHomeFeedView(
                            feed = discoverFeed,
                            isLoading = isLoadingFeed,
                            onRefresh = { viewModel.refreshDiscoverFeed() },
                            actions = catalogActions
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoverTopSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    showFilters: Boolean,
    onToggleFilters: () -> Unit,
    hasActiveFilters: Boolean,
    onRefreshFeed: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            placeholder = {
                Text(
                    text = "Buscar canciones, álbumes, artistas…",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Buscar",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Limpiar"
                            )
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(26.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onToggleFilters,
            modifier = Modifier.background(
                color = if (hasActiveFilters) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape
            )
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Filtros",
                tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = onRefreshFeed
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refrescar recomendaciones",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DiscoverCategoryChipsRow(
    selectedCategory: CatalogCategory,
    onSelectCategory: (CatalogCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val categories = listOf(
            CatalogCategory.SONGS to "Canciones",
            CatalogCategory.ALBUMS to "Álbumes",
            CatalogCategory.PLAYLISTS to "Playlists",
            CatalogCategory.GENRES to "Géneros",
            CatalogCategory.CHARTS to "Top / Charts"
        )
        categories.forEach { (cat, label) ->
            FilterChip(
                selected = selectedCategory == cat,
                onClick = { onSelectCategory(cat) },
                label = { Text(label) },
                modifier = Modifier.height(ListDensity.filterChipHeight)
            )
        }
    }
}

@Composable
fun DiscoverRecentSearchesView(
    recentSearches: List<String>,
    onSelectQuery: (String) -> Unit,
    onRemoveQuery: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Búsquedas recientes",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            TextButton(onClick = onClearAll) {
                Text("Borrar todo")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            recentSearches.forEach { query ->
                InputChip(
                    selected = false,
                    onClick = { onSelectQuery(query) },
                    label = { Text(query) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Eliminar",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onRemoveQuery(query) }
                        )
                    }
                )
            }
        }
    }
}

/** Level 2: Track library action buttons (Downloaded, Saved Remote, Download). */
@Composable
fun TrackLibraryActionButtons(
    status: ItemLibraryStatus,
    onDownload: () -> Unit,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadyInLibrary: () -> Unit = { onNotifyStatus?.invoke(status.trackMessage) },
    modifier: Modifier = Modifier
) {
    when (status) {
        ItemLibraryStatus.DOWNLOADED -> {
            IconButton(onClick = onAlreadyInLibrary, modifier = modifier) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "En la biblioteca",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        ItemLibraryStatus.SAVED_REMOTE -> {
            Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAlreadyInLibrary) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdded,
                        contentDescription = "Guardada en biblioteca",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Descargar localmente",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        ItemLibraryStatus.NOT_IN_LIBRARY -> {
            IconButton(onClick = onDownload, modifier = modifier) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Descargar",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Level 2: Album save / saved icon button. */
@Composable
fun AlbumLibraryActionButton(
    status: ItemLibraryStatus,
    onSave: () -> Unit,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadySaved: () -> Unit = { onNotifyStatus?.invoke(status.albumMessage) },
    modifier: Modifier = Modifier
) {
    when (status) {
        ItemLibraryStatus.DOWNLOADED -> {
            IconButton(onClick = onAlreadySaved, modifier = modifier) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Álbum descargado en tu biblioteca",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        ItemLibraryStatus.SAVED_REMOTE -> {
            IconButton(onClick = onAlreadySaved, modifier = modifier) {
                Icon(
                    imageVector = Icons.Default.BookmarkAdded,
                    contentDescription = "Álbum guardado en tu biblioteca",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        ItemLibraryStatus.NOT_IN_LIBRARY -> {
            IconButton(onClick = onSave, modifier = modifier) {
                Icon(
                    imageVector = Icons.Default.BookmarkAdd,
                    contentDescription = "Guardar álbum",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Level 2: Album save / status button for collection headers. */
@Composable
fun AlbumLibraryHeaderButton(
    status: ItemLibraryStatus,
    onSaveAlbum: () -> Unit,
    onAlreadyInLibrary: (String) -> Unit
) {
    when (status) {
        ItemLibraryStatus.DOWNLOADED -> {
            FilledTonalButton(
                onClick = { onAlreadyInLibrary(status.albumMessage) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("En biblioteca")
            }
        }
        ItemLibraryStatus.SAVED_REMOTE -> {
            OutlinedButton(
                onClick = { onAlreadyInLibrary(status.albumMessage) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.BookmarkAdded, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Guardado")
            }
        }
        ItemLibraryStatus.NOT_IN_LIBRARY -> {
            OutlinedButton(
                onClick = onSaveAlbum,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Guardar")
            }
        }
    }
}

/**
 * Level 1: Low-level primitive media card (Artwork + text + customizable overlay badges/actions).
 * Provides continuous granularity for all catalog/discover cards.
 */
@Composable
fun DiscoverMediaCard(
    title: String,
    subtitle: String,
    artworkUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp? = null,
    imageSize: Dp? = null,
    aspectRatio: Float = 1f,
    topEndBadge: @Composable (BoxScope.() -> Unit)? = null,
    bottomEndAction: @Composable (BoxScope.() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .then(if (cardWidth != null) Modifier.width(cardWidth) else Modifier.fillMaxWidth())
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            val imageBoxModifier = if (imageSize != null) {
                Modifier.size(imageSize)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
            }
            Box(modifier = imageBoxModifier) {
                ArtworkThumbnail(
                    artworkUri = artworkUri,
                    size = imageSize,
                    cornerRadius = 12.dp,
                    modifier = Modifier.fillMaxSize()
                )

                topEndBadge?.invoke(this)
                bottomEndAction?.invoke(this)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Level 1: Reusable top-end badge container for media cards. */
@Composable
fun BoxScope.MediaCardBadge(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(26.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Level 1: Reusable bottom-end circular action button for media cards. */
@Composable
fun BoxScope.MediaCardAction(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimary,
    size: Dp = 36.dp
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp)
            .size(size)
            .background(containerColor, CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

/** Level 2: Track card with play overlay and library status badge. */
@Composable
fun DiscoverTrackCard(
    track: OnlineCatalogTrack,
    onPlay: () -> Unit,
    onDownload: () -> Unit = {},
    status: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadyInLibrary: () -> Unit = { onNotifyStatus?.invoke(status.trackMessage) },
    modifier: Modifier = Modifier
) {
    DiscoverMediaCard(
        title = track.title,
        subtitle = track.artist,
        artworkUri = track.artworkUri,
        cardWidth = 140.dp,
        imageSize = 124.dp,
        onClick = onPlay,
        modifier = modifier,
        topEndBadge = {
            when (status) {
                ItemLibraryStatus.DOWNLOADED -> MediaCardBadge {
                    IconButton(onClick = onAlreadyInLibrary, modifier = Modifier.size(26.dp)) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "En la biblioteca",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                ItemLibraryStatus.SAVED_REMOTE -> MediaCardBadge {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdded,
                        contentDescription = "Guardado (remoto)",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                ItemLibraryStatus.NOT_IN_LIBRARY -> Unit
            }
        },
        bottomEndAction = {
            MediaCardAction(
                onClick = onPlay,
                icon = Icons.Default.PlayArrow,
                contentDescription = "Reproducir"
            )
        }
    )
}

/** Level 2: Album card with save/status action button. */
@Composable
fun DiscoverAlbumCard(
    album: CatalogAlbum,
    onClick: () -> Unit,
    onSave: () -> Unit,
    status: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadySaved: () -> Unit = { onNotifyStatus?.invoke(status.albumMessage) },
    modifier: Modifier = Modifier
) {
    DiscoverMediaCard(
        title = album.title,
        subtitle = album.artist,
        artworkUri = album.coverUrl,
        cardWidth = 150.dp,
        imageSize = 134.dp,
        onClick = onClick,
        modifier = modifier,
        bottomEndAction = {
            AlbumLibraryActionButton(
                status = status,
                onSave = onSave,
                onAlreadySaved = onAlreadySaved,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
            )
        }
    )
}

/** Level 2: Reusable Discover track list item using [TrackMetaRow]. */
@Composable
fun DiscoverTrackListItem(
    track: OnlineCatalogTrack,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    status: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadyInLibrary: () -> Unit = { onNotifyStatus?.invoke(status.trackMessage) },
    modifier: Modifier = Modifier
) {
    TrackMetaRow(
        artworkUri = track.artworkUri,
        title = track.title,
        subtitle = track.artist,
        onClick = onPlay,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        trailing = {
            TrackLibraryActionButtons(
                status = status,
                onDownload = onDownload,
                onAlreadyInLibrary = onAlreadyInLibrary
            )
        }
    )
}

/**
 * Level 2: Shared stack frame bundling user interaction callbacks across Discover feed and search.
 */
data class DiscoverCatalogActions(
    val onPlayTrack: (OnlineCatalogTrack) -> Unit,
    val onDownloadTrack: (OnlineCatalogTrack) -> Unit,
    val onSelectAlbum: (CatalogAlbum) -> Unit,
    val onSaveAlbum: (CatalogAlbum) -> Unit,
    val onSelectPlaylist: (CatalogPlaylist) -> Unit = {},
    val onSelectGenre: (CatalogGenre) -> Unit = {},
    val getTrackStatus: (TrackMeta) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    val getAlbumStatus: (String, String) -> ItemLibraryStatus = { _, _ -> ItemLibraryStatus.NOT_IN_LIBRARY },
    val onAlreadyInLibrary: (String) -> Unit = {}
)

/** Level 2: Home feed view using bundled [DiscoverCatalogActions]. */
@Composable
fun DiscoverHomeFeedView(
    feed: DiscoverFeed,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    actions: DiscoverCatalogActions,
    modifier: Modifier = Modifier
) {
    DiscoverHomeFeedView(
        feed = feed,
        isLoading = isLoading,
        onRefresh = onRefresh,
        onPlayTrack = actions.onPlayTrack,
        onDownloadTrack = actions.onDownloadTrack,
        onSelectAlbum = actions.onSelectAlbum,
        onSaveAlbum = actions.onSaveAlbum,
        getTrackStatus = actions.getTrackStatus,
        getAlbumStatus = actions.getAlbumStatus,
        onAlreadyInLibrary = actions.onAlreadyInLibrary,
        modifier = modifier
    )
}

/** Level 1: Low-level primitive home feed view with individual callbacks. */
@Composable
fun DiscoverHomeFeedView(
    feed: DiscoverFeed,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onPlayTrack: (OnlineCatalogTrack) -> Unit,
    onDownloadTrack: (OnlineCatalogTrack) -> Unit,
    onSelectAlbum: (CatalogAlbum) -> Unit,
    onSaveAlbum: (CatalogAlbum) -> Unit,
    getTrackStatus: (TrackMeta) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    getAlbumStatus: (String, String) -> ItemLibraryStatus = { _, _ -> ItemLibraryStatus.NOT_IN_LIBRARY },
    onAlreadyInLibrary: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isFeedEmpty = feed.recommendedTracks.isEmpty() && feed.recommendedAlbums.isEmpty() && feed.chartTracks.isEmpty()
    if (isLoading && isFeedEmpty) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Section: Recommended Songs
        if (feed.recommendedTracks.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Canciones para ti",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Fuente: ${feed.recommendationSource}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(feed.recommendedTracks.take(12)) { track ->
                            val trackStatus = getTrackStatus(track.identity)
                            DiscoverTrackCard(
                                track = track,
                                onPlay = { onPlayTrack(track) },
                                onDownload = { onDownloadTrack(track) },
                                status = trackStatus,
                                onNotifyStatus = onAlreadyInLibrary
                            )
                        }
                    }
                }
            }
        }

        // Section: Recommended Albums
        if (feed.recommendedAlbums.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Álbumes recomendados",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(feed.recommendedAlbums) { album ->
                            val albumStatus = getAlbumStatus(album.title, album.artist)
                            DiscoverAlbumCard(
                                album = album,
                                onClick = { onSelectAlbum(album) },
                                onSave = { onSaveAlbum(album) },
                                status = albumStatus,
                                onNotifyStatus = onAlreadyInLibrary
                            )
                        }
                    }
                }
            }
        }

        // Section: Top Charts
        if (feed.chartTracks.isNotEmpty()) {
            item {
                Text(
                    text = "Tendencias y Charts",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            items(feed.chartTracks.take(8)) { track ->
                val trackStatus = getTrackStatus(track.identity)
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    DiscoverTrackListItem(
                        track = track,
                        onPlay = { onPlayTrack(track) },
                        onDownload = { onDownloadTrack(track) },
                        status = trackStatus,
                        onNotifyStatus = onAlreadyInLibrary
                    )
                }
            }
        }
    }
}

/** Level 2: Search results view using bundled [DiscoverCatalogActions]. */
@Composable
fun DiscoverSearchResultsView(
    category: CatalogCategory,
    isSearching: Boolean,
    tracks: List<OnlineCatalogTrack>,
    albums: List<CatalogAlbum>,
    playlists: List<CatalogPlaylist>,
    genres: List<CatalogGenre>,
    actions: DiscoverCatalogActions,
    modifier: Modifier = Modifier
) {
    DiscoverSearchResultsView(
        category = category,
        isSearching = isSearching,
        tracks = tracks,
        albums = albums,
        playlists = playlists,
        genres = genres,
        onPlayTrack = actions.onPlayTrack,
        onDownloadTrack = actions.onDownloadTrack,
        onSelectAlbum = actions.onSelectAlbum,
        onSaveAlbum = actions.onSaveAlbum,
        onSelectPlaylist = actions.onSelectPlaylist,
        onSelectGenre = actions.onSelectGenre,
        getTrackStatus = actions.getTrackStatus,
        getAlbumStatus = actions.getAlbumStatus,
        onAlreadyInLibrary = actions.onAlreadyInLibrary,
        modifier = modifier
    )
}

/** Level 1: Low-level primitive search results view with individual callbacks. */
@Composable
fun DiscoverSearchResultsView(
    category: CatalogCategory,
    isSearching: Boolean,
    tracks: List<OnlineCatalogTrack>,
    albums: List<CatalogAlbum>,
    playlists: List<CatalogPlaylist>,
    genres: List<CatalogGenre>,
    onPlayTrack: (OnlineCatalogTrack) -> Unit,
    onDownloadTrack: (OnlineCatalogTrack) -> Unit,
    onSelectAlbum: (CatalogAlbum) -> Unit,
    onSaveAlbum: (CatalogAlbum) -> Unit,
    onSelectPlaylist: (CatalogPlaylist) -> Unit = {},
    onSelectGenre: (CatalogGenre) -> Unit = {},
    getTrackStatus: (TrackMeta) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    getAlbumStatus: (String, String) -> ItemLibraryStatus = { _, _ -> ItemLibraryStatus.NOT_IN_LIBRARY },
    onAlreadyInLibrary: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (isSearching) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    when (category) {
        CatalogCategory.SONGS, CatalogCategory.CHARTS -> {
            if (tracks.isEmpty()) {
                EmptyListHint(text = "No se encontraron canciones", modifier = modifier.fillMaxWidth().padding(32.dp))
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tracks) { track ->
                        val trackStatus = getTrackStatus(track.identity)
                        DiscoverTrackListItem(
                            track = track,
                            onPlay = { onPlayTrack(track) },
                            onDownload = { onDownloadTrack(track) },
                            status = trackStatus,
                            onNotifyStatus = onAlreadyInLibrary
                        )
                    }
                }
            }
        }
        CatalogCategory.ALBUMS -> {
            if (albums.isEmpty()) {
                EmptyListHint(text = "No se encontraron álbumes", modifier = modifier.fillMaxWidth().padding(32.dp))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(albums) { album ->
                        val albumStatus = getAlbumStatus(album.title, album.artist)
                        DiscoverAlbumCard(
                            album = album,
                            onClick = { onSelectAlbum(album) },
                            onSave = { onSaveAlbum(album) },
                            status = albumStatus,
                            onNotifyStatus = onAlreadyInLibrary
                        )
                    }
                }
            }
        }
        CatalogCategory.PLAYLISTS -> {
            if (playlists.isEmpty()) {
                EmptyListHint(text = "No se encontraron playlists", modifier = modifier.fillMaxWidth().padding(32.dp))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(playlists) { playlist ->
                        DiscoverMediaCard(
                            title = playlist.title,
                            subtitle = "",
                            artworkUri = playlist.coverUrl,
                            onClick = { onSelectPlaylist(playlist) }
                        )
                    }
                }
            }
        }
        CatalogCategory.GENRES -> {
            if (genres.isEmpty()) {
                EmptyListHint(text = "No se encontraron géneros", modifier = modifier.fillMaxWidth().padding(32.dp))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(genres) { genre ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .clickable { onSelectGenre(genre) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = genre.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
}

@Composable
fun DiscoverCollectionDetailView(
    title: String,
    kind: CatalogCollectionKind,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onSaveAlbum: () -> Unit,
    onDownloadAll: () -> Unit,
    onPlayCandidate: (CatalogTrackCandidate) -> Unit,
    onDownloadCandidate: (CatalogTrackCandidate) -> Unit,
    albumStatus: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    getTrackStatus: (TrackMeta) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    onAlreadyInLibrary: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenBackHeader(
            title = title,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                // Header Info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkThumbnail(
                        artworkUri = coverUrl,
                        size = 100.dp,
                        cornerRadius = 16.dp
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "${candidates.size} canciones",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = onPlayAll,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play")
                            }

                            FilledTonalButton(
                                onClick = onShuffle,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            }

                            if (kind == CatalogCollectionKind.ALBUM) {
                                AlbumLibraryHeaderButton(
                                    status = albumStatus,
                                    onSaveAlbum = onSaveAlbum,
                                    onAlreadyInLibrary = onAlreadyInLibrary
                                )
                            }

                            if (albumStatus != ItemLibraryStatus.DOWNLOADED) {
                                IconButton(
                                    onClick = onDownloadAll,
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Descargar todo", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
            }

            items(candidates) { candidate ->
                val trackStatus = getTrackStatus(candidate.identity)
                TrackMetaRow(
                    artworkUri = null,
                    title = candidate.title,
                    subtitle = candidate.artist,
                    onClick = { onPlayCandidate(candidate) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    leading = {
                        val num = candidate.trackNumber.takeIf { it > 0 }
                        if (num != null) {
                            Text(
                                text = "$num",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp)
                            )
                        }
                    },
                    trailing = {
                        TrackLibraryActionButtons(
                            status = trackStatus,
                            onDownload = { onDownloadCandidate(candidate) },
                            onNotifyStatus = onAlreadyInLibrary
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun DiscoverAdvancedFiltersPanel(
    artist: String,
    onArtistChange: (String) -> Unit,
    album: String,
    onAlbumChange: (String) -> Unit,
    year: String,
    onYearChange: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Filtros de búsqueda avanzada",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = artist,
                    onValueChange = onArtistChange,
                    label = { Text("Artista") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = onAlbumChange,
                    label = { Text("Álbum") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = year,
                    onValueChange = onYearChange,
                    label = { Text("Año") },
                    modifier = Modifier.width(76.dp),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onClear) {
                    Text("Limpiar")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onApply) {
                    Text("Aplicar")
                }
            }
        }
    }
}
