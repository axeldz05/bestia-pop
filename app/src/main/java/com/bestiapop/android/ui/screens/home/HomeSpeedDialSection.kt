package com.bestiapop.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.ui.components.ArtworkThumbnail

@Immutable
data class HomeSpeedDialItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val isRemote: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Level 2: Factory function building speed dial items from playlists, albums, and online playlists.
 */
fun buildHomeSpeedDialItems(
    playlists: List<Playlist>,
    albums: List<Album>,
    onlinePlaylists: List<LbPlaylistSummary>,
    isOfflineMode: Boolean,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlayAlbum: (Album) -> Unit,
    onOpenOnlinePlaylist: (LbPlaylistSummary) -> Unit,
): List<HomeSpeedDialItem> =
    buildList {
        playlists.take(4).forEach { pl ->
            add(
                HomeSpeedDialItem(
                    id = "pl-${pl.id}",
                    title = pl.name,
                    subtitle = "Playlist",
                    artworkUri = pl.coverUri,
                    isRemote = false,
                    onClick = { onOpenPlaylist(pl) },
                ),
            )
        }
        albums.take(4).forEach { alb ->
            add(
                HomeSpeedDialItem(
                    id = "alb-${alb.name}",
                    title = alb.displayName,
                    subtitle = alb.artist,
                    artworkUri = alb.artworkUri,
                    isRemote = false,
                    onClick = { onPlayAlbum(alb) },
                ),
            )
        }
        if (!isOfflineMode) {
            onlinePlaylists.take(2).forEach { lbPl ->
                add(
                    HomeSpeedDialItem(
                        id = "lb-${lbPl.mbid}",
                        title = lbPl.title,
                        subtitle = "ListenBrainz",
                        artworkUri = null,
                        isRemote = true,
                        onClick = { onOpenOnlinePlaylist(lbPl) },
                    ),
                )
            }
        }
    }

/**
 * Level 2: Speed dial carousel with 2 items per column.
 */
@Composable
fun HomeSpeedDialSection(
    items: List<HomeSpeedDialItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val columns = items.chunked(2)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
    ) {
        Text(
            text = "Acceso rápido",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(columns, key = { it.first().id }) { pair ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.width(200.dp),
                ) {
                    HomeSpeedDialCard(
                        item = pair[0],
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    )
                    if (pair.size > 1) {
                        HomeSpeedDialCard(
                            item = pair[1],
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Level 1: Individual speed dial card with artwork and title.
 */
@Composable
fun HomeSpeedDialCard(
    item: HomeSpeedDialItem,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable { item.onClick() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.size(56.dp)) {
                ArtworkThumbnail(
                    artworkUri = item.artworkUri,
                    size = 56.dp,
                    cornerRadius = 0.dp,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (item.isRemote) Icons.Default.Cloud else Icons.Default.Smartphone,
                        contentDescription = if (item.isRemote) "Streaming" else "Local",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
