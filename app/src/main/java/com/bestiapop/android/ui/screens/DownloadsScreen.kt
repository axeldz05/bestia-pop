package com.bestiapop.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.isFailed
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ActiveDownloadRow
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.previewFlags

@Composable
fun DownloadsScreen(viewModel: MusicPlayerViewModel) {
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val downloadSettings by viewModel.downloadSettings.collectAsState()
    val catalogPreviewKey by viewModel.catalogPreviewKey.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val resolvingRemote by viewModel.resolvingRemote.collectAsState()
    val currentItem by viewModel.currentItem.collectAsState()
    val backgroundExecutionStatus by viewModel.backgroundExecutionStatus.collectAsState()

    val totalBytes = downloadSettings.totalMeteredBytes + downloadSettings.totalUnmeteredBytes
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DownloadsHeader(
            downloads = activeDownloads,
            onResumeAll = viewModel::resumeAllDownloads,
            onClearAll = viewModel::dismissAllActiveDownloads
        )

        if (backgroundExecutionStatus.blocksBackgroundPlayback) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "Descargas limitadas en segundo plano",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = "Android marcó la app como restringida. Las transferencias pueden pausarse al bloquear el celular.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = viewModel::openPlaybackSettings) {
                        Text("Revisar ajustes")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Guardando en: ${StorageUtils.userVisibleMusicDirLabel()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        if (totalBytes > 0L) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Con límite de datos: ${StorageUtils.formatByteCount(downloadSettings.totalMeteredBytes)}" +
                    " · Sin límite: ${StorageUtils.formatByteCount(downloadSettings.totalUnmeteredBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
        TextButton(
            onClick = { viewModel.openDownloadSettings() },
            modifier = Modifier.align(Alignment.Start)
        ) {
            Text("Ajustes de descarga")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (activeDownloads.isEmpty()) {
            EmptyListHint(
                text = "No hay descargas",
                subtitle = "Buscá música online y descargá desde Añadir música o el catálogo.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        } else {
            activeDownloads.forEach { download ->
                val track = download.currentTrack
                val flags = previewFlags(
                    catalogPreviewKey,
                    track?.let { viewModel.catalogPreviewKeyFor(it) },
                    isPlaying && currentItem is PlayableItem.Remote,
                    resolvingRemote
                )
                ActiveDownloadRow(
                    download = download,
                    isPreviewPlaying = flags.isPlaying,
                    isPreviewResolving = flags.isResolving,
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

@Composable
internal fun DownloadsHeader(
    downloads: List<com.bestiapop.android.data.model.ActiveDownload>,
    onResumeAll: () -> Unit,
    onClearAll: () -> Unit
) {
    val hasRetryable = downloads.any {
        it.state.isFailed && it.currentTrack != null
    }
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
        if (hasRetryable) {
            TextButton(onClick = onResumeAll) {
                Text("Reanudar todo")
            }
        }
        if (downloads.isNotEmpty()) {
            TextButton(onClick = onClearAll) {
                Text("Limpiar todo")
            }
        }
    }
}
