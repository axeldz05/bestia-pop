package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Equalizer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.NAV_DISCOVER
import com.bestiapop.android.data.preferences.NAV_LIBRARY
import com.bestiapop.android.domain.util.findMatchingAlbum
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkHero
import com.bestiapop.android.ui.components.PlaybackScrubber
import com.bestiapop.android.ui.components.ProgressiveSheetState
import com.bestiapop.android.ui.components.RadioModeControl
import com.bestiapop.android.ui.components.rememberProgressiveSheetState
import com.bestiapop.android.ui.components.sheetDragDownDismiss
import com.bestiapop.android.ui.components.sheetDragUpTrigger
import com.bestiapop.android.ui.components.sheetLayout
import com.bestiapop.android.ui.components.sheetNestedScrollConnection
import com.bestiapop.android.ui.screens.library.AlbumEditDialogsHost
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs
import com.bestiapop.android.ui.screens.nowplaying.NowPlayingControlsRow
import com.bestiapop.android.ui.screens.nowplaying.NowPlayingLyricsView
import com.bestiapop.android.ui.screens.nowplaying.NowPlayingRemoteDownloadButton
import com.bestiapop.android.ui.screens.nowplaying.NowPlayingTabSelector
import com.bestiapop.android.ui.state.NowPlayingTransportActions
import com.bestiapop.android.ui.state.PlaylistDetailNav
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun NowPlayingScreen(
    viewModel: MusicPlayerViewModel,
    onDismiss: () -> Unit,
    sheetState: ProgressiveSheetState? = null,
) {
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val isPlaybackLoading by viewModel.isPlaybackLoading.collectAsStateWithLifecycle()
    // Do NOT collect playbackPositionMs here — it ticks every 200ms and would recompose
    // the whole screen (including the Cola LazyColumn). Scrubber/lyrics collect locally.
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val isShuffle by viewModel.isShuffle.collectAsStateWithLifecycle()
    val queueItems by viewModel.displayQueue.collectAsStateWithLifecycle()
    val resolvingRemote by viewModel.resolvingRemote.collectAsStateWithLifecycle()
    val radioState by viewModel.radioState.collectAsStateWithLifecycle()
    val transportActions =
        remember(viewModel) {
            NowPlayingTransportActions(
                onTogglePlayPause = viewModel::togglePlayPause,
                onSkipNext = viewModel::skipToNext,
                onSkipPrevious = viewModel::skipToPrevious,
                onToggleShuffle = viewModel::toggleShuffle,
                onToggleRepeatMode = viewModel::toggleRepeatMode,
                onSeek = viewModel::seekTo,
            )
        }
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val discoverOrigin by viewModel.discoverPlaybackOrigin.collectAsStateWithLifecycle()
    val isFetchingLyrics by viewModel.isFetchingLyrics.collectAsStateWithLifecycle()
    val lyricsFetchError by viewModel.lyricsFetchError.collectAsStateWithLifecycle()
    val playbackSettings by viewModel.playbackSettings.collectAsStateWithLifecycle()
    var actionsMenuExpanded by remember { mutableStateOf(false) }
    var showEqualizerScreen by rememberSaveable { mutableStateOf(false) }
    val songDialogs =
        rememberSongActionDialogs(
            viewModel = viewModel,
            playlists = playlists,
            onAfterPlaylistAdd = {
                if (viewModel.navigation.value.playlistDetail is PlaylistDetailNav.Local) {
                    onDismiss()
                }
            },
        )
    var albumForEdit by remember { mutableStateOf<Album?>(null) }
    var containingPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })

    LaunchedEffect(currentItem) {
        if (currentItem == null) {
            onDismiss()
        }
    }

    val item = currentItem ?: return
    val baseLocalSong = (item as? PlayableItem.Local)?.song
    val localSong =
        when {
            baseLocalSong == null -> {
                null
            }

            currentSong?.id == baseLocalSong.id -> {
                baseLocalSong.copy(
                    lyrics =
                        (currentSong?.lyrics ?: baseLocalSong.lyrics)?.trim()?.takeIf {
                            it.isNotBlank() &&
                                !it.equals("null", ignoreCase = true)
                        },
                )
            }

            else -> {
                baseLocalSong.copy(
                    lyrics = baseLocalSong.lyrics?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) },
                )
            }
        }

    val lyricsSong: Song =
        localSong ?: Song(
            id =
                -kotlin.math.abs(
                    item.mediaId
                        .hashCode()
                        .toLong()
                        .takeIf { it != 0L } ?: 1L,
                ),
            title = item.title,
            artist = item.artist,
            album = item.album,
            durationMs = item.durationMs,
            artworkUri = item.artworkUri,
            uriString = item.mediaId,
            lyrics = (item as? PlayableItem.Remote)?.lyrics?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) },
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
    val albumLabel =
        when (item) {
            is PlayableItem.Local -> item.song.album
            is PlayableItem.Remote -> item.album.takeIf { it.isNotBlank() } ?: "Stream"
        }
    val matchedAlbum by remember(viewModel, item.album, item.artist) {
        viewModel.libraryProjection.albums
            .map { list ->
                item.album.takeIf { it.isNotBlank() }?.let { albumName ->
                    findMatchingAlbum(list, albumName, item.artist)
                }
            }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val matchedArtist by remember(viewModel, item.artist) {
        viewModel.libraryProjection.artists
            .map { list ->
                item.artist.takeIf { it.isNotBlank() }?.let { artistName ->
                    list.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                }
            }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(localSong?.id, playlists) {
        val songId = localSong?.id
        containingPlaylists =
            if (songId != null) {
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

    val isGenericAlbum =
        item.album.equals("Single", ignoreCase = true) ||
            item.album.equals("Unknown Album", ignoreCase = true) ||
            item.album.equals("Stream", ignoreCase = true) ||
            item.album.equals("YouTube", ignoreCase = true)
    val effectiveAlbumName = matchedAlbum?.name ?: item.album.takeIf { it.isNotBlank() && !isGenericAlbum }
    val effectiveArtistName =
        matchedArtist?.name ?: item.artist.takeIf { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }

    val navigateToAlbum: (String) -> Unit = { name ->
        val local = matchedAlbum ?: viewModel.findMatchingLocalAlbum(name, item.artist)
        if (local != null) {
            goToLibrary { viewModel.openAlbum(name, item.artist, item.artworkUri) }
        } else {
            goToDiscover { viewModel.openAlbum(name, item.artist, item.artworkUri) }
        }
    }

    val navigateToArtist: (String) -> Unit = { name ->
        val local = matchedArtist
        if (local != null) {
            goToLibrary { viewModel.openLibraryArtist(local.name) }
        } else {
            goToDiscover { viewModel.selectArtistForInspection(name) }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = configuration.densityDpi / 160f
    val screenHeightPx = configuration.screenHeightDp * density

    val effectiveSheetState =
        sheetState ?: rememberProgressiveSheetState(
            screenHeightPx = screenHeightPx,
            onDismiss = onDismiss,
        ).apply {
            LaunchedEffect(Unit) { open() }
        }

    val queueSheetState =
        rememberProgressiveSheetState(
            screenHeightPx = screenHeightPx,
            onOpen = {
                viewModel.refreshQueueSuggestions()
            },
        )

    BackHandler {
        if (queueSheetState.isOpen) {
            queueSheetState.dismiss()
        } else {
            effectiveSheetState.dismiss()
        }
    }

    val dismissDraggableModifier =
        Modifier.sheetDragDownDismiss(
            state = effectiveSheetState,
            enabled = pagerState.currentPage == 0 && !queueSheetState.isOpen,
        )

    val surfaceModifier =
        Modifier
            .fillMaxSize()
            .sheetLayout(effectiveSheetState)

    Surface(
        modifier = surfaceModifier,
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val containerHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
            LaunchedEffect(containerHeightPx) {
                if (containerHeightPx > 0f) {
                    queueSheetState.updateScreenHeight(containerHeightPx)
                    effectiveSheetState.updateScreenHeight(containerHeightPx)
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
            ) {
                // Header: Close Chevron + Segmented Pill Tab Selector + Radio Control
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(dismissDraggableModifier)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { effectiveSheetState.dismiss() }) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Cerrar reproductor",
                            modifier = Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }

                    NowPlayingTabSelector(
                        selectedTab = pagerState.currentPage,
                        onTabSelected = { page ->
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(page)
                            }
                        },
                    )

                    RadioModeControl(
                        state = radioState,
                        onStartMode = { mode ->
                            viewModel.startRadio(mode = mode, announceMode = true)
                        },
                        onStop = viewModel::stopRadio,
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                ) { page ->
                    when (page) {
                        0 -> {
                            // Page 0: Reproductor principal limpio + Barra inferior interactiva para abrir la cola
                            BoxWithConstraints(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .then(dismissDraggableModifier)
                                        .padding(horizontal = 24.dp),
                            ) {
                                val artSize = minOf(maxWidth * 0.94f, maxHeight * 0.50f).coerceAtLeast(160.dp)

                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    // 1. Espacio superior hacia el techo reducido (sube todo)
                                    Spacer(modifier = Modifier.weight(0.24f))

                                    // 2. Hero Artwork
                                    ArtworkHero(
                                        uri = item.artworkUri,
                                        contentDescription = item.title,
                                        fallback = Icons.Default.MusicNote,
                                        fallbackTint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(artSize),
                                    )

                                    // 3. Espacio reducido entre portada y metadatos/botones
                                    Spacer(modifier = Modifier.weight(0.12f))

                                    // 4. Metadatos de la canción y menú de acciones
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 84.dp),
                                        ) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onBackground,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                val artistModifier =
                                                    if (effectiveArtistName != null) {
                                                        Modifier.clickable { navigateToArtist(effectiveArtistName) }
                                                    } else {
                                                        Modifier
                                                    }
                                                Text(
                                                    text = item.artist,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = artistModifier.weight(1f, fill = false),
                                                )
                                                if (item.artist.isNotBlank() && albumLabel.isNotBlank()) {
                                                    Text(
                                                        text = " • ",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                                    )
                                                }
                                                val albumModifier =
                                                    if (effectiveAlbumName != null) {
                                                        Modifier.clickable { navigateToAlbum(effectiveAlbumName) }
                                                    } else {
                                                        Modifier
                                                    }
                                                if (albumLabel.isNotBlank()) {
                                                    Text(
                                                        text = albumLabel,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = albumModifier.weight(1f, fill = false),
                                                    )
                                                }
                                            }
                                            if (resolvingRemote) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "Resolviendo stream…",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            } else if (radioState.loading) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = MusicPlayerViewModel.RADIO_LOADING_LABEL,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            } else if (radioState.statusLabel != null) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = radioState.statusLabel!!,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.align(Alignment.CenterEnd),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            IconButton(
                                                onClick = { showEqualizerScreen = true },
                                                modifier = Modifier.size(40.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Equalizer,
                                                    contentDescription = "Ecualizador",
                                                    tint =
                                                        if (playbackSettings.equalizerSettings.enabled) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                                        },
                                                )
                                            }
                                            IconButton(
                                                onClick = { actionsMenuExpanded = true },
                                                modifier = Modifier.size(40.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.MoreVert,
                                                    contentDescription = "Acciones de la canción",
                                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                                )
                                            }
                                            NowPlayingActionsMenu(
                                                expanded = actionsMenuExpanded,
                                                onDismiss = { actionsMenuExpanded = false },
                                                matchedAlbumName = effectiveAlbumName,
                                                matchedArtistName = effectiveArtistName,
                                                containingPlaylists = containingPlaylists,
                                                discoverOrigin = discoverOrigin,
                                                isLocal = localSong != null,
                                                canEditAlbum = localSong != null && matchedAlbum != null,
                                                actions =
                                                    remember(
                                                        matchedAlbum,
                                                        effectiveAlbumName,
                                                        effectiveArtistName,
                                                        localSong,
                                                        songDialogs,
                                                        viewModel,
                                                        onDismiss,
                                                    ) {
                                                        NowPlayingMenuActions(
                                                            navigation =
                                                                NowPlayingNavigationActions(
                                                                    onGoToAlbum = navigateToAlbum,
                                                                    onGoToArtist = navigateToArtist,
                                                                    onGoToLocalPlaylist = { id ->
                                                                        goToPlaylists { viewModel.openLocalPlaylist(id) }
                                                                    },
                                                                    onGoToListenBrainz = { mbid ->
                                                                        goToDiscover { viewModel.openListenBrainzPlaylistDetail(mbid) }
                                                                    },
                                                                    onGoToCfRecommendations = {
                                                                        goToDiscover { viewModel.openCfRecommendationsDetail() }
                                                                    },
                                                                ),
                                                            song =
                                                                NowPlayingSongActions.from(
                                                                    dialogs = songDialogs,
                                                                    localSong = localSong,
                                                                    onEditAlbum = { albumForEdit = matchedAlbum },
                                                                    onStartRadio = { viewModel.startRadio() },
                                                                ),
                                                        )
                                                    },
                                            )
                                        }
                                    }

                                    val remoteItem = item as? PlayableItem.Remote
                                    if (remoteItem != null) {
                                        NowPlayingRemoteDownloadButton(
                                            viewModel = viewModel,
                                            remoteItem = remoteItem,
                                        )
                                    }

                                    // 3. Scrubber interactivo
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp),
                                    ) {
                                        PlaybackScrubber(
                                            durationMs = item.durationMs,
                                            positionMsFlow = viewModel.playbackPositionMs,
                                            onSeek = { viewModel.seekTo(it) },
                                        )
                                    }

                                    // 4. Fila de controles de reproducción
                                    NowPlayingControlsRow(
                                        isPlaying = isPlaying,
                                        isPlaybackLoading = isPlaybackLoading,
                                        isShuffle = isShuffle,
                                        repeatMode = repeatMode,
                                        actions = transportActions,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                    )

                                    // 5. Espacio inferior que despega los botones de la barra de cola
                                    Spacer(modifier = Modifier.weight(0.80f))
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
                                isPlaybackLoading = isPlaybackLoading,
                                isShuffle = isShuffle,
                                repeatMode = repeatMode,
                                isFetchingLyrics = isFetchingLyrics,
                                lyricsFetchError = lyricsFetchError,
                                actions = transportActions,
                                onSeekToLyric = viewModel::seekToAndPlay,
                                onRetryFetchLyrics = viewModel::retryFetchLyrics,
                            )
                        }
                    }
                }

                // 5. Barra horizontal fina inferior - trigger con swipe up progresivo (fuera del pager)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .sheetDragUpTrigger(
                                state = queueSheetState,
                                onClick = {
                                    viewModel.refreshQueueSuggestions()
                                    queueSheetState.open()
                                },
                                onStartDrag = {
                                    viewModel.refreshQueueSuggestions()
                                },
                            ),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .width(42.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        Text(
                            text = "Cola" + if (queueItems.isNotEmpty()) " (${queueItems.size})" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            // Scrim overlay behind QueueScreen
            if (queueSheetState.isOpen) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = queueSheetState.progress * 0.55f
                            }.background(Color.Black),
                )

                QueueScreen(
                    viewModel = viewModel,
                    onDismiss = {
                        queueSheetState.dismiss()
                    },
                    sheetState = queueSheetState,
                    backEnabled = true,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .sheetLayout(queueSheetState),
                )
            }

            if (showEqualizerScreen) {
                BackHandler { showEqualizerScreen = false }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    EqualizerScreen(
                        viewModel = viewModel,
                        onBack = { showEqualizerScreen = false },
                    )
                }
            }
        }
    }

    AlbumEditDialogsHost(
        albumForEdit = albumForEdit,
        viewModel = viewModel,
        onDismissEdit = { albumForEdit = null },
    )
}
