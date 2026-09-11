package com.bestiapop.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.preferences.JapanesePhoneticMode
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.SettingsScrollColumn
import com.bestiapop.android.ui.components.SettingsSwitchRow

@Composable
fun LyricsSettingsScreen(viewModel: MusicPlayerViewModel) {
    val settings by viewModel.lyricsSettings.collectAsStateWithLifecycle()

    SettingsScrollColumn(
        intro = "Configurá la pronunciación fonética y la traducción de letras para canciones en otros idiomas y alfabetos."
    ) {
        Text(
            text = "Guía fonética (pronunciación)",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Muestra la parte simplificada o romanizada en texto pequeño para alfabetos no latinos (japonés, coreano, cirílico, etc.).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))

        SettingsSwitchRow(
            title = "Mostrar guía fonética",
            subtitle = if (settings.phoneticGuideEnabled) {
                "Activa: muestra la pronunciación bajo la letra original"
            } else {
                "Desactivada: solo se muestra la letra original"
            },
            checked = settings.phoneticGuideEnabled,
            onCheckedChange = viewModel::setPhoneticGuideEnabled
        )

        if (settings.phoneticGuideEnabled) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Modo para idioma japonés",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Elegí si preferís ver los kanjis en alfabeto latino (Rōmaji) o en silabario japonés (Hiragana).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            JapaneseModeOptionRow(
                title = "Romanizado (Rōmaji)",
                subtitle = "Ejemplo: yoru ni kakeru",
                selected = settings.japanesePhoneticMode == JapanesePhoneticMode.ROMAJI,
                onClick = { viewModel.setJapanesePhoneticMode(JapanesePhoneticMode.ROMAJI) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            JapaneseModeOptionRow(
                title = "Hiragana",
                subtitle = "Ejemplo: よるにかける",
                selected = settings.japanesePhoneticMode == JapanesePhoneticMode.HIRAGANA,
                onClick = { viewModel.setJapanesePhoneticMode(JapanesePhoneticMode.HIRAGANA) }
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "Traducción de letras",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Al presionar el botón de traducir en la pantalla de reproducción, se busca primero en sitios comunitarios (Musixmatch).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))

        SettingsSwitchRow(
            title = "Preguntar antes de usar Google Traductor",
            subtitle = if (settings.askBeforeGoogleTranslate) {
                "Si no hay traducción comunitaria, pide confirmación antes de traducir con Google"
            } else {
                "Traduce automáticamente con Google Traductor si no se encuentra en línea"
            },
            checked = settings.askBeforeGoogleTranslate,
            onCheckedChange = viewModel::setAskBeforeGoogleTranslate
        )
    }
}

@Composable
private fun JapaneseModeOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val containerColor =
        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(
            selected = selected,
            onClick = onClick
        )
    }
}
