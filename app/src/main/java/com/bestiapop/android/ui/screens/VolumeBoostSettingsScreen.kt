package com.bestiapop.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.SettingsScrollColumn
import com.bestiapop.android.ui.components.SettingsSwitchRow
import kotlin.math.roundToInt

@Composable
fun VolumeBoostSettingsScreen(viewModel: MusicPlayerViewModel) {
    val boostEnabled by viewModel.volumeBoostEnabled.collectAsState()
    val leftGain by viewModel.stereoLeftGain.collectAsState()
    val rightGain by viewModel.stereoRightGain.collectAsState()

    SettingsScrollColumn(
        intro = "El volumen de Now Playing es general. Acá podés amplificar por encima del 100% y atenuar el canal izquierdo o derecho por separado."
    ) {
        Text(
            text = "Amplificar volumen",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Permite subir el volumen de Now Playing por encima del 100% del sistema. Puede distorsionar temas ya masterizados a alto volumen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        SettingsSwitchRow(
            title = "Amplificar volumen",
            subtitle = if (boostEnabled) {
                "Activo — la barra de Now Playing llega hasta 200%"
            } else {
                "Desactivado — volumen limitado al 100% del sistema"
            },
            checked = boostEnabled,
            onCheckedChange = { viewModel.setVolumeBoostEnabled(it) }
        )

        Spacer(modifier = Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Balance estéreo",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            TextButton(onClick = { viewModel.resetStereoBalance() }) {
                Text("Restablecer")
            }
        }
        Text(
            text = "Cada fader atenúa solo su canal (independientes). Con amplificar activo el boost se aplica a ambos por igual.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        StereoGainSlider(
            label = "Izquierdo",
            value = leftGain,
            onValueChange = { viewModel.setStereoLeftGain(it) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        StereoGainSlider(
            label = "Derecho",
            value = rightGain,
            onValueChange = { viewModel.setStereoRightGain(it) }
        )
    }
}

@Composable
private fun StereoGainSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val percent = (value.coerceIn(0f, 1f) * 100f).roundToInt()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier.semantics {
                contentDescription = "Balance $label"
            }
        )
    }
}
