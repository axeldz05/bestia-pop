package com.bestiapop.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector

fun playPauseVector(isPlaying: Boolean): ImageVector =
    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow

fun playbackProgressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

/** L2: catalog/identify preview — tiny hint bar while duration is still unknown. */
fun previewProgressFraction(positionMs: Long, durationMs: Long): Float = when {
    durationMs > 0L -> playbackProgressFraction(positionMs, durationMs)
    positionMs > 0L -> 0.05f
    else -> 0f
}

data class PreviewFlags(
    val isThisPreview: Boolean,
    val isPlaying: Boolean,
    val isResolving: Boolean
)

fun previewFlags(
    catalogPreviewKey: String?,
    trackKey: String?,
    isPlaying: Boolean,
    resolving: Boolean
): PreviewFlags {
    val isThis = !catalogPreviewKey.isNullOrEmpty() && catalogPreviewKey == trackKey
    return PreviewFlags(
        isThisPreview = isThis,
        isPlaying = isThis && isPlaying,
        isResolving = isThis && resolving
    )
}
