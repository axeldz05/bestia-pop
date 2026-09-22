package com.bestiapop.android.ui.screens.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.isRemote
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.state.LibraryBrowseFilter

/**
 * Level 2: "Vuelve a escuchar" horizontal carousel for frequent songs.
 */
@Composable
fun FrequentSongsCarousel(
    songs: List<Song>,
    onPlaySong: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) return

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Repeat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Vuelve a escuchar",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            items(songs, key = { "freq-${it.id}" }) { song ->
                SongCarouselCard(
                    song = song,
                    onClick = { onPlaySong(song) },
                )
            }
        }
    }
}

/**
 * Level 2: "Escuchado recientemente" horizontal carousel.
 */
@Composable
fun RecentSongsCarousel(
    songs: List<Song>,
    onPlaySong: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) return

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
    ) {
        Text(
            text = "Escuchado recientemente",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            items(songs.take(15), key = { "recent-${it.id}" }) { song ->
                SongCarouselCard(
                    song = song,
                    onClick = { onPlaySong(song) },
                )
            }
        }
    }
}

/**
 * Level 1: Song card for horizontal carousels.
 */
@Composable
fun SongCarouselCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier =
            modifier
                .width(124.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier =
                    Modifier
                        .size(124.dp)
                        .clip(RoundedCornerShape(10.dp)),
            ) {
                ArtworkThumbnail(
                    artworkUri = song.artworkUri,
                    size = 124.dp,
                    cornerRadius = 10.dp,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (song.isRemote) Icons.Default.Cloud else Icons.Default.Smartphone,
                        contentDescription = if (song.isRemote) "Streaming" else "Local",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = song.title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Level 2: Library shortcuts bar ("Tu Biblioteca").
 */
@Composable
fun LibraryCollectionsRow(
    songCount: Int,
    albumCount: Int,
    artistCount: Int,
    playlistCount: Int,
    onSelectFilter: (LibraryBrowseFilter) -> Unit,
    modifier: Modifier = Modifier,
    filters: List<LibraryBrowseFilter> =
        listOf(
            LibraryBrowseFilter.SONGS,
            LibraryBrowseFilter.ALBUMS,
            LibraryBrowseFilter.ARTISTS,
            LibraryBrowseFilter.PLAYLISTS,
            LibraryBrowseFilter.GENRES,
        ),
    showTitle: Boolean = false,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = if (showTitle) 8.dp else 2.dp),
    ) {
        if (showTitle) {
            Text(
                text = "Tu Biblioteca",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(filters, key = { it.name }) { filter ->
                when (filter) {
                    LibraryBrowseFilter.SONGS -> {
                        CollectionChip(
                            label = if (songCount > 0) "Canciones ($songCount)" else "Canciones",
                            icon = Icons.Default.LibraryMusic,
                            onClick = { onSelectFilter(LibraryBrowseFilter.SONGS) },
                        )
                    }

                    LibraryBrowseFilter.ALBUMS -> {
                        CollectionChip(
                            label = if (albumCount > 0) "Álbumes ($albumCount)" else "Álbumes",
                            icon = Icons.Default.LibraryMusic,
                            onClick = { onSelectFilter(LibraryBrowseFilter.ALBUMS) },
                        )
                    }

                    LibraryBrowseFilter.ARTISTS -> {
                        CollectionChip(
                            label = if (artistCount > 0) "Artistas ($artistCount)" else "Artistas",
                            icon = Icons.Default.Person,
                            onClick = { onSelectFilter(LibraryBrowseFilter.ARTISTS) },
                        )
                    }

                    LibraryBrowseFilter.PLAYLISTS -> {
                        CollectionChip(
                            label = if (playlistCount > 0) "Playlists ($playlistCount)" else "Playlists",
                            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            onClick = { onSelectFilter(LibraryBrowseFilter.PLAYLISTS) },
                        )
                    }

                    LibraryBrowseFilter.GENRES -> {
                        CollectionChip(
                            label = "Géneros",
                            icon = Icons.Default.Folder,
                            onClick = { onSelectFilter(LibraryBrowseFilter.GENRES) },
                        )
                    }

                    LibraryBrowseFilter.RECENT -> {
                        CollectionChip(
                            label = "Recientes",
                            icon = Icons.Default.History,
                            onClick = { onSelectFilter(LibraryBrowseFilter.RECENT) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        shape = RoundedCornerShape(20.dp),
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                labelColor = MaterialTheme.colorScheme.onSurface,
            ),
    )
}
