package com.bestiapop.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.SettingsScrollColumn
import com.bestiapop.android.ui.components.SettingsSwitchRow

@Composable
fun TelemetrySettingsScreen(viewModel: MusicPlayerViewModel) {
    val telemetryEnabled by viewModel.telemetryEnabled.collectAsStateWithLifecycle()

    SettingsScrollColumn(
        intro = "BestiaPop incluye telemetría técnica para diagnosticar problemas de estabilidad, " +
            "cierres del sistema operativo y consumo crítico de memoria."
    ) {
        SettingsSwitchRow(
            title = "Diagnósticos y estabilidad",
            subtitle = if (telemetryEnabled) {
                "Activo — se transmiten reportes anónimos de cierres inesperados y memoria crítica"
            } else {
                "Desactivado — ninguna métrica técnica se recopila ni envía al servidor"
            },
            checked = telemetryEnabled,
            onCheckedChange = { viewModel.setTelemetryEnabled(it) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        TelemetryInfoCard(
            title = "¿Qué se recolecta?",
            icon = Icons.Default.Analytics,
            items = listOf(
                "Cierres del sistema: Low Memory Killer (LMK), límites de CPU o batería excedidos según Android, bloqueos ANR y cierres nativos.",
                "Presión crítica de RAM: advertencias extremas del kernel (onTrimMemory severo y onLowMemory) para prevenir fallos antes de que ocurran.",
                "Métricas del proceso: consumo de memoria física (PSS / RSS en MB), estado de ejecución (primer plano / segundo plano) y descripción del error del sistema."
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        TelemetryInfoCard(
            title = "¿Cuándo se envía?",
            icon = Icons.Default.Schedule,
            items = listOf(
                "Post-mortem al iniciar: si la app fue cerrada forzosamente por el sistema operativo, se evalúa en el siguiente inicio y se reporta una única vez.",
                "Cierres de usuario ignorados: cuando cerrás la app manualmente (deslizando de recientes o botón atrás) no se envía absolutamente nada.",
                "Canal seguro: los reportes se envían a través de Firebase Crashlytics con cifrado en tránsito."
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        TelemetryInfoCard(
            title = "Privacidad garantizada",
            icon = Icons.Default.Lock,
            items = listOf(
                "Cero datos personales: no se recolectan cuentas, nombres, correos ni datos identificatorios del usuario.",
                "Cero contenido multimedia: ningún nombre de archivo de audio, canción, artista, álbum o playlist es enviado jamás.",
                "Sin rastreo publicitario: BestiaPop no incluye Google Analytics ni identificadores de publicidad (Advertising ID)."
            )
        )
    }
}

@Composable
private fun TelemetryInfoCard(
    title: String,
    icon: ImageVector,
    items: List<String>
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            items.forEachIndexed { index, item ->
                if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
