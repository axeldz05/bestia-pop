package com.bestiapop.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row

/** L1: play IconButton with primary tint. */
@Composable
fun PlayIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

/** L1: shuffle IconButton. */
@Composable
fun ShuffleIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Default.Shuffle,
            contentDescription = contentDescription
        )
    }
}

/** L2: play + shuffle icon pair. */
@Composable
fun PlayShuffleIconPair(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    playDescription: String,
    shuffleDescription: String,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier) {
        PlayIconButton(onClick = onPlay, contentDescription = playDescription)
        ShuffleIconButton(onClick = onShuffle, contentDescription = shuffleDescription)
    }
}
