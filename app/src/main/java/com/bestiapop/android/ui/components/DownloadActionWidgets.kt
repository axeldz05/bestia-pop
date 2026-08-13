package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import com.bestiapop.android.data.model.DownloadMessages
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
        text = DownloadMessages.queued,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    )
}

/** L1: success check + short label (catalog "Listo"). */
@Composable
fun DownloadSuccessReadyLabel(label: String = "Listo") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Descargado",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
    }
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

/** L1 UI lookup by artist+title (claim ownership remains in ProcessDownloadCoordinator). */
fun List<ActiveDownload>.findUiDownloadByTrack(artist: String, title: String): ActiveDownload? {
    val key = TrackMatchKeys.downloadIdFor(artist, title)
    if (key.isEmpty()) return null
    val variants = TrackMatchKeys.downloadIdVariantsFor(artist, title)
    find { it.id in variants }?.let { return it }
    return find { download ->
        val displayTitle = download.titleOverride?.takeIf { it.isNotBlank() } ?: download.title
        TrackMatchKeys.downloadIdFor(download.artist, displayTitle) == key ||
            TrackMatchKeys.downloadIdFor(download.artist, download.title) == key
    }
}

/**
 * L1: status fragment for subtitles (queued / downloading / success / error).
 * Callers prepend artist / source as needed.
 */
fun downloadStateStatusLabel(
    state: CandidateDownloadState,
    progressMessage: String? = null,
    errorMessage: String? = null,
    successLabel: String = DownloadMessages.downloadedShort,
    queuedLabel: String = DownloadMessages.queued,
    downloadingFallback: String = DownloadMessages.downloadingEllipsis,
    idleLabel: String? = null
): String? = when (state) {
    CandidateDownloadState.QUEUED -> queuedLabel
    CandidateDownloadState.DOWNLOADING ->
        progressMessage?.takeIf { it.isNotBlank() } ?: downloadingFallback
    CandidateDownloadState.SUCCESS -> successLabel
    CandidateDownloadState.ERROR -> errorMessage?.takeIf { it.isNotBlank() }
    CandidateDownloadState.IDLE -> idleLabel
}

/**
 * L1: in-flight status plus a cancel button. Queued and downloading rows had no way out, so a job
 * that never finished stayed forever — and since the enqueue gate refuses a track that is already
 * queued or downloading, that one stuck row blocked every later attempt at the same song.
 */
@Composable
private fun DownloadInFlightActions(
    onDismiss: (() -> Unit)?,
    status: @Composable () -> Unit
) {
    if (onDismiss == null) {
        status()
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        status()
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancelar descarga",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/** L2: trailing chrome for queued / progress / retry / download / success. */
@Composable
fun DownloadStateTrailing(
    state: CandidateDownloadState?,
    percent: Int = 0,
    onRetry: (() -> Unit)? = null,
    onCycle: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onSuccessPlay: (() -> Unit)? = null,
    successLabel: String? = null,
    successContent: (@Composable () -> Unit)? = null,
    idleContent: (@Composable () -> Unit)? = null
) {
    when (state) {
        CandidateDownloadState.QUEUED -> DownloadInFlightActions(onDismiss) { DownloadQueuedLabel() }
        CandidateDownloadState.DOWNLOADING ->
            DownloadInFlightActions(onDismiss) { DownloadProgressPercent(percent) }
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
        CandidateDownloadState.SUCCESS -> when {
            successContent != null -> successContent()
            onSuccessPlay != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSuccessPlay) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Reproducir",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Limpiar",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            successLabel != null -> Text(
                text = successLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            onDownload != null -> IconButton(onClick = onDownload) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Descargar",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        CandidateDownloadState.IDLE, null -> {
            if (idleContent != null) {
                idleContent()
            } else if (onDownload != null) {
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
