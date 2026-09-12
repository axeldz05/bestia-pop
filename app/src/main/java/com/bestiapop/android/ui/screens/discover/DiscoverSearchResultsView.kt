package com.bestiapop.android.ui.screens.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogCategory
import com.bestiapop.android.data.model.CatalogGenre
import com.bestiapop.android.data.model.CatalogPlaylist
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.findUiDownloadByTrack

/**
 * Level 2: Shared stack frame bundling user interaction callbacks across Discover feed and search.
 */
@Immutable
data class DiscoverCatalogActions(
    val onPlayTrack: (OnlineCatalogTrack) -> Unit,
    val onDownloadTrack: (OnlineCatalogTrack) -> Unit,
    val onSelectAlbum: (CatalogAlbum) -> Unit,
    val onSaveAlbum: (CatalogAlbum) -> Unit,
    val onSelectPlaylist: (CatalogPlaylist) -> Unit = {},
    val onSelectGenre: (CatalogGenre) -> Unit = {},
    val onSearchMore: () -> Unit = {},
    val onSelectArtist: (String) -> Unit = {},
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true
)

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
        isLoadingMore = actions.isLoadingMore,
        canLoadMore = actions.canLoadMore,
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
        onSearchMore = actions.onSearchMore,
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
    isLoadingMore: Boolean = false,
    canLoadMore: Boolean = true,
    onSelectPlaylist: (CatalogPlaylist) -> Unit = {},
    onSelectGenre: (CatalogGenre) -> Unit = {},
    onSearchMore: () -> Unit = {},
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
                Column(
                    modifier = modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EmptyListHint(text = "No se encontraron canciones")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onSearchMore,
                        enabled = !isLoadingMore
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscando en YouTube…")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscar en YouTube / búsqueda profunda")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = tracks,
                        key = { "search-track-${it.id}" },
                        contentType = { "search-track-item" }
                    ) { track ->
                        val activeDownload = LocalDiscoverContext.current.activeDownloads.findUiDownloadByTrack(track.artist, track.title)
                        DiscoverTrackListItem(
                            track = track,
                            onPlay = { onPlayTrack(track) },
                            onDownload = { onDownloadTrack(track) },
                            activeDownload = activeDownload
                        )
                    }

                    if (canLoadMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = onSearchMore,
                                    enabled = !isLoadingMore
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Buscando más…")
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Buscar más resultados")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        CatalogCategory.ALBUMS -> {
            if (albums.isEmpty()) {
                Column(
                    modifier = modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EmptyListHint(text = "No se encontraron álbumes")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onSearchMore,
                        enabled = !isLoadingMore
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscando más…")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscar más álbumes")
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = albums,
                        key = { "search-album-${it.id.ifEmpty { "${it.artist}|${it.title}" }}" },
                        contentType = { "search-album-card" }
                    ) { album ->
                        DiscoverAlbumCard(
                            album = album,
                            onClick = { onSelectAlbum(album) },
                            onSave = { onSaveAlbum(album) }
                        )
                    }

                    if (canLoadMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = onSearchMore,
                                    enabled = !isLoadingMore
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Buscando más…")
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Buscar más álbumes")
                                    }
                                }
                            }
                        }
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
                    items(
                        items = playlists,
                        key = { "search-playlist-${it.id}" },
                        contentType = { "search-playlist-card" }
                    ) { playlist ->
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
                    items(
                        items = genres,
                        key = { "search-genre-${it.id}" },
                        contentType = { "search-genre-card" }
                    ) { genre ->
                        Card(
                            onClick = { onSelectGenre(genre) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
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
