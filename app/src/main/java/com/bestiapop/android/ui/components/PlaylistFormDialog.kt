package com.bestiapop.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun PlaylistFormDialog(
    title: String,
    initialName: String = "",
    initialDescription: String = "",
    initialCoverUri: String? = null,
    confirmText: String = "Crear",
    confirmAndOpenText: String? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String?, coverUri: String?) -> Unit,
    onSaveAndOpen: ((name: String, description: String?, coverUri: String?) -> Unit)? = null
) {
    var nameInput by remember { mutableStateOf(initialName) }
    var descInput by remember { mutableStateOf(initialDescription) }
    var coverUriInput by remember { mutableStateOf(initialCoverUri) }

    val imagePickerLauncher = rememberImagePicker { coverUriInput = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ArtworkPickerBlock(
                    artworkUri = coverUriInput,
                    onPick = { imagePickerLauncher.launch("image/*") },
                    buttonText = if (coverUriInput.isNullOrEmpty()) {
                        "Seleccionar imagen"
                    } else {
                        "Cambiar imagen"
                    },
                    spacing = 12.dp,
                    preview = { uri ->
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { imagePickerLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (!uri.isNullOrEmpty()) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "Portada de Playlist",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.AddPhotoAlternate,
                                        contentDescription = "Elegir Portada",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Portada local",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    buttonLeading = {},
                    trailing = {
                        if (!coverUriInput.isNullOrEmpty()) {
                            IconButton(onClick = { coverUriInput = null }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Quitar portada",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                )

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Nombre de la playlist *") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist-name-input")
                )

                OutlinedTextField(
                    value = descInput,
                    onValueChange = { descInput = it },
                    label = { Text("Descripción (opcional)") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist-description-input")
                )
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onSaveAndOpen != null) {
                    OutlinedButton(
                        onClick = {
                            if (nameInput.isNotBlank()) {
                                onSave(nameInput.trim(), descInput.trim(), coverUriInput)
                            }
                        },
                        enabled = nameInput.isNotBlank()
                    ) {
                        Text(confirmText)
                    }
                    Button(
                        onClick = {
                            if (nameInput.isNotBlank()) {
                                onSaveAndOpen(nameInput.trim(), descInput.trim(), coverUriInput)
                            }
                        },
                        enabled = nameInput.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(confirmAndOpenText ?: "$confirmText y entrar")
                    }
                } else {
                    Button(
                        onClick = {
                            if (nameInput.isNotBlank()) {
                                onSave(nameInput.trim(), descInput.trim(), coverUriInput)
                            }
                        },
                        enabled = nameInput.isNotBlank()
                    ) {
                        Text(confirmText)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
