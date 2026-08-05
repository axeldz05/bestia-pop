package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState

@Composable
fun ActiveDownloadRow(
    download: ActiveDownload,
    isPreviewPlaying: Boolean,
    isPreviewResolving: Boolean,
    onPreview: () -> Unit,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onCycle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkThumbnail(
                artworkUri = download.artworkUrl,
                size = 48.dp,
                cornerRadius = 8.dp
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.displayTitle.ifBlank { "Descarga" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = buildSubtitle(download),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (download.state == CandidateDownloadState.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    }
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            when (download.state) {
                CandidateDownloadState.QUEUED -> DownloadQueuedLabel()
                CandidateDownloadState.DOWNLOADING -> DownloadProgressPercent(download.progressPercent)
                CandidateDownloadState.SUCCESS -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPlay) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Reproducir",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Limpiar",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                CandidateDownloadState.ERROR -> {
                    RetryCycleDismissActions(
                        onRetry = onRetry,
                        onCycle = onCycle,
                        onDismiss = onDismiss
                    )
                }
                CandidateDownloadState.IDLE -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        PreviewPlayPauseButton(
                            isResolving = isPreviewResolving,
                            isPlaying = isPreviewPlaying,
                            onClick = onPreview
                        )
                        DownloadOutlinedActionButton(
                            label = "Buscar otro",
                            onClick = onCycle,
                            contentDescription = "Buscar otro",
                            horizontalPadding = 10
                        )
                    }
                }
            }
        }
    }
}

private fun buildSubtitle(download: ActiveDownload): String {
    val artist = download.displayArtist.trim()
    val sourceLabel = when (download.source) {
        ActiveDownloadSource.CATALOG -> "Catálogo"
        ActiveDownloadSource.LINK -> "Enlace"
        ActiveDownloadSource.SAVE_WHILE_LISTENING -> "Guardar al escuchar"
        ActiveDownloadSource.BATCH -> "Lote"
        ActiveDownloadSource.LB_IMPORT -> "Import Para Ti"
        ActiveDownloadSource.DISCOVER -> "Para Ti"
    }
    return when (download.state) {
        CandidateDownloadState.QUEUED -> {
            listOfNotNull(artist.takeIf { it.isNotBlank() }, sourceLabel, "En cola").joinToString(" · ")
        }
        CandidateDownloadState.DOWNLOADING -> {
            val msg = download.progressMessage?.takeIf { it.isNotBlank() } ?: "Descargando…"
            listOfNotNull(artist.takeIf { it.isNotBlank() }, sourceLabel, msg).joinToString(" · ")
        }
        CandidateDownloadState.SUCCESS -> {
            listOfNotNull(artist.takeIf { it.isNotBlank() }, sourceLabel, "Descargada").joinToString(" · ")
        }
        CandidateDownloadState.ERROR -> {
            download.errorMessage?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(artist.takeIf { it.isNotBlank() }, sourceLabel, "Error").joinToString(" · ")
        }
        CandidateDownloadState.IDLE -> listOfNotNull(artist.takeIf { it.isNotBlank() }, sourceLabel).joinToString(" · ")
    }
}
