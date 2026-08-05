package com.bestiapop.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.preferences.MAX_SAVE_WHILE_LISTENING_PERCENT
import com.bestiapop.android.data.preferences.MIN_SAVE_WHILE_LISTENING_PERCENT
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.TokenValidationUiState
import java.text.DateFormat
import java.util.Date

@Composable
fun ListenBrainzSettingsScreen(viewModel: MusicPlayerViewModel) {
    val settings by viewModel.listenBrainzSettings.collectAsState()
    val pendingCount by viewModel.pendingListenCount.collectAsState()
    val validationState by viewModel.tokenValidationState.collectAsState()

    var tokenDraft by remember(settings.userToken) { mutableStateOf(settings.userToken) }
    var showToken by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "Registrá lo que escuchás en ListenBrainz. Sin conexión se guarda en cola y se envía de a poco al reconectar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        SettingsSwitchRow(
            title = "Registrar escuchas",
            subtitle = if (settings.enabled) "Activo" else "Desactivado",
            checked = settings.enabled,
            onCheckedChange = { viewModel.setListenBrainzEnabled(it) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Token de usuario",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Copialo desde listenbrainz.org/settings/",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Pegá tu token aquí") },
            visualTransformation = if (showToken) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showToken) "Ocultar token" else "Mostrar token"
                    )
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    viewModel.saveListenBrainzToken(tokenDraft)
                    viewModel.validateListenBrainzToken(tokenDraft)
                },
                enabled = tokenDraft.isNotBlank() && validationState !is TokenValidationUiState.Validating,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = when (validationState) {
                        is TokenValidationUiState.Validating -> "Validando…"
                        else -> "Validar"
                    }
                )
            }
            if (settings.userToken.isNotBlank() || settings.username != null) {
                TextButton(onClick = {
                    tokenDraft = ""
                    viewModel.clearListenBrainz()
                }) {
                    Text("Borrar")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        ConnectionStatusBlock(
            settingsUsername = settings.username,
            validationState = validationState
        )

        Spacer(modifier = Modifier.height(24.dp))

        val canEnableDiscover = !settings.username.isNullOrBlank() && settings.userToken.isNotBlank()
        SettingsSwitchRow(
            title = "Mostrar Para Ti",
            subtitle = "Daily/Weekly Jams y otras playlists Discover de tu cuenta en la pestaña Playlists.",
            checked = settings.discoverEnabled,
            onCheckedChange = { viewModel.setListenBrainzDiscoverEnabled(it) },
            enabled = canEnableDiscover
        )

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSwitchRow(
            title = "Guardar al escuchar",
            subtitle = "Descargar a la biblioteca en segundo plano los temas en stream (Para Ti / Radio) al alcanzar un porcentaje de reproducción.",
            checked = settings.saveWhileListening,
            onCheckedChange = { viewModel.setListenBrainzSaveWhileListening(it) },
            enabled = canEnableDiscover
        )

        if (settings.saveWhileListening) {
            Spacer(modifier = Modifier.height(12.dp))
            SaveWhileListeningPercentSlider(
                percent = settings.saveWhileListeningPercent,
                enabled = canEnableDiscover,
                onPercentChange = { viewModel.setListenBrainzSaveWhileListeningPercent(it) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Cola offline",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = when {
                pendingCount == 0 -> "No hay escuchas pendientes."
                pendingCount == 1 -> "1 escucha pendiente de envío."
                else -> "$pendingCount escuchas pendientes de envío."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        settings.lastSyncAt?.let { syncAt ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Último sync: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(syncAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SaveWhileListeningPercentSlider(
    percent: Int,
    enabled: Boolean,
    onPercentChange: (Int) -> Unit
) {
    val min = MIN_SAVE_WHILE_LISTENING_PERCENT.toFloat()
    val max = MAX_SAVE_WHILE_LISTENING_PERCENT.toFloat()
    var sliderValue by remember(percent) { mutableFloatStateOf(percent.toFloat()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Empezar a descargar al",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${sliderValue.toInt()}%",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = sliderValue.coerceIn(min, max),
            onValueChange = { if (enabled) sliderValue = it },
            valueRange = min..max,
            steps = ((max - min) / 5f).toInt() - 1,
            enabled = enabled,
            onValueChangeFinished = {
                onPercentChange(sliderValue.toInt())
            }
        )
        Text(
            text = "Porcentaje del tema reproducido antes de guardar en biblioteca. Al terminar el tema también se guarda.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConnectionStatusBlock(
    settingsUsername: String?,
    validationState: TokenValidationUiState
) {
    val (label, color) = when (validationState) {
        is TokenValidationUiState.Idle -> {
            if (settingsUsername != null) {
                "Conectado como $settingsUsername" to MaterialTheme.colorScheme.primary
            } else {
                "Sin validar" to MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
        is TokenValidationUiState.Validating ->
            "Validando token…" to MaterialTheme.colorScheme.onSurfaceVariant
        is TokenValidationUiState.Success ->
            "Conectado como ${validationState.username}" to MaterialTheme.colorScheme.primary
        is TokenValidationUiState.Error ->
            validationState.message to MaterialTheme.colorScheme.error
    }

    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = color
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

