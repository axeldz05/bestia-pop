package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.NAV_DISCOVER
import com.bestiapop.android.data.preferences.NAV_LIBRARY
import com.bestiapop.android.data.util.SyncedLyrics
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkHero
import com.bestiapop.android.ui.components.DownloadStateTrailing
import com.bestiapop.android.ui.components.PlaybackScrubber
import com.bestiapop.android.ui.components.QueueItemRow
import com.bestiapop.android.ui.components.RadioModeControl
import com.bestiapop.android.ui.components.findUiDownloadByTrack
import com.bestiapop.android.ui.components.focusedQueueIndex
import com.bestiapop.android.ui.components.formatDuration
import com.bestiapop.android.ui.components.playPauseVector
import com.bestiapop.android.ui.screens.library.AlbumEditDialogsHost
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs
import com.bestiapop.android.ui.state.PlaylistDetailNav
import com.bestiapop.android.ui.theme.ListDensity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun NowPlayingScreen(
    viewModel: MusicPlayerViewModel,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    val currentItem by viewModel.currentItem.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    // Do NOT collect playbackPositionMs here — it ticks every 200ms and would recompose
    // the whole screen (including the Cola LazyColumn). Scrubber/lyrics collect locally.
    val repeatMode by viewModel.repeatMode.collectAsState()
    val isShuffle by viewModel.isShuffle.collectAsState()
    val queueItems by viewModel.displayQueue.collectAsState()
    val resolvingRemote by viewModel.resolvingRemote.collectAsState()
    val radioActive by viewModel.radioActive.collectAsState()
    val radioLoading by viewModel.radioLoading.collectAsState()
    val radioMode by viewModel.radioMode.collectAsState()
    val radioStatusLabel by viewModel.radioStatusLabel.collectAsState()
    val albums by viewModel.libraryProjection.albums.collectAsState()
    val artists by viewModel.libraryProjection.artists.collectAsState()
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val discoverOrigin by viewModel.discoverPlaybackOrigin.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()

    var actionsMenuExpanded by remember { mutableStateOf(false) }
    val songDialogs = rememberSongActionDialogs(
        viewModel = viewModel,
        playlists = playlists,
        onAfterPlaylistAdd = {
            if (viewModel.navigation.value.playlistDetail is PlaylistDetailNav.Local) {
                onDismiss()
            }
        }
    )
    var albumForEdit by remember { mutableStateOf<Album?>(null) }
    var containingPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val queueListState = rememberLazyListState()

    val item = currentItem ?: return
    val baseLocalSong = (item as? PlayableItem.Local)?.song
    val localSong = when {
        baseLocalSong == null -> null
        currentSong?.id == baseLocalSong.id -> baseLocalSong.copy(
            lyrics = currentSong?.lyrics ?: baseLocalSong.lyrics
        )
        else -> baseLocalSong
    }
    val albumLabel = when (item) {
        is PlayableItem.Local -> item.song.album
        is PlayableItem.Remote -> item.album.takeIf { it.isNotBlank() } ?: "Stream"
    }
    val matchedAlbum = remember(albums, item.album) {
        item.album.takeIf { it.isNotBlank() }?.let { albumName ->
            albums.firstOrNull { it.name.equals(albumName, ignoreCase = true) }
        }
    }
    val matchedArtist = remember(artists, item.artist) {
        item.artist.takeIf { it.isNotBlank() }?.let { artistName ->
            artists.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
        }
    }

    LaunchedEffect(localSong?.id, playlists) {
        val songId = localSong?.id
        containingPlaylists = if (songId != null) {
            viewModel.playlistsContainingSong(songId)
        } else {
            emptyList()
        }
    }

    fun goToLibrary(open: () -> Unit) {
        viewModel.setSearchQuery("")
        viewModel.setSelectedNavIndex(NAV_LIBRARY)
        open()
        onDismiss()
    }

    fun goToPlaylists(open: () -> Unit) {
        open()
        onDismiss()
    }

    fun goToDiscover(open: () -> Unit) {
        viewModel.setSelectedNavIndex(NAV_DISCOVER)
        open()
        onDismiss()
    }

    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = configuration.densityDpi / 160f
    val screenHeightPx = configuration.screenHeightDp * density
    val dismissThresholdPx = screenHeightPx * 0.30f

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val enterOffset = remember { Animatable(screenHeightPx) }

    LaunchedEffect(Unit) {
        enterOffset.animateTo(0f, tween(280))
    }

    fun settleSwipeDismiss() {
        if (dragOffset > dismissThresholdPx) {
            onDismiss()
        } else if (dragOffset > 0f) {
            val start = dragOffset
            coroutineScope.launch {
                Animatable(start).animateTo(0f, tween(200)) {
                    dragOffset = value
                }
            }
        }
    }

    val nestedScrollConnection = remember(dismissThresholdPx, onDismiss) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (dragOffset > 0f) {
                    val old = dragOffset
                    val newOffset = (old + delta).coerceAtLeast(0f)
                    dragOffset = newOffset
                    return Offset(0f, newOffset - old)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val delta = available.y
                if (delta > 0f) {
                    dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                    if (dragOffset > dismissThresholdPx) {
                        onDismiss()
                    }
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                settleSwipeDismiss()
                return available
            }
        }
    }

    val currentQueueIndex = remember(queueItems, item.queueEntryId) {
        focusedQueueIndex(queueItems, item.queueEntryId)
    }

    val surfaceModifier = Modifier
        .fillMaxSize()
        .nestedScroll(nestedScrollConnection)
        .graphicsLayer {
            translationY = enterOffset.value + dragOffset
            alpha = (1f - (dragOffset / screenHeightPx)).coerceIn(0f, 1f)
        }

    Surface(
        modifier = surfaceModifier,
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header: Close Chevron + Segmented Pill Tab Selector + Radio Control
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Cerrar reproductor",
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                NowPlayingTabSelector(
                    selectedTab = pagerState.currentPage,
                    onTabSelected = { page ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(page)
                        }
                    }
                )

                RadioModeControl(
                    radioActive = radioActive,
                    radioLoading = radioLoading,
                    activeMode = radioMode,
                    onStartMode = { mode ->
                        viewModel.startRadio(mode = mode, announceMode = true)
                    },
                    onStop = viewModel::stopRadio
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> {
                        // Page 0: Portada + Cola (Unified vertical scroll + Docked mini player)
                        val showDockedBar by remember {
                            derivedStateOf { queueListState.firstVisibleItemIndex >= 3 }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = queueListState,
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                contentPadding = PaddingValues(top = 0.dp, bottom = 170.dp)
                            ) {
                                // 1. Hero Artwork
                                item(key = "hero_artwork") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp, bottom = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ArtworkHero(
                                            uri = item.artworkUri,
                                            contentDescription = item.title,
                                            fallback = Icons.Default.MusicNote,
                                            fallbackTint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .fillMaxWidth(0.80f)
                                                .aspectRatio(1f)
                                        )
                                    }
                                }

                                // 2. Track Info & Actions
                                item(key = "track_info") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp)
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 36.dp)
                                        ) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onBackground,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "${item.artist} • $albumLabel",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            if (resolvingRemote) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "Resolviendo stream…",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            } else if (radioLoading) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = MusicPlayerViewModel.RADIO_LOADING_LABEL,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            } else if (radioStatusLabel != null) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = radioStatusLabel!!,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }

                                        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                                            IconButton(onClick = { actionsMenuExpanded = true }) {
                                                Icon(
                                                    imageVector = Icons.Default.MoreVert,
                                                    contentDescription = "Acciones de la canción",
                                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                                )
                                            }
                                            NowPlayingActionsMenu(
                                                expanded = actionsMenuExpanded,
                                                onDismiss = { actionsMenuExpanded = false },
                                                matchedAlbumName = matchedAlbum?.name,
                                                matchedArtistName = matchedArtist?.name,
                                                containingPlaylists = containingPlaylists,
                                                discoverOrigin = discoverOrigin,
                                                isLocal = localSong != null,
                                                canEditAlbum = localSong != null && matchedAlbum != null,
                                                onGoToAlbum = { name ->
                                                    goToLibrary { viewModel.openLibraryAlbum(name, fromNestedParent = false) }
                                                },
                                                onGoToArtist = { name ->
                                                    goToLibrary { viewModel.openLibraryArtist(name) }
                                                },
                                                onGoToLocalPlaylist = { id ->
                                                    goToPlaylists { viewModel.openLocalPlaylist(id) }
                                                },
                                                onGoToListenBrainz = { mbid ->
                                                    goToDiscover { viewModel.openListenBrainzPlaylistDetail(mbid) }
                                                },
                                                onGoToCfRecommendations = {
                                                    goToDiscover { viewModel.openCfRecommendationsDetail() }
                                                },
                                                onAddToPlaylist = { localSong?.let(songDialogs.onAddToPlaylist) },
                                                onIdentify = { localSong?.let { viewModel.identifySongForReview(it) } },
                                                onEditSong = { localSong?.let(songDialogs.onEdit) },
                                                onEditLyrics = { localSong?.let(songDialogs.onEditLyrics) },
                                                onEditAlbum = { albumForEdit = matchedAlbum },
                                                onStartRadio = { viewModel.startRadio() }
                                            )
                                        }
                                    }

                                    val remoteItem = item as? PlayableItem.Remote
                                    if (remoteItem != null) {
                                        NowPlayingRemoteDownloadAction(
                                            download = activeDownloads.findUiDownloadByTrack(
                                                remoteItem.artist,
                                                remoteItem.title
                                            ),
                                            onDownload = { viewModel.downloadRemoteItem(remoteItem) },
                                            onRetry = viewModel::retryActiveDownload,
                                            onCancel = viewModel::dismissActiveDownload
                                        )
                                    }
                                }

                                // 3. Interactive Time Scrubber
                                item(key = "scrubber") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp, vertical = 12.dp)
                                    ) {
                                        PlaybackScrubber(
                                            durationMs = item.durationMs,
                                            positionMsFlow = viewModel.playbackPositionMs,
                                            onSeek = { viewModel.seekTo(it) }
                                        )
                                    }
                                }

                                // 4. Playback Controls Row
                                item(key = "controls") {
                                    NowPlayingControlsRow(
                                        isPlaying = isPlaying,
                                        isShuffle = isShuffle,
                                        repeatMode = repeatMode,
                                        onToggleShuffle = viewModel::toggleShuffle,
                                        onSkipPrevious = viewModel::skipToPrevious,
                                        onTogglePlayPause = viewModel::togglePlayPause,
                                        onSkipNext = viewModel::skipToNext,
                                        onToggleRepeatMode = viewModel::toggleRepeatMode,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 8.dp)
                                    )
                                }

                                // 5. Queue Section Header
                                item(key = "queue_header") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "A continuación",
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = "${queueItems.size}",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        if (radioActive) {
                                            Text(
                                                text = "Radio activa",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }

                                // 6. Queue Items
                                itemsIndexed(
                                    items = queueItems,
                                    key = { _, qItem -> qItem.queueEntryId },
                                    contentType = { _, _ -> "queue_item" }
                                ) { index, qItem ->
                                    val currentIndex by rememberUpdatedState(index)
                                    val currentOnRemove by rememberUpdatedState(viewModel::removeFromQueue)
                                    val dismissState = rememberSwipeToDismissBoxState(
                                        confirmValueChange = { value ->
                                            if (value == SwipeToDismissBoxValue.StartToEnd) {
                                                currentOnRemove(currentIndex)
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                    )

                                    SwipeToDismissBox(
                                        state = dismissState,
                                        enableDismissFromStartToEnd = true,
                                        enableDismissFromEndToStart = false,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                        backgroundContent = {
                                            if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(
                                                            MaterialTheme.colorScheme.errorContainer,
                                                            shape = RoundedCornerShape(ListDensity.corner)
                                                        )
                                                        .padding(horizontal = 16.dp),
                                                    contentAlignment = Alignment.CenterStart
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Quitar de la cola",
                                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (index == currentQueueIndex) {
                                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                                    } else {
                                                        MaterialTheme.colorScheme.surface
                                                    },
                                                    shape = RoundedCornerShape(ListDensity.corner)
                                                )
                                        ) {
                                            val formattedDuration = remember(qItem.durationMs) {
                                                formatDuration(qItem.durationMs)
                                            }
                                            QueueItemRow(
                                                item = qItem,
                                                isCurrentPlaying = (index == currentQueueIndex),
                                                onClick = { viewModel.skipToQueueIndex(index) },
                                                onRemove = { viewModel.removeFromQueue(index) },
                                                showIndex = true,
                                                index = index,
                                                trailingDuration = formattedDuration,
                                                compact = true,
                                                reorderCount = queueItems.size,
                                                onReorder = viewModel::moveDisplayQueueItem
                                            )
                                        }
                                    }
                                }
                            }

                            // Floating Docked Mini Player when scrolled into queue
                            androidx.compose.animation.AnimatedVisibility(
                                visible = showDockedBar,
                                enter = slideInVertically { it } + fadeIn(),
                                exit = slideOutVertically { it } + fadeOut(),
                                modifier = Modifier.align(Alignment.BottomCenter)
                            ) {
                                NowPlayingDockedBar(
                                    item = item,
                                    isPlaying = isPlaying,
                                    durationMs = item.durationMs,
                                    positionMsFlow = viewModel.playbackPositionMs,
                                    onTogglePlayPause = viewModel::togglePlayPause,
                                    onSkipPrevious = viewModel::skipToPrevious,
                                    onSkipNext = viewModel::skipToNext,
                                    onTapTitle = {
                                        coroutineScope.launch {
                                            queueListState.animateScrollToItem(0)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    1 -> {
                        // Page 1: Letra a pantalla completa con controles anclados abajo
                        NowPlayingLyricsView(
                            localSong = localSong,
                            positionMsFlow = viewModel.playbackPositionMs,
                            durationMs = item.durationMs,
                            isPlaying = isPlaying,
                            isShuffle = isShuffle,
                            repeatMode = repeatMode,
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onSkipPrevious = viewModel::skipToPrevious,
                            onSkipNext = viewModel::skipToNext,
                            onToggleShuffle = viewModel::toggleShuffle,
                            onToggleRepeatMode = viewModel::toggleRepeatMode,
                            onSeekTo = viewModel::seekTo,
                            onSeekToLyric = viewModel::seekToAndPlay,
                            onRetryFetchLyrics = viewModel::retryFetchLyrics
                        )
                    }
                }
            }
        }
    }

    AlbumEditDialogsHost(
        albumForEdit = albumForEdit,
        viewModel = viewModel,
        onDismissEdit = { albumForEdit = null }
    )
}

