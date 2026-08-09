package com.bestiapop.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.bestiapop.android.data.model.PlayableItem
import kotlin.math.roundToInt

/** L1: artwork + title + artist for a queue/playable row. */
@Composable
fun PlayableItemRowContent(
    item: PlayableItem,
    isCurrentPlaying: Boolean,
    artworkSize: Dp = 48.dp,
    appendRemoteSuffix: Boolean = true,
    boldWhenCurrent: Boolean = false,
    modifier: Modifier = Modifier
) {
    val titleColor = when {
        boldWhenCurrent && isCurrentPlaying -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val titleWeight = when {
        boldWhenCurrent && isCurrentPlaying -> FontWeight.Bold
        boldWhenCurrent -> FontWeight.Normal
        else -> FontWeight.SemiBold
    }
    val subtitle = joinMeta(
        item.artist,
        if (appendRemoteSuffix && item is PlayableItem.Remote) "stream" else null,
        sep = " · "
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkThumbnail(
            artworkUri = item.artworkUri,
            size = artworkSize,
            cornerRadius = 8.dp,
            contentDescription = item.title
        )
        Spacer(modifier = Modifier.width(if (artworkSize <= 40.dp) 10.dp else 12.dp))
        TrackTextColumn(
            title = item.title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
            titleColor = titleColor,
            titleWeight = titleWeight,
            titleStyle = if (boldWhenCurrent) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.titleMedium
            },
            subtitleColor = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (boldWhenCurrent) 0.55f else 0.7f
            )
        )
    }
}

/**
 * L2: queue row with optional index / play icon, drag handle and remove action.
 */
@Composable
fun QueueItemRow(
    item: PlayableItem,
    isCurrentPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    showIndex: Boolean = false,
    index: Int = 0,
    removeIcon: ImageVector = Icons.Default.Delete,
    removeContentDescription: String = "Quitar",
    trailingDuration: String? = null,
    compact: Boolean = false,
    reorderCount: Int = 0,
    onReorder: ((from: Int, to: Int) -> Unit)? = null
) {
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val rowModifier = if (onReorder != null && reorderCount > 1) {
        Modifier
            .zIndex(if (dragOffsetY != 0f) 1f else 0f)
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
    } else {
        Modifier
    }
    val handleModifier = if (onReorder != null && reorderCount > 1) {
        Modifier.pointerInput(index, reorderCount) {
            detectVerticalDragGestures(
                onDragEnd = {
                    val rowPx = 56.dp.toPx()
                    val deltaSlots = (dragOffsetY / rowPx).roundToInt()
                    val to = (index + deltaSlots).coerceIn(0, reorderCount - 1)
                    if (to != index) onReorder(index, to)
                    dragOffsetY = 0f
                },
                onDragCancel = { dragOffsetY = 0f },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    dragOffsetY += amount
                }
            )
        }
    } else {
        null
    }

    if (compact) {
        val bgColor = if (isCurrentPlaying) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        }
        Row(
            modifier = rowModifier
                .fillMaxWidth()
                .background(bgColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QueueDragHandle(handleModifier)
            if (showIndex) {
                Box(
                    modifier = Modifier.width(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCurrentPlaying) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Reproduciendo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            PlayableItemRowContent(
                item = item,
                isCurrentPlaying = isCurrentPlaying,
                artworkSize = 40.dp,
                appendRemoteSuffix = false,
                boldWhenCurrent = true,
                modifier = Modifier.weight(1f)
            )
            if (trailingDuration != null) {
                Text(
                    text = trailingDuration,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = removeIcon,
                        contentDescription = removeContentDescription,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    } else {
        Row(
            modifier = rowModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QueueDragHandle(handleModifier)
            Surface(
                onClick = onClick,
                color = if (isCurrentPlaying) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            ) {
                PlayableItemRowContent(
                    item = item,
                    isCurrentPlaying = isCurrentPlaying,
                    modifier = Modifier.padding(10.dp)
                )
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = removeIcon,
                        contentDescription = removeContentDescription,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueDragHandle(handleModifier: Modifier?) {
    if (handleModifier == null) return
    Icon(
        imageVector = Icons.Default.DragHandle,
        contentDescription = "Reordenar",
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = handleModifier
            .size(28.dp)
            .padding(end = 2.dp)
    )
}
