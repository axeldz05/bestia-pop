package com.bestiapop.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.BottomPlayerBar

@Composable
fun MainScreen(
    viewModel: MusicPlayerViewModel,
    onSelectFolderClick: () -> Unit
) {
    var selectedNavIndex by remember { mutableIntStateOf(0) }
    var showFullPlayer by remember { mutableStateOf(false) }

    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.playbackPositionMs.collectAsState()

    var targetPlaylistForAddition by remember { mutableStateOf<com.bestiapop.android.data.model.Playlist?>(null) }
    var selectedPlaylistIdForDetail by remember { mutableStateOf<Long?>(null) }

    val navItems = listOf(
        NavItem("Biblioteca", Icons.Default.LibraryMusic),
        NavItem("Playlists", Icons.Default.QueueMusic),
        NavItem("WiFi Sync", Icons.Default.Wifi),
        NavItem("Temas", Icons.Default.Palette)
    )

    Scaffold(
        bottomBar = {
            Column {
                if (!showFullPlayer) {
                    BottomPlayerBar(
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        progressMs = positionMs,
                        onPlayPauseClick = { viewModel.togglePlayPause() },
                        onNextClick = { viewModel.skipToNext() },
                        onBarClick = { showFullPlayer = true }
                    )
                }

                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    navItems.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selectedNavIndex == index,
                            onClick = {
                                selectedNavIndex = index
                                showFullPlayer = false
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
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
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                    onSongSelect = { showFullPlayer = true }
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
                2 -> WebServerScreen()
                3 -> ThemeSettingsScreen(viewModel = viewModel)
            }
        }

        AnimatedVisibility(
            visible = showFullPlayer,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            NowPlayingScreen(
                viewModel = viewModel,
                onDismiss = { showFullPlayer = false }
            )
        }
    }
}

private data class NavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
