package com.bestiapop.android.ui.components

import androidx.compose.foundation.background
import androidx.core.graphics.toColorInt
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.bestiapop.android.data.model.ColorSchemeData
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.theme.ThemeHarmonizer
import kotlin.math.roundToInt

private class ColorHslState(initialColor: Color) {
    private val initialHsl = ThemeHarmonizer.rgbToHsl(initialColor)
    var hue by mutableFloatStateOf(initialHsl[0])
    var saturation by mutableFloatStateOf(
        if (initialHsl[1] == 0f && (initialHsl[2] <= 0.005f || initialHsl[2] >= 0.995f)) 0.8f else initialHsl[1]
    )
    var lightness by mutableFloatStateOf(initialHsl[2])

    val color: Color
        get() = ThemeHarmonizer.hslToColor(hue, saturation, lightness)

    fun setColor(newColor: Color) {
        val hsl = ThemeHarmonizer.rgbToHsl(newColor)
        val l = hsl[2]
        if (l <= 0.005f || l >= 0.995f) {
            // Extremos absolutos (0% o 100% brillo): solo se actualiza brillo, conservando tono y saturación
            lightness = l
        } else if (hsl[1] <= 0.005f) {
            lightness = l
            saturation = 0f
        } else {
            hue = hsl[0]
            saturation = hsl[1]
            lightness = hsl[2]
        }
    }
}

enum class ThemeColorTarget(val title: String) {
    PRIMARY("Primario"),
    BACKGROUND("Fondo"),
    SURFACE("Superficie"),
    ACCENT("Acento"),
    SECONDARY("Secundario")
}

