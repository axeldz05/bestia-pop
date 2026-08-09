package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.domain.util.TrackMatchKeys

/** L1: circular progress + percent label. */
@Composable
fun DownloadProgressPercent(percent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** L1: "En cola" label. */
@Composable
fun DownloadQueuedLabel() {
    Text(
        text = "En cola",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    )
}

/** L1: outlined button with Refresh icon + label. */
@Composable
fun DownloadOutlinedActionButton(
    label: String,
    onClick: () -> Unit,
    contentDescription: String = label,
    horizontalPadding: Int = 8
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = horizontalPadding.dp, vertical = 4.dp)
    ) {
        Icon(
            Icons.Default.Refresh,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** L1: retry + cycle (search) + optional dismiss. */
@Composable
fun RetryCycleDismissActions(
    onRetry: () -> Unit,
    onCycle: () -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DownloadOutlinedActionButton(
            label = "Reintentar",
            onClick = onRetry,
            contentDescription = "Reintentar"
        )
        IconButton(onClick = onCycle) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Buscar otro",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Descartar",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** L1: preview play/pause/resolving IconButton. */
@Composable
fun PreviewPlayPauseButton(
    isResolving: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val playTint = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    }
    IconButton(onClick = onClick, enabled = enabled) {
        when {
            isResolving -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            isPlaying -> Icon(
                imageVector = Icons.Default.Pause,
                contentDescription = "Pausar preview",
                tint = MaterialTheme.colorScheme.primary
            )
            else -> Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Preview",
                tint = playTint
            )
        }
    }
}

/** L1: lookup a tracked download by artist+title match key. */
fun List<ActiveDownload>.findByTrack(artist: String, title: String): ActiveDownload? {
    val key = TrackMatchKeys.downloadIdFor(artist, title)
    if (key.isEmpty()) return null
    return find { it.id == key }
}

/** L2: trailing chrome for queued / progress / retry / download. NP omits cycle; catalog omits dismiss. */
@Composable
fun DownloadStateTrailing(
    state: CandidateDownloadState?,
    percent: Int = 0,
    onRetry: (() -> Unit)? = null,
    onCycle: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null
) {
    when (state) {
        CandidateDownloadState.QUEUED -> DownloadQueuedLabel()
        CandidateDownloadState.DOWNLOADING -> DownloadProgressPercent(percent)
        CandidateDownloadState.ERROR -> when {
            onRetry != null && onCycle != null -> RetryCycleDismissActions(
                onRetry = onRetry,
                onCycle = onCycle,
                onDismiss = onDismiss
            )
            onRetry != null -> DownloadOutlinedActionButton(
                label = "Reintentar",
                onClick = onRetry
            )
        }
        CandidateDownloadState.SUCCESS,
        CandidateDownloadState.IDLE, null -> {
            if (onDownload != null) {
                IconButton(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Descargar",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
