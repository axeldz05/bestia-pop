package com.bestiapop.android.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.filterNotMatchingAlbums
import com.bestiapop.android.domain.util.filterNotMatchingSongs
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.SongItemActions
import com.bestiapop.android.ui.components.SongListItem
import com.bestiapop.android.ui.components.findUiDownloadByTrack
import com.bestiapop.android.ui.screens.discover.DiscoverTrackListItem

@Composable
fun UnifiedSearchSection(
    searchQuery: String,
    localSongs: List<Song>,
    catalogTracks: List<OnlineCatalogTrack>,
    catalogAlbums: List<CatalogAlbum>,
    isSearchingOnline: Boolean,
    currentSongUri: String?,
    songItemActions: SongItemActions,
    onPlayLocalSong: (Song) -> Unit,
    onPlayCatalogTrack: (OnlineCatalogTrack) -> Unit,
    onDownloadCatalogTrack: (OnlineCatalogTrack) -> Unit,
    onSelectCatalogAlbum: (CatalogAlbum) -> Unit,
    onSearchMoreOnline: () -> Unit,
    activeDownloads: List<ActiveDownload>,
    canLoadMoreOnline: Boolean,
    isLoadingMoreOnline: Boolean,
    modifier: Modifier = Modifier,
    localAlbums: List<Album> = emptyList(),
    onSelectLocalAlbum: (Album) -> Unit = {},
) {
    val deduplicatedCatalogTracks =
        remember(catalogTracks, localSongs) {
            catalogTracks.filterNotMatchingSongs(localSongs)
        }

    val deduplicatedCatalogAlbums =
        remember(catalogAlbums, localAlbums) {
            catalogAlbums.filterNotMatchingAlbums(localAlbums)
        }

    var isLocalExpanded by rememberSaveable { mutableStateOf(true) }
    var isStreamingExpanded by rememberSaveable { mutableStateOf(true) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        // --- 1. LOCAL RESULTS FIRST ---
        val localCount = localSongs.size + localAlbums.size
        item {
            SearchSectionHeader(
                title = "En tu dispositivo",
                icon = Icons.Default.Smartphone,
                count = localCount,
                isExpanded = isLocalExpanded,
                onToggleExpand = { isLocalExpanded = !isLocalExpanded },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (isLocalExpanded) {
            if (localAlbums.isNotEmpty()) {
                item {
                    Text(
                        text = "Álbumes en tu dispositivo",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        items(localAlbums, key = { "local-album-${it.groupingKey.ifBlank { it.name }}" }) { album ->
                            SearchAlbumMiniCard(
                                album = album,
                                onClick = { onSelectLocalAlbum(album) },
                            )
                        }
                    }
                }
            }

            if (localSongs.isEmpty() && localAlbums.isEmpty()) {
                item {
                    Text(
                        text = "Sin resultados locales que coincidan con \"$searchQuery\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else if (localSongs.isNotEmpty()) {
                items(
                    items = localSongs.take(20),
                    key = { "local-search-${it.id}" },
                ) { song ->
                    SongListItem(
                        song = song,
                        actions = songItemActions,
                        isCurrentPlaying = currentSongUri == song.uriString,
                        onClick = { onPlayLocalSong(song) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(if (isLocalExpanded) 16.dp else 8.dp))
        }

        // --- 2. STREAMING SUGGESTIONS ---
        val streamingCount = deduplicatedCatalogTracks.size + deduplicatedCatalogAlbums.size
        item {
            SearchSectionHeader(
                title = "En streaming (Sugerencias)",
                icon = Icons.Default.Cloud,
                count = streamingCount,
                isExpanded = isStreamingExpanded,
                onToggleExpand = { isStreamingExpanded = !isStreamingExpanded },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (isStreamingExpanded) {
            if (isSearchingOnline && deduplicatedCatalogTracks.isEmpty() && deduplicatedCatalogAlbums.isEmpty()) {
                item {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = "Buscando sugerencias en catálogo online…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (deduplicatedCatalogTracks.isEmpty() && deduplicatedCatalogAlbums.isEmpty()) {
                item {
                    val message =
                        when {
                            isSearchingOnline -> {
                                "Buscando…"
                            }

                            catalogTracks.isNotEmpty() || catalogAlbums.isNotEmpty() -> {
                                "Las sugerencias encontradas ya están en tu dispositivo"
                            }

                            else -> {
                                "Sin resultados en streaming"
                            }
                        }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                items(
                    items = deduplicatedCatalogTracks,
                    key = { "stream-search-${it.id}" },
                ) { track ->
                    val activeDownload = activeDownloads.findUiDownloadByTrack(track.artist, track.title)
                    DiscoverTrackListItem(
                        track = track,
                        onPlay = { onPlayCatalogTrack(track) },
                        onDownload = { onDownloadCatalogTrack(track) },
                        activeDownload = activeDownload,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }

                if (deduplicatedCatalogAlbums.isNotEmpty()) {
                    item {
                        Text(
                            text = "Álbumes en streaming",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            items(deduplicatedCatalogAlbums, key = { "album-${it.id}" }) { album ->
                                SearchAlbumMiniCard(
                                    album = album,
                                    onClick = { onSelectCatalogAlbum(album) },
                                )
                            }
                        }
                    }
                }
            }

            if (canLoadMoreOnline) {
                item {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        OutlinedButton(
                            onClick = onSearchMoreOnline,
                            enabled = !isLoadingMoreOnline,
                        ) {
                            if (isLoadingMoreOnline) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cargando más…")
                            } else {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Buscar más en streaming")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleExpand)
                .padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        if (count > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onToggleExpand,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Contraer $title" else "Expandir $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SearchAlbumMiniCard(
    album: CatalogAlbum,
    onClick: () -> Unit,
) {
    SearchAlbumMiniCard(
        title = album.title,
        artist = album.artist,
        coverUrl = album.coverUrl,
        onClick = onClick,
    )
}

@Composable
private fun SearchAlbumMiniCard(
    album: Album,
    onClick: () -> Unit,
) {
    SearchAlbumMiniCard(
        title = album.displayName,
        artist = album.artist,
        coverUrl = album.artworkUri,
        onClick = onClick,
    )
}

@Composable
private fun SearchAlbumMiniCard(
    title: String,
    artist: String,
    coverUrl: String?,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier =
            Modifier
                .width(110.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            ArtworkThumbnail(
                artworkUri = coverUrl,
                size = 98.dp,
                cornerRadius = 6.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