@Composable
fun ThemeEditorDialog(
    initialColors: ColorSchemeData,
    sampleSongs: List<Song> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (ColorSchemeData) -> Unit
) {
    val primaryState = remember { ColorHslState(Color(initialColors.primary)) }
    val secondaryState = remember { ColorHslState(Color(initialColors.secondary)) }
    val backgroundState = remember { ColorHslState(Color(initialColors.background)) }
    val surfaceState = remember { ColorHslState(Color(initialColors.surface)) }
    val accentState = remember { ColorHslState(Color(initialColors.accent)) }

    var activeTarget by remember { mutableStateOf(ThemeColorTarget.PRIMARY) }
    var isDark by remember { mutableStateOf(true) }

    val activeState = when (activeTarget) {
        ThemeColorTarget.PRIMARY -> primaryState
        ThemeColorTarget.BACKGROUND -> backgroundState
        ThemeColorTarget.SURFACE -> surfaceState
        ThemeColorTarget.ACCENT -> accentState
        ThemeColorTarget.SECONDARY -> secondaryState
    }

    // Live Draft Scheme for Preview
    val draftData = remember(
        primaryState.color,
        secondaryState.color,
        backgroundState.color,
        surfaceState.color,
        accentState.color
    ) {
        ColorSchemeData(
            primary = primaryState.color.toArgb().toLong(),
            onPrimary = ThemeHarmonizer.bestOnColor(primaryState.color).toArgb().toLong(),
            secondary = secondaryState.color.toArgb().toLong(),
            background = backgroundState.color.toArgb().toLong(),
            surface = surfaceState.color.toArgb().toLong(),
            surfaceVariant = surfaceState.color.toArgb().toLong(),
            accent = accentState.color.toArgb().toLong()
        )
    }

    val previewColorScheme = remember(draftData, isDark) {
        ThemeHarmonizer.toMaterialColorScheme(draftData, isDark)
    }

    val contrastRatio = remember(primaryState.color, backgroundState.color) {
        ThemeHarmonizer.calculateContrastRatio(primaryState.color, backgroundState.color)
    }

    val curatedSwatches = remember(activeTarget) {
        when (activeTarget) {
            ThemeColorTarget.PRIMARY -> listOf(
                Color(0xFF9D4EDD), Color(0xFF00F5D4), Color(0xFFFF9E00), Color(0xFFE63946),
                Color(0xFF3A86FF), Color(0xFF2EC4B6), Color(0xFFFF007F), Color(0xFF10B981),
                Color(0xFFBB86FC), Color(0xFF6200EE), Color(0xFFFF6B6B), Color(0xFF03DAC6)
            )
            ThemeColorTarget.BACKGROUND -> listOf(
                Color(0xFF0F0C1B), Color(0xFF000000), Color(0xFF121212), Color(0xFF1A0B2E),
                Color(0xFF05050A), Color(0xFF0A0F1D), Color(0xFF14141E), Color(0xFF1E1E24),
                Color(0xFFF6F8FA), Color(0xFFECEFF1), Color(0xFFFFFFFF), Color(0xFF202124)
            )
            ThemeColorTarget.SURFACE -> listOf(
                Color(0xFF1A162B), Color(0xFF1E1E1E), Color(0xFF281347), Color(0xFF0D0D1A),
                Color(0xFF131C2E), Color(0xFF232332), Color(0xFF2A2A38), Color(0xFF333344),
                Color(0xFFFFFFFF), Color(0xFFF0F2F5), Color(0xFFEAEAEA), Color(0xFF303134)
            )
            ThemeColorTarget.ACCENT -> listOf(
                Color(0xFFE0AFA0), Color(0xFFCF6679), Color(0xFFFFD166), Color(0xFFF72585),
                Color(0xFF3700B3), Color(0xFF48CAE4), Color(0xFF06D6A0), Color(0xFFFFB703),
                Color(0xFFFF758F), Color(0xFF7209B7), Color(0xFF4CC9F0), Color(0xFFFB8500)
            )
            ThemeColorTarget.SECONDARY -> listOf(
                Color(0xFFC77DFF), Color(0xFF03DAC6), Color(0xFFFF6B6B), Color(0xFF7B2CBF),
                Color(0xFF4895EF), Color(0xFF4361EE), Color(0xFF3F37C9), Color(0xFF560BAD),
                Color(0xFF80ED99), Color(0xFF57CC99), Color(0xFF38A3A5), Color(0xFF22577A)
            )
        }
    }

    val rainbowBrush = remember {
        Brush.horizontalGradient(
            colors = listOf(
                Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .fillMaxHeight(0.88f)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 12.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Personalizar Apariencia",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Dark/Light switch pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { isDark = !isDark }
                ) {
                    Text(
                        text = if (isDark) "Modo Oscuro" else "Modo Claro",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. LIVE LIBRARY PREVIEW
                Text(
                    text = "Previsualización en vivo (Biblioteca)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                MaterialTheme(colorScheme = previewColorScheme) {
                    ThemeLibraryPreview(
                        songs = sampleSongs,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Contrast verification indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isHighContrast = contrastRatio >= 4.5f
                        Icon(
                            imageVector = if (isHighContrast) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isHighContrast) Color(0xFF10B981) else Color(0xFFF59E0B),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Contraste: ${"%.1f".format(contrastRatio)}:1 ${if (isHighContrast) "(Apto WCAG)" else "(Bajo)"}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                                color = if (isHighContrast) Color(0xFF10B981) else Color(0xFFF59E0B)
                            )
                        )
                    }

                    if (contrastRatio < 4.5f) {
                        Button(
                            onClick = {
                                val fixed = ThemeHarmonizer.ensureContrast(
                                    color = primaryState.color,
                                    background = backgroundState.color,
                                    minRatio = 4.5f,
                                    isDarkTheme = isDark
                                )
                                primaryState.setColor(fixed)
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Ajustar seguro", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. TARGET ROLE SELECTOR CHIPS
                Text(
                    text = "Elemento a personalizar:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeColorTarget.entries.forEach { target ->
                        val targetColor = when (target) {
                            ThemeColorTarget.PRIMARY -> primaryState.color
                            ThemeColorTarget.BACKGROUND -> backgroundState.color
                            ThemeColorTarget.SURFACE -> surfaceState.color
                            ThemeColorTarget.ACCENT -> accentState.color
                            ThemeColorTarget.SECONDARY -> secondaryState.color
                        }
                        val isSelected = activeTarget == target

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.clickable { activeTarget = target }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(targetColor)
                                        .border(0.5.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = target.title,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. CURATED SWATCHES
                Text(
                    text = "Paleta sugerida (${activeTarget.title}):",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(curatedSwatches) { swatch ->
                        val isCurrent = activeState.color.toArgb() == swatch.toArgb()
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(
                                    width = if (isCurrent) 2.5.dp else 0.5.dp,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                    shape = CircleShape
                                )
                                .clickable { activeState.setColor(swatch) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = ThemeHarmonizer.bestOnColor(swatch),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4. PRECISION HSL SLIDERS
                Text(
                    text = "Ajuste fino (Tono, Saturación, Luminosidad):",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Hue Slider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Tono: ${activeState.hue.toInt()}°", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(68.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(rainbowBrush)
                    )
                }
                Slider(
                    value = activeState.hue,
                    onValueChange = { activeState.hue = it },
                    valueRange = 0f..360f,
                    modifier = Modifier.fillMaxWidth()
                )

                // Saturation Slider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Saturación: ${(activeState.saturation * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(96.dp))
                    Slider(
                        value = activeState.saturation,
                        onValueChange = { activeState.saturation = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Lightness Slider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Brillo: ${(activeState.lightness * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(96.dp))
                    Slider(
                        value = activeState.lightness,
                        onValueChange = { activeState.lightness = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 5. HEX INPUT & AUTO-HARMONIZE BUTTON
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val hexString = remember(activeState.color) {
                        val argb = activeState.color.toArgb()
                        String.format("#%06X", 0xFFFFFF and argb)
                    }

                    var hexDraft by remember(activeState.color) { mutableStateOf(hexString) }

                    OutlinedTextField(
                        value = hexDraft,
                        onValueChange = { input ->
                            hexDraft = input
                            val clean = input.trim().removePrefix("#")
                            if (clean.length == 6) {
                                try {
                                    val parsed = "#$clean".toColorInt()
                                    activeState.setColor(Color(parsed))
                                } catch (_: Exception) {}
                            }
                        },
                        label = { Text("HEX", fontSize = 10.sp) },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )

                    // Auto-harmonize button
                    Button(
                        onClick = {
                            val harmonized = ThemeHarmonizer.autoHarmonizePalette(primaryState.color, isDark)
                            secondaryState.setColor(Color(harmonized.secondary))
                            backgroundState.setColor(Color(harmonized.background))
                            surfaceState.setColor(Color(harmonized.surface))
                            accentState.setColor(Color(harmonized.accent))
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Auto-armonizar", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(draftData)
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Guardar Tema")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancelar")
            }
        }
    )
}
