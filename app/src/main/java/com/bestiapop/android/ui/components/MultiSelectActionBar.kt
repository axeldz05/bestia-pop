package com.bestiapop.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.theme.ListDensity

private val ActionIconSize = 22.dp
private val ChromeIconButtonSize = 32.dp
private val ActionSlotWidth = 64.dp
private val SimilarActionSlotWidth = 72.dp

@Composable
fun MultiSelectActionBar(
    selectedCount: Int,
    onPlaySelected: () -> Unit,
    onEnqueueSelected: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onIdentifySelected: () -> Unit,
    onSimilarSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = ListDensity.rowHorizontalPadding,
                vertical = ListDensity.rowVerticalPadding
            ),
        shape = RoundedCornerShape(ListDensity.corner),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ListDensity.rowInnerPadding,
                    vertical = ListDensity.rowVerticalPadding
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onClearSelection,
                        modifier = Modifier.size(ChromeIconButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancelar selección",
                            modifier = Modifier.size(ActionIconSize)
                        )
                    }
                    Text(
                        text = "$selectedCount seleccionados",
                        style = ListDensity.titleStyle,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(
                    onClick = onSelectAll,
                    contentPadding = PaddingValues(
                        horizontal = ListDensity.rowInnerPadding,
                        vertical = ListDensity.rowVerticalPadding
                    )
                ) {
                    Text("Seleccionar todo")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Top
            ) {
                MultiSelectAction(
                    icon = Icons.Default.PlayArrow,
                    label = "Play",
                    description = "Reproducir seleccionados",
                    onClick = onPlaySelected,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(ActionSlotWidth)
                )
                MultiSelectAction(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = "Cola",
                    description = "Agregar a la cola",
                    onClick = onEnqueueSelected,
                    modifier = Modifier.width(ActionSlotWidth)
                )
                MultiSelectAction(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = "Lista",
                    description = "Agregar a playlist",
                    onClick = onAddToPlaylist,
                    modifier = Modifier.width(ActionSlotWidth)
                )
                MultiSelectAction(
                    icon = Icons.Default.Radio,
                    label = "Similares",
                    description = "Generar playlist de similares",
                    onClick = onSimilarSelected,
                    modifier = Modifier.width(SimilarActionSlotWidth)
                )
                MultiSelectAction(
                    icon = Icons.Default.AutoFixHigh,
                    label = "ID",
                    description = "Identificar metadata",
                    onClick = onIdentifySelected,
                    modifier = Modifier.width(ActionSlotWidth)
                )
                MultiSelectAction(
                    icon = Icons.Default.Delete,
                    label = "Borrar",
                    description = "Eliminar seleccionados",
                    onClick = onDeleteSelected,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.width(ActionSlotWidth)
                )
            }
        }
    }
}

@Composable
private fun MultiSelectAction(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick, role = Role.Button)
            .padding(
                vertical = ListDensity.rowVerticalPadding,
                horizontal = 2.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(ActionIconSize)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

@Composable
fun PlaylistAdditionActionBar(
    playlistName: String,
    selectedCount: Int,
    onConfirmAddition: () -> Unit,
    onCancelAddition: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = ListDensity.rowHorizontalPadding,
                vertical = ListDensity.rowVerticalPadding
            ),
        shape = RoundedCornerShape(ListDensity.corner),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ListDensity.rowInnerPadding,
                    vertical = ListDensity.rowVerticalPadding
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onCancelAddition,
                    modifier = Modifier.size(ChromeIconButtonSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancelar",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(ActionIconSize)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "$selectedCount seleccionadas",
                    style = ListDensity.titleStyle.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onConfirmAddition,
                    enabled = selectedCount > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(ListDensity.corner),
                    contentPadding = PaddingValues(
                        horizontal = ListDensity.rowInnerPadding,
                        vertical = ListDensity.rowVerticalPadding + 2.dp
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(ActionIconSize)
                    )
                    Text(
                        text = "Añadir a $playlistName",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