/**
 * Selector superior de píldora para alternar entre Portada y Letra.
 */
@Composable
private fun NowPlayingTabSelector(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(3.dp)
        ) {
            NowPlayingTabPill(
                selected = selectedTab == 0,
                icon = Icons.Default.Album,
                text = "Portada",
                onClick = { onTabSelected(0) }
            )
            NowPlayingTabPill(
                selected = selectedTab == 1,
                icon = Icons.Default.Lyrics,
                text = "Letra",
                onClick = { onTabSelected(1) }
            )
        }
    }
}

@Composable
private fun NowPlayingTabPill(
    selected: Boolean,
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                ),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Fila unificada de botones de transporte (Shuffle, Prev, Play/Pause, Next, Repeat).
 */
@Composable
private fun NowPlayingControlsRow(
    isPlaying: Boolean,
    isShuffle: Boolean,
    repeatMode: RepeatMode,
    onToggleShuffle: () -> Unit,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleRepeatMode: () -> Unit,
    modifier: Modifier = Modifier,
    playFabSize: Dp = 64.dp,
    playIconSize: Dp = 36.dp
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Aleatorio",
                tint = if (isShuffle) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }

        IconButton(onClick = onSkipPrevious) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Anterior",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(34.dp)
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier = Modifier
                .size(playFabSize)
                .clip(CircleShape),
            shadowElevation = 8.dp
        ) {
            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    imageVector = playPauseVector(isPlaying),
                    contentDescription = "Play/Pause",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(playIconSize)
                )
            }
        }

        IconButton(onClick = onSkipNext) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Siguiente",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(34.dp)
            )
        }

        IconButton(onClick = onToggleRepeatMode) {
            val icon = when (repeatMode) {
                RepeatMode.OFF, RepeatMode.ALL -> Icons.Default.Repeat
                RepeatMode.ONE -> Icons.Default.RepeatOne
            }
            val tint = when (repeatMode) {
                RepeatMode.OFF -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.primary
            }
            Icon(
                imageVector = icon,
                contentDescription = "Repetir",
                tint = tint
            )
        }
    }
}

