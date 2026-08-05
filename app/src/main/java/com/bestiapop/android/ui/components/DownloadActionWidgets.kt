package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
