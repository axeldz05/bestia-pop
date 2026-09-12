package com.bestiapop.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.PlaylistMessages
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.screens.library.SongActionDialogsController
import com.bestiapop.android.ui.theme.ListDensity
import java.util.Locale

/**
 * Level 2: Bundled actions for a song row item.
 * Encapsulates playback/queue actions as well as metadata/playlist/deletion callbacks.
 */
import com.bestiapop.android.data.preferences.SubmenuSwipeAction

@Immutable
data class SongItemActions(
    val onPlayNext: ((Song) -> Unit)? = null,
    val onAddToQueue: ((Song) -> Unit)? = null,
    val onStartRadio: ((Song) -> Unit)? = null,
    val onAddToPlaylist: ((Song) -> Unit)? = null,
    val onEditMetadata: ((Song) -> Unit)? = null,
    val onEditLyrics: ((Song) -> Unit)? = null,
    val onIdentify: ((Song) -> Unit)? = null,
    val onDelete: ((Song) -> Unit)? = null,
    val deleteLabel: String = "Eliminar",
    val swipeAction: SubmenuSwipeAction = SubmenuSwipeAction.ENQUEUE_ALL
) {
    companion object {
        fun from(
            queueActions: SongQueueActions,
            onAddToPlaylist: ((Song) -> Unit)? = null,
            onEditMetadata: ((Song) -> Unit)? = null,
            onEditLyrics: ((Song) -> Unit)? = null,
            onIdentify: ((Song) -> Unit)? = null,
            onDelete: ((Song) -> Unit)? = null,
            deleteLabel: String = "Eliminar",
            swipeAction: SubmenuSwipeAction = SubmenuSwipeAction.ENQUEUE_ALL
        ): SongItemActions = SongItemActions(
            onPlayNext = queueActions.onPlayNext,
            onAddToQueue = queueActions.onAddToQueue,
            onStartRadio = queueActions.onStartRadio,
            onAddToPlaylist = onAddToPlaylist,
            onEditMetadata = onEditMetadata,
            onEditLyrics = onEditLyrics,
            onIdentify = onIdentify,
            onDelete = onDelete,
            deleteLabel = deleteLabel,
            swipeAction = swipeAction
        )

        fun from(
            queueActions: SongQueueActions,
            dialogs: SongActionDialogsController,
            onIdentify: ((Song) -> Unit)? = dialogs.onIdentify,
            onDelete: ((Song) -> Unit)? = dialogs.onDelete,
            deleteLabel: String = "Eliminar",
            swipeAction: SubmenuSwipeAction = SubmenuSwipeAction.ENQUEUE_ALL
        ): SongItemActions = SongItemActions(
            onPlayNext = queueActions.onPlayNext,
            onAddToQueue = queueActions.onAddToQueue,
            onStartRadio = queueActions.onStartRadio,
            onAddToPlaylist = dialogs.onAddToPlaylist,
            onEditMetadata = dialogs.onEdit,
            onEditLyrics = dialogs.onEditLyrics,
            onIdentify = onIdentify,
            onDelete = onDelete,
            deleteLabel = deleteLabel,
            swipeAction = swipeAction
        )

    }
}

/**
 * Level 2: Bundled song actions overload for [SongListItem].
 */
