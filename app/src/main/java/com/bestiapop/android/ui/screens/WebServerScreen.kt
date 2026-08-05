package com.bestiapop.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.WifiTransferItem
import com.bestiapop.android.data.model.WifiTransferState
import com.bestiapop.android.service.WebServerService
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.SongListItem
import com.bestiapop.android.ui.screens.library.SongActionDialogsHost

@Composable
fun WebServerScreen(viewModel: MusicPlayerViewModel) {
    val context = LocalContext.current
    val serverAddress by WebServerService.serverState.collectAsState()
    val transfers by WebServerService.transfers.collectAsState()
    val songs by viewModel.songsState.collectAsState()
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val currentItem by viewModel.currentItem.collectAsState()
    val currentSongId = (currentItem as? PlayableItem.Local)?.song?.id

    var editingSong by remember { mutableStateOf<Song?>(null) }
    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var songsForDeletion by remember { mutableStateOf<List<Song>?>(null) }

    val songsById = remember(songs) { songs.associateBy { it.id } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = "Transferencia WiFi",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = if (serverAddress != null) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = if (serverAddress != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                Text(
                    text = if (serverAddress != null) "Servidor Web Activo" else "Servidor Inactivo",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (serverAddress != null) {
                    val urlDisplay = "http://$serverAddress"
                    val copyUrl = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("WiFi Sync", urlDisplay))
                        Toast.makeText(context, "Link copiado", Toast.LENGTH_SHORT).show()
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Podés añadir música desde otro dispositivo usando la url:",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = copyUrl)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = urlDisplay,
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 20.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                IconButton(onClick = copyUrl) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copiar link",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Text(
                                text = "Tocá para copiar · abrí en el navegador",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Encendé el servidor para poder transferir canciones por WiFi desde tu computadora o celular sin cables.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (serverAddress != null) "Servidor encendido" else "Servidor apagado",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 12.dp)
                    )

                    Switch(
                        checked = serverAddress != null,
                        onCheckedChange = { start ->
                            val intent = Intent(context, WebServerService::class.java)
                            if (start) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            } else {
                                context.stopService(intent)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Transferencias",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (transfers.isNotEmpty()) {
                Text(
                    text = "${transfers.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (transfers.isEmpty()) {
            Text(
                text = "Las canciones recibidas o en proceso aparecerán aquí",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center
            )
        } else {
            transfers.forEach { transfer ->
                val doneSong = transfer.songId?.let { songsById[it] }
                if (transfer.state == WifiTransferState.DONE && doneSong != null) {
                    SongListItem(
                        song = doneSong,
                        isCurrentPlaying = currentSongId == doneSong.id,
                        onClick = {
                            viewModel.playSong(doneSong)
                        },
                        onPlayNext = { viewModel.playNextInQueue(doneSong) },
                        onAddToQueue = { viewModel.playNextBatch(listOf(doneSong)) },
                        onStartRadio = { viewModel.startRadio(doneSong) },
                        onAddToPlaylist = { songForPlaylist = doneSong },
                        onEditMetadata = { editingSong = doneSong },
                        onDelete = { songsForDeletion = listOf(doneSong) }
                    )
                } else {
                    WifiTransferProgressRow(
                        transfer = transfer,
                        onDismiss = { WebServerService.dismissTransfer(transfer.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Instrucciones de uso:",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("1. Conectá ambos dispositivos a la misma red WiFi.", style = MaterialTheme.typography.bodySmall)
                Text("2. Activá la llave del servidor web de arriba.", style = MaterialTheme.typography.bodySmall)
                Text("3. Abrí el navegador web en la computadora o teléfono.", style = MaterialTheme.typography.bodySmall)
                Text("4. Ingresá la dirección IP:Puerto que figura arriba y arrastrá tus archivos MP3/FLAC.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    SongActionDialogsHost(
        editingSong = editingSong,
        songForPlaylistAddition = songForPlaylist,
        songsForDeletion = songsForDeletion,
        playlists = playlists,
        viewModel = viewModel,
        onDismissEdit = { editingSong = null },
        onDismissPlaylist = { songForPlaylist = null },
        onDismissDelete = { songsForDeletion = null },
        onSelectPlaylist = { playlist, song ->
            viewModel.addSongToPlaylist(playlist.id, song)
        },
        onAfterDelete = { targetSongs ->
            targetSongs.forEach { song ->
                transfers.find { it.songId == song.id }?.let {
                    WebServerService.dismissTransfer(it.id)
                }
            }
        }
    )
}

@Composable
private fun WifiTransferProgressRow(
    transfer: WifiTransferItem,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkThumbnail(
                artworkUri = transfer.artworkUri,
                size = 48.dp,
                cornerRadius = 8.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.title.ifBlank { transfer.fileName },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (transfer.state) {
                        WifiTransferState.PENDING -> "Pendiente"
                        WifiTransferState.UPLOADING -> "Recibiendo… ${transfer.progressPercent}%"
                        WifiTransferState.PROCESSING -> "Procesando…"
                        WifiTransferState.DONE -> transfer.artist
                        WifiTransferState.ERROR -> transfer.errorMessage ?: "Error"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (transfer.state == WifiTransferState.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (transfer.state == WifiTransferState.UPLOADING ||
                    transfer.state == WifiTransferState.PROCESSING
                ) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { transfer.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            when (transfer.state) {
                WifiTransferState.UPLOADING, WifiTransferState.PROCESSING, WifiTransferState.PENDING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                WifiTransferState.ERROR, WifiTransferState.DONE -> {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Descartar",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
