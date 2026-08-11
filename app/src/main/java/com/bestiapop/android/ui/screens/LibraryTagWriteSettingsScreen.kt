package com.bestiapop.android.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.SettingsScrollColumn
import com.bestiapop.android.ui.components.SettingsSwitchRow

@Composable
fun LibraryTagWriteSettingsScreen(viewModel: MusicPlayerViewModel) {
    val settings by viewModel.libraryTagWriteSettings.collectAsState()
    val job by viewModel.libraryJobProgress.collectAsState()
    val syncBusy = job != null

    SettingsScrollColumn(
        intro = "Escribí en los archivos de audio (Music/BestiaPop) la misma metadata que ves en la app. " +
            "No modifica canciones importadas solo vía MediaStore (content://)."
    ) {
        SettingsSwitchRow(
            title = "Escribir tags al guardar",
            subtitle = if (settings.autoWriteTagsEnabled) {
                "Activo — al identificar o editar, también actualiza el archivo si es escribible"
            } else {
                "Desactivado — la metadata queda solo en la app hasta que sincronices"
            },
            checked = settings.autoWriteTagsEnabled,
            onCheckedChange = { viewModel.setAutoWriteTagsEnabled(it) }
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Sincronización manual",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Recorre la biblioteca y escribe title / artist / álbum / género / año / pista / portada " +
                "en cada archivo local escribible (mp3, m4a, flac, ogg).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = { viewModel.syncLibraryTagsToFiles() },
            enabled = !syncBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (syncBusy) "Sincronizando…" else "Sincronizar tags a archivos ahora")
        }
    }
}