@Composable
fun SongListItem(
    song: Song,
    actions: SongItemActions,
    modifier: Modifier = Modifier,
    isCurrentPlaying: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isReorderMode: Boolean = false,
    index: Int = 0,
    reorderCount: Int = 0,
    onReorder: ((from: Int, to: Int) -> Unit)? = null,
    secondaryInfo: String? = null,
    title: String? = null,
    subtitle: String? = null,
    trailing: String? = null,
    trailingIsSortKey: Boolean = false,
    artworkUri: String? = song.artworkUri,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    onOptionsClick: (() -> Unit)? = null,
    swipeAction: SubmenuSwipeAction = actions.swipeAction,
    onSwipeAction: (() -> Unit)? = null
) = SongListItem(
    song = song,
    modifier = modifier,
    isCurrentPlaying = isCurrentPlaying,
    isSelectionMode = isSelectionMode,
    isSelected = isSelected,
    isReorderMode = isReorderMode,
    index = index,
    reorderCount = reorderCount,
    onReorder = onReorder,
    secondaryInfo = secondaryInfo,
    title = title,
    subtitle = subtitle,
    trailing = trailing,
    trailingIsSortKey = trailingIsSortKey,
    artworkUri = artworkUri,
    onClick = onClick,
    onLongClick = onLongClick,
    onToggleSelect = onToggleSelect,
    onOptionsClick = onOptionsClick,
    onPlayNext = { actions.onPlayNext?.invoke(song) },
    onAddToQueue = { actions.onAddToQueue?.invoke(song) },
    onStartRadio = actions.onStartRadio?.let { cb -> { cb(song) } },
    onAddToPlaylist = actions.onAddToPlaylist?.let { cb -> { cb(song) } },
    onEditMetadata = actions.onEditMetadata?.let { cb -> { cb(song) } },
    onEditLyrics = actions.onEditLyrics?.let { cb -> { cb(song) } },
    onIdentify = actions.onIdentify?.let { cb -> { cb(song) } },
    onDelete = actions.onDelete?.let { cb -> { cb(song) } },
    deleteLabel = actions.deleteLabel,
    swipeAction = swipeAction,
    onSwipeAction = onSwipeAction
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListItem(
    song: Song,
    modifier: Modifier = Modifier,
    isCurrentPlaying: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isReorderMode: Boolean = false,
    index: Int = 0,
    reorderCount: Int = 0,
    onReorder: ((from: Int, to: Int) -> Unit)? = null,
    secondaryInfo: String? = null,
    title: String? = null,
    subtitle: String? = null,
    trailing: String? = null,
    trailingIsSortKey: Boolean = false,
    artworkUri: String? = song.artworkUri,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    onOptionsClick: (() -> Unit)? = null,
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onStartRadio: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
    onEditLyrics: (() -> Unit)? = null,
    onIdentify: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "Eliminar",
    swipeAction: SubmenuSwipeAction = SubmenuSwipeAction.ENQUEUE_ALL,
    onSwipeAction: (() -> Unit)? = null
) {
    val colors = playingRowColors(highlighted = isCurrentPlaying, selected = isSelected)
    val displayTitle = title ?: song.title
    val displaySubtitle = subtitle ?: remember(song.artist, song.album, secondaryInfo) {
        joinMeta(song.artist, song.album, secondaryInfo)
    }
    val trailingText = trailing ?: remember(song.durationMs) { formatDuration(song.durationMs) }

    val drag = rememberVerticalReorderDrag(
        index = index,
        reorderCount = reorderCount,
        enabled = isReorderMode && onReorder != null && reorderCount > 1,
        onReorder = onReorder
    )
    val rowDragModifier = drag.rowModifier
    val handleModifier = drag.handleModifier

    val resolvedSwipeAction: (() -> Unit)? = onSwipeAction ?: when (swipeAction) {
        SubmenuSwipeAction.ENQUEUE_ALL -> onAddToQueue
        SubmenuSwipeAction.PLAY_NEXT -> onPlayNext
        SubmenuSwipeAction.START_RADIO -> onStartRadio
        SubmenuSwipeAction.ADD_TO_PLAYLIST -> onAddToPlaylist
        SubmenuSwipeAction.SEARCH_SIMILAR -> null
        SubmenuSwipeAction.DISABLED -> null
    }
    val canSwipe = !isSelectionMode && !isReorderMode && resolvedSwipeAction != null && swipeAction != SubmenuSwipeAction.DISABLED

    ItemSwipeBox(
        action = swipeAction,
        onSwipeAction = { resolvedSwipeAction?.invoke() },
        enabled = canSwipe,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(rowDragModifier)
                .padding(
                    horizontal = ListDensity.rowHorizontalPadding,
                    vertical = ListDensity.rowVerticalPadding
                )
                .then(
                    if (colors.background != Color.Transparent) {
                        Modifier
                            .clip(RoundedCornerShape(ListDensity.corner))
                            .background(colors.background)
                    } else {
                        Modifier
                    }
                )
                .combinedClickable(
                    onClick = {
                        if (isReorderMode) {
                            // no-op while reordering
                        } else if (isSelectionMode) {
                            onToggleSelect()
                        } else {
                            onClick()
                        }
                    },
                    onLongClick = if (isReorderMode) null else onLongClick
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
            artworkUri = artworkUri,
            size = ListDensity.artworkSong,
            contentDescription = song.title
        )

        Spacer(modifier = Modifier.width(12.dp))

        TrackTextColumn(
            title = displayTitle,
            subtitle = displaySubtitle,
            modifier = Modifier.weight(1f),
            titleColor = colors.title,
            titleWeight = colors.titleWeight
        )

        Text(
            text = trailingText,
            style = MaterialTheme.typography.labelMedium,
            color = if (trailingIsSortKey) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            },
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(min = 56.dp)
                .padding(horizontal = 8.dp)
        )

        if (isReorderMode) {
            Box(
                modifier = (handleModifier ?: Modifier)
                    .size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Reordenar canción",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }
        } else if (!isSelectionMode) {
            if (onOptionsClick != null) {
                IconButton(
                    onClick = onOptionsClick,
                    modifier = Modifier.testTag("song-options-${song.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Opciones",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            } else {
                InlineSongOptionsButton(
                    songId = song.id,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onStartRadio = onStartRadio,
                    onAddToPlaylist = onAddToPlaylist,
                    onEditMetadata = onEditMetadata,
                    onEditLyrics = onEditLyrics,
                    onIdentify = onIdentify,
                    onDelete = onDelete,
                    deleteLabel = deleteLabel
                )
            }
        }
    }
}
}

@Composable
private fun InlineSongOptionsButton(
    songId: Long,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onEditMetadata: (() -> Unit)?,
    onEditLyrics: (() -> Unit)?,
    onIdentify: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    deleteLabel: String = "Eliminar"
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.testTag("song-options-$songId")
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Opciones",
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
                onEditLyrics = onEditLyrics,
                onIdentify = onIdentify,
                onDelete = onDelete,
                deleteLabel = deleteLabel
            )
        }
    }
}

