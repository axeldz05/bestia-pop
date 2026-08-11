package com.bestiapop.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.bestiapop.android.data.model.ActiveDownload
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.theme.ListDensity

/** L1: placeholder row for a remote/unmatched track (stream + optional download). */
@Composable
fun RemoteTrackPlaceholderRow(
    title: String,
    artist: String,
    badge: String,
    leadingIcon: ImageVector,
    highlighted: Boolean,
    onClick: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    download: ActiveDownload? = null,
    onRetry: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (onClick == null && onDownload == null) 0.55f else 0.85f)
            .padding(
                horizontal = ListDensity.rowHorizontalPadding,
                vertical = ListDensity.rowVerticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(ListDensity.rowInnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(ListDensity.artworkSong)
                    .clip(RoundedCornerShape(ListDensity.corner))
                    .background(
                        if (highlighted) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (highlighted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                TrackTextColumn(
                    title = title,
                    subtitle = artist,
                    titleStyle = MaterialTheme.typography.titleSmall,
                    titleWeight = FontWeight.SemiBold
                )
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (onClick == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                    }
                )
            }
        }
        if (onDownload != null || download != null) {
            DownloadStateTrailing(
                state = download?.state,
                percent = download?.progressPercent ?: 0,
                onRetry = onRetry,
                onDownload = onDownload
            )
        }
    }
}
