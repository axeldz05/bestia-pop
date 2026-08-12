package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.bestiapop.android.data.model.PlayableItem

internal fun queueRowKey(item: PlayableItem): String = item.queueEntryId

internal fun focusedQueueIndex(
    items: List<PlayableItem>,
    currentQueueEntryId: String?
): Int {
    if (currentQueueEntryId == null) return -1
    return items.indexOfFirst { it.queueEntryId == currentQueueEntryId }
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
    onReorder: ((Int, Int) -> Unit)? = null
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
            QueueItemRow(
                item = item,
                isCurrentPlaying = isCurrentPlaying(index, item),
                onClick = { onSkipTo(index) },
                onRemove = { onRemove(index) },
                showIndex = showIndex,
                index = index,
                removeIcon = removeIcon,
                removeContentDescription = removeContentDescription,
                trailingDuration = trailingDuration?.invoke(item),
                compact = compact,
                reorderCount = items.size,
                onReorder = onReorder
            )
        }
    }
}
