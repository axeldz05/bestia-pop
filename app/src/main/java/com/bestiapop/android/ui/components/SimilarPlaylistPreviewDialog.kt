package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.state.SimilarPlaylistPreviewState

@Composable
fun SimilarPlaylistPreviewDialog(
    state: SimilarPlaylistPreviewState,
    onDismiss: () -> Unit,
    onToggleItem: (String) -> Unit,
    onModeChange: (RadioMode) -> Unit,
    onPlaylistNameChange: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedCount = state.selectedItems.size
    val canConfirm = !state.loading && selectedCount > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                if (state.loading) {
                    MusicPlayerViewModel.RADIO_LOADING_LABEL
                } else {
                    "Similares (${state.items.size})"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.playlistName,
                    onValueChange = onPlaylistNameChange,
                    label = { Text("Nombre de playlist") },
                    singleLine = true,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RadioModeChip(
                        label = "Solo conocidos",
                        selected = state.mode == RadioMode.KNOWN,
                        enabled = !state.loading,
                        onClick = { onModeChange(RadioMode.KNOWN) }
                    )
                    RadioModeChip(
                        label = "Solo nuevos",
                        selected = state.mode == RadioMode.NEW,
                        enabled = !state.loading,
                        onClick = { onModeChange(RadioMode.NEW) }
                    )
                    RadioModeChip(
                        label = "Ambos",
                        selected = state.mode == RadioMode.BOTH,
                        enabled = !state.loading,
                        onClick = { onModeChange(RadioMode.BOTH) }
                    )
                }
                if (state.loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (state.items.isEmpty()) {
                    Text(
                        text = when {
                            state.failedOnline -> "No pude completar la búsqueda online. Probá de nuevo o usá solo conocidos."
                            else -> "No encontré canciones parecidas"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "$selectedCount seleccionadas · ${state.seedCount} seeds",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(
                            items = state.items,
                            key = { SimilarPlaylistPreviewState.previewKey(it) }
                        ) { item ->
                            val key = SimilarPlaylistPreviewState.previewKey(item)
                            val checked = key in state.selectedKeys
                            SimilarPreviewRow(
                                item = item,
                                checked = checked,
                                onToggle = { onToggleItem(key) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreatePlaylist, enabled = canConfirm) {
                Text("Crear playlist")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onEnqueue, enabled = canConfirm) {
                    Text("Encolar")
                }
                TextButton(onClick = onPlay, enabled = canConfirm) {
                    Text("Reproducir")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
            }
        }
    )
}

@Composable
private fun RadioModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) }
    )
}

@Composable
private fun SimilarPreviewRow(
    item: PlayableItem,
    checked: Boolean,
    onToggle: () -> Unit
) {
    TrackMetaRow(
        artworkUri = item.artworkUri,
        title = item.title,
        subtitle = joinMeta(
            item.artist,
            if (item is PlayableItem.Remote) "stream" else null,
            sep = " · "
        ),
        highlighted = checked,
        leading = {
            Checkbox(checked = checked, onCheckedChange = null)
        },
        onClick = onToggle
    )
}
