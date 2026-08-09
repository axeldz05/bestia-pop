package com.bestiapop.android.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.MusicPlayerViewModel

private enum class SettingsSection {
    Themes,
    ListenBrainz,
    Sound
}

@Composable
fun SettingsScreen(viewModel: MusicPlayerViewModel) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    BackHandler(enabled = section != null) {
        section = null
    }

    when (section) {
        null -> SettingsHome(
            onOpenThemes = { section = SettingsSection.Themes },
            onOpenListenBrainz = { section = SettingsSection.ListenBrainz },
            onOpenSound = { section = SettingsSection.Sound }
        )
        SettingsSection.Themes -> Column(modifier = Modifier.fillMaxSize()) {
            SettingsSubHeader(
                title = "Temas",
                onBack = { section = null }
            )
            ThemeSettingsScreen(viewModel = viewModel, showTitle = false)
        }
        SettingsSection.ListenBrainz -> Column(modifier = Modifier.fillMaxSize()) {
            SettingsSubHeader(
                title = "ListenBrainz",
                onBack = { section = null }
            )
            ListenBrainzSettingsScreen(viewModel = viewModel)
        }
        SettingsSection.Sound -> Column(modifier = Modifier.fillMaxSize()) {
            SettingsSubHeader(
                title = "Sonido",
                onBack = { section = null }
            )
            VolumeBoostSettingsScreen(viewModel = viewModel)
        }
    }
}

@Composable
private fun SettingsHome(
    onOpenThemes: () -> Unit,
    onOpenListenBrainz: () -> Unit,
    onOpenSound: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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

        Spacer(modifier = Modifier.height(24.dp))

        SettingsEntryCard(
            title = "Temas",
            subtitle = "Colores y estilo visual",
            icon = Icons.Default.Palette,
            onClick = onOpenThemes
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingsEntryCard(
            title = "ListenBrainz",
            subtitle = "Registrar canciones escuchadas",
            icon = Icons.Default.Headset,
            onClick = onOpenListenBrainz
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingsEntryCard(
            title = "Sonido",
            subtitle = "Amplificar y balance estéreo",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            onClick = onOpenSound
        )
    }
}

@Composable
private fun SettingsSubHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

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
