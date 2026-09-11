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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.bestiapop.android.data.model.DisplayLyricLine
import com.bestiapop.android.data.util.LyricsPhoneticProcessor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import com.bestiapop.android.ui.components.DismissibleQueueItemRow
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

    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    // Do NOT collect playbackPositionMs here — it ticks every 200ms and would recompose
    // the whole screen (including the Cola LazyColumn). Scrubber/lyrics collect locally.
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val isShuffle by viewModel.isShuffle.collectAsStateWithLifecycle()
    val queueItems by viewModel.displayQueue.collectAsStateWithLifecycle()
    val resolvingRemote by viewModel.resolvingRemote.collectAsStateWithLifecycle()
    val radioActive by viewModel.radioActive.collectAsStateWithLifecycle()
    val radioLoading by viewModel.radioLoading.collectAsStateWithLifecycle()
    val radioMode by viewModel.radioMode.collectAsStateWithLifecycle()
    val radioStatusLabel by viewModel.radioStatusLabel.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val discoverOrigin by viewModel.discoverPlaybackOrigin.collectAsStateWithLifecycle()
    val isFetchingLyrics by viewModel.isFetchingLyrics.collectAsStateWithLifecycle()
    val lyricsFetchError by viewModel.lyricsFetchError.collectAsStateWithLifecycle()
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

    LaunchedEffect(currentItem) {
        if (currentItem == null) {
            onDismiss()
        }
    }

    val item = currentItem ?: return
    val baseLocalSong = (item as? PlayableItem.Local)?.song
    val localSong = when {
        baseLocalSong == null -> null
        currentSong?.id == baseLocalSong.id -> baseLocalSong.copy(
            lyrics = (currentSong?.lyrics ?: baseLocalSong.lyrics)?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        )
        else -> baseLocalSong.copy(
            lyrics = baseLocalSong.lyrics?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        )
    }

    LaunchedEffect(localSong?.id) {
        viewModel.clearLyricsFetchError()
    }
    val albumLabel = when (item) {
        is PlayableItem.Local -> item.song.album
        is PlayableItem.Remote -> item.album.takeIf { it.isNotBlank() } ?: "Stream"
    }
    val matchedAlbum by remember(viewModel, item.album) {
        viewModel.libraryProjection.albums.map { list ->
            item.album.takeIf { it.isNotBlank() }?.let { albumName ->
                list.firstOrNull { it.name.equals(albumName, ignoreCase = true) }
            }
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val matchedArtist by remember(viewModel, item.artist) {
        viewModel.libraryProjection.artists.map { list ->
            item.artist.takeIf { it.isNotBlank() }?.let { artistName ->
                list.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
            }
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

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

    val nestedScrollConnection = remember(dismissThresholdPx, onDismiss, pagerState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pagerState.currentPage != 0) return Offset.Zero
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
                if (pagerState.currentPage != 0) return Offset.Zero
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val delta = available.y
                if (delta > 0f) {
                    dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (pagerState.currentPage == 0) {
                    settleSwipeDismiss()
                }
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
                                                actions = remember(
                                                    matchedAlbum,
                                                    localSong,
                                                    songDialogs,
                                                    viewModel,
                                                    onDismiss
                                                ) {
                                                    NowPlayingMenuActions(
                                                        navigation = NowPlayingNavigationActions(
                                                            onGoToAlbum = { name ->
                                                                goToLibrary { viewModel.openLibraryAlbum(name, fromNestedParent = false) }
                                                            },
                                                            onGoToArtist = { name ->
                                                                if (item is PlayableItem.Remote) {
                                                                    goToDiscover { viewModel.selectArtistForInspection(name) }
                                                                } else {
                                                                    goToLibrary { viewModel.openLibraryArtist(name) }
                                                                }
                                                            },
                                                            onGoToLocalPlaylist = { id ->
                                                                goToPlaylists { viewModel.openLocalPlaylist(id) }
                                                            },
                                                            onGoToListenBrainz = { mbid ->
                                                                goToDiscover { viewModel.openListenBrainzPlaylistDetail(mbid) }
                                                            },
                                                            onGoToCfRecommendations = {
                                                                goToDiscover { viewModel.openCfRecommendationsDetail() }
                                                            }
                                                        ),
                                                        song = NowPlayingSongActions.from(
                                                            dialogs = songDialogs,
                                                            localSong = localSong,
                                                            onEditAlbum = { albumForEdit = matchedAlbum },
                                                            onStartRadio = { viewModel.startRadio() }
                                                        )
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    val remoteItem = item as? PlayableItem.Remote
                                    if (remoteItem != null) {
                                        NowPlayingRemoteDownloadButton(
                                            viewModel = viewModel,
                                            remoteItem = remoteItem
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

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (radioActive) {
                                                Text(
                                                    text = "Radio activa",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            if (queueItems.isNotEmpty()) {
                                                TextButton(
                                                    onClick = viewModel::clearQueue,
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "Limpiar",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // 6. Queue Items
                                itemsIndexed(
                                    items = queueItems,
                                    key = { _, qItem -> qItem.queueEntryId },
                                    contentType = { _, _ -> "queue_item" }
                                ) { index, qItem ->
                                    val formattedDuration = remember(qItem.durationMs) {
                                        formatDuration(qItem.durationMs)
                                    }
                                    DismissibleQueueItemRow(
                                        item = qItem,
                                        isCurrentPlaying = (index == currentQueueIndex),
                                        index = index,
                                        queueSize = queueItems.size,
                                        onClick = { viewModel.skipToQueueIndex(index) },
                                        onRemove = { viewModel.removeFromQueue(qItem.queueEntryId) },
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                        trailingDuration = formattedDuration,
                                        compact = true,
                                        showIndex = true,
                                        onReorder = viewModel::moveDisplayQueueItem
                                    )
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
                            viewModel = viewModel,
                            positionMsFlow = viewModel.playbackPositionMs,
                            durationMs = item.durationMs,
                            isPlaying = isPlaying,
                            isShuffle = isShuffle,
                            repeatMode = repeatMode,
                            isFetchingLyrics = isFetchingLyrics,
                            lyricsFetchError = lyricsFetchError,
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
            DockedProgressIndicator(
                durationMs = durationMs,
                positionMsFlow = positionMsFlow
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

@Composable
private fun DockedProgressIndicator(
    durationMs: Long,
    positionMsFlow: StateFlow<Long>,
    modifier: Modifier = Modifier
) {
    val positionMs by positionMsFlow.collectAsStateWithLifecycle()
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
}

/**
 * Pantalla completa de letras sincronizadas con timestamps sutiles y controles fijados en la base.
 */
@Composable
private fun NowPlayingLyricsView(
    localSong: Song?,
    viewModel: MusicPlayerViewModel,
    positionMsFlow: StateFlow<Long>,
    durationMs: Long,
    isPlaying: Boolean,
    isShuffle: Boolean,
    repeatMode: RepeatMode,
    isFetchingLyrics: Boolean,
    lyricsFetchError: String?,
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
    val rawLyrics = localSong?.lyrics?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

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
                val plainLines = remember(parsedLrc) { parsedLrc.map { it.text } }
                val timed = remember(parsedLrc) { SyncedLyrics.hasTimestamps(parsedLrc) }

                val lyricsSettings by viewModel.lyricsSettings.collectAsStateWithLifecycle()
                val isTranslationActive by viewModel.isTranslationActive.collectAsStateWithLifecycle()
                val isFetchingTranslation by viewModel.isFetchingTranslation.collectAsStateWithLifecycle()
                val translationSource by viewModel.translationSource.collectAsStateWithLifecycle()
                val pendingPrompt by viewModel.pendingGoogleTranslatePrompt.collectAsStateWithLifecycle()
                val romanizationVersion by viewModel.romanizationVersion.collectAsStateWithLifecycle()
                val translationVersion by viewModel.translationVersion.collectAsStateWithLifecycle()
                val context = LocalContext.current

                LaunchedEffect(localSong.id, plainLines, lyricsSettings.phoneticGuideEnabled) {
                    if (lyricsSettings.phoneticGuideEnabled) {
                        viewModel.ensureRomanization(localSong.id, plainLines)
                    }
                }

                val displayLines = remember(
                    parsedLrc,
                    isTranslationActive,
                    romanizationVersion,
                    translationVersion,
                    lyricsSettings
                ) {
                    val translated = if (isTranslationActive) {
                        viewModel.getTranslatedLines(localSong.id)
                    } else {
                        null
                    }
                    val romanized = viewModel.getRomanizedLines(localSong.id)

                    parsedLrc.mapIndexed { idx, line ->
                        val formattedTime = line.timeMs?.let { formatLyricStamp(it) }
                        if (line.text.isEmpty()) {
                            DisplayLyricLine(line.timeMs, "", null, formattedTime)
                        } else if (isTranslationActive) {
                            val transText = translated?.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: line.text
                            DisplayLyricLine(
                                timeMs = line.timeMs,
                                primaryText = transText,
                                secondaryText = if (transText != line.text) line.text else null,
                                formattedTime = formattedTime
                            )
                        } else {
                            val romCandidate = romanized?.getOrNull(idx)
                            val secondary = if (lyricsSettings.phoneticGuideEnabled) {
                                LyricsPhoneticProcessor.formatPhoneticLine(
                                    original = line.text,
                                    romanizedCandidate = romCandidate,
                                    japaneseMode = lyricsSettings.japanesePhoneticMode
                                )
                            } else {
                                null
                            }
                            DisplayLyricLine(
                                timeMs = line.timeMs,
                                primaryText = line.text,
                                secondaryText = secondary,
                                formattedTime = formattedTime
                            )
                        }
                    }
                }

                if (pendingPrompt) {
                    AlertDialog(
                        onDismissRequest = viewModel::cancelGoogleTranslatePrompt,
                        title = {
                            Text("Traducción no encontrada")
                        },
                        text = {
                            Text("No se encontró una traducción comunitaria en Musixmatch ni sitios similares.\n\n¿Querés traducir esta letra con Google Traductor?")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.confirmGoogleTranslate(localSong, plainLines)
                                }
                            ) {
                                Text("Traducir con Google")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::cancelGoogleTranslatePrompt) {
                                Text("Cancelar")
                            }
                        }
                    )
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Barra superior: Atribución de fuente y botón de traducción
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isTranslationActive && translationSource != null) {
                            val source = translationSource!!
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = !source.url.isNullOrBlank()) {
                                        source.url?.let { urlStr ->
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr))
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        }
                                    }
                            ) {
                                Text(
                                    text = "Fuente: ${source.name} ↗",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        FilledTonalButton(
                            onClick = {
                                viewModel.toggleLyricsTranslation(localSong, plainLines)
                            },
                            enabled = !isFetchingTranslation,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isTranslationActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                },
                                contentColor = if (isTranslationActive) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        ) {
                            if (isFetchingTranslation) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = if (isTranslationActive) "Original" else "Traducir",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (timed) {
                            val currentLineIndex by remember(parsedLrc, positionMsFlow) {
                                positionMsFlow
                                    .map { pos -> SyncedLyrics.currentLineIndex(parsedLrc, pos) }
                                    .distinctUntilChanged()
                            }.collectAsStateWithLifecycle(initialValue = SyncedLyrics.currentLineIndex(parsedLrc, positionMsFlow.value))

                            val listState = rememberLazyListState()
                            val isDragged by listState.interactionSource.collectIsDraggedAsState()
                            var userScrolledRecent by remember { mutableStateOf(false) }

                            LaunchedEffect(isDragged) {
                                if (isDragged) {
                                    userScrolledRecent = true
                                } else if (userScrolledRecent) {
                                    kotlinx.coroutines.delay(3500)
                                    userScrolledRecent = false
                                }
                            }

                            LaunchedEffect(currentLineIndex, userScrolledRecent, isDragged) {
                                if (!userScrolledRecent && !isDragged && currentLineIndex in displayLines.indices) {
                                    val targetIndex = (currentLineIndex - 1).coerceAtLeast(0)
                                    listState.animateScrollToItem(
                                        index = targetIndex,
                                        scrollOffset = 0
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
                                    items = displayLines,
                                    key = { index, line -> "lyric_${index}_${line.timeMs ?: 0}" }
                                ) { index, line ->
                                    if (line.primaryText.isNotEmpty()) {
                                        TimedLyricRow(
                                            line = line,
                                            isCurrent = (index == currentLineIndex),
                                            onSeekToLyric = onSeekToLyric
                                        )
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
                                displayLines.forEach { line ->
                                    if (line.primaryText.isNotEmpty()) {
                                        UntimedLyricRow(line = line)
                                    }
                                }
                            }
                        }
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
                            enabled = !isFetchingLyrics,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isFetchingLyrics) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Buscando en línea…")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Buscar en línea")
                            }
                        }
                        if (!lyricsFetchError.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = lyricsFetchError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
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
private fun TimedLyricRow(
    line: DisplayLyricLine,
    isCurrent: Boolean,
    onSeekToLyric: (Long) -> Unit
) {
    val timeMs = line.timeMs
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
        if (line.formattedTime != null) {
            Text(
                text = line.formattedTime,
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
            text = line.primaryText,
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
        if (!line.secondaryText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = line.secondaryText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = if (isCurrent) 13.sp else 11.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun UntimedLyricRow(line: DisplayLyricLine) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 12.dp)
    ) {
        Text(
            text = line.primaryText,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 26.sp
            ),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.fillMaxWidth()
        )
        if (!line.secondaryText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = line.secondaryText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NowPlayingRemoteDownloadButton(
    viewModel: MusicPlayerViewModel,
    remoteItem: PlayableItem.Remote
) {
    val download by remember(viewModel, remoteItem.artist, remoteItem.title) {
        viewModel.activeDownloads.map { list ->
            list.findUiDownloadByTrack(remoteItem.artist, remoteItem.title)
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    NowPlayingRemoteDownloadAction(
        download = download,
        onDownload = { viewModel.downloadRemoteItem(remoteItem) },
        onRetry = viewModel::retryActiveDownload,
        onCancel = viewModel::dismissActiveDownload
    )
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

