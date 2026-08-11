package com.bestiapop.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.theme.ListDensity
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListItem(
    song: Song,
    isCurrentPlaying: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    secondaryInfo: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
    onIdentify: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = playingRowColors(highlighted = isCurrentPlaying, selected = isSelected)
    val subtitle = remember(song.artist, song.album, secondaryInfo) {
        joinMeta(song.artist, song.album, secondaryInfo)
    }
    val durationText = remember(song.durationMs) { formatDuration(song.durationMs) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ListDensity.rowHorizontalPadding,
                vertical = ListDensity.rowVerticalPadding
            )
            .clip(RoundedCornerShape(ListDensity.corner))
            .background(colors.background)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelect()
                    } else {
                        onClick()
                    }
                },
                onLongClick = onLongClick
            )
            .padding(ListDensity.rowInnerPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        ArtworkThumbnail(
            artworkUri = song.artworkUri,
            size = ListDensity.artworkSong,
            contentDescription = song.title
        )

        Spacer(modifier = Modifier.width(12.dp))

        TrackTextColumn(
            title = song.title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
            titleColor = colors.title,
            titleWeight = colors.titleWeight
        )

        Text(
            text = durationText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        if (!isSelectionMode) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                if (menuExpanded) {
                    SongOptionsMenu(
                        onDismiss = { menuExpanded = false },
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onStartRadio = onStartRadio,
                        onAddToPlaylist = onAddToPlaylist,
                        onEditMetadata = onEditMetadata,
                        onIdentify = onIdentify,
                        onDelete = onDelete
                    )
                }
            }
        }
    }
}

@Composable
fun SongOverflowMenuItems(
    onDismiss: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onIdentify: (() -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    if (onAddToPlaylist != null) {
        DropdownMenuItem(
            text = { Text("Añadir a playlist") },
            onClick = {
                onDismiss()
                onAddToPlaylist()
            }
        )
    }
    if (onIdentify != null) {
        DropdownMenuItem(
            text = { Text("Identificar…") },
            onClick = {
                onDismiss()
                onIdentify()
            }
        )
    }
    if (onEditMetadata != null) {
        DropdownMenuItem(
            text = { Text("Editar información") },
            onClick = {
                onDismiss()
                onEditMetadata()
            }
        )
    }
    if (onDelete != null) {
        DropdownMenuItem(
            text = { Text("Eliminar", color = MaterialTheme.colorScheme.error) },
            onClick = {
                onDismiss()
                onDelete()
            }
        )
    }
}

@Composable
private fun SongOptionsMenu(
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onEditMetadata: (() -> Unit)?,
    onIdentify: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Reproducir a continuación") },
            onClick = {
                onDismiss()
                onPlayNext()
            }
        )
        DropdownMenuItem(
            text = { Text("Añadir a la cola") },
            onClick = {
                onDismiss()
                onAddToQueue()
            }
        )
        if (onStartRadio != null) {
            DropdownMenuItem(
                text = { Text("Iniciar radio") },
                onClick = {
                    onDismiss()
                    onStartRadio()
                }
            )
        }
        SongOverflowMenuItems(
            onDismiss = onDismiss,
            onAddToPlaylist = onAddToPlaylist,
            onIdentify = onIdentify,
            onEditMetadata = onEditMetadata,
            onDelete = onDelete
        )
    }
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
