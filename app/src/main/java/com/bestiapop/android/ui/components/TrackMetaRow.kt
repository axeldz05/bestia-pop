package com.bestiapop.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.ui.theme.ListDensity

fun joinMeta(first: String?, second: String?, sep: String = " • "): String {
    val f = first?.trim()?.takeIf { it.isNotEmpty() }
    val s = second?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        f == null -> s ?: ""
        s == null -> f
        else -> "$f$sep$s"
    }
}

fun joinMeta(first: String?, second: String?, third: String?, sep: String = " • "): String {
    val f = first?.trim()?.takeIf { it.isNotEmpty() }
    val s = second?.trim()?.takeIf { it.isNotEmpty() }
    val t = third?.trim()?.takeIf { it.isNotEmpty() }
    if (f == null && s == null && t == null) return ""
    val sb = java.lang.StringBuilder()
    if (f != null) sb.append(f)
    if (s != null) {
        if (sb.isNotEmpty()) sb.append(sep)
        sb.append(s)
    }
    if (t != null) {
        if (sb.isNotEmpty()) sb.append(sep)
        sb.append(t)
    }
    return sb.toString()
}

fun joinMeta(vararg parts: String?, sep: String = " • "): String {
    if (parts.isEmpty()) return ""
    val sb = java.lang.StringBuilder()
    for (part in parts) {
        val trimmed = part?.trim()
        if (!trimmed.isNullOrEmpty()) {
            if (sb.isNotEmpty()) sb.append(sep)
            sb.append(trimmed)
        }
    }
    return sb.toString()
}

fun TrackMeta.artistAlbumLabel(sep: String = " • "): String = joinMeta(artist, album, sep = sep)

data class PlayingRowColors(
    val background: Color,
    val title: Color,
    val titleWeight: FontWeight
)

@Composable
fun playingRowColors(highlighted: Boolean, selected: Boolean = false): PlayingRowColors {
    val background = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else -> Color.Transparent
    }
    val title = if (highlighted || selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val weight = if (highlighted || selected) FontWeight.Bold else FontWeight.Medium
    return PlayingRowColors(background = background, title = title, titleWeight = weight)
}

@Composable
fun playingTitleStyle(highlighted: Boolean): Pair<Color, FontWeight> {
    val colors = playingRowColors(highlighted)
    return colors.title to colors.titleWeight
}

@Composable
fun TrackTextColumn(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    titleWeight: FontWeight = FontWeight.Medium,
    titleStyle: TextStyle = ListDensity.titleStyle,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    maxTitleLines: Int = 1,
    maxSubtitleLines: Int = 1
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = titleStyle,
            fontWeight = titleWeight,
            color = titleColor,
            maxLines = maxTitleLines,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = ListDensity.subtitleStyle,
                color = subtitleColor,
                maxLines = maxSubtitleLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TrackMetaRow(
    artworkUri: String?,
    title: String,
    subtitle: String,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    artworkSize: Dp = ListDensity.artworkSong,
    leading: @Composable (RowScope.() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val colors = playingRowColors(highlighted)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ListDensity.corner))
            .background(colors.background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke(this)
        ArtworkThumbnail(
            artworkUri = artworkUri,
            size = artworkSize,
            contentDescription = title
        )
        Spacer(modifier = Modifier.width(12.dp))
        TrackTextColumn(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
            titleColor = colors.title,
            titleWeight = colors.titleWeight
        )
        trailing?.invoke(this)
    }
}
