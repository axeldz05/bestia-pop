package com.bestiapop.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.ColorSchemeData

@Composable
fun ColorPickerDialog(
    initialColors: ColorSchemeData,
    onDismiss: () -> Unit,
    onConfirm: (ColorSchemeData) -> Unit
) {
    var primaryColor by remember { mutableStateOf(Color(initialColors.primary)) }
    var backgroundColor by remember { mutableStateOf(Color(initialColors.background)) }
    var surfaceColor by remember { mutableStateOf(Color(initialColors.surface)) }
    var accentColor by remember { mutableStateOf(Color(initialColors.accent)) }

    var activeTarget by remember { mutableStateOf("Primary") }

    val presetSwatches = listOf(
        Color(0xFF9D4EDD), Color(0xFFFF9E00), Color(0xFF00F5D4), Color(0xFFE63946),
        Color(0xFF457B9D), Color(0xFF2A9D8F), Color(0xFFF4A261), Color(0xFFE76F51),
        Color(0xFF0F0C1B), Color(0xFF000000), Color(0xFF1A0B2E), Color(0xFFF6F8FA)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Personalizar Colores del Tema",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Seleccioná el elemento a modificar:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TargetChip("Primary", primaryColor, activeTarget == "Primary") { activeTarget = "Primary" }
                    TargetChip("Fondo", backgroundColor, activeTarget == "Fondo") { activeTarget = "Fondo" }
                    TargetChip("Superficie", surfaceColor, activeTarget == "Superficie") { activeTarget = "Superficie" }
                    TargetChip("Acento", accentColor, activeTarget == "Acento") { activeTarget = "Acento" }
                }

                Spacer(modifier = Modifier.height(20.dp))

                val currentColor = when (activeTarget) {
                    "Primary" -> primaryColor
                    "Fondo" -> backgroundColor
                    "Superficie" -> surfaceColor
                    else -> accentColor
                }

                Text(
                    text = "Seleccionar Color ($activeTarget):",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(presetSwatches) { swatch ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(
                                    width = if (currentColor == swatch) 3.dp else 1.dp,
                                    color = if (currentColor == swatch) MaterialTheme.colorScheme.primary else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable {
                                    when (activeTarget) {
                                        "Primary" -> primaryColor = swatch
                                        "Fondo" -> backgroundColor = swatch
                                        "Superficie" -> surfaceColor = swatch
                                        "Acento" -> accentColor = swatch
                                    }
                                }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val updatedData = ColorSchemeData(
                        primary = primaryColor.toArgb().toLong(),
                        onPrimary = 0xFFFFFFFF,
                        secondary = accentColor.toArgb().toLong(),
                        background = backgroundColor.toArgb().toLong(),
                        surface = surfaceColor.toArgb().toLong(),
                        surfaceVariant = surfaceColor.toArgb().toLong(),
                        accent = accentColor.toArgb().toLong()
                    )
                    onConfirm(updatedData)
                }
            ) {
                Text("Guardar Tema")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun TargetChip(
    label: String,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
