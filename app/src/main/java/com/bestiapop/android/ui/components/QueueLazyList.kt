package com.bestiapop.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.ui.theme.ListDensity

internal fun queueRowKey(item: PlayableItem): String = item.queueEntryId

internal fun focusedQueueIndex(
    items: List<PlayableItem>,
    currentQueueEntryId: String?
): Int {
    if (currentQueueEntryId == null) return -1
    return items.indexOfFirst { it.queueEntryId == currentQueueEntryId }
}

@Composable
fun DismissibleQueueItemRow(
    item: PlayableItem,
    isCurrentPlaying: Boolean,
    index: Int,
    queueSize: Int,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    trailingDuration: String? = null,
    compact: Boolean = false,
    showIndex: Boolean = false,
    removeIcon: ImageVector = Icons.Default.Delete,
    removeContentDescription: String = "Quitar de la cola",
    onReorder: ((Int, Int) -> Unit)? = null
) {
    val currentOnRemove by rememberUpdatedState(onRemove)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                currentOnRemove()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        modifier = modifier,
        backgroundContent = {
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(ListDensity.corner)
                        )
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Icon(
                        imageVector = removeIcon,
                        contentDescription = removeContentDescription,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    ) {
        val backgroundColor = when {
            isCurrentPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            compact -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surface
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = backgroundColor,
                    shape = RoundedCornerShape(ListDensity.corner)
                )
        ) {
            QueueItemRow(
                item = item,
                isCurrentPlaying = isCurrentPlaying,
                onClick = onClick,
                onRemove = onRemove,
                showIndex = showIndex,
                index = index,
                removeIcon = removeIcon,
                removeContentDescription = removeContentDescription,
                trailingDuration = trailingDuration,
                compact = compact,
                reorderCount = queueSize,
                onReorder = onReorder
            )
        }
    }
}

@Composable
fun QueueLazyList(
    items: List<PlayableItem>,
    isCurrentPlaying: (index: Int, item: PlayableItem) -> Boolean,
    onSkipTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    emptyTitle: String = "La cola está vacía",
    emptySubtitle: String? = null,
    compact: Boolean = false,
    showIndex: Boolean = false,
    removeIcon: ImageVector = Icons.Default.Delete,
    removeContentDescription: String = "Quitar",
    trailingDuration: ((PlayableItem) -> String?)? = null,
    onReorder: ((Int, Int) -> Unit)? = null,
    onRemoveEntry: ((String) -> Unit)? = null
) {
    if (items.isEmpty()) {
        EmptyListHint(
            text = emptyTitle,
            subtitle = emptySubtitle,
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            modifier = modifier.fillMaxSize()
        )
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize()
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> queueRowKey(item) },
            contentType = { _, _ -> "queue_row" }
        ) { index, item ->
            val currentItems by rememberUpdatedState(items)
            val currentOnRemove by rememberUpdatedState(onRemove)
            val currentOnRemoveEntry by rememberUpdatedState(onRemoveEntry)
            DismissibleQueueItemRow(
                item = item,
                isCurrentPlaying = isCurrentPlaying(index, item),
                index = index,
                queueSize = items.size,
                onClick = { onSkipTo(index) },
                onRemove = {
                    val removeById = currentOnRemoveEntry
                    if (removeById != null) {
                        removeById(item.queueEntryId)
                    } else {
                        val targetIndex = currentItems.indexOfFirst { it.queueEntryId == item.queueEntryId }
                        if (targetIndex >= 0) {
                            currentOnRemove(targetIndex)
                        }
                    }
                },
                compact = compact,
                showIndex = showIndex,
                removeIcon = removeIcon,
                removeContentDescription = removeContentDescription,
                trailingDuration = trailingDuration?.invoke(item),
                onReorder = onReorder
            )
        }
    }
}
