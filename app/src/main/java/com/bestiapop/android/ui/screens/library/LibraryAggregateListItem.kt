package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.PlayShuffleIconPair
import com.bestiapop.android.ui.theme.ListDensity

/** Shared row chrome for artist / genre browse aggregates. */
@Composable
fun LibraryAggregateListItem(
    title: String,
    subtitle: String,
    artworkUri: String?,
    artworkCornerRadius: Dp,
    fallbackIcon: ImageVector,
    playDescription: String,
    shuffleDescription: String,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ListDensity.rowHorizontalPadding,
                vertical = ListDensity.rowVerticalPadding
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(ListDensity.corner),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(ListDensity.rowInnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkThumbnail(
                artworkUri = artworkUri,
                size = ListDensity.artworkChipRow,
                cornerRadius = artworkCornerRadius,
                fallbackIcon = fallbackIcon
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = ListDensity.titleStyle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = ListDensity.subtitleStyle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            PlayShuffleIconPair(
                onPlay = onPlay,
                onShuffle = onShuffle,
                playDescription = playDescription,
                shuffleDescription = shuffleDescription
            )
        }
    }
}
