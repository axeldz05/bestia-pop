package com.bestiapop.android.ui.screens

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.bestiapop.android.data.preferences.MAX_STREAM_SKIP_GRACE_SECONDS
import com.bestiapop.android.data.system.BackgroundExecutionProbe
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.SettingsScrollColumn
import com.bestiapop.android.ui.components.SettingsSwitchRow
import kotlin.math.roundToInt

@Composable
fun PlaybackSettingsScreen(viewModel: MusicPlayerViewModel) {
    val rememberShuffle by viewModel.rememberShuffleOnLaunch.collectAsState()
    val rememberRepeat by viewModel.rememberRepeatOnLaunch.collectAsState()
    val autoplayOnLaunch by viewModel.autoplayOnLaunch.collectAsState()
    val clearShuffleOnManualPlay by viewModel.clearShuffleOnManualPlay.collectAsState()
    val clearRepeatAllOnManualPlay by viewModel.clearRepeatAllOnManualPlay.collectAsState()
    val clearRepeatOneOnManualPlay by viewModel.clearRepeatOneOnManualPlay.collectAsState()
    val clearShuffleOnSkip by viewModel.clearShuffleOnSkip.collectAsState()
    val clearRepeatOneOnSkip by viewModel.clearRepeatOneOnSkip.collectAsState()
    val streamGraceSeconds by viewModel.streamSkipGraceSeconds.collectAsState()
    val backgroundExecutionStatus by viewModel.backgroundExecutionStatus.collectAsState()

    val context = LocalContext.current
    val oemScreenOffCleanupIntent = remember(context) {
        BackgroundExecutionProbe.oemScreenOffCleanupIntent(context)
    }
    val restrictionGuidance = remember {
        BackgroundExecutionProbe.restrictionGuidance()
    }

    SettingsScrollColumn(
        intro = "Elegí qué se restaura al abrir la app y si al reproducir o saltar se sale del aleatorio y la repetición. Los switches de recordar no cambian la sesión actual."
    ) {
        SettingsSwitchRow(
            title = "Reproducir al abrir",
            subtitle = if (autoplayOnLaunch) {
                "Al abrir la app, seguir la última cola automáticamente (local o stream)"
            } else {
                "Al abrir la app, mostrar la última canción sin reproducir"
            },
            checked = autoplayOnLaunch,
            onCheckedChange = { viewModel.setAutoplayOnLaunch(it) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        SettingsSwitchRow(
            title = "Recordar aleatorio",
            subtitle = if (rememberShuffle) {
                "Al abrir la app, conservar si el modo aleatorio estaba activo"
            } else {
                "Al abrir la app, el aleatorio arranca apagado"
            },
            checked = rememberShuffle,
            onCheckedChange = { viewModel.setRememberShuffleOnLaunch(it) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        SettingsSwitchRow(
            title = "Recordar repetición",
            subtitle = if (rememberRepeat) {
                "Al abrir la app, conservar el último modo (todo / una / off)"
            } else {
                "Al abrir la app, la repetición arranca apagada"
            },
            checked = rememberRepeat,
            onCheckedChange = { viewModel.setRememberRepeatOnLaunch(it) }
        )

        Spacer(modifier = Modifier.height(28.dp))
        PlaybackSettingsSectionTitle("Al elegir qué reproducir")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Canción, álbum, playlist o un ítem de la cola.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        ClearModeSwitchRow(
            title = "Salir del aleatorio",
            checked = clearShuffleOnManualPlay,
            onCheckedChange = { viewModel.setClearShuffleOnManualPlay(it) },
            onSubtitle = "Al elegir qué reproducir, se apaga el aleatorio",
            offSubtitle = "El aleatorio se mantiene si ya estaba activo"
        )

        Spacer(modifier = Modifier.height(20.dp))

        ClearModeSwitchRow(
            title = "Salir de repetir todo",
            checked = clearRepeatAllOnManualPlay,
            onCheckedChange = { viewModel.setClearRepeatAllOnManualPlay(it) },
            onSubtitle = "Al elegir qué reproducir, se apaga repetir todo",
            offSubtitle = "Repetir todo se mantiene si ya estaba activo"
        )

        Spacer(modifier = Modifier.height(20.dp))

        ClearModeSwitchRow(
            title = "Salir de repetir una",
            checked = clearRepeatOneOnManualPlay,
            onCheckedChange = { viewModel.setClearRepeatOneOnManualPlay(it) },
            onSubtitle = "Al elegir qué reproducir, se apaga repetir una",
            offSubtitle = "Repetir una se mantiene si ya estaba activo"
        )

        Spacer(modifier = Modifier.height(28.dp))
        PlaybackSettingsSectionTitle("Al usar siguiente o anterior")
        Spacer(modifier = Modifier.height(12.dp))

        ClearModeSwitchRow(
            title = "Salir del aleatorio",
            checked = clearShuffleOnSkip,
            onCheckedChange = { viewModel.setClearShuffleOnSkip(it) },
            onSubtitle = "Siguiente o anterior apaga el aleatorio (la cola no se reordena)",
            offSubtitle = "Siguiente o anterior no cambia el aleatorio"
        )

        Spacer(modifier = Modifier.height(20.dp))

        ClearModeSwitchRow(
            title = "Salir de repetir una",
            checked = clearRepeatOneOnSkip,
            onCheckedChange = { viewModel.setClearRepeatOneOnSkip(it) },
            onSubtitle = "Siguiente o anterior sale de repetir una y pasa de tema",
            offSubtitle = "Se mantiene repetir una"
        )

        Spacer(modifier = Modifier.height(28.dp))
        PlaybackSettingsSectionTitle("Canciones online")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Cuánto insistir con una canción de internet antes de pasar a la siguiente. " +
                "Mientras dura, vuelve a pedir el audio para que arranque en vez de saltearla.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (streamGraceSeconds <= 0) {
                "Saltear al primer error"
            } else {
                "Insistir $streamGraceSeconds ${if (streamGraceSeconds == 1) "segundo" else "segundos"}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Slider(
            value = streamGraceSeconds.toFloat(),
            onValueChange = { viewModel.setStreamSkipGraceSeconds(it.roundToInt()) },
            valueRange = 0f..MAX_STREAM_SKIP_GRACE_SECONDS.toFloat(),
            steps = MAX_STREAM_SKIP_GRACE_SECONDS - 1
        )

        Spacer(modifier = Modifier.height(28.dp))
        PlaybackSettingsSectionTitle("Batería")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Android administra por separado la actividad en segundo plano y la optimización de batería.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (backgroundExecutionStatus.blocksBackgroundPlayback) {
            Text(
                text = "Actividad en segundo plano restringida",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = restrictionGuidance.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { BackgroundExecutionProbe.openApplicationDetails(context) }) {
                Text("Abrir ficha de la app")
            }
        } else {
            SettingsSwitchRow(
                title = "Actividad en segundo plano permitida",
                subtitle = "Android no marcó la app como restringida",
                checked = true,
                onCheckedChange = {},
                enabled = false
            )
        }

        if (backgroundExecutionStatus.oemScreenOffCleanupActive) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Cerrar al apagar la pantalla",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = "Este teléfono puede cortar la reproducción al bloquear o apagar la pantalla. Desactivá esa opción en Batería para BestiaPop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (oemScreenOffCleanupIntent != null) {
                TextButton(onClick = { BackgroundExecutionProbe.openOemScreenOffCleanupSettings(context) }) {
                    Text("Abrir ajuste de batería")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (backgroundExecutionStatus.ignoringBatteryOptimizations) {
            SettingsSwitchRow(
                title = "Optimización de batería desactivada",
                subtitle = "Doze no debería suspender la reproducción ni las transferencias",
                checked = true,
                onCheckedChange = {},
                enabled = false
            )
        } else {
            TextButton(onClick = {
                requestIgnoreBatteryOptimizations(context)
            }) {
                Text("Desactivar optimización de batería")
            }
        }
    }
}

// Explicit, user-triggered exception for uninterrupted media playback; never requested silently.
@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${context.packageName}".toUri()
            }
        )
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

@Composable
private fun PlaybackSettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun ClearModeSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onSubtitle: String,
    offSubtitle: String
) {
    SettingsSwitchRow(
        title = title,
        subtitle = if (checked) onSubtitle else offSubtitle,
        checked = checked,
        onCheckedChange = onCheckedChange
    )
}
