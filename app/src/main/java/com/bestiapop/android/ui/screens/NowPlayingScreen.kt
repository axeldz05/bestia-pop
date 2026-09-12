package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.NAV_DISCOVER
import com.bestiapop.android.data.preferences.NAV_LIBRARY
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkHero
import com.bestiapop.android.ui.components.PlaybackScrubber
import com.bestiapop.android.ui.components.RadioModeControl
import com.bestiapop.android.ui.components.focusedQueueIndex
import com.bestiapop.android.ui.screens.library.AlbumEditDialogsHost
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs
import com.bestiapop.android.ui.screens.nowplaying.NowPlayingControlsRow
import com.bestiapop.android.ui.screens.nowplaying.NowPlayingDockedBar
import com.bestiapop.android.ui.screens.nowplaying.NowPlayingLyricsView
import com.bestiapop.android.ui.screens.nowplaying.NowPlayingQueueHeader
import com.bestiapop.android.ui.screens.nowplaying.NowPlayingRemoteDownloadButton
import com.bestiapop.android.ui.screens.nowplaying.NowPlayingTabSelector
import com.bestiapop.android.ui.screens.nowplaying.nowPlayingQueueItems
import com.bestiapop.android.ui.state.NowPlayingTransportActions
import com.bestiapop.android.ui.state.PlaylistDetailNav
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    val radioState by viewModel.radioState.collectAsStateWithLifecycle()
    val transportActions = remember(viewModel) {
        NowPlayingTransportActions(
            onTogglePlayPause = viewModel::togglePlayPause,
            onSkipNext = viewModel::skipToNext,
            onSkipPrevious = viewModel::skipToPrevious,
            onToggleShuffle = viewModel::toggleShuffle,
            onToggleRepeatMode = viewModel::toggleRepeatMode,
            onSeek = viewModel::seekTo
        )
    }
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

    val lyricsSong: Song = localSong ?: Song(
        id = -kotlin.math.abs(item.mediaId.hashCode().toLong().takeIf { it != 0L } ?: 1L),
        title = item.title,
        artist = item.artist,
        album = item.album,
        durationMs = item.durationMs,
        artworkUri = item.artworkUri,
        uriString = item.mediaId,
        lyrics = (item as? PlayableItem.Remote)?.lyrics?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    )

    LaunchedEffect(lyricsSong.id) {
        viewModel.clearLyricsFetchError()
    }
    LaunchedEffect(pagerState.currentPage, pagerState.targetPage, lyricsSong.id) {
        val isLyricsTab = pagerState.currentPage == 1 || pagerState.targetPage == 1
        if (isLyricsTab && lyricsSong.lyrics.isNullOrBlank()) {
            viewModel.ensureLyrics(lyricsSong)
        }
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
                    state = radioState,
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
                                            } else if (radioState.loading) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = MusicPlayerViewModel.RADIO_LOADING_LABEL,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            } else if (radioState.statusLabel != null) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = radioState.statusLabel!!,
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
                                        actions = transportActions,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 8.dp)
                                    )
                                }

                                // 5. Queue Section Header
                                item(key = "queue_header") {
                                    NowPlayingQueueHeader(
                                        queueSize = queueItems.size,
                                        isRadioActive = radioState.active,
                                        onClearQueue = viewModel::clearQueue
                                    )
                                }

                                // 6. Queue Items
                                nowPlayingQueueItems(
                                    queueItems = queueItems,
                                    currentQueueIndex = currentQueueIndex,
                                    onItemClick = { viewModel.skipToQueueIndex(it) },
                                    onRemoveItem = { viewModel.removeFromQueue(it) },
                                    onReorder = viewModel::moveDisplayQueueItem
                                )
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
                            song = lyricsSong,
                            viewModel = viewModel,
                            positionMsFlow = viewModel.playbackPositionMs,
                            durationMs = item.durationMs,
                            isPlaying = isPlaying,
                            isShuffle = isShuffle,
                            repeatMode = repeatMode,
                            isFetchingLyrics = isFetchingLyrics,
                            lyricsFetchError = lyricsFetchError,
                            actions = transportActions,
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
