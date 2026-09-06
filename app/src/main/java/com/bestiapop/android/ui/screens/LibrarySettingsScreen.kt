package com.bestiapop.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.preferences.FastScrollSide
import com.bestiapop.android.data.preferences.LibraryBlobConfig
import com.bestiapop.android.data.preferences.LibraryBlobsSettings
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ReorderDragModifiers
import com.bestiapop.android.ui.components.rememberVerticalReorderDrag
import com.bestiapop.android.ui.components.SettingsScrollColumn
import com.bestiapop.android.ui.components.SettingsSwitchRow
import com.bestiapop.android.ui.screens.library.chipLabel

@Composable
fun LibrarySettingsScreen(viewModel: MusicPlayerViewModel) {
    val fastScrollSettings by viewModel.fastScrollSettings.collectAsStateWithLifecycle()
    val libraryBlobsSettings by viewModel.libraryBlobsSettings.collectAsStateWithLifecycle()

    SettingsScrollColumn(
        intro = "Personalizá las categorías de tu biblioteca, su orden y el desplazamiento rápido."
    ) {
        Text(
            text = "Categorías de biblioteca (Blobs)",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Elegí qué secciones mostrar en la biblioteca y arrastrá para ordenarlas. La primera categoría activa se usará como principal al abrir la biblioteca.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))

        val blobItems = libraryBlobsSettings.items
        val enabledCount = blobItems.count { it.enabled }
        val primaryFilter = libraryBlobsSettings.primaryFilter

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                blobItems.forEachIndexed { index, blobConfig ->
                    val drag = rememberVerticalReorderDrag(
                        index = index,
                        reorderCount = blobItems.size,
                        enabled = true,
                        onReorder = { from, to ->
                            val updated = blobItems.toMutableList()
                            val moved = updated.removeAt(from)
                            updated.add(to, moved)
                            viewModel.setLibraryBlobsSettings(LibraryBlobsSettings(items = updated))
                        }
                    )
                    LibraryBlobReorderRow(
                        config = blobConfig,
                        isPrimary = blobConfig.enabled && blobConfig.filter == primaryFilter,
                        canDisable = !blobConfig.enabled || enabledCount > 1,
                        onToggle = { isEnabled ->
                            val updated = blobItems.map {
                                if (it.filter == blobConfig.filter) it.copy(enabled = isEnabled) else it
                            }
                            viewModel.setLibraryBlobsSettings(LibraryBlobsSettings(items = updated))
                        },
                        drag = drag
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        SettingsSwitchRow(
            title = "Scroll vertical rápido (estilo Niagara)",
            checked = fastScrollSettings.enabled,
            onCheckedChange = { viewModel.setFastScrollEnabled(it) },
            onSubtitle = "Activo — deslizá el pulgar por el lateral para saltar entre letras y categorías",
            offSubtitle = "Desactivado — usa solo el desplazamiento vertical habitual"
        )

        if (fastScrollSettings.enabled) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Posición del scroll vertical",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Elegí en qué borde de la pantalla mostrar el riel con las letras del abecedario.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FastScrollSideCard(
                    title = "Izquierda",
                    subtitle = "Borde izquierdo (zurdos)",
                    icon = Icons.AutoMirrored.Filled.FormatAlignLeft,
                    selected = fastScrollSettings.side == FastScrollSide.LEFT,
                    onClick = { viewModel.setFastScrollSide(FastScrollSide.LEFT) },
                    modifier = Modifier.weight(1f)
                )

                FastScrollSideCard(
                    title = "Derecha",
                    subtitle = "Borde derecho (diestros)",
                    icon = Icons.AutoMirrored.Filled.FormatAlignRight,
                    selected = fastScrollSettings.side == FastScrollSide.RIGHT,
                    onClick = { viewModel.setFastScrollSide(FastScrollSide.RIGHT) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FastScrollSideCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }
        ),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        },
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Seleccionado",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LibraryBlobReorderRow(
    config: LibraryBlobConfig,
    isPrimary: Boolean,
    canDisable: Boolean,
    onToggle: (Boolean) -> Unit,
    drag: ReorderDragModifiers
) {
    Row(
        modifier = drag.rowModifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (drag.handleModifier != null) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Reordenar categoría",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = drag.handleModifier
                    .size(36.dp)
                    .padding(6.dp)
            )
        } else {
            Spacer(modifier = Modifier.size(36.dp))
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = config.filter.chipLabel(),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Medium
                ),
                color = if (config.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )

            if (isPrimary) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "Principal",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Switch(
            checked = config.enabled,
            onCheckedChange = onToggle,
            enabled = canDisable
        )
    }
}
