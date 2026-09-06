package com.bestiapop.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.WifiTransferItem
import com.bestiapop.android.data.model.WifiTransferState
import com.bestiapop.android.data.model.isFailed
import com.bestiapop.android.data.model.isInFlight
import com.bestiapop.android.service.WebServerService
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.SongListItem
import com.bestiapop.android.ui.components.TrackTextColumn
import com.bestiapop.android.ui.components.rememberSongQueueActions
import com.bestiapop.android.ui.screens.library.SongActionDialogsController
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs

/**
 * Level 2 / 3: Screen coordinator for adding and importing music.
 * Integrates WiFi transfer, local folder import (SAF), and URL downloads.
 */
@Composable
fun WebServerScreen(
    viewModel: MusicPlayerViewModel,
    onSelectFolderClick: () -> Unit = {},
    onOpenDownloads: () -> Unit = {}
) {
    val context = LocalContext.current
    val serverAddress by WebServerService.serverState.collectAsStateWithLifecycle()
    val transfers by WebServerService.transfers.collectAsStateWithLifecycle()
    val songList by viewModel.libraryProjection.songList.collectAsStateWithLifecycle()
    val identifyReview by viewModel.identifyReview.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val currentSongId = (currentItem as? PlayableItem.Local)?.song?.id

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var linkUrlInput by rememberSaveable { mutableStateOf("") }

    val linkDownloads = remember(activeDownloads) {
        activeDownloads.filter {
            it.source == ActiveDownloadSource.LINK &&
                (it.state.isInFlight || it.state.isFailed)
        }
    }

    val songDialogs = rememberSongActionDialogs(
        viewModel = viewModel,
        playlists = playlists,
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

    val songActions = rememberSongQueueActions(viewModel)
    val songsById = songList.songsById

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DriveFolderUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(32.dp)
                    .padding(end = 8.dp)
            )
            Column {
                Text(
                    text = "Añadir e Importar",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "WiFi, carpetas locales o enlace web",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Navigation Tabs (Level 2)
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("WiFi Sync", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Wifi, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Carpeta local", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Por enlace", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Link, contentDescription = null) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        when (selectedTab) {
            0 -> WifiSyncTabContent(
                serverAddress = serverAddress,
                transfers = transfers,
                songsById = songsById,
                currentSongId = currentSongId,
                pendingConflicts = identifyReview.pendingCount,
                onToggleServer = { start ->
                    val intent = Intent(context, WebServerService::class.java)
                    if (start) {
                        context.startForegroundService(intent)
                    } else {
                        context.stopService(intent)
                    }
                },
                onCopyUrl = { url ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("WiFi Sync", url))
                    Toast.makeText(context, "Link copiado", Toast.LENGTH_SHORT).show()
                },
                onShowIdentifyReview = { viewModel.showIdentifyReview() },
                onDismissTransfer = { id -> WebServerService.dismissTransfer(id) },
                onPlaySong = { song -> viewModel.playSong(song) },
                songActions = songActions,
                songDialogs = songDialogs
            )
            1 -> LocalFolderTabContent(
                onSelectFolderClick = onSelectFolderClick
            )
            2 -> LinkDownloaderTabContent(
                urlInput = linkUrlInput,
                onUrlInputChange = { linkUrlInput = it },
                linkDownloads = linkDownloads,
                onDownloadClick = {
                    viewModel.downloadFromUrl(linkUrlInput)
                },
                onRetryDownload = { id -> viewModel.retryActiveDownload(id) },
                onOpenDownloads = onOpenDownloads
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// Level 1 Primitives: Specialized Tab Contents
// ---------------------------------------------------------------------------

/**
 * Level 1: WiFi sync server controls, active transfers, and instructions.
 */
@Composable
fun WifiSyncTabContent(
    serverAddress: String?,
    transfers: List<WifiTransferItem>,
    songsById: Map<Long, Song>,
    currentSongId: Long?,
    pendingConflicts: Int,
    onToggleServer: (Boolean) -> Unit,
    onCopyUrl: (String) -> Unit,
    onShowIdentifyReview: () -> Unit,
    onDismissTransfer: (String) -> Unit,
    onPlaySong: (Song) -> Unit,
    songActions: com.bestiapop.android.ui.components.SongQueueActions,
    songDialogs: SongActionDialogsController
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
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

                Spacer(modifier = Modifier.height(12.dp))

                if (serverAddress != null) {
                    val urlDisplay = "http://$serverAddress"
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
                                text = "Añadí música desde tu PC o celular abriendo:",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = { onCopyUrl(urlDisplay) })
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
                                IconButton(onClick = { onCopyUrl(urlDisplay) }) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copiar link",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Text(
                                text = "Tocá para copiar · abrí en el navegador",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Encendé el servidor para transferir canciones por WiFi desde tu computadora u otro celular sin cables.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

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
                        onCheckedChange = onToggleServer,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Transfers header & conflicts
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

        if (pendingConflicts > 0) {
            TextButton(
                onClick = onShowIdentifyReview,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (pendingConflicts == 1) {
                        "Revisar conflictos de información (1)"
                    } else {
                        "Revisar conflictos de información ($pendingConflicts)"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
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
                        onClick = { onPlaySong(doneSong) },
                        onPlayNext = { songActions.onPlayNext(doneSong) },
                        onAddToQueue = { songActions.onAddToQueue(doneSong) },
                        onStartRadio = { songActions.onStartRadio(doneSong) },
                        onAddToPlaylist = { songDialogs.onAddToPlaylist(doneSong) },
                        onEditMetadata = { songDialogs.onEdit(doneSong) },
                        onEditLyrics = { songDialogs.onEditLyrics(doneSong) },
                        onIdentify = { /* Handled through songsById */ },
                        onDelete = { songDialogs.onDelete(doneSong) }
                    )
                } else {
                    WifiTransferProgressRow(
                        transfer = transfer,
                        onDismiss = { onDismissTransfer(transfer.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

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
                Text("4. Ingresá la dirección IP:Puerto y arrastrá tus archivos de audio.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Level 1: Local folder import using Storage Access Framework (SAF).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalFolderTabContent(
    onSelectFolderClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Importar Carpeta de Música",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Seleccioná cualquier carpeta en tu dispositivo o tarjeta SD para escanear e incorporar archivos de música a tu biblioteca.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Supported audio formats chips
                Text(
                    text = "Formatos admitidos:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("MP3", "FLAC", "M4A", "OGG", "WAV", "OPUS").forEach { format ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(horizontal = 3.dp)
                        ) {
                            Text(
                                text = format,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onSelectFolderClick,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Seleccionar Carpeta", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Consejos de almacenamiento:",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Podés elegir carpetas como 'Music', 'Download' o cualquier directorio de tu tarjeta SD.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• BestiaPop preserva tus archivos originales sin modificarlos a menos que actives la escritura de etiquetas en Ajustes.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• Los metadatos y carátulas se leen de forma automática; si faltan datos, la app puede identificarlos online.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Level 1: Download audio track by web/YouTube URL.
 */
@Composable
fun LinkDownloaderTabContent(
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    linkDownloads: List<ActiveDownload>,
    onDownloadClick: () -> Unit,
    onRetryDownload: (String) -> Unit,
    onOpenDownloads: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val isDownloading = linkDownloads.any { it.state.isInFlight }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Descargar por Enlace Web",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Pegá un enlace de YouTube (youtube.com o youtu.be) o ingresá la URL de un audio para descargarlo e incorporarlo a tu biblioteca.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = onUrlInputChange,
                    placeholder = { Text("https://youtube.com/watch?v=… o https://youtu.be/…") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    trailingIcon = {
                        if (urlInput.isNotEmpty()) {
                            IconButton(onClick = { onUrlInputChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Limpiar")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            if (urlInput.isNotBlank() && !isDownloading) {
                                onDownloadClick()
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        keyboardController?.hide()
                        onDownloadClick()
                    },
                    enabled = urlInput.isNotBlank() && !isDownloading,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Descargar MP3 y Agregar", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                ActiveDownloadsSummaryBanner(
                    downloads = linkDownloads,
                    onRetry = onRetryDownload,
                    onOpenDownloads = onOpenDownloads
                )
            }
        }
    }
}

/**
 * Level 1: Summary banner for downloads in progress or with errors.
 */
@Composable
fun ActiveDownloadsSummaryBanner(
    downloads: List<ActiveDownload>,
    onRetry: (String) -> Unit,
    onOpenDownloads: () -> Unit
) {
    if (downloads.isEmpty()) return
    val downloading = downloads.filter { it.state.isInFlight }
    val failed = downloads.filter { it.state.isFailed }
    if (downloading.isEmpty() && failed.isEmpty()) return

    AnimatedVisibility(visible = true) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (downloading.isNotEmpty()) {
                val latest = downloading.first()
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (downloading.size == 1) {
                                latest.progressMessage
                                    ?: DownloadMessages.downloadingQuoted(latest.displayLabel)
                            } else {
                                DownloadMessages.downloadingCount(downloading.size)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            if (failed.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (failed.size == 1) {
                                failed.first().errorMessage
                                    ?: DownloadMessages.failedQuoted(failed.first().displayLabel)
                            } else {
                                DownloadMessages.downloadsFailed(failed.size)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onRetry(failed.first().id) }) {
                                Text("Reintentar", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = onOpenDownloads) {
                                Text("Ver descargas", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
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
                TrackTextColumn(
                    title = transfer.title.ifBlank { transfer.fileName },
                    subtitle = when (transfer.state) {
                        WifiTransferState.PENDING -> "Pendiente"
                        WifiTransferState.UPLOADING -> "Recibiendo… ${transfer.progressPercent}%"
                        WifiTransferState.PROCESSING -> "Procesando…"
                        WifiTransferState.DONE -> transfer.artist
                        WifiTransferState.ERROR -> transfer.errorMessage ?: "Error"
                    },
                    titleStyle = MaterialTheme.typography.bodyMedium,
                    titleWeight = FontWeight.SemiBold,
                    subtitleColor = if (transfer.state == WifiTransferState.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    }
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

