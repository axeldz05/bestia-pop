package com.bestiapop.android.ui.screens.nowplaying

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.DownloadStateTrailing
import com.bestiapop.android.ui.components.findUiDownloadByTrack
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Stateful download button observing active download progress for remote streaming tracks.
 */
@Composable
fun NowPlayingRemoteDownloadButton(
    viewModel: MusicPlayerViewModel,
    remoteItem: PlayableItem.Remote,
    modifier: Modifier = Modifier
) {
    val download by remember(viewModel, remoteItem.artist, remoteItem.title) {
        viewModel.activeDownloads.map { list ->
            list.findUiDownloadByTrack(remoteItem.artist, remoteItem.title)
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    NowPlayingRemoteDownloadAction(
        download = download,
        onDownload = { viewModel.downloadRemoteItem(remoteItem) },
        onRetry = viewModel::retryActiveDownload,
        onCancel = viewModel::dismissActiveDownload,
        modifier = modifier
    )
}

/**
 * Stateless action widget presenting download progress, retry/cancel, or download CTA.
 */
@Composable
fun NowPlayingRemoteDownloadAction(
    download: ActiveDownload?,
    onDownload: () -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Spacer(modifier = Modifier.height(8.dp))
    DownloadStateTrailing(
        state = download?.state,
        percent = download?.progressPercent ?: 0,
        onRetry = download?.let { d -> { onRetry(d.id) } },
        onDismiss = download?.let { d -> { onCancel(d.id) } },
        successLabel = DownloadMessages.inLibrary,
        idleContent = {
            Button(onClick = onDownload, modifier = modifier) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Descargar ahora")
            }
        }
    )
}
