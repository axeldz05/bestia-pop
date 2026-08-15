package com.bestiapop.android.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.preferences.activeDownloadBadgeCount
import com.bestiapop.android.data.system.BackgroundExecutionProbe
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.BottomPlayerBar
import com.bestiapop.android.ui.update.AppUpdateDialogs
import com.bestiapop.android.ui.update.AppUpdateUiState
import com.bestiapop.android.ui.update.AppUpdateViewModel
import kotlinx.coroutines.launch

private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

@Composable
fun MainScreen(
    viewModel: MusicPlayerViewModel,
    appUpdateViewModel: AppUpdateViewModel,
    onSelectFolderClick: () -> Unit,
    onRequestUnknownSources: () -> Unit
) {
    val selectedNavIndex by viewModel.selectedNavIndex.collectAsState()
    var showFullPlayer by remember { mutableStateOf(false) }
    /** Ignores only the same-gesture UP after mid-drag dismiss lands on the mini bar. */
    var suppressBarOpenUntilElapsedRealtime by remember { mutableLongStateOf(0L) }
    var lastExitBackAtMs by remember { mutableLongStateOf(0L) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val currentItem by viewModel.currentItem.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val radioStatusLabel by viewModel.radioStatusLabel.collectAsState()
    val resolvingRemote by viewModel.resolvingRemote.collectAsState()
    val radioLoading by viewModel.radioLoading.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val pendingOpenDownloads by viewModel.pendingOpenDownloads.collectAsState()
    val downloadConflict by viewModel.downloadConflict.collectAsState()
    val identifyReview by viewModel.identifyReview.collectAsState()
    val identifySetup by viewModel.identifySetup.collectAsState()
    val pendingAlbumMerge by viewModel.pendingAlbumMerge.collectAsState()
    val appUpdateState by appUpdateViewModel.state.collectAsState()
    val downloadBadgeCount = activeDownloadBadgeCount(activeDownloads)
    val backgroundExecutionStatus by viewModel.backgroundExecutionStatus.collectAsState()
    val oemScreenOffCleanupHintDismissed by viewModel.oemScreenOffCleanupHintDismissed.collectAsState()
    val oemScreenOffCleanupIntent = remember(context) {
        BackgroundExecutionProbe.oemScreenOffCleanupIntent(context)
    }
    val restrictionGuidance = remember {
        BackgroundExecutionProbe.restrictionGuidance()
    }

    val miniPlayerStatusLabel = when {
        resolvingRemote -> "Resolviendo stream…"
        radioLoading -> MusicPlayerViewModel.RADIO_LOADING_LABEL
        else -> radioStatusLabel
    }

    fun clearPendingExit() {
        lastExitBackAtMs = 0L
    }

    // Any back consumed by a nested layer has to restart the 2s window; otherwise
    // root-back (warning) → nested-back → root-back exited without a second warning, spending a
    // prompt the user had already "used up" on an unrelated gesture.
    LaunchedEffect(
        selectedNavIndex,
        showFullPlayer,
        identifyReview.isVisible,
        identifySetup != null,
        pendingAlbumMerge,
        downloadConflict
    ) {
        clearPendingExit()
    }

    LaunchedEffect(pendingOpenDownloads) {
        if (pendingOpenDownloads) {
            viewModel.openDownloadsTabTransient()
            showFullPlayer = false
            clearPendingExit()
            viewModel.consumeOpenDownloads()
        }
    }

    LaunchedEffect(Unit) {
        appUpdateViewModel.maybeCheckOnLaunch()
    }

    val needsUnknownSources = appUpdateState is AppUpdateUiState.NeedsInstallPermission
    val apkReadyToInstall = (appUpdateState as? AppUpdateUiState.ReadyToInstall)?.apkFile
    LaunchedEffect(needsUnknownSources) {
        if (needsUnknownSources) onRequestUnknownSources()
    }
    LaunchedEffect(apkReadyToInstall) {
        val apk = apkReadyToInstall ?: return@LaunchedEffect
        appUpdateViewModel.launchInstaller(apk)
    }

    var targetPlaylistForAddition by remember { mutableStateOf<com.bestiapop.android.data.model.Playlist?>(null) }

    val density = LocalDensity.current
    var bottomChromeHeightPx by remember { mutableIntStateOf(0) }
    val bottomChromePadding = with(density) {
        if (bottomChromeHeightPx > 0) bottomChromeHeightPx.toDp() else 152.dp
    }

    val navItems = listOf(
        NavItem("Biblioteca", Icons.Default.LibraryMusic),
        NavItem("Playlists", Icons.AutoMirrored.Filled.QueueMusic),
        NavItem("Descargas", Icons.Default.Download),
        NavItem("WiFi Sync", Icons.Default.Wifi),
        NavItem("Ajustes", Icons.Default.Settings)
    )

    fun openFullPlayer() {
        if (android.os.SystemClock.elapsedRealtime() < suppressBarOpenUntilElapsedRealtime) return
        showFullPlayer = true
        clearPendingExit()
    }

    fun dismissFullPlayer() {
        showFullPlayer = false
        // Brief enough to drop the same swipe's UP, not a deliberate follow-up tap.
        suppressBarOpenUntilElapsedRealtime = android.os.SystemClock.elapsedRealtime() + 50L
    }

    // Root-tab exit only: nested screens / Now Playing register their own BackHandlers above this.
    BackHandler {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastExitBackAtMs > 0L && now - lastExitBackAtMs <= EXIT_CONFIRM_WINDOW_MS) {
            (context as? Activity)?.finish()
        } else {
            lastExitBackAtMs = now
            scope.launch {
                snackbarHostState.showSnackbar("Pulsa otra vez para salir")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Only statusBars here: bottom chrome (mini player + NavigationBar) already owns
        // navigation-bar insets. Applying systemBars in Scaffold would double-pad and leave
        // a empty strip between page content and BottomPlayerBar (worse with nested Scaffolds).
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.statusBars
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
                            val playlistId = targetPlaylistForAddition?.id
                            targetPlaylistForAddition = null
                            if (playlistId != null) viewModel.openLocalPlaylist(playlistId)
                            viewModel.setSelectedNavIndex(1)
                            clearPendingExit()
                        },
                        onCancelPlaylistAddition = {
                            val playlistId = targetPlaylistForAddition?.id
                            targetPlaylistForAddition = null
                            if (playlistId != null) viewModel.openLocalPlaylist(playlistId)
                            viewModel.setSelectedNavIndex(1)
                            clearPendingExit()
                        },
                        onSelectFolderClick = onSelectFolderClick,
                        onOpenDownloads = {
                            viewModel.setSelectedNavIndex(2)
                            clearPendingExit()
                        }
                    )
                    1 -> PlaylistsScreen(
                        viewModel = viewModel,
                        onAddSongsRequest = { playlist ->
                            viewModel.openLocalPlaylist(playlist.id)
                            targetPlaylistForAddition = playlist
                            viewModel.setSelectedNavIndex(0)
                            clearPendingExit()
                        }
                    )
                    2 -> DownloadsScreen(viewModel = viewModel)
                    3 -> WebServerScreen(viewModel = viewModel)
                    4 -> SettingsScreen(
                        viewModel = viewModel,
                        appUpdateViewModel = appUpdateViewModel
                    )
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
            if (backgroundExecutionStatus.blocksBackgroundPlayback) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = restrictionGuidance.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = restrictionGuidance.body,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = viewModel::openPlaybackSettings) {
                            Text("Abrir ajustes")
                        }
                    }
                }
            }
            BottomPlayerBar(
                currentItem = currentItem,
                isPlaying = isPlaying,
                positionMsFlow = viewModel.playbackPositionMs,
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
                            viewModel.setSelectedNavIndex(index)
                            dismissFullPlayer()
                            clearPendingExit()
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomChromePadding + 8.dp),
            snackbar = { data ->
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier.testTag("root-exit-confirmation")
                )
            }
        )

        if (showFullPlayer) {
            NowPlayingScreen(
                viewModel = viewModel,
                onDismiss = { dismissFullPlayer() }
            )
        }

        if (identifyReview.isOpen) {
            IdentifyReviewScreen(viewModel = viewModel)
        }

        identifySetup?.let { setup ->
            com.bestiapop.android.ui.components.IdentifySetupDialog(
                songs = setup.songs,
                applyFields = setup.applyFields,
                contextTitle = setup.contextTitle,
                onFieldsChanged = { viewModel.setIdentifySetupFields(it) },
                onConfirm = { viewModel.confirmIdentifySetup() },
                onDismiss = { viewModel.dismissIdentifySetup() }
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

        pendingAlbumMerge?.let { pending ->
            com.bestiapop.android.ui.screens.library.ConfirmMergeAlbumsDialog(
                source = pending.source,
                target = pending.target,
                onDismiss = { viewModel.dismissPendingAlbumMerge() },
                onConfirm = {
                    val sourceKey = pending.source.name
                    val targetKey = pending.target.name
                    viewModel.confirmPendingAlbumMerge()
                    viewModel.renameRestoredLibraryAlbum(sourceKey, targetKey)
                }
            )
        }

        AppUpdateDialogs(
            state = appUpdateState,
            onConfirmUpdate = { appUpdateViewModel.confirmUpdate() },
            onDismiss = { appUpdateViewModel.dismiss() }
        )

        val blockingOverlay = identifyReview.isOpen ||
            identifySetup != null ||
            downloadConflict != null ||
            pendingAlbumMerge != null ||
            appUpdateState !is AppUpdateUiState.Idle
        if (
            oemScreenOffCleanupIntent != null &&
            backgroundExecutionStatus.oemScreenOffCleanupActive &&
            !oemScreenOffCleanupHintDismissed &&
            !blockingOverlay
        ) {
            AlertDialog(
                onDismissRequest = viewModel::dismissOemScreenOffCleanupHint,
                title = { Text("Cerrar al apagar la pantalla") },
                text = {
                    Text(
                        "Este teléfono puede cerrar apps al bloquear o apagar la pantalla y cortar " +
                            "la reproducción. Desactivá esa opción en Batería para BestiaPop."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.dismissOemScreenOffCleanupHint()
                            BackgroundExecutionProbe.openOemScreenOffCleanupSettings(context)
                        }
                    ) {
                        Text("Abrir ajuste")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissOemScreenOffCleanupHint) {
                        Text("Ahora no")
                    }
                }
            )
        }
    }
}

private data class NavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
