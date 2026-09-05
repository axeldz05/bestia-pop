package com.bestiapop.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.ui.screens.library.AlbumEditCoverMenuItems
import com.bestiapop.android.ui.screens.library.AlbumHeaderSelectionState
import com.bestiapop.android.ui.theme.ListDensity

/**
 * Compact circular action button for collection headers (36dp box, 20dp icon).
 */
@Composable
fun HeaderActionIcon(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Base compact row for collections (albums, playlists, etc.).
 * Includes thumbnail, title/subtitle, optional selection / collapse, 3-dots overflow menu,
 * and quick play / shuffle action buttons.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionHeader(
    title: String,
    subtitle: String,
    artworkUri: String?,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    isCollapsed: Boolean = false,
    isSelectionMode: Boolean = false,
    selectionState: ToggleableState = ToggleableState.Off,
    showCollapseToggle: Boolean = false,
    collapseContentDescription: String = if (isCollapsed) "Expandir" else "Plegar",
    playContentDescription: String = "Reproducir",
    shuffleContentDescription: String = "Aleatorio",
    menuContentDescription: String = "Opciones",
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onToggleCollapse: () -> Unit = {},
    onOpen: () -> Unit = {},
    menuContent: (@Composable ColumnScope.(dismissMenu: () -> Unit) -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val handleHeaderClick = remember(isSelectionMode, onToggleSelect, onOpen) {
        if (isSelectionMode) onToggleSelect else onOpen
    }
    val onOpenMenu = remember { { menuExpanded = true } }
    val onDismissMenu = remember { { menuExpanded = false } }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = ListDensity.rowHorizontalPadding,
                vertical = ListDensity.rowVerticalPadding
            )
            .clip(RoundedCornerShape(ListDensity.corner))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .combinedClickable(
                onClick = handleHeaderClick,
                onLongClick = onLongClick
            )
            .padding(ListDensity.rowInnerPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            TriStateCheckbox(
                state = selectionState,
                onClick = onToggleSelect,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        ArtworkThumbnail(
            artworkUri = artworkUri,
            size = ListDensity.artworkAlbumHeader,
            cornerRadius = ListDensity.corner,
            fallbackIcon = fallbackIcon
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = ListDensity.titleStyle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = ListDensity.subtitleStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showCollapseToggle) {
            HeaderActionIcon(
                onClick = onToggleCollapse,
                icon = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = collapseContentDescription
            )
        }
        if (!isSelectionMode) {
            if (menuContent != null) {
                Box {
                    HeaderActionIcon(
                        onClick = onOpenMenu,
                        icon = Icons.Default.MoreVert,
                        contentDescription = menuContentDescription
                    )
                    if (menuExpanded) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = onDismissMenu
                        ) {
                            menuContent(onDismissMenu)
                        }
                    }
                }
            }
            HeaderActionIcon(
                onClick = onPlay,
                icon = Icons.Default.PlayArrow,
                contentDescription = playContentDescription,
                tint = MaterialTheme.colorScheme.primary
            )
            HeaderActionIcon(
                onClick = onShuffle,
                icon = Icons.Default.Shuffle,
                contentDescription = shuffleContentDescription
            )
        }
    }
}

/**
 * Album header row with album metadata, cover editing options, and play/shuffle actions.
 */
@Composable
fun AlbumHeader(
    title: String,
    artistName: String,
    artworkUri: String?,
    songCount: Int,
    subtitle: String = "$artistName • $songCount canciones",
    sortHint: String? = null,
    isCollapsed: Boolean = false,
    isSelectionMode: Boolean = false,
    selectionState: AlbumHeaderSelectionState = AlbumHeaderSelectionState.NONE,
    showCollapseToggle: Boolean = true,
    onPlayAlbum: () -> Unit,
    onShuffleAlbum: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onToggleCollapse: () -> Unit = {},
    onEditAlbum: () -> Unit = {},
    onChangeAlbumCover: () -> Unit = {},
    onIdentifyAlbum: (() -> Unit)? = null,
    onOpenAlbum: () -> Unit = {}
) {
    val toggleState = when (selectionState) {
        AlbumHeaderSelectionState.NONE -> ToggleableState.Off
        AlbumHeaderSelectionState.PARTIAL -> ToggleableState.Indeterminate
        AlbumHeaderSelectionState.ALL -> ToggleableState.On
    }
    val effectiveSubtitle = remember(subtitle, sortHint) {
        if (sortHint.isNullOrBlank()) subtitle else "$subtitle • $sortHint"
    }
    CollectionHeader(
        title = title,
        subtitle = effectiveSubtitle,
        artworkUri = artworkUri,
        fallbackIcon = Icons.Default.MusicNote,
        isCollapsed = isCollapsed,
        isSelectionMode = isSelectionMode,
        selectionState = toggleState,
        showCollapseToggle = showCollapseToggle,
        collapseContentDescription = if (isCollapsed) "Expandir álbum" else "Plegar álbum",
        playContentDescription = "Reproducir álbum",
        shuffleContentDescription = "Mezclar álbum",
        menuContentDescription = "Opciones de álbum",
        onPlay = onPlayAlbum,
        onShuffle = onShuffleAlbum,
        onToggleSelect = onToggleSelect,
        onLongClick = onLongClick,
        onToggleCollapse = onToggleCollapse,
        onOpen = onOpenAlbum,
        menuContent = { dismissMenu ->
            AlbumEditCoverMenuItems(
                onEditAlbum = {
                    dismissMenu()
                    onEditAlbum()
                },
                onChangeCover = {
                    dismissMenu()
                    onChangeAlbumCover()
                },
                onIdentifyAlbum = onIdentifyAlbum?.let { action ->
                    {
                        dismissMenu()
                        action()
                    }
                }
            )
        }
    )
}

/**
 * Playlist header row with playlist metadata, quick play/shuffle, 3-dots menu for quick
 * edit/delete/queue, and click handling to open the playlist detail view.
 */
@Composable
fun PlaylistHeader(
    playlist: Playlist,
    onPlayPlaylist: () -> Unit,
    onShufflePlaylist: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onEditPlaylist: () -> Unit,
    onDeletePlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null
) {
    val subtitle = remember(playlist.songCount, playlist.description) {
        val countText = if (playlist.songCount == 1) "1 canción" else "${playlist.songCount} canciones"
        if (playlist.description.isNullOrBlank()) {
            countText
        } else {
            "$countText • ${playlist.description}"
        }
    }
    CollectionHeader(
        title = playlist.name,
        subtitle = subtitle,
        artworkUri = playlist.coverUri,
        fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
        modifier = modifier,
        playContentDescription = "Reproducir playlist",
        shuffleContentDescription = "Mezclar playlist",
        menuContentDescription = "Opciones de playlist",
        onPlay = onPlayPlaylist,
        onShuffle = onShufflePlaylist,
        onOpen = onOpenPlaylist,
        menuContent = { dismissMenu ->
            DropdownMenuItem(
                text = { Text("Editar playlist") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    dismissMenu()
                    onEditPlaylist()
                }
            )
            if (onPlayNext != null) {
                DropdownMenuItem(
                    text = { Text("Reproducir siguiente") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                    onClick = {
                        dismissMenu()
                        onPlayNext()
                    }
                )
            }
            if (onAddToQueue != null) {
                DropdownMenuItem(
                    text = { Text("Añadir a la cola") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                    onClick = {
                        dismissMenu()
                        onAddToQueue()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Eliminar playlist", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    dismissMenu()
                    onDeletePlaylist()
                }
            )
        }
    )
}
