package com.bestiapop.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.QueueLazyList

@Composable
fun QueueScreen(
    viewModel: MusicPlayerViewModel
) {
    val queue by viewModel.displayQueue.collectAsState()
    val currentItem by viewModel.currentItem.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = "Cola de Reproducción (${queue.size})",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        QueueLazyList(
            items = queue,
            isCurrentPlaying = { _, item -> currentItem?.mediaId == item.mediaId },
            onSkipTo = viewModel::skipToQueueIndex,
            onRemove = viewModel::removeFromQueue,
            emptyTitle = "La cola está vacía",
            emptySubtitle = "Reproducí una canción o seleccioná 'Añadir a la cola' desde la biblioteca.",
            removeIcon = Icons.Default.Delete,
            removeContentDescription = "Quitar",
            onReorder = viewModel::moveDisplayQueueItem
        )
    }
}
