package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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

/** L2: labeled play + shuffle buttons (playlist / library headers). */
@Composable
fun LabeledPlayShuffleButtons(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    enabled: Boolean = true,
    playLabel: String = "Reproducir",
    shuffleLabel: String = "Aleatorio",
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onPlay,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp),
            contentPadding = contentPadding,
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 0.dp)
        ) {
            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = playLabel,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedButton(
            onClick = onShuffle,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            contentPadding = contentPadding,
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 0.dp)
        ) {
            Icon(imageVector = Icons.Default.Shuffle, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = shuffleLabel,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
