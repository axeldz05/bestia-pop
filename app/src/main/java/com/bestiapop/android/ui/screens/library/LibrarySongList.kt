package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.SongListItem
import com.bestiapop.android.ui.state.LibraryListItem

@Composable
fun LibrarySongList(
    items: List<LibraryListItem>,
    currentSongId: Long?,
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
    if (items.isEmpty()) {
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

    val onSongClickState = rememberUpdatedState(onSongClick)
    val onSongLongClickState = rememberUpdatedState(onSongLongClick)
    val onToggleSelectState = rememberUpdatedState(onToggleSelect)
    val onPlayNextState = rememberUpdatedState(onPlayNext)
    val onAddToQueueState = rememberUpdatedState(onAddToQueue)
    val onAddToPlaylistState = rememberUpdatedState(onAddToPlaylist)
    val onEditMetadataState = rememberUpdatedState(onEditMetadata)
    val onDeleteSongState = rememberUpdatedState(onDeleteSong)
    val onPlayAlbumState = rememberUpdatedState(onPlayAlbum)
    val onShuffleAlbumState = rememberUpdatedState(onShuffleAlbum)

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(
            items = items,
            key = { it.key },
            contentType = { it.contentType }
        ) { item ->
            when (item) {
                is LibraryListItem.AlbumHeader -> {
                    val playAlbum = remember(item.albumName, item.albumSongs) {
                        { onPlayAlbumState.value(item.albumName, item.albumSongs) }
                    }
                    val shuffleAlbum = remember(item.albumName, item.albumSongs) {
                        { onShuffleAlbumState.value(item.albumName, item.albumSongs) }
                    }
                    TauonAlbumHeader(
                        albumName = item.albumName,
                        artistName = item.artistName,
                        artworkUri = item.artworkUri,
                        songCount = item.songCount,
                        onPlayAlbum = playAlbum,
                        onShuffleAlbum = shuffleAlbum
                    )
                }

                is LibraryListItem.SongRow -> {
                    LibrarySongRow(
                        song = item.song,
                        index = item.index,
                        currentSongId = currentSongId,
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedSongIds.contains(item.song.id),
                        onSongClickState = onSongClickState,
                        onSongLongClickState = onSongLongClickState,
                        onToggleSelectState = onToggleSelectState,
                        onPlayNextState = onPlayNextState,
                        onAddToQueueState = onAddToQueueState,
                        onAddToPlaylistState = onAddToPlaylistState,
                        onEditMetadataState = onEditMetadataState,
                        onDeleteSongState = onDeleteSongState
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySongRow(
    song: Song,
    index: Int,
    currentSongId: Long?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onSongClickState: State<(Song, Int) -> Unit>,
    onSongLongClickState: State<(Song) -> Unit>,
    onToggleSelectState: State<(Song) -> Unit>,
    onPlayNextState: State<(Song) -> Unit>,
    onAddToQueueState: State<(Song) -> Unit>,
    onAddToPlaylistState: State<(Song) -> Unit>,
    onEditMetadataState: State<(Song) -> Unit>,
    onDeleteSongState: State<(Song) -> Unit>
) {
    val songState = rememberUpdatedState(song)
    val onClick = remember(song.id, index) {
        { onSongClickState.value(songState.value, index) }
    }
    val onLongClick = remember(song.id) {
        { onSongLongClickState.value(songState.value) }
    }
    val onToggleSelect = remember(song.id) {
        { onToggleSelectState.value(songState.value) }
    }
    val onPlayNext = remember(song.id) {
        { onPlayNextState.value(songState.value) }
    }
    val onAddToQueue = remember(song.id) {
        { onAddToQueueState.value(songState.value) }
    }
    val onAddToPlaylist = remember(song.id) {
        { onAddToPlaylistState.value(songState.value) }
    }
    val onEditMetadata = remember(song.id) {
        { onEditMetadataState.value(songState.value) }
    }
    val onDelete = remember(song.id) {
        { onDeleteSongState.value(songState.value) }
    }

    SongListItem(
        song = song,
        isCurrentPlaying = currentSongId == song.id,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        onToggleSelect = onToggleSelect,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onAddToPlaylist = onAddToPlaylist,
        onEditMetadata = onEditMetadata,
        onDelete = onDelete
    )
}

@Composable
fun TauonAlbumHeader(
    albumName: String,
    artistName: String,
    artworkUri: String?,
    songCount: Int,
    onPlayAlbum: () -> Unit,
    onShuffleAlbum: () -> Unit
) {
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
                        text = "$artistName • $songCount canciones",
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
