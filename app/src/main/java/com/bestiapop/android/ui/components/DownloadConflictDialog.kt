package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.DownloadConflict

@Composable
fun DownloadConflictDialog(
    conflict: DownloadConflict,
    onOverwrite: () -> Unit,
    onSaveAs: (String) -> Unit,
    onCancel: () -> Unit
) {
    var showRename by remember(conflict.downloadId) { mutableStateOf(false) }
    var newTitle by remember(conflict.downloadId) {
        mutableStateOf("${conflict.existing.title} (2)")
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Nuevo título") },
            text = {
                Column {
                    Text("Se guardará como una canción nueva en la biblioteca.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Título") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onSaveAs(newTitle) }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text("Atrás")
                }
            }
        )
        return
    }

    val batchHint = if (conflict.applyToRemainingBatch) {
        " Esta elección se aplicará al resto del lote."
    } else {
        ""
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Canción ya en la biblioteca") },
        text = {
            Text(
                "«${conflict.existing.title}» de ${conflict.existing.artist} ya está guardada." +
                    " ¿Querés sobrescribirla o crear una copia con otro título?$batchHint"
            )
        },
        confirmButton = {
            TextButton(onClick = onOverwrite) {
                Text("Sobrescribir")
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { showRename = true }) {
                    Text("Crear nueva")
                }
                TextButton(onClick = onCancel) {
                    Text("Cancelar")
                }
            }
        }
    )
}
