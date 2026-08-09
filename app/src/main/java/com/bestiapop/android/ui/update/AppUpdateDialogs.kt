package com.bestiapop.android.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppUpdateDialogs(
    state: AppUpdateUiState,
    onConfirmUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        AppUpdateUiState.Idle,
        is AppUpdateUiState.NeedsInstallPermission,
        is AppUpdateUiState.ReadyToInstall -> Unit
        AppUpdateUiState.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Actualización") },
            text = { Text("Buscando en GitHub…") },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        )
        AppUpdateUiState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Actualización") },
            text = { Text("Ya tenés la última versión.") },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        )
        is AppUpdateUiState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Nueva versión ${state.info.versionName}") },
            text = {
                val notes = state.info.changelog?.trim().orEmpty()
                Text(
                    if (notes.isBlank()) {
                        "Hay una actualización lista para instalar."
                    } else {
                        notes
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmUpdate) { Text("Actualizar") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Después") }
            }
        )
        is AppUpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Descargando ${state.info.versionName}") },
            text = {
                Column {
                    val progress = state.progress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        )
        is AppUpdateUiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Actualización") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        )
    }
}
