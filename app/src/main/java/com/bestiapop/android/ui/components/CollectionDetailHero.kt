package com.bestiapop.android.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bestiapop.android.domain.util.HtmlSanitizer

/**
 * Shared hero header for collection details (Album, Custom Playlist, Generated Playlist).
 * Displays artwork, title, clickable/static subtitle, metadata indicator, and standard action buttons.
 */
@Composable
fun CollectionDetailHero(
    title: String,
    subtitle: String?,
    metadata: String?,
    artworkUri: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    artworkSize: Dp = 100.dp,
    artworkCornerRadius: Dp = 12.dp,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    onSubtitleClick: (() -> Unit)? = null,
    playEnabled: Boolean = true,
    shuffleEnabled: Boolean = true,
    actionButtons: @Composable RowScope.() -> Unit = {},
    bannerContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val cleanSubtitle = remember(subtitle) { HtmlSanitizer.stripHtml(subtitle) }
        var isExpanded by remember(cleanSubtitle) { mutableStateOf(false) }

        // Hero Info Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = if (isExpanded) Alignment.Top else Alignment.CenterVertically,
        ) {
            ArtworkThumbnail(
                artworkUri = artworkUri,
                size = artworkSize,
                cornerRadius = artworkCornerRadius,
                fallbackIcon = fallbackIcon,
                contentDescription = title,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!cleanSubtitle.isNullOrBlank()) {
                    val subtitleModifier =
                        if (onSubtitleClick != null) {
                            Modifier.clickable(onClick = onSubtitleClick)
                        } else {
                            Modifier
                                .animateContentSize()
                                .clickable { isExpanded = !isExpanded }
                        }
                    val maxSubtitleLines =
                        when {
                            onSubtitleClick != null -> 1
                            isExpanded -> Int.MAX_VALUE
                            else -> 2
                        }
                    val subtitleOverflow =
                        if (onSubtitleClick != null || !isExpanded) {
                            TextOverflow.Ellipsis
                        } else {
                            TextOverflow.Clip
                        }
                    Text(
                        text = cleanSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = maxSubtitleLines,
                        overflow = subtitleOverflow,
                        modifier = subtitleModifier,
                    )
                }
                if (!metadata.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onPlay,
                enabled = playEnabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Play")
            }

            FilledTonalButton(
                onClick = onShuffle,
                enabled = shuffleEnabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            }

            actionButtons()
        }

        if (bannerContent != null) {
            Spacer(modifier = Modifier.height(8.dp))
            bannerContent()
        }
    }
}

/**
 * Shared hero header for artist details (Library and Discover).
 * Displays an 88.dp circular avatar (or initial letter fallback), artist name,
 * summary metadata text, and action buttons for Play, Shuffle, and Radio.
 */
@Composable
fun ArtistDetailHero(
    artistName: String,
    summary: String,
    coverUrl: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onStartRadio: () -> Unit,
    modifier: Modifier = Modifier,
    playEnabled: Boolean = true,
    shuffleEnabled: Boolean = true,
    radioEnabled: Boolean = true,
    actionButtons: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (!coverUrl.isNullOrBlank()) {
                ArtworkThumbnail(
                    artworkUri = coverUrl,
                    contentDescription = artistName,
                    size = 88.dp,
                    cornerRadius = 44.dp,
                    modifier = Modifier.clip(CircleShape),
                )
            } else {
                Text(
                    text = artistName.firstOrNull()?.uppercase().orEmpty(),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artistName,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onPlay,
                    enabled = playEnabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play")
                }

                FilledTonalButton(
                    onClick = onShuffle,
                    enabled = shuffleEnabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                }

                OutlinedButton(
                    onClick = onStartRadio,
                    enabled = radioEnabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Radio")
                }

                actionButtons?.invoke(this)
            }
        }
    }
}
