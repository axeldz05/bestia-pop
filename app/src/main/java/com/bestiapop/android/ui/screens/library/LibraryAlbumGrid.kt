package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.formatSortRelevantInfo

@Composable
fun LibraryAlbumGrid(
    albums: List<Album>,
    sortOption: SortOption = SortOption.TITLE,
    onAlbumClick: (Album) -> Unit,
    onPlayAlbum: (Album) -> Unit,
    onShuffleAlbum: (Album) -> Unit,
    onChangeAlbumCover: (Album) -> Unit,
    onEditAlbum: (Album) -> Unit = onChangeAlbumCover,
    modifier: Modifier = Modifier
) {
    if (albums.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No se encontraron álbumes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(albums, key = { it.name }) { album ->
            AlbumGridCard(
                album = album,
                sortOption = sortOption,
                onClick = { onAlbumClick(album) },
                onPlay = { onPlayAlbum(album) },
                onShuffle = { onShuffleAlbum(album) },
                onChangeCover = { onChangeAlbumCover(album) },
                onEditAlbum = { onEditAlbum(album) }
            )
        }
    }
}

@Composable
fun AlbumGridCard(
    album: Album,
    sortOption: SortOption = SortOption.TITLE,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onChangeCover: () -> Unit,
    onEditAlbum: () -> Unit = onChangeCover
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val sortInfo = remember(album.genre, album.dateAdded, sortOption) {
        formatSortRelevantInfo(
            sortOption = sortOption,
            genre = album.genre,
            dateAdded = album.dateAdded,
            alreadyShowsArtist = true,
            alreadyShowsAlbum = true,
            alreadyShowsTitle = true
        )
    }
    val subtitle = remember(album.artist, album.songCount, sortInfo) {
        val base = "${album.artist} • ${album.songCount} canciones"
        if (sortInfo.isNullOrBlank()) base else "$base • $sortInfo"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                ArtworkThumbnail(
                    artworkUri = album.artworkUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    cornerRadius = 8.dp
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                ) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Opciones de álbum",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Reproducir álbum") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                            onClick = {
                                menuExpanded = false
                                onPlay()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Mezclar álbum") },
                            leadingIcon = { Icon(Icons.Default.Shuffle, null) },
                            onClick = {
                                menuExpanded = false
                                onShuffle()
                            }
                        )
                        AlbumEditCoverMenuItems(
                            onEditAlbum = {
                                menuExpanded = false
                                onEditAlbum()
                            },
                            onChangeCover = {
                                menuExpanded = false
                                onChangeCover()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = album.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Shared Edit / Change-cover items for album overflow menus (grid + song-list header). */
@Composable
fun AlbumEditCoverMenuItems(
    onEditAlbum: () -> Unit,
    onChangeCover: () -> Unit
) {
    DropdownMenuItem(
        text = { Text("Editar álbum") },
        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
        onClick = onEditAlbum
    )
    DropdownMenuItem(
        text = { Text("Cambiar portada") },
        leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null) },
        onClick = onChangeCover
    )
}
