package com.bestiapop.android.ui.screens.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.ui.components.DismissibleQueueItemRow
import com.bestiapop.android.ui.components.formatDuration

/**
 * Encabezado de la cola de reproducción con contador de canciones, indicador de radio y acción para limpiar.
 */
@Composable
fun NowPlayingQueueHeader(
    queueSize: Int,
    isRadioActive: Boolean,
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "A continuación",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "$queueSize",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isRadioActive) {
                Text(
                    text = "Radio activa",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (queueSize > 0) {
                TextButton(
                    onClick = onClearQueue,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Limpiar",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Emite los elementos interactivos de la cola de reproducción en un [LazyListScope].
 */
fun LazyListScope.nowPlayingQueueItems(
    queueItems: List<PlayableItem>,
    currentQueueIndex: Int,
    onItemClick: (Int) -> Unit,
    onRemoveItem: (String) -> Unit,
    onReorder: (Int, Int) -> Unit
) {
    itemsIndexed(
        items = queueItems,
        key = { _, qItem -> qItem.queueEntryId },
        contentType = { _, _ -> "queue_item" }
    ) { index, qItem ->
        val formattedDuration = remember(qItem.durationMs) {
            formatDuration(qItem.durationMs)
        }
        DismissibleQueueItemRow(
            item = qItem,
            isCurrentPlaying = (index == currentQueueIndex),
            index = index,
            queueSize = queueItems.size,
            onClick = { onItemClick(index) },
            onRemove = { onRemoveItem(qItem.queueEntryId) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            trailingDuration = formattedDuration,
            compact = true,
            showIndex = true,
            onReorder = onReorder
        )
    }
}