/**
 * Barra inferior colapsable que aparece al scrollear hacia la cola.
 */
@Composable
private fun NowPlayingDockedBar(
    item: PlayableItem,
    isPlaying: Boolean,
    durationMs: Long,
    positionMsFlow: StateFlow<Long>,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onTapTitle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(12.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            // Scrubber progress line en la parte superior de la barra
            val positionMs by positionMsFlow.collectAsState()
            val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onTapTitle)
                        .padding(end = 8.dp)
                ) {
                    AsyncImage(
                        model = item.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = item.artist,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = onSkipPrevious) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Anterior",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(42.dp)
                    ) {
                        IconButton(onClick = onTogglePlayPause) {
                            Icon(
                                imageVector = playPauseVector(isPlaying),
                                contentDescription = "Play/Pause",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    IconButton(onClick = onSkipNext) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Siguiente",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pantalla completa de letras sincronizadas con timestamps sutiles y controles fijados en la base.
 */
@Composable
private fun NowPlayingLyricsView(
    localSong: Song?,
    positionMsFlow: StateFlow<Long>,
    durationMs: Long,
    isPlaying: Boolean,
    isShuffle: Boolean,
    repeatMode: RepeatMode,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeatMode: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekToLyric: (Long) -> Unit,
    onRetryFetchLyrics: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val positionMs by positionMsFlow.collectAsState()
    val rawLyrics = localSong?.lyrics

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!rawLyrics.isNullOrEmpty()) {
                val parsedLrc = remember(rawLyrics) { SyncedLyrics.parse(rawLyrics) }
                val timed = remember(parsedLrc) { SyncedLyrics.hasTimestamps(parsedLrc) }

                if (timed) {
                    val currentLineIndex = remember(parsedLrc, positionMs) {
                        SyncedLyrics.currentLineIndex(parsedLrc, positionMs)
                    }
                    val listState = rememberLazyListState()
                    var userScrolledRecent by remember { mutableStateOf(false) }
                    var lastUserScrollEpoch by remember { mutableLongStateOf(0L) }

                    LaunchedEffect(listState.isScrollInProgress) {
                        if (listState.isScrollInProgress) {
                            userScrolledRecent = true
                            lastUserScrollEpoch = System.currentTimeMillis()
                        }
                    }

                    LaunchedEffect(lastUserScrollEpoch) {
                        if (lastUserScrollEpoch > 0) {
                            kotlinx.coroutines.delay(3500)
                            userScrolledRecent = false
                        }
                    }

                    LaunchedEffect(currentLineIndex, userScrolledRecent) {
                        if (!userScrolledRecent && currentLineIndex in parsedLrc.indices) {
                            listState.animateScrollToItem(
                                index = currentLineIndex,
                                scrollOffset = -180
                            )
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        contentPadding = PaddingValues(vertical = 40.dp)
                    ) {
                        itemsIndexed(
                            items = parsedLrc,
                            key = { index, line -> "lyric_${index}_${line.timeMs ?: 0}" }
                        ) { index, line ->
                            if (line.text.isNotEmpty()) {
                                val isCurrent = index == currentLineIndex
                                val timeMs = line.timeMs
                                val formattedStamp = remember(timeMs) {
                                    timeMs?.let { formatLyricStamp(it) }
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(
                                            enabled = timeMs != null,
                                            onClick = { timeMs?.let(onSeekToLyric) }
                                        )
                                        .padding(vertical = 8.dp, horizontal = 12.dp)
                                ) {
                                    if (formattedStamp != null) {
                                        Text(
                                            text = formattedStamp,
                                            fontSize = 11.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isCurrent) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                            },
                                            modifier = Modifier.padding(bottom = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = line.text,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontSize = if (isCurrent) 20.sp else 16.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                            letterSpacing = if (isCurrent) 0.2.sp else 0.sp
                                        ),
                                        color = if (isCurrent) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                        },
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Letra en texto plano (sin timestamps)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = rawLyrics,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                // Estado sin letra
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (localSong == null) "Letra no disponible en stream" else "Sin letra disponible",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    if (localSong != null) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { onRetryFetchLyrics(localSong) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscar en línea")
                        }
                    }
                }
            }
        }

        // Controles de reproducción fijos al pie en vista de letras
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 6.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                PlaybackScrubber(
                    durationMs = durationMs,
                    positionMsFlow = positionMsFlow,
                    onSeek = onSeekTo
                )
                Spacer(modifier = Modifier.height(8.dp))
                NowPlayingControlsRow(
                    isPlaying = isPlaying,
                    isShuffle = isShuffle,
                    repeatMode = repeatMode,
                    onToggleShuffle = onToggleShuffle,
                    onSkipPrevious = onSkipPrevious,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipNext = onSkipNext,
                    onToggleRepeatMode = onToggleRepeatMode,
                    playFabSize = 56.dp,
                    playIconSize = 32.dp
                )
            }
        }
    }
}

private fun formatLyricStamp(timeMs: Long): String {
    val totalSec = (timeMs / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

@Composable
private fun NowPlayingRemoteDownloadAction(
    download: ActiveDownload?,
    onDownload: () -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit
) {
    Spacer(modifier = Modifier.height(8.dp))
    DownloadStateTrailing(
        state = download?.state,
        percent = download?.progressPercent ?: 0,
        onRetry = download?.let { d -> { onRetry(d.id) } },
        onDismiss = download?.let { d -> { onCancel(d.id) } },
        successLabel = DownloadMessages.inLibrary,
        idleContent = {
            Button(onClick = onDownload) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Descargar ahora")
            }
        }
    )
}

