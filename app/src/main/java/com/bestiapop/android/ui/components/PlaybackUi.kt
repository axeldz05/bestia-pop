package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

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

@Composable
fun PlaybackScrubber(
    durationMs: Long,
    positionMsFlow: StateFlow<Long>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdAtZero: Boolean = false
) {
    val livePositionMs by positionMsFlow.collectAsState()
    val positionMs = if (holdAtZero) 0L else livePositionMs
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    val maxDuration = durationMs.toFloat().coerceAtLeast(1f)
    val displayPosition = if (isDragging) dragPosition.toLong() else positionMs
    val sliderValue = if (isDragging) dragPosition else positionMs.toFloat().coerceIn(0f, maxDuration)

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = sliderValue,
            onValueChange = { newValue ->
                if (!enabled) return@Slider
                isDragging = true
                dragPosition = newValue
            },
            onValueChangeFinished = {
                isDragging = false
                if (enabled) onSeek(dragPosition.toLong())
            },
            valueRange = 0f..maxDuration,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(displayPosition),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}
