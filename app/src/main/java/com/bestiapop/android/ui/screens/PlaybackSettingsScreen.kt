package com.bestiapop.android.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.SettingsScrollColumn
import com.bestiapop.android.ui.components.SettingsSwitchRow

@Composable
fun PlaybackSettingsScreen(viewModel: MusicPlayerViewModel) {
    val rememberShuffle by viewModel.rememberShuffleOnLaunch.collectAsState()
    val rememberRepeat by viewModel.rememberRepeatOnLaunch.collectAsState()
    val autoplayOnLaunch by viewModel.autoplayOnLaunch.collectAsState()

    SettingsScrollColumn(
        intro = "Elegí qué se restaura al abrir la app. Si un switch está apagado, ese modo arranca desactivado. No cambia la sesión actual (si la música sigue sonando en segundo plano)."
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
    }
}
