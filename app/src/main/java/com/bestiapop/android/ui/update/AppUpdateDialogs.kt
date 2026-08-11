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

/**
 * Update surfaces that must show over any screen: the launch-time find, the download progress and
 * its errors. Browsing notes and checking on demand live in `AppUpdateScreen`.
 */
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
        is AppUpdateUiState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Nueva versión ${state.release.versionName}") },
            text = {
                val notes = state.release.notes?.trim().orEmpty()
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
            // Not onDismiss: a scrim tap or back cancelled the APK download with no confirmation and
            // no feedback. Cancelling stays on the explicit button.
            onDismissRequest = {},
            title = { Text("Descargando ${state.release.versionName}") },
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
