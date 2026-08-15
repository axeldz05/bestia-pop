package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.albumDiscNumber
import com.bestiapop.android.data.util.albumTrackDisplayNumber
import com.bestiapop.android.data.util.encodeAlbumTrack
import com.bestiapop.android.ui.components.ArtworkPickerBlock
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.rememberImagePicker

@Composable
fun EditSongMetadataDialog(
    song: Song,
    onDismiss: () -> Unit,
    onConfirm: (title: String, artist: String, album: String, genre: String, year: Int, trackNumber: Int) -> Unit
) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    var genre by remember { mutableStateOf(song.genre) }
    var yearText by remember { mutableStateOf(if (song.year > 0) song.year.toString() else "") }
    val displayTrack = albumTrackDisplayNumber(song.trackNumber)
    var trackText by remember { mutableStateOf(if (displayTrack > 0) displayTrack.toString() else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar información de canción") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artista") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Álbum") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text("Género") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = yearText,
                        onValueChange = { yearText = it.filter { ch -> ch.isDigit() }.take(4) },
                        label = { Text("Año") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = trackText,
                        onValueChange = { trackText = it.filter { ch -> ch.isDigit() }.take(3) },
                        label = { Text("Nº de pista") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val track = encodeAlbumTrack(
                    trackText.toIntOrNull() ?: 0,
                    albumDiscNumber(song.trackNumber)
                )
                onConfirm(title, artist, album, genre, yearText.toIntOrNull() ?: 0, track)
            }) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun EditAlbumMetadataDialog(
    album: com.bestiapop.android.data.model.Album,
    onDismiss: () -> Unit,
    onSaveAlbumOnly: (displayName: String, artist: String, genre: String, year: Int, artworkUri: String?) -> Unit,
    onSaveAlbumAndSongs: (displayName: String, artist: String, genre: String, year: Int, artworkUri: String?) -> Unit
) {
    var displayName by remember { mutableStateOf(album.displayName) }
    var artist by remember { mutableStateOf(album.artist) }
    var genre by remember { mutableStateOf(album.genre.orEmpty()) }
    var yearText by remember { mutableStateOf(if (album.year > 0) album.year.toString() else "") }
    var selectedUri by remember { mutableStateOf(album.artworkUri) }

    val imagePickerLauncher = rememberImagePicker { selectedUri = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar álbum") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ArtworkPickerBlock(
                    artworkUri = selectedUri,
                    onPick = { imagePickerLauncher.launch("image/*") },
                    buttonText = "Cambiar portada"
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Nombre del álbum") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artista") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text("Género") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = yearText,
                    onValueChange = { yearText = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = { Text("Año") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End
            ) {
                Button(
                    onClick = {
                        onSaveAlbumAndSongs(
                            displayName,
                            artist,
                            genre,
                            yearText.toIntOrNull() ?: 0,
                            selectedUri
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Guardar para álbum y canciones")
                }
                OutlinedButton(
                    onClick = {
                        onSaveAlbumOnly(
                            displayName,
                            artist,
                            genre,
                            yearText.toIntOrNull() ?: 0,
                            selectedUri
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Guardar para álbum")
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancelar")
                }
            }
        }
    )
}

@Composable
fun ConfirmMergeAlbumsDialog(
    source: com.bestiapop.android.data.model.Album,
    target: com.bestiapop.android.data.model.Album,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val yearLabel = if (target.year > 0) target.year.toString() else "—"
    val genreLabel = target.genre?.takeIf { it.isNotBlank() } ?: "—"
    val resultCount = source.songCount + target.songCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unir álbumes") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "«${source.displayName}» (${source.songCount} canciones) se unirá a «${target.displayName}» (${target.songCount} canciones).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Las canciones de «${source.displayName}» adoptarán exactamente la metadata de «${target.displayName}». Los demás campos del formulario se descartarán.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ArtworkThumbnail(
                        artworkUri = target.artworkUri,
                        size = 64.dp,
                        cornerRadius = 8.dp
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = target.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Artista: ${target.artist}", style = MaterialTheme.typography.bodySmall)
                        Text("Género: $genreLabel", style = MaterialTheme.typography.bodySmall)
                        Text("Año: $yearLabel", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "Resultado: $resultCount canciones",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Unir álbumes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun SetAlbumArtworkDialog(
    albumName: String,
    currentArtworkUri: String?,
    onDismiss: () -> Unit,
    onArtworkSelected: (String) -> Unit
) {
    var selectedUri by remember { mutableStateOf(currentArtworkUri) }

    val imagePickerLauncher = rememberImagePicker { selectedUri = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cambiar portada del álbum") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Asignar nueva portada para \"$albumName\". Todas las canciones del álbum heredarán esta imagen.",
                    style = MaterialTheme.typography.bodySmall
                )

                ArtworkPickerBlock(
                    artworkUri = selectedUri,
                    onPick = { imagePickerLauncher.launch("image/*") },
                    buttonText = "Seleccionar imagen de la galería",
                    spacing = 12.dp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    selectedUri?.let { onArtworkSelected(it) }
                },
                enabled = !selectedUri.isNullOrEmpty()
            ) {
                Text("Guardar Portada")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onCreateNewPlaylist: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar a playlist") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCreateNewPlaylist,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ Crear nueva playlist")
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.height(200.dp)) {
                    items(playlists) { playlist ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectPlaylist(playlist) }
                                .padding(vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkThumbnail(
                                    artworkUri = playlist.coverUri,
                                    size = 40.dp
                                )
                                Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                                Column {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "${playlist.songCount} canciones",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun ConfirmDeleteSongsDialog(
    songCount: Int,
    onDismiss: () -> Unit,
    onConfirmDeleteFromApp: () -> Unit,
    onConfirmDeleteFromDevice: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eliminar canción(es)") },
        text = {
            Text("¿Cómo deseas eliminar $songCount canción(es)?\n\n- Solo de la aplicación: Elimina el registro de la base de datos de BestiaPop.\n- Del dispositivo: Borra el archivo físico de audio de la memoria.")
        },
        confirmButton = {
            Button(
                onClick = onConfirmDeleteFromDevice,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Borrar del dispositivo")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
                TextButton(onClick = onConfirmDeleteFromApp) {
                    Text("Solo de la app")
                }
            }
        }
    )
}
