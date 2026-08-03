package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.SongListItem
import com.bestiapop.android.ui.state.LibraryViewMode

@Composable
fun LibrarySongList(
    songs: List<Song>,
    currentSong: Song?,
    viewMode: LibraryViewMode,
    isSelectionMode: Boolean,
    selectedSongIds: Set<Long>,
    onSongClick: (Song, Int) -> Unit,
    onSongLongClick: (Song) -> Unit,
    onToggleSelect: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onEditMetadata: (Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    onPlayAlbum: (String, List<Song>) -> Unit,
    onShuffleAlbum: (String, List<Song>) -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No se encontraron canciones",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        return
    }

    when (viewMode) {
        LibraryViewMode.FLAT -> {
            LazyColumn(modifier = modifier.fillMaxSize()) {
                items(songs, key = { it.id }) { song ->
                    val index = songs.indexOf(song)
                    SongListItem(
                        song = song,
                        isCurrentPlaying = currentSong?.id == song.id,
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedSongIds.contains(song.id),
                        onClick = { onSongClick(song, index) },
                        onLongClick = { onSongLongClick(song) },
                        onToggleSelect = { onToggleSelect(song) },
                        onPlayNext = { onPlayNext(song) },
                        onAddToQueue = { onAddToQueue(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onEditMetadata = { onEditMetadata(song) },
                        onDelete = { onDeleteSong(song) }
                    )
                }
            }
        }
        LibraryViewMode.ALBUM_GROUPS -> {
            val albumGroups = songs.groupBy { it.album }
            LazyColumn(modifier = modifier.fillMaxSize()) {
                albumGroups.forEach { (albumName, albumSongs) ->
                    item(key = "header_$albumName") {
                        TauonAlbumHeader(
                            albumName = albumName,
                            albumSongs = albumSongs,
                            onPlayAlbum = { onPlayAlbum(albumName, albumSongs) },
                            onShuffleAlbum = { onShuffleAlbum(albumName, albumSongs) }
                        )
                    }
                    items(albumSongs, key = { it.id }) { song ->
                        val index = songs.indexOf(song)
                        SongListItem(
                            song = song,
                            isCurrentPlaying = currentSong?.id == song.id,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedSongIds.contains(song.id),
                            onClick = { onSongClick(song, index) },
                            onLongClick = { onSongLongClick(song) },
                            onToggleSelect = { onToggleSelect(song) },
                            onPlayNext = { onPlayNext(song) },
                            onAddToQueue = { onAddToQueue(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) },
                            onEditMetadata = { onEditMetadata(song) },
                            onDelete = { onDeleteSong(song) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TauonAlbumHeader(
    albumName: String,
    albumSongs: List<Song>,
    onPlayAlbum: () -> Unit,
    onShuffleAlbum: () -> Unit
) {
    val artworkUri = albumSongs.firstOrNull { !it.artworkUri.isNullOrEmpty() }?.artworkUri
    val artistName = albumSongs.firstOrNull()?.artist ?: "Artista desconocido"

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkThumbnail(
                    artworkUri = artworkUri,
                    size = 52.dp,
                    cornerRadius = 8.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = albumName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$artistName • ${albumSongs.size} canciones",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            Row {
                IconButton(onClick = onPlayAlbum) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Reproducir álbum",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onShuffleAlbum) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Mezclar álbum"
                    )
                }
            }
        }
    }
}
