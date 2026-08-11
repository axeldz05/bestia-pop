package com.bestiapop.android.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.SettingsScrollColumn
import com.bestiapop.android.ui.components.SettingsSwitchRow

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

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ignoringBatteryOptimizations by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
        PlaybackSettingsSectionTitle("Batería")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Algunos celulares pausan apps en segundo plano. Permití la excepción para que la reproducción no se corte con la pantalla apagada.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (ignoringBatteryOptimizations) {
            SettingsSwitchRow(
                title = "Sin restricciones de batería",
                subtitle = "La app puede seguir reproduciendo en segundo plano",
                checked = true,
                onCheckedChange = {},
                enabled = false
            )
        } else {
            TextButton(onClick = {
                requestIgnoreBatteryOptimizations(context)
                ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
            }) {
                Text("Permitir ejecución en segundo plano")
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
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
