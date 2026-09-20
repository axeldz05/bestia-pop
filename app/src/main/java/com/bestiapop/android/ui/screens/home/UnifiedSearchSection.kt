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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.EmptyListHint
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
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        // --- 1. LOCAL RESULTS FIRST ---
        item {
            SearchSectionHeader(
                title = "En tu dispositivo",
                icon = Icons.Default.Smartphone,
                count = localSongs.size,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (localSongs.isEmpty()) {
            item {
                Text(
                    text = "Sin canciones locales que coincidan con \"$searchQuery\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        } else {
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

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- 2. STREAMING SUGGESTIONS ---
        item {
            SearchSectionHeader(
                title = "En streaming (Sugerencias)",
                icon = Icons.Default.Cloud,
                count = catalogTracks.size,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (isSearchingOnline && catalogTracks.isEmpty()) {
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
        } else if (catalogTracks.isEmpty()) {
            item {
                Text(
                    text = if (isSearchingOnline) "Buscando…" else "Sin resultados en streaming",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        } else {
            items(
                items = catalogTracks,
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

            if (catalogAlbums.isNotEmpty()) {
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
                        items(catalogAlbums, key = { "album-${it.id}" }) { album ->
                            SearchAlbumMiniCard(
                                album = album,
                                onClick = { onSelectCatalogAlbum(album) },
                            )
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
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        if (count > 0) {
            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchAlbumMiniCard(
    album: CatalogAlbum,
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
                artworkUri = album.coverUrl,
                size = 98.dp,
                cornerRadius = 6.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
