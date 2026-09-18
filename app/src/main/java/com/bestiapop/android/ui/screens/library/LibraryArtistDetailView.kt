package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.firstArtworkUri
import com.bestiapop.android.data.model.isRemote
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtistDetailHero
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.ScreenBackHeader
import com.bestiapop.android.ui.components.findUiDownloadByTrack
import com.bestiapop.android.ui.components.isCurrentPlaying
import com.bestiapop.android.ui.screens.discover.DiscoverAlbumCard
import com.bestiapop.android.ui.screens.discover.DiscoverCarouselRow
import com.bestiapop.android.ui.screens.discover.DiscoverMediaCard
import com.bestiapop.android.ui.screens.discover.DiscoverTrackListItem
import com.bestiapop.android.ui.state.ItemLibraryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated Artist Detail Submenu in Library and Discover.
 * Loads local artist information and local albums instantly (0ms delay),
 * followed by an asynchronous background fetch of artist discography and top tracks
 * when offline mode is disabled. Also supports displaying preloaded catalog data.
 */
@Composable
fun LibraryArtistDetailView(
    artistName: String,
    viewModel: MusicPlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialCoverUrl: String? = null,
    initialAlbums: List<CatalogAlbum> = emptyList(),
    initialTopTracks: List<CatalogTrackCandidate> = emptyList(),
    isLoading: Boolean = false,
) {
    val allSongs by viewModel.libraryProjection.songs.collectAsStateWithLifecycle()
    val allAlbums by viewModel.libraryProjection.albums.collectAsStateWithLifecycle()
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val isOfflineMode by viewModel.isOfflineMode.collectAsStateWithLifecycle()

    val localSongs =
        remember(allSongs, artistName) {
            viewModel.songsForArtist(allSongs, artistName)
        }
    val localAlbums =
        remember(allAlbums, localSongs, artistName) {
            val albumTitles = localSongs.map { it.album.lowercase().trim() }.toSet()
            allAlbums.filter { album ->
                album.artist.equals(artistName, ignoreCase = true) ||
                    albumTitles.contains(album.name.lowercase().trim())
            }
        }

    var showArtistMenu by remember { mutableStateOf(false) }

    // Online catalog data
    var onlineCoverUrl by remember { mutableStateOf<String?>(initialCoverUrl) }
    var onlineAlbums by remember { mutableStateOf<List<CatalogAlbum>>(initialAlbums) }
    var onlineTopTracks by remember { mutableStateOf<List<CatalogTrackCandidate>>(initialTopTracks) }

    LaunchedEffect(artistName, isOfflineMode, initialAlbums, initialTopTracks) {
        if (initialAlbums.isNotEmpty() || initialTopTracks.isNotEmpty()) {
            onlineAlbums = initialAlbums
            onlineTopTracks = initialTopTracks
            onlineCoverUrl = initialCoverUrl ?: initialAlbums.firstOrNull()?.coverUrl
            return@LaunchedEffect
        }
        if (!isOfflineMode && artistName.isNotBlank()) {
            try {
                val deezerHit =
                    withContext(Dispatchers.IO) {
                        MetadataFetcher.searchDeezerArtist(artistName)
                    }
                val albums =
                    withContext(Dispatchers.IO) {
                        MetadataFetcher.fetchArtistAlbums(artistName, deezerHit?.id)
                    }
                val topTracks =
                    withContext(Dispatchers.IO) {
                        MetadataFetcher.fetchArtistTopTracks(artistName, deezerHit?.id)
                    }
                onlineCoverUrl = deezerHit?.pictureUrl ?: albums.firstOrNull()?.coverUrl
                onlineAlbums = albums
                onlineTopTracks = topTracks.map { MetadataFetcher.toCatalogCandidate(it) }
            } catch (_: Exception) {
                // Ignore network errors gracefully
            }
        } else {
            onlineCoverUrl = null
            onlineAlbums = emptyList()
            onlineTopTracks = emptyList()
        }
    }

    val displayCoverUrl =
        onlineCoverUrl ?: localAlbums.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) }
            ?: localSongs.firstArtworkUri()

    val summaryText =
        buildString {
            if (localSongs.isNotEmpty()) append("${localSongs.size} en biblioteca • ")
            val totalAlbums = if (onlineAlbums.isNotEmpty()) onlineAlbums.size else localAlbums.size
            append("$totalAlbums álbumes")
        }

    val onStartRadio: () -> Unit = {
        val seed = localSongs.randomOrNull()
        if (seed != null) {
            viewModel.startRadio(seedSong = seed)
        } else {
            viewModel.startRadio()
        }
    }

    ArtistDetailContent(
        artistName = artistName,
        summary = summaryText,
        displayCoverUrl = displayCoverUrl,
        localAlbums = localAlbums,
        onlineAlbums = onlineAlbums,
        onlineTopTracks = onlineTopTracks,
        currentItem = currentItem,
        activeDownloads = activeDownloads,
        onBack = onBack,
        onPlayAll = {
            if (localSongs.isNotEmpty()) {
                viewModel.playArtist(artistName, startShuffled = false)
            } else if (onlineTopTracks.isNotEmpty()) {
                viewModel.playCatalogCandidates(onlineTopTracks, startIndex = 0, startShuffled = false)
            }
        },
        onShuffle = {
            if (localSongs.isNotEmpty()) {
                viewModel.playArtist(artistName, startShuffled = true)
            } else if (onlineTopTracks.isNotEmpty()) {
                viewModel.playCatalogCandidates(onlineTopTracks, startIndex = 0, startShuffled = true)
            }
        },
        onStartRadio = onStartRadio,
        onSelectLocalAlbum = { album ->
            viewModel.openLibraryAlbum(album.name, fromNestedParent = true)
        },
        onSelectOnlineAlbum = { album ->
            viewModel.selectAlbumForInspection(album)
            viewModel.openLibraryAlbum(album.title, fromNestedParent = true)
        },
        onSaveOnlineAlbum = { album ->
            viewModel.saveAlbumToLibrary(album)
        },
        onPlayTrack = { candidate ->
            viewModel.playCatalogCandidate(candidate)
        },
        onDownloadTrack = { candidate ->
            viewModel.downloadCatalogCandidate(candidate)
        },
        getAlbumStatus = { album -> viewModel.getAlbumLibraryStatus(album) },
        getTrackStatus = { candidate -> viewModel.getTrackLibraryStatus(candidate.identity) },
        modifier = modifier,
        isLoading = isLoading,
        headerTrailing = {
            Box {
                IconButton(onClick = { showArtistMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Opciones del artista",
                    )
                }
                DropdownMenu(
                    expanded = showArtistMenu,
                    onDismissRequest = { showArtistMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Reproducir siguiente") },
                        onClick = {
                            showArtistMenu = false
                            viewModel.playArtistNext(artistName)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir a la cola") },
                        onClick = {
                            showArtistMenu = false
                            viewModel.enqueueArtist(artistName)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Iniciar radio") },
                        onClick = {
                            showArtistMenu = false
                            onStartRadio()
                        },
                    )
                }
            }
        },
    )
}

/**
 * Level 1: Shared layout for Artist Detail views in Library and Discover (continuous granularity).
 */
@Composable
fun ArtistDetailContent(
    artistName: String,
    summary: String,
    displayCoverUrl: String?,
    localAlbums: List<Album>,
    onlineAlbums: List<CatalogAlbum>,
    onlineTopTracks: List<CatalogTrackCandidate>,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onStartRadio: () -> Unit,
    onSelectLocalAlbum: (Album) -> Unit,
    onSelectOnlineAlbum: (CatalogAlbum) -> Unit,
    onSaveOnlineAlbum: (CatalogAlbum) -> Unit,
    onPlayTrack: (CatalogTrackCandidate) -> Unit,
    onDownloadTrack: (CatalogTrackCandidate) -> Unit,
    getAlbumStatus: (CatalogAlbum) -> ItemLibraryStatus,
    getTrackStatus: (CatalogTrackCandidate) -> ItemLibraryStatus,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        ScreenBackHeader(
            title = artistName,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            trailing = { headerTrailing?.invoke(this) },
        )

        if (isLoading && localAlbums.isEmpty() && onlineAlbums.isEmpty() && onlineTopTracks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            // Artist Hero
            item(key = "artist-hero-header") {
                ArtistDetailHero(
                    artistName = artistName,
                    summary = summary,
                    coverUrl = displayCoverUrl,
                    playEnabled = localAlbums.isNotEmpty() || onlineTopTracks.isNotEmpty(),
                    shuffleEnabled = localAlbums.isNotEmpty() || onlineTopTracks.isNotEmpty(),
                    radioEnabled = true,
                    onPlay = onPlayAll,
                    onShuffle = onShuffle,
                    onStartRadio = onStartRadio,
                )
            }

            // Section 1: En tu biblioteca
            item(key = "local-section-header") {
                Text(
                    text = "En tu biblioteca",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (localAlbums.isEmpty()) {
                item(key = "local-empty-hint") {
                    EmptyListHint(
                        text = "No tienes álbumes guardados de este artista todavía",
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                item(key = "local-albums-carousel") {
                    DiscoverCarouselRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = localAlbums,
                            key = { "local-alb-${it.name}" },
                        ) { album ->
                            DiscoverMediaCard(
                                title = album.displayName,
                                subtitle = "${album.songCount} canciones",
                                artworkUri = album.artworkUri,
                                cardWidth = 150.dp,
                                imageSize = 134.dp,
                                onClick = { onSelectLocalAlbum(album) },
                                topEndBadge = {
                                    Box(
                                        modifier =
                                            Modifier
                                                .padding(6.dp)
                                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                                .padding(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Section 2: Álbumes del artista (Catálogo online)
            if (onlineAlbums.isNotEmpty()) {
                item(key = "online-albums-header") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Álbumes del artista (${onlineAlbums.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                item(key = "online-albums-carousel") {
                    DiscoverCarouselRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(
                            items = onlineAlbums,
                            key = { index, it -> "online-alb-${it.id.ifEmpty { it.title }}-$index" },
                        ) { _, album ->
                            DiscoverAlbumCard(
                                album = album,
                                onClick = { onSelectOnlineAlbum(album) },
                                onSave = { onSaveOnlineAlbum(album) },
                                status = getAlbumStatus(album),
                            )
                        }
                    }
                }
            }

            // Section 3: Canciones populares (Top tracks online)
            if (onlineTopTracks.isNotEmpty()) {
                item(key = "online-top-tracks-header") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Canciones populares",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                itemsIndexed(
                    items = onlineTopTracks,
                    key = { index, it -> "online-top-${it.trackNumber}-${it.identity.artist}-${it.identity.title}-$index" },
                ) { _, candidate ->
                    val activeDownload = activeDownloads.findUiDownloadByTrack(candidate.artist, candidate.title)
                    val isPlaying = isCurrentPlaying(currentItem, candidate.identity.artist, candidate.identity.title)
                    val trackStatus = getTrackStatus(candidate)
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                        DiscoverTrackListItem(
                            track = candidate,
                            onPlay = { onPlayTrack(candidate) },
                            onDownload = { onDownloadTrack(candidate) },
                            activeDownload = activeDownload,
                            status = trackStatus,
                            highlighted = isPlaying,
                            leading = {
                                val num = candidate.trackNumber.takeIf { it > 0 }
                                if (num != null) {
                                    Text(
                                        text = "$num",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(28.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
