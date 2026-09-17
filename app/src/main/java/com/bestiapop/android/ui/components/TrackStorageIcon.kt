package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.isRemote

/**
 * Level 1: Visual indicator representing whether a track is saved locally on device or streaming from the cloud.
 */
@Composable
fun TrackStorageIcon(
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    streamingTint: Color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
    savedTint: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
) {
    if (isStreaming) {
        Icon(
            imageVector = Icons.Default.Cloud,
            contentDescription = "Streaming",
            tint = streamingTint,
            modifier = modifier.size(size)
        )
    }
}

/**
 * Level 2: TrackStorageIcon overload for a [Song].
 */
@Composable
fun TrackStorageIcon(
    song: Song,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    TrackStorageIcon(isStreaming = song.isRemote, modifier = modifier, size = size)
}

/**
 * Level 2: TrackStorageIcon overload for a [PlayableItem].
 */
@Composable
fun TrackStorageIcon(
    item: PlayableItem,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    val isStreaming = item is PlayableItem.Remote || (item is PlayableItem.Local && item.song.isRemote)
    TrackStorageIcon(isStreaming = isStreaming, modifier = modifier, size = size)
}
