package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.bestiapop.android.data.preferences.NAV_LIBRARY
import com.bestiapop.android.data.preferences.NAV_PLAYLISTS
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkHero
import com.bestiapop.android.ui.components.DownloadStateTrailing
import com.bestiapop.android.ui.components.QueueLazyList
import com.bestiapop.android.ui.components.RadioModeControl
import com.bestiapop.android.ui.components.findByTrack
import com.bestiapop.android.ui.components.focusedQueueIndex
import com.bestiapop.android.ui.components.playPauseVector
import com.bestiapop.android.ui.components.formatDuration
import com.bestiapop.android.ui.screens.library.AlbumEditDialogsHost
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


/** Layout hit-test coords without Compose state (avoids recomposition on every layout pass). */
private class ScrollZoneCoords {
    var surface: LayoutCoordinates? = null
    var inner: LayoutCoordinates? = null
}

private data class LrcLine(val timeMs: Long, val text: String)

private fun parseLrcLyrics(rawLyrics: String): List<LrcLine> {
    val lines = mutableListOf<LrcLine>()
    val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")

    rawLyrics.lines().forEach { lineStr ->
        val match = regex.find(lineStr.trim())
        if (match != null) {
            val min = match.groupValues[1].toLongOrNull() ?: 0L
            val sec = match.groupValues[2].toLongOrNull() ?: 0L
            val msPart = match.groupValues[3].toLongOrNull() ?: 0L
            val text = match.groupValues[4].trim()
            val totalMs = (min * 60 + sec) * 1000 + if (msPart < 100) msPart * 10 else msPart
            if (text.isNotEmpty()) {
                lines.add(LrcLine(totalMs, text))
            }
        }
    }
    return lines.sortedBy { it.timeMs }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    viewModel: MusicPlayerViewModel,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    val currentItem by viewModel.currentItem.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    // Do NOT collect playbackPositionMs here — it ticks every 200ms and would recompose
    // the whole screen (including the Cola LazyColumn). Scrubber/lyrics collect locally.
    val repeatMode by viewModel.repeatMode.collectAsState()
    val isShuffle by viewModel.isShuffle.collectAsState()
    val volumeLevel by viewModel.volumeLevel.collectAsState()
    val volumeBoostEnabled by viewModel.volumeBoostEnabled.collectAsState()
    val queueItems by viewModel.displayQueue.collectAsState()
    val queueFocusEpoch by viewModel.queueFocusEpoch.collectAsState()
    val resolvingRemote by viewModel.resolvingRemote.collectAsState()
    val radioActive by viewModel.radioActive.collectAsState()
    val radioLoading by viewModel.radioLoading.collectAsState()
    val radioStatusLabel by viewModel.radioStatusLabel.collectAsState()
    val albums by viewModel.albumsState.collectAsState()
    val artists by viewModel.artistsState.collectAsState()
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val discoverOrigin by viewModel.discoverPlaybackOrigin.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    var actionsMenuExpanded by remember { mutableStateOf(false) }
    val songDialogs = rememberSongActionDialogs(viewModel = viewModel, playlists = playlists)
    var albumForEdit by remember { mutableStateOf<Album?>(null) }
    var containingPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Portada, 1 = Letra, 2 = Cola
    val queueListState = rememberLazyListState()

    val item = currentItem ?: return
    val localSong = (item as? PlayableItem.Local)?.song
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
        viewModel.setSelectedNavIndex(NAV_PLAYLISTS)
        open()
        onDismiss()
    }

    // Swipe-to-dismiss: Portada uses nested scroll; Letra/Cola dismiss only outside the
    // lyrics/queue panel so those lists keep exclusive vertical scrolling.
    //
    // dragOffset is updated synchronously (not via Animatable.snapTo in a coroutine) so the
    // threshold check on finger-up always sees the real drag distance. Using async snapTo
    // raced with onPostFling and left a translucent full-screen Surface covering the mini bar.
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = configuration.densityDpi / 160f
    val screenHeightPx = configuration.screenHeightDp * density
    val dismissThresholdPx = screenHeightPx * 0.30f

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val enterOffset = remember { Animatable(screenHeightPx) }
    val scrollZoneCoords = remember { ScrollZoneCoords() }

    LaunchedEffect(Unit) {
        enterOffset.animateTo(0f, tween(280))
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) {
            scrollZoneCoords.inner = null
        }
        dragOffset = 0f
    }

    fun touchInInnerScrollZone(positionInSurface: Offset): Boolean {
        val surface = scrollZoneCoords.surface ?: return false
        val inner = scrollZoneCoords.inner ?: return false
        if (!surface.isAttached || !inner.isAttached) return false
        val bounds = surface.localBoundingBoxOf(inner, clipBounds = false)
        return bounds.contains(positionInSurface)
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

    val nestedScrollConnection = remember(dismissThresholdPx, selectedTab, onDismiss) {
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
                if (selectedTab != 0) return Offset.Zero
                val delta = available.y
                if (delta > 0f) {
                    dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                    // Crossed threshold mid-drag: remove overlay immediately so it cannot
                    // keep eating hits over the already-visible mini bar.
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

    val outsideScrollDismissPointer = Modifier.pointerInput(
        selectedTab,
        dismissThresholdPx,
        onDismiss
    ) {
        if (selectedTab == 0) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (touchInInnerScrollZone(down.position)) {
                return@awaitEachGesture
            }
            var totalY = dragOffset
            drag(down.id) { change ->
                val dy = change.positionChange().y
                if (dy != 0f) {
                    change.consume()
                    totalY = (totalY + dy).coerceAtLeast(0f)
                    dragOffset = totalY
                    if (totalY > dismissThresholdPx) {
                        onDismiss()
                    }
                }
            }
            // If still composed (did not cross threshold during drag), settle.
            settleSwipeDismiss()
        }
    }

    val currentQueueIndex = remember(queueItems, item.queueEntryId) {
        focusedQueueIndex(queueItems, item.queueEntryId)
    }

    // Jump (no animation) so opening Cola stays snappy on long queues
    LaunchedEffect(selectedTab, queueFocusEpoch, item.queueEntryId) {
        if (selectedTab != 2) return@LaunchedEffect
        val index = currentQueueIndex
        if (index >= 0) {
            queueListState.scrollToItem(index)
        }
    }

    val surfaceModifier = Modifier
        .fillMaxSize()
        .onGloballyPositioned { scrollZoneCoords.surface = it }
        .nestedScroll(nestedScrollConnection)
        .then(outsideScrollDismissPointer)
        .offset {
            IntOffset(0, (enterOffset.value + dragOffset).roundToInt())
        }
        .alpha((1f - (dragOffset / screenHeightPx).coerceIn(0f, 1f)))

    Surface(
        modifier = surfaceModifier,
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header with statusBarsPadding & clear margins
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Cerrar",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                Text(
                    text = "REPRODUCIENDO AHORA",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                    color = MaterialTheme.colorScheme.primary
                )

                RadioModeControl(
                    radioActive = radioActive,
                    radioLoading = radioLoading,
                    onStartPreferred = { viewModel.startRadio() },
                    onStartMode = { mode ->
                        viewModel.startRadio(mode = mode, announceMode = true)
                    },
                    onStop = viewModel::stopRadio
                )
            }

            // Tab Selector (Portada / Letra / Cola)
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Album, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Portada", fontSize = 13.sp)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Lyrics, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Letra", fontSize = 13.sp)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cola", fontSize = 13.sp)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Central View Container (Artwork, Lyrics, or Queue)
            when (selectedTab) {
                0 -> {
                    ArtworkHero(
                        uri = item.artworkUri,
                        contentDescription = item.title,
                        fallback = Icons.Default.MusicNote,
                        fallbackTint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .aspectRatio(1f)
                    )
                }
                1 -> {
                    NowPlayingLyricsPanel(
                        localSong = localSong,
                        positionMsFlow = viewModel.playbackPositionMs,
                        onRetryFetchLyrics = viewModel::retryFetchLyrics,
                        onPanelPositioned = remember(scrollZoneCoords) {
                            { coords: LayoutCoordinates -> scrollZoneCoords.inner = coords }
                        }
                    )
                }
                2 -> {
                    NowPlayingQueuePanel(
                        queueItems = queueItems,
                        currentIndex = currentQueueIndex,
                        listState = queueListState,
                        onSkipTo = viewModel::skipToQueueIndex,
                        onRemove = viewModel::removeFromQueue,
                        onReorder = viewModel::moveDisplayQueueItem,
                        onPanelPositioned = remember(scrollZoneCoords) {
                            { coords: LayoutCoordinates -> scrollZoneCoords.inner = coords }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Title & Artist Info (Clean & readable)
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 40.dp)
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
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
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
                            goToPlaylists { viewModel.openListenBrainzPlaylistDetail(mbid) }
                        },
                        onGoToCfRecommendations = {
                            goToPlaylists { viewModel.openCfRecommendationsDetail() }
                        },
                        onAddToPlaylist = { localSong?.let(songDialogs.onAddToPlaylist) },
                        onIdentify = { localSong?.let { viewModel.identifySongForReview(it) } },
                        onEditSong = { localSong?.let(songDialogs.onEdit) },
                        onEditAlbum = { albumForEdit = matchedAlbum },
                        onStartRadio = { viewModel.startRadio() }
                    )
                }
            }

            val remoteItem = item as? PlayableItem.Remote
            if (remoteItem != null) {
                NowPlayingRemoteDownloadAction(
                    download = activeDownloads.findByTrack(remoteItem.artist, remoteItem.title),
                    onDownload = { viewModel.downloadRemoteItem(remoteItem) },
                    onRetry = viewModel::retryActiveDownload,
                    onCancel = viewModel::dismissActiveDownload
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Interactive Time Scrubber Slider Bar
            NowPlayingScrubber(
                durationMs = item.durationMs,
                positionMsFlow = viewModel.playbackPositionMs,
                onSeek = { viewModel.seekTo(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Playback Controls Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle Button
                IconButton(onClick = { viewModel.toggleShuffle() }) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Aleatorio",
                        tint = if (isShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }

                // Previous Button
                IconButton(onClick = { viewModel.skipToPrevious() }) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Main Play/Pause Fab Button
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape),
                    shadowElevation = 8.dp
                ) {
                    IconButton(onClick = { viewModel.togglePlayPause() }) {
                        Icon(
                            imageVector = playPauseVector(isPlaying),
                            contentDescription = "Play/Pause",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Next Button
                IconButton(onClick = { viewModel.skipToNext() }) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Repeat Mode Toggle
                IconButton(onClick = { viewModel.toggleRepeatMode() }) {
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
                        contentDescription = "Repeat Mode",
                        tint = tint
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Compact Volume Slider Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (volumeLevel > 0f) viewModel.setVolume(0f) else viewModel.setVolume(0.5f)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (volumeLevel == 0f) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeDown,
                        contentDescription = "Volumen Bajo",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                val volumeBoosted = volumeBoostEnabled && volumeLevel > 1f
                val volumeActiveColor = if (volumeBoosted) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                }
                Slider(
                    value = volumeLevel.coerceIn(0f, if (volumeBoostEnabled) 2f else 1f),
                    onValueChange = { viewModel.setVolume(it) },
                    valueRange = if (volumeBoostEnabled) 0f..2f else 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = volumeActiveColor,
                        activeTrackColor = volumeActiveColor.copy(alpha = 0.8f),
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp)
                        .padding(horizontal = 6.dp)
                )

                IconButton(
                    onClick = { viewModel.setVolume(1.0f) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Volumen Alto",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
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

@Composable
private fun NowPlayingScrubber(
    durationMs: Long,
    positionMsFlow: StateFlow<Long>,
    onSeek: (Long) -> Unit
) {
    val positionMs by positionMsFlow.collectAsState()
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    val maxDuration = durationMs.toFloat().coerceAtLeast(1f)
    val displayPosition = if (isDragging) dragPosition.toLong() else positionMs
    val sliderValue = if (isDragging) dragPosition else positionMs.toFloat().coerceIn(0f, maxDuration)

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderValue,
            onValueChange = { newValue ->
                isDragging = true
                dragPosition = newValue
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek(dragPosition.toLong())
            },
            valueRange = 0f..maxDuration,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(displayPosition),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun NowPlayingLyricsPanel(
    localSong: Song?,
    positionMsFlow: StateFlow<Long>,
    onRetryFetchLyrics: (Song) -> Unit,
    onPanelPositioned: (LayoutCoordinates) -> Unit
) {
    val positionMs by positionMsFlow.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .height(280.dp)
            .onGloballyPositioned(onPanelPositioned)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        val rawLyrics = localSong?.lyrics
        if (!rawLyrics.isNullOrEmpty()) {
            val parsedLrc = remember(rawLyrics) { parseLrcLyrics(rawLyrics) }

            if (parsedLrc.isNotEmpty()) {
                val currentLineIndex = remember(parsedLrc, positionMs) {
                    var idx = -1
                    for (i in parsedLrc.indices) {
                        if (positionMs >= parsedLrc[i].timeMs) idx = i else break
                    }
                    idx
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    parsedLrc.forEachIndexed { index, line ->
                        val isCurrentLine = index == currentLineIndex
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal,
                                fontSize = if (isCurrentLine) 17.sp else 14.sp
                            ),
                            color = if (isCurrentLine) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = rawLyrics,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (localSong == null) {
                        "Letra no disponible en stream"
                    } else {
                        "Sin letra disponible"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                if (localSong != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onRetryFetchLyrics(localSong) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Buscar en línea", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingQueuePanel(
    queueItems: List<PlayableItem>,
    currentIndex: Int,
    listState: LazyListState,
    onSkipTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onPanelPositioned: (LayoutCoordinates) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .height(280.dp)
            .onGloballyPositioned(onPanelPositioned)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        QueueLazyList(
            items = queueItems,
            isCurrentPlaying = { index, _ -> index == currentIndex },
            onSkipTo = onSkipTo,
            onRemove = onRemove,
            listState = listState,
            compact = true,
            showIndex = true,
            removeIcon = Icons.Default.Close,
            removeContentDescription = "Quitar de la cola",
            trailingDuration = { formatDuration(it.durationMs) },
            onReorder = onReorder
        )
    }
}
