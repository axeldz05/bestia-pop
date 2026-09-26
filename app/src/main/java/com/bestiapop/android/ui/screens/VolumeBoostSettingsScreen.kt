package com.bestiapop.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.SettingsScrollColumn
import com.bestiapop.android.ui.components.SettingsSwitchRow
import kotlin.math.roundToInt

@Composable
fun VolumeBoostSettingsScreen(viewModel: MusicPlayerViewModel) {
    val settings by viewModel.playbackSettings.collectAsStateWithLifecycle()

    SettingsScrollColumn(
        intro =
            "El volumen general se controla con los botones del dispositivo. " +
                "Acá podés amplificar por encima del 100%, ajustar el ecualizador y atenuar el canal izquierdo o derecho por separado.",
    ) {
        Text(
            text = "Ecualizador",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Ajuste de respuesta de frecuencia de 5 a 12 bandas con preajustes y faders verticales.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable { viewModel.openEqualizer() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = "Configurar ecualizador",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text =
                                if (settings.equalizerSettings.enabled) {
                                    "Activo · ${settings.equalizerSettings.bandCount} bandas (${settings.equalizerSettings.presetName})"
                                } else {
                                    "Desactivado · ${settings.equalizerSettings.bandCount} bandas"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Amplificar volumen",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                "Permite subir el volumen por encima del 100% del sistema. Puede distorsionar temas ya " +
                    "masterizados a alto volumen. Nota: Deshabilita la decodificación por hardware de " +
                    "ultra-bajo consumo (Audio Offload) del sistema, lo que puede incrementar el consumo " +
                    "de batería durante la reproducción en segundo plano.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        SettingsSwitchRow(
            title = "Amplificar volumen",
            subtitle =
                if (settings.volumeBoostEnabled) {
                    "Activo — amplificación disponible hasta 200%"
                } else {
                    "Desactivado — volumen limitado al 100% del sistema"
                },
            checked = settings.volumeBoostEnabled,
            onCheckedChange = { viewModel.setVolumeBoostEnabled(it) },
        )

        if (settings.volumeBoostEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            BoostGainSlider(
                value = settings.volumeBoostAmount,
                onValueChange = { viewModel.setVolumeBoostAmount(it) },
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Balance estéreo",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = { viewModel.resetStereoBalance() }) {
                Text("Restablecer")
            }
        }
        Text(
            text = "Cada fader atenúa solo su canal (independientes). Con amplificar activo el boost se aplica a ambos por igual.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        StereoGainSlider(
            label = "Izquierdo",
            value = settings.stereoLeftGain,
            onValueChange = { viewModel.setStereoLeftGain(it) },
        )

        Spacer(modifier = Modifier.height(12.dp))

        StereoGainSlider(
            label = "Derecho",
            value = settings.stereoRightGain,
            onValueChange = { viewModel.setStereoRightGain(it) },
        )
    }
}

@Composable
private fun StereoGainSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val percent = (value.coerceIn(0f, 1f) * 100f).roundToInt()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier =
                Modifier.semantics {
                    contentDescription = "Balance $label"
                },
        )
    }
}

@Composable
private fun BoostGainSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val boostPercent = (value.coerceIn(0f, 1f) * 100f).roundToInt()
    val totalPercent = 100 + boostPercent
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Nivel de amplificación",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "+$boostPercent% ($totalPercent%)",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            steps = 9,
            modifier =
                Modifier.semantics {
                    contentDescription = "Nivel de amplificación"
                },
        )
    }
}