@Composable
fun SongOverflowMenuItems(
    onDismiss: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onIdentify: (() -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
    onEditLyrics: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "Eliminar"
) {
    optionalOverflowItem(PlaylistMessages.addToPlaylist, onAddToPlaylist, onDismiss)
    optionalOverflowItem("Identificar…", onIdentify, onDismiss)
    optionalOverflowItem("Editar información", onEditMetadata, onDismiss)
    optionalOverflowItem("Editar letra", onEditLyrics, onDismiss)
    optionalOverflowItem(deleteLabel, onDelete, onDismiss, MaterialTheme.colorScheme.error)
}

@Composable
private fun optionalOverflowItem(
    label: String,
    onClick: (() -> Unit)?,
    onDismiss: () -> Unit,
    textColor: Color = Color.Unspecified
) {
    if (onClick == null) return
    DropdownMenuItem(
        text = { Text(label, color = textColor) },
        onClick = {
            onDismiss()
            onClick()
        }
    )
}

/**
 * Level 2: Bundled options menu for a song item using [SongItemActions].
 */
@Composable
fun SongOptionsMenu(
    song: Song,
    actions: SongItemActions,
    onDismiss: () -> Unit
) = SongOptionsMenu(
    onDismiss = onDismiss,
    onPlayNext = { actions.onPlayNext?.invoke(song) },
    onAddToQueue = { actions.onAddToQueue?.invoke(song) },
    onStartRadio = actions.onStartRadio?.let { cb -> { cb(song) } },
    onAddToPlaylist = actions.onAddToPlaylist?.let { cb -> { cb(song) } },
    onEditMetadata = actions.onEditMetadata?.let { cb -> { cb(song) } },
    onEditLyrics = actions.onEditLyrics?.let { cb -> { cb(song) } },
    onIdentify = actions.onIdentify?.let { cb -> { cb(song) } },
    onDelete = actions.onDelete?.let { cb -> { cb(song) } },
    deleteLabel = actions.deleteLabel
)

/**
 * Level 2: Convenience overload when song is optional or captured in actions.
 */
@Composable
fun SongOptionsMenu(
    actions: SongItemActions,
    onDismiss: () -> Unit,
    song: Song? = null
) {
    if (song != null) {
        SongOptionsMenu(song = song, actions = actions, onDismiss = onDismiss)
    } else {
        SongOptionsMenu(
            onDismiss = onDismiss,
            onPlayNext = {},
            onAddToQueue = {},
            onStartRadio = null,
            onAddToPlaylist = null,
            onEditMetadata = null,
            onEditLyrics = null,
            onIdentify = null,
            onDelete = null,
            deleteLabel = actions.deleteLabel
        )
    }
}

/**
 * Level 1: Primitive callback options menu for a song item.
 */
@Composable
fun SongOptionsMenu(
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onEditMetadata: (() -> Unit)?,
    onEditLyrics: (() -> Unit)?,
    onIdentify: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    deleteLabel: String = "Eliminar"
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
            onEditLyrics = onEditLyrics,
            onDelete = onDelete,
            deleteLabel = deleteLabel
        )
    }
}

fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0:00"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val secTens = (seconds / 10).toInt()
    val secOnes = (seconds % 10).toInt()
    val sb = StringBuilder(8)
    sb.append(minutes).append(':').append(secTens).append(secOnes)
    return sb.toString()
}
