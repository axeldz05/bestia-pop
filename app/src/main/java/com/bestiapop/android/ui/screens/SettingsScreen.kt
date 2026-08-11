package com.bestiapop.android.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.BuildConfig
import com.bestiapop.android.data.update.GitHubReleaseUrls
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ScreenBackHeader
import com.bestiapop.android.ui.update.AppUpdateScreen
import com.bestiapop.android.ui.update.AppUpdateViewModel

private enum class SettingsSection {
    Themes,
    ListenBrainz,
    Playback,
    Sound,
    Downloads,
    LibraryTags,
    Update
}

@Composable
fun SettingsScreen(viewModel: MusicPlayerViewModel, appUpdateViewModel: AppUpdateViewModel) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    val pendingSettingsSection by viewModel.pendingSettingsSection.collectAsState()

    LaunchedEffect(pendingSettingsSection) {
        when (pendingSettingsSection) {
            "downloads" -> {
                section = SettingsSection.Downloads
                viewModel.consumePendingSettingsSection()
            }
        }
    }

    val closeSection = {
        section = null
        // Returns the user to the tab a deep link pulled them out of (Descargas → Ajustes).
        viewModel.returnFromTransientSettings()
        Unit
    }

    BackHandler(enabled = section != null) { closeSection() }

    when (section) {
        null -> SettingsHome(
            appUpdateViewModel = appUpdateViewModel,
            onOpenThemes = { section = SettingsSection.Themes },
            onOpenListenBrainz = { section = SettingsSection.ListenBrainz },
            onOpenPlayback = { section = SettingsSection.Playback },
            onOpenSound = { section = SettingsSection.Sound },
            onOpenDownloads = { section = SettingsSection.Downloads },
            onOpenLibraryTags = { section = SettingsSection.LibraryTags },
            onOpenUpdate = { section = SettingsSection.Update }
        )
        SettingsSection.Themes -> SettingsSectionPage("Temas", onBack = closeSection) {
            ThemeSettingsScreen(viewModel = viewModel, showTitle = false)
        }
        SettingsSection.ListenBrainz -> SettingsSectionPage("ListenBrainz", onBack = closeSection) {
            ListenBrainzSettingsScreen(viewModel = viewModel)
        }
        SettingsSection.Playback -> SettingsSectionPage("Reproducción", onBack = closeSection) {
            PlaybackSettingsScreen(viewModel = viewModel)
        }
        SettingsSection.Sound -> SettingsSectionPage("Sonido", onBack = closeSection) {
            VolumeBoostSettingsScreen(viewModel = viewModel)
        }
        SettingsSection.Downloads -> SettingsSectionPage("Descargas", onBack = closeSection) {
            DownloadSettingsScreen(viewModel = viewModel)
        }
        SettingsSection.LibraryTags -> SettingsSectionPage("Archivos", onBack = closeSection) {
            LibraryTagWriteSettingsScreen(viewModel = viewModel)
        }
        SettingsSection.Update -> SettingsSectionPage("Actualización", onBack = closeSection) {
            AppUpdateScreen(viewModel = appUpdateViewModel)
        }
    }
}

@Composable
private fun SettingsSectionPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenBackHeader(
            title = title,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
        content()
    }
}

@Composable
private fun SettingsHome(
    appUpdateViewModel: AppUpdateViewModel,
    onOpenThemes: () -> Unit,
    onOpenListenBrainz: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenSound: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLibraryTags: () -> Unit,
    onOpenUpdate: () -> Unit
) {
    val context = LocalContext.current
    val updateNotes by appUpdateViewModel.notes.collectAsState()
    val repo = BuildConfig.GITHUB_REPOSITORY.trim()
    val latestUrl = if (repo.isNotEmpty()) GitHubReleaseUrls.latestPageUrl(repo) else ""
    val inviteText = """
        BestiaPop — descargá la app:

        $latestUrl

        En el celular: descargá el APK y permití “Instalar apps desconocidas” para el navegador.
    """.trimIndent()
    val newestVersion = updateNotes.newer.firstOrNull()?.versionName
    val updateSubtitle = when {
        newestVersion != null -> "Nueva versión $newestVersion"
        else -> "Versión ${BuildConfig.VERSION_NAME} · notas y cambios"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = "Ajustes",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Personalizá la app y conectá servicios.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Versión ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        val entries = listOf(
            SettingsHomeEntry("Temas", "Colores y estilo visual", Icons.Default.Palette, onOpenThemes),
            SettingsHomeEntry(
                "ListenBrainz",
                "Registrar canciones escuchadas",
                Icons.Default.Headset,
                onOpenListenBrainz
            ),
            SettingsHomeEntry(
                "Reproducción",
                "Aleatorio y repetición al abrir",
                Icons.Default.Repeat,
                onOpenPlayback
            ),
            SettingsHomeEntry(
                "Sonido",
                "Amplificar y balance estéreo",
                Icons.AutoMirrored.Filled.VolumeUp,
                onOpenSound
            ),
            SettingsHomeEntry(
                "Descargas",
                "Datos móviles y carpeta de guardado",
                Icons.Default.Download,
                onOpenDownloads
            ),
            SettingsHomeEntry(
                "Archivos",
                "Escribir metadata de la app a los archivos",
                Icons.Default.AudioFile,
                onOpenLibraryTags
            ),
            SettingsHomeEntry(
                "Actualización",
                updateSubtitle,
                Icons.Default.SystemUpdate,
                onOpenUpdate
            ),
            SettingsHomeEntry(
                "Invitar amigos",
                "Link de descarga del APK",
                Icons.Default.Share
            ) {
                if (repo.isEmpty()) {
                    Toast.makeText(
                        context,
                        "Falta GITHUB_REPOSITORY en github-release.properties",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "BestiaPop")
                                putExtra(Intent.EXTRA_TEXT, inviteText)
                            },
                            "Invitar amigos"
                        )
                    )
                }
            }
        )
        entries.forEachIndexed { index, entry ->
            if (index > 0) Spacer(modifier = Modifier.height(12.dp))
            SettingsEntryCard(
                title = entry.title,
                subtitle = entry.subtitle,
                icon = entry.icon,
                onClick = entry.onClick
            )
        }
    }
}

private data class SettingsHomeEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
private fun SettingsEntryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
