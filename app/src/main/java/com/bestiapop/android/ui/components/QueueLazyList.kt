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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.bestiapop.android.data.model.PlayableItem

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
    // mediaId plus an occurrence number instead of the position: the same track can sit in the queue
    // twice (hence the old `_$index`), but baking the index in changed the key of every row after an
    // edit, so reordering disposed and rebuilt them and the drag offset / scroll anchor were lost.
    val itemKeys = remember(items) {
        val seen = HashMap<String, Int>(items.size)
        items.map { item ->
            val occurrence = seen.getOrDefault(item.mediaId, 0) + 1
            seen[item.mediaId] = occurrence
            if (occurrence == 1) item.mediaId else "${item.mediaId}#$occurrence"
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize()
    ) {
        itemsIndexed(
            items = items,
            key = { index, _ -> itemKeys.getOrElse(index) { index } },
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
