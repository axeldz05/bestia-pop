package com.bestiapop.android.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.SettingsScrollColumn
import com.bestiapop.android.ui.components.SettingsSwitchRow

@Composable
fun DownloadSettingsScreen(viewModel: MusicPlayerViewModel) {
    val downloadOnMetered by viewModel.downloadOnMeteredNetwork.collectAsState()
    val savePathLabel = StorageUtils.userVisibleMusicDirLabel()
    val absolutePath = StorageUtils.publicBestiaPopDir().absolutePath

    SettingsScrollColumn(
        intro = "Dónde se guardan las canciones descargadas y si se pueden bajar con datos móviles."
    ) {
        SettingsSwitchRow(
            title = "Descargar con datos móviles",
            subtitle = if (downloadOnMetered) {
                "Activo — también descarga en redes metered (datos / hotspot)"
            } else {
                "Desactivado — solo descarga en Wi‑Fi u otras redes no metered"
            },
            checked = downloadOnMetered,
            onCheckedChange = { viewModel.setDownloadOnMeteredNetwork(it) }
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Ubicación",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Guardando en: $savePathLabel",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = absolutePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
