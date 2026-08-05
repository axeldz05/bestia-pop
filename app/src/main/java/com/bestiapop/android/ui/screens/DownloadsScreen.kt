package com.bestiapop.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ActiveDownloadRow

@Composable
fun DownloadsScreen(viewModel: MusicPlayerViewModel) {
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val catalogPreviewKey by viewModel.catalogPreviewKey.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val resolvingRemote by viewModel.resolvingRemote.collectAsState()
    val currentItem by viewModel.currentItem.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = "Descargas",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (activeDownloads.isNotEmpty()) {
                TextButton(onClick = { viewModel.dismissAllActiveDownloads() }) {
                    Text("Limpiar todo")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (activeDownloads.isEmpty()) {
            Text(
                text = "No hay descargas",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                textAlign = TextAlign.Center
            )
        } else {
            activeDownloads.forEach { download ->
                val track = download.currentTrack
                val previewKey = track?.let { viewModel.catalogPreviewKeyFor(it) }
                val isThisPreview = previewKey != null && catalogPreviewKey == previewKey
                ActiveDownloadRow(
                    download = download,
                    isPreviewPlaying = isThisPreview && isPlaying && currentItem is PlayableItem.Remote,
                    isPreviewResolving = isThisPreview && resolvingRemote,
                    onPreview = { viewModel.previewActiveDownload(download.id) },
                    onPlay = { viewModel.playActiveDownload(download.id) },
                    onRetry = { viewModel.retryActiveDownload(download.id) },
                    onCycle = { viewModel.cycleActiveDownload(download.id) },
                    onDismiss = { viewModel.dismissActiveDownload(download.id) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
