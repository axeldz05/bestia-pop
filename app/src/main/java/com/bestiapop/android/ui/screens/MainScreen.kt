package com.bestiapop.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.preferences.activeDownloadBadgeCount
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.BottomPlayerBar

@Composable
fun MainScreen(
    viewModel: MusicPlayerViewModel,
    onSelectFolderClick: () -> Unit
) {
    var selectedNavIndex by remember { mutableIntStateOf(0) }
    var showFullPlayer by remember { mutableStateOf(false) }
    /** Ignores only the same-gesture UP after mid-drag dismiss lands on the mini bar. */
    var suppressBarOpenUntilElapsedRealtime by remember { mutableLongStateOf(0L) }

    val currentItem by viewModel.currentItem.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.playbackPositionMs.collectAsState()
    val radioStatusLabel by viewModel.radioStatusLabel.collectAsState()
    val resolvingRemote by viewModel.resolvingRemote.collectAsState()
    val radioLoading by viewModel.radioLoading.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val pendingOpenDownloads by viewModel.pendingOpenDownloads.collectAsState()
    val downloadConflict by viewModel.downloadConflict.collectAsState()
    val downloadBadgeCount = activeDownloadBadgeCount(activeDownloads)

    val miniPlayerStatusLabel = when {
        resolvingRemote -> "Resolviendo stream…"
        radioLoading -> "Armando radio…"
        else -> radioStatusLabel
    }

    LaunchedEffect(pendingOpenDownloads) {
        if (pendingOpenDownloads) {
            selectedNavIndex = 2
            showFullPlayer = false
            viewModel.consumeOpenDownloads()
        }
    }

    var targetPlaylistForAddition by remember { mutableStateOf<com.bestiapop.android.data.model.Playlist?>(null) }
    var selectedPlaylistIdForDetail by remember { mutableStateOf<Long?>(null) }

    val density = LocalDensity.current
    var bottomChromeHeightPx by remember { mutableIntStateOf(0) }
    val bottomChromePadding = with(density) {
        if (bottomChromeHeightPx > 0) bottomChromeHeightPx.toDp() else 152.dp
    }

    val navItems = listOf(
        NavItem("Biblioteca", Icons.Default.LibraryMusic),
        NavItem("Playlists", Icons.Default.QueueMusic),
        NavItem("Descargas", Icons.Default.Download),
        NavItem("WiFi Sync", Icons.Default.Wifi),
        NavItem("Ajustes", Icons.Default.Settings)
    )

    fun openFullPlayer() {
        if (android.os.SystemClock.elapsedRealtime() < suppressBarOpenUntilElapsedRealtime) return
        showFullPlayer = true
    }

    fun dismissFullPlayer() {
        showFullPlayer = false
        // Brief enough to drop the same swipe's UP, not a deliberate follow-up tap.
        suppressBarOpenUntilElapsedRealtime = android.os.SystemClock.elapsedRealtime() + 50L
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(bottom = bottomChromePadding)
            ) {
                when (selectedNavIndex) {
                    0 -> LibraryScreen(
                        viewModel = viewModel,
                        targetPlaylistForAddition = targetPlaylistForAddition,
                        onCompletePlaylistAddition = {
                            selectedPlaylistIdForDetail = targetPlaylistForAddition?.id
                            targetPlaylistForAddition = null
                            selectedNavIndex = 1
                        },
                        onCancelPlaylistAddition = {
                            selectedPlaylistIdForDetail = targetPlaylistForAddition?.id
                            targetPlaylistForAddition = null
                            selectedNavIndex = 1
                        },
                        onSelectFolderClick = onSelectFolderClick,
                        onSongSelect = { openFullPlayer() },
                        onOpenDownloads = {
                            selectedNavIndex = 2
                        }
                    )
                    1 -> PlaylistsScreen(
                        viewModel = viewModel,
                        activeSelectedPlaylistId = selectedPlaylistIdForDetail,
                        onSelectPlaylistDetail = { id ->
                            selectedPlaylistIdForDetail = id
                        },
                        onAddSongsRequest = { playlist ->
                            selectedPlaylistIdForDetail = playlist.id
                            targetPlaylistForAddition = playlist
                            selectedNavIndex = 0
                        }
                    )
                    2 -> DownloadsScreen(viewModel = viewModel)
                    3 -> WebServerScreen()
                    4 -> SettingsScreen(viewModel = viewModel)
                }
            }
        }

        // Chrome sits above page content. Now Playing (when open) sits above chrome.
        // When dismissed, this Column is the topmost layer over the bar region — immediately tappable.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned { bottomChromeHeightPx = it.size.height }
        ) {
            BottomPlayerBar(
                currentItem = currentItem,
                isPlaying = isPlaying,
                progressMs = positionMs,
                onPlayPauseClick = { viewModel.togglePlayPause() },
                onPreviousClick = { viewModel.skipToPrevious() },
                onNextClick = { viewModel.skipToNext() },
                onBarClick = { openFullPlayer() },
                statusLabel = miniPlayerStatusLabel
            )

            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                navItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedNavIndex == index,
                        onClick = {
                            selectedNavIndex = index
                            dismissFullPlayer()
                        },
                        icon = {
                            if (index == 2 && downloadBadgeCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge {
                                            Text(
                                                if (downloadBadgeCount > 9) "9+" else downloadBadgeCount.toString()
                                            )
                                        }
                                    }
                                ) {
                                    Icon(item.icon, contentDescription = item.label)
                                }
                            } else {
                                Icon(item.icon, contentDescription = item.label)
                            }
                        },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }

        if (showFullPlayer) {
            NowPlayingScreen(
                viewModel = viewModel,
                onDismiss = { dismissFullPlayer() }
            )
        }

        downloadConflict?.let { conflict ->
            com.bestiapop.android.ui.components.DownloadConflictDialog(
                conflict = conflict,
                onOverwrite = { viewModel.resolveDownloadConflictOverwrite() },
                onSaveAs = { title -> viewModel.resolveDownloadConflictSaveAs(title) },
                onCancel = { viewModel.cancelDownloadConflict() }
            )
        }
    }
}

private data class NavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
