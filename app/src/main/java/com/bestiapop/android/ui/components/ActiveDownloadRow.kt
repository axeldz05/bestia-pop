package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.ui.theme.ListDensity

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
        shape = RoundedCornerShape(ListDensity.corner),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = ListDensity.rowVerticalPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ListDensity.rowInnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkThumbnail(
                artworkUri = download.artworkUri,
                size = ListDensity.artworkSong,
                cornerRadius = ListDensity.corner
            )

            Spacer(modifier = Modifier.width(10.dp))

            TrackTextColumn(
                title = download.displayLabel,
                subtitle = buildSubtitle(download),
                modifier = Modifier.weight(1f),
                titleStyle = MaterialTheme.typography.bodyMedium,
                titleWeight = FontWeight.SemiBold,
                subtitleColor = if (download.state == CandidateDownloadState.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                },
                maxSubtitleLines = 2
            )

            Spacer(modifier = Modifier.width(4.dp))

            DownloadStateTrailing(
                state = download.state,
                percent = download.progressPercent,
                onRetry = onRetry,
                onCycle = onCycle,
                onDismiss = onDismiss,
                onSuccessPlay = onPlay,
                idleContent = {
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
                        // A conflict row whose dialog was replaced by a later one is stranded in
                        // IDLE; without these, "Limpiar todo" was the only way to get rid of it.
                        DownloadOutlinedActionButton(
                            label = "Reintentar",
                            onClick = onRetry,
                            contentDescription = "Reintentar",
                            horizontalPadding = 10
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Descartar"
                            )
                        }
                    }
                }
            )
        }
    }
}

private fun buildSubtitle(download: ActiveDownload): String {
    val artist = download.artist.trim().takeIf { it.isNotBlank() }
    val sourceLabel = when (download.source) {
        ActiveDownloadSource.CATALOG -> "Catálogo"
        ActiveDownloadSource.LINK -> "Enlace"
        ActiveDownloadSource.SAVE_WHILE_LISTENING -> "Guardar al escuchar"
        ActiveDownloadSource.BATCH -> "Lote"
        ActiveDownloadSource.LB_IMPORT -> "Import Para Ti"
        ActiveDownloadSource.DISCOVER -> "Para Ti"
    }
    val status = downloadStateStatusLabel(
        state = download.state,
        progressMessage = download.progressMessage,
        errorMessage = download.errorMessage,
        successLabel = DownloadMessages.downloadedShort
    )
    return when (download.state) {
        CandidateDownloadState.ERROR ->
            status ?: joinMeta(artist, sourceLabel, "Error", sep = " · ")
        else -> joinMeta(artist, sourceLabel, status, sep = " · ")
    }
}
