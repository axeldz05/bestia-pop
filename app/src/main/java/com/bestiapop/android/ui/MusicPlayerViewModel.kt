package com.bestiapop.android.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.ListenSyncCoordinator
import com.bestiapop.android.data.listenbrainz.ListenTracker
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.model.*
import com.bestiapop.android.data.network.ConnectivityObserver
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.MAX_SAVE_WHILE_LISTENING_PERCENT
import com.bestiapop.android.data.preferences.MIN_SAVE_WHILE_LISTENING_PERCENT
import com.bestiapop.android.data.preferences.ThemePreferencesRepository
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.data.stream.StreamResolver
import com.bestiapop.android.domain.radio.ListenBrainzRadio
import com.bestiapop.android.domain.radio.LocalMetadataRadio
import com.bestiapop.android.domain.radio.RadioEngine
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase
import com.bestiapop.android.service.MusicService
import com.bestiapop.android.service.StreamPlaybackTag
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryViewMode
import com.bestiapop.android.ui.theme.ThemePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.widget.Toast

enum class SortOption {
    TITLE,
    ARTIST,
    ALBUM,
    GENRE,
    DATE_ADDED
}

sealed class TokenValidationUiState {
    data object Idle : TokenValidationUiState()
    data object Validating : TokenValidationUiState()
    data class Success(val username: String) : TokenValidationUiState()
    data class Error(val message: String) : TokenValidationUiState()
}

sealed class LbDiscoverListUiState {
    data object Idle : LbDiscoverListUiState()
    data object Loading : LbDiscoverListUiState()
    data object Success : LbDiscoverListUiState()
    data class Error(val message: String) : LbDiscoverListUiState()
}

sealed class LbPlaylistDetailUiState {
    data object Idle : LbPlaylistDetailUiState()
    data object Loading : LbPlaylistDetailUiState()
    data object Success : LbPlaylistDetailUiState()
    data class Error(val message: String) : LbPlaylistDetailUiState()
}

@OptIn(UnstableApi::class)
class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val themeRepository = ThemePreferencesRepository(application)
    private val listenBrainzPreferences = ListenBrainzPreferencesRepository(application)
    private val pendingListenDao = AppDatabase.getDatabase(application).pendingListenDao()
    private val connectivityObserver = ConnectivityObserver(application)

    private val listenSyncCoordinator = ListenSyncCoordinator(
        scope = viewModelScope,
        pendingListenDao = pendingListenDao,
        preferences = listenBrainzPreferences,
        isOnline = { connectivityObserver.isCurrentlyOnline() }
    )

    private val listenTracker = ListenTracker(
        scope = viewModelScope,
        pendingListenDao = pendingListenDao,
        preferences = listenBrainzPreferences,
        onListenEnqueued = { listenSyncCoordinator.requestSync() }
    )

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Theme state
    val currentThemeState: StateFlow<CustomTheme> = themeRepository.selectedThemeFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, ThemePresets.MidnightDark)

    // ListenBrainz state
    val listenBrainzSettings: StateFlow<ListenBrainzSettings> =
        listenBrainzPreferences.settingsFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ListenBrainzSettings())

    val pendingListenCount: StateFlow<Int> = pendingListenDao.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _tokenValidationState = MutableStateFlow<TokenValidationUiState>(TokenValidationUiState.Idle)
    val tokenValidationState = _tokenValidationState.asStateFlow()

    private val matchListenBrainzTracksUseCase = MatchListenBrainzTracksUseCase()

    private val _lbDiscoverPlaylists = MutableStateFlow<List<LbPlaylistSummary>>(emptyList())
    val lbDiscoverPlaylists = _lbDiscoverPlaylists.asStateFlow()

    private val _lbDiscoverListState = MutableStateFlow<LbDiscoverListUiState>(LbDiscoverListUiState.Idle)
    val lbDiscoverListState = _lbDiscoverListState.asStateFlow()

    private val _selectedLbPlaylist = MutableStateFlow<MatchedLbPlaylist?>(null)
    val selectedLbPlaylist = _selectedLbPlaylist.asStateFlow()

    private val _lbPlaylistDetailState = MutableStateFlow<LbPlaylistDetailUiState>(LbPlaylistDetailUiState.Idle)
    val lbPlaylistDetailState = _lbPlaylistDetailState.asStateFlow()

    // Raw songs & playlists
    val rawSongs = repository.allSongsFlow
    val playlists = repository.playlistsFlow

    // Sorting & Searching
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(SortOption.TITLE)
    val sortOption = _sortOption.asStateFlow()

    private val getLibrarySongsUseCase = com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase()
    private val downloadAudioTrackUseCase =
        com.bestiapop.android.domain.usecase.DownloadAudioTrackUseCase(repository)

    val songsState: StateFlow<List<Song>> = combine(rawSongs, _searchQuery, _sortOption) { list, query, sort ->
        getLibrarySongsUseCase.execute(list, query, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun buildLibraryListItems(
        songs: List<Song>,
        viewMode: LibraryViewMode
    ): List<LibraryListItem> =
        getLibrarySongsUseCase.buildListItems(songs, viewMode)

    val albumsState: StateFlow<List<Album>> = songsState.map { songs ->
        getLibrarySongsUseCase.extractAlbums(songs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _artistPhotos = MutableStateFlow<Map<String, String>>(emptyMap())

    val artistsState: StateFlow<List<Artist>> = combine(songsState, _artistPhotos) { songs: List<Song>, photoMap: Map<String, String> ->
        getLibrarySongsUseCase.extractArtists(songs, photoMap)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Player State
    private val streamResolver = StreamResolver()

    private val _currentItem = MutableStateFlow<PlayableItem?>(null)
    val currentItem = _currentItem.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs = _playbackPositionMs.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle = _isShuffle.asStateFlow()

    private val _queue = MutableStateFlow<List<PlayableItem>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _resolvingRemote = MutableStateFlow(false)
    val resolvingRemote = _resolvingRemote.asStateFlow()

    /** Keys already attempted for "Guardar al escuchar" in this process (in-flight + done). */
    private val saveWhileListeningAttempted = mutableSetOf<String>()

    private val _radioActive = MutableStateFlow(false)
    val radioActive = _radioActive.asStateFlow()

    private val _radioLoading = MutableStateFlow(false)
    val radioLoading = _radioLoading.asStateFlow()

    private val _radioMode = MutableStateFlow(RadioMode.EASY)
    val radioMode = _radioMode.asStateFlow()

    private val _radioForceOnline = MutableStateFlow(false)
    val radioForceOnline = _radioForceOnline.asStateFlow()

    private val _radioStatusLabel = MutableStateFlow<String?>(null)
    val radioStatusLabel = _radioStatusLabel.asStateFlow()

    /** Stable key of the catalog track being previewed inside Add Music (null = no catalog preview). */
    private val _catalogPreviewKey = MutableStateFlow<String?>(null)
    val catalogPreviewKey = _catalogPreviewKey.asStateFlow()

    private var prefetchJob: Job? = null
    private var remoteErrorRetryUsed = false
    private var resolvingTransitionJob: Job? = null
    private var radioRefillJob: Job? = null
    private val playedInRadioSession = linkedSetOf<String>()
    /** Last user-chosen mode (session); auto uses this when not forcing. */
    private var radioPreferredMode: RadioMode? = null

    private val radioEngine = RadioEngine(
        localRadio = LocalMetadataRadio(),
        listenBrainzRadio = ListenBrainzRadio(
            lookupMetadata = { artist, recording, token ->
                ListenBrainzClient.lookupRecordingMetadata(artist, recording, token)
            },
            fetchLbRadio = { artistMbid, token, mode ->
                ListenBrainzClient.fetchLbRadioArtist(artistMbid, token, mode = mode)
            },
            fetchRecordingMetadata = { mbids, token ->
                ListenBrainzClient.fetchRecordingMetadata(mbids, token)
            }
        )
    )

    /** Bumped on user-initiated track changes so the queue UI can scroll to the current item. */
    private val _queueFocusEpoch = MutableStateFlow(0)
    val queueFocusEpoch = _queueFocusEpoch.asStateFlow()

    private fun bumpQueueFocus() {
        _queueFocusEpoch.value = _queueFocusEpoch.value + 1
    }

    private val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _volumeLevel = MutableStateFlow(getDeviceVolumeRatio())
    val volumeLevel = _volumeLevel.asStateFlow()

    // Online Catalog & Link Downloader State
    private val _catalogSearchResults = MutableStateFlow<List<OnlineCatalogTrack>>(emptyList())
    val catalogSearchResults = _catalogSearchResults.asStateFlow()

    private val _catalogCategory = MutableStateFlow(CatalogCategory.SONGS)
    val catalogCategory = _catalogCategory.asStateFlow()

    private val _albumSearchResults = MutableStateFlow<List<CatalogAlbum>>(emptyList())
    val albumSearchResults = _albumSearchResults.asStateFlow()

    private val _playlistSearchResults = MutableStateFlow<List<CatalogPlaylist>>(emptyList())
    val playlistSearchResults = _playlistSearchResults.asStateFlow()

    private val _selectedCollectionTitle = MutableStateFlow<String?>(null)
    val selectedCollectionTitle = _selectedCollectionTitle.asStateFlow()

    private val _activeTrackCandidates = MutableStateFlow<List<CatalogTrackCandidate>>(emptyList())
    val activeTrackCandidates = _activeTrackCandidates.asStateFlow()

    private val _isLoadingCollection = MutableStateFlow(false)
    val isLoadingCollection = _isLoadingCollection.asStateFlow()

    private val _isSearchingCatalog = MutableStateFlow(false)
    val isSearchingCatalog = _isSearchingCatalog.asStateFlow()

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus = _downloadStatus.asStateFlow()

    private fun getDeviceVolumeRatio(): Float {

        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    fun setVolume(ratio: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVolume = (ratio * max).toInt().coerceIn(0, max)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _volumeLevel.value = ratio
    }

    init {
        initMediaController()
        startPositionTracker()
        viewModelScope.launch {
            repository.scanMediaStore()
        }
        viewModelScope.launch {
            _catalogSearchResults.value = MetadataFetcher.getFeaturedDemoCatalog()
            _albumSearchResults.value = MetadataFetcher.searchAlbums("")
            _playlistSearchResults.value = MetadataFetcher.searchPlaylists("")
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.migrateLegacyYouTubeMusicSongs()
        }

        viewModelScope.launch {
            connectivityObserver.isOnline.collect { online ->
                if (online) listenSyncCoordinator.requestSync()
            }
        }

        viewModelScope.launch {
            // Kick sync on startup if there are pending listens and we're online.
            if (pendingListenDao.count() > 0 && connectivityObserver.isCurrentlyOnline()) {
                listenSyncCoordinator.requestSync()
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            songsState.collect { songs ->
                val artists = songs.map { it.artist }.distinct().filter { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
                for (artist in artists) {
                    if (!_artistPhotos.value.containsKey(artist)) {
                        val photoUrl = MetadataFetcher.fetchArtistPhotoUrl(artist)
                        if (!photoUrl.isNullOrEmpty()) {
                            _artistPhotos.value = _artistPhotos.value + (artist to photoUrl)
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            songsState.collect { songs ->
                _currentItem.value?.let { current ->
                    if (current is PlayableItem.Local) {
                        songs.find { it.uriString == current.song.uriString }?.let { updated ->
                            setCurrentItem(PlayableItem.Local(updated))
                        }
                    }
                }
                val q = _queue.value
                if (q.any { it is PlayableItem.Local }) {
                    _queue.value = q.map { item ->
                        if (item is PlayableItem.Local) {
                            songs.find { it.uriString == item.song.uriString }?.toPlayable() ?: item
                        } else {
                            item
                        }
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            songsState.collect { songs ->
                val unenhanced = songs.filter {
                    it.artworkUri.isNullOrEmpty() || it.artworkUri?.startsWith("content://") == true
                }
                for (song in unenhanced.take(20)) {
                    repository.enhanceSongMetadataAndLyrics(song)
                }
            }
        }
    }

    private fun initMediaController() {
        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), MusicService::class.java)
        )
        controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                setupPlayerListener()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private var lastMediaItemIndex: Int = -1
    private var suppressShuffleWrapDetection: Boolean = false

    private fun setCurrentItem(item: PlayableItem?) {
        _currentItem.value = item
        val localSong = (item as? PlayableItem.Local)?.song
        _currentSong.value = localSong
        if (localSong != null) {
            listenTracker.onTrackChanged(localSong)
            requestMetadataEnhancement(localSong)
        } else {
            listenTracker.onTrackChanged(null)
        }
    }

    private fun setupPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                _isPlaying.value = isPlayingNow
                if (!isPlayingNow) {
                    listenTracker.onStopped()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlayerError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    (_currentItem.value as? PlayableItem.Remote)?.let { remote ->
                        maybeEnqueueSaveWhileListening(remote, force = true)
                    }
                    maybeAutoStartRadioOnQueueEnd()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val controller = mediaController
                val newIndex = controller?.currentMediaItemIndex ?: -1
                val wrappedShuffleCycle = !suppressShuffleWrapDetection &&
                    _isShuffle.value &&
                    _repeatMode.value == RepeatMode.ALL &&
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    lastMediaItemIndex >= 0 &&
                    _queue.value.isNotEmpty() &&
                    lastMediaItemIndex == _queue.value.lastIndex &&
                    newIndex == 0

                mediaItem?.let { item ->
                    val idOrUri = item.mediaId
                    val playable = _queue.value.find { it.mediaId == idOrUri }
                        ?: _queue.value.find {
                            it is PlayableItem.Local &&
                                (it.song.uriString == idOrUri || it.song.id.toString() == idOrUri)
                        }
                    if (playable != null) {
                        setCurrentItem(playable)
                        remoteErrorRetryUsed = false
                        ensureRemoteReadyAt(newIndex)
                        prefetchAround(newIndex)
                        if (_radioActive.value) {
                            rememberRadioPlayed(playable)
                            maybeRefillRadio(newIndex)
                        }
                    } else {
                        listenTracker.onTrackChanged(null)
                    }
                } ?: listenTracker.onTrackChanged(null)

                if (wrappedShuffleCycle) {
                    val avoid = _queue.value.getOrNull(lastMediaItemIndex)
                    applyShuffledQueue(
                        items = _queue.value,
                        keepItemFirst = null,
                        avoidStartingWith = avoid,
                        startPlaying = true
                    )
                } else {
                    lastMediaItemIndex = newIndex
                }
            }
        })
    }

    private fun songToMediaItem(s: Song): MediaItem {
        return MediaItem.Builder()
            .setMediaId(s.uriString)
            .setUri(parseToMediaUri(s.uriString))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.title)
                    .setArtist(s.artist)
                    .setAlbumTitle(s.album)
                    .setArtworkUri(parseToArtworkUri(s.artworkUri))
                    .build()
            )
            .build()
    }

    private fun playableToMediaItem(item: PlayableItem): MediaItem {
        return when (item) {
            is PlayableItem.Local -> songToMediaItem(item.song)
            is PlayableItem.Remote -> {
                val resolved = item.resolved
                val uri = if (resolved != null) {
                    parseToMediaUri(resolved.audioUrl)
                } else {
                    Uri.EMPTY
                }
                val builder = MediaItem.Builder()
                    .setMediaId(item.mediaId)
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(item.artist)
                            .setAlbumTitle(item.album)
                            .setArtworkUri(parseToArtworkUri(item.artworkUri))
                            .build()
                    )
                if (resolved != null) {
                    builder.setTag(StreamPlaybackTag(resolved.userAgent, resolved.videoId))
                }
                builder.build()
            }
        }
    }

    private fun remoteNeedsResolve(item: PlayableItem.Remote): Boolean {
        val resolved = item.resolved ?: return true
        return !streamResolver.isFresh(resolved)
    }

    private suspend fun resolveRemote(item: PlayableItem.Remote): PlayableItem.Remote? {
        val resolved = streamResolver.resolve(item).getOrNull() ?: return null
        return item.copy(resolved = resolved)
    }

    private fun updateQueueItem(index: Int, item: PlayableItem) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices) return
        list[index] = item
        _queue.value = list
        if (_currentItem.value?.mediaId == item.mediaId ||
            (index == (mediaController?.currentMediaItemIndex ?: -1))
        ) {
            setCurrentItem(item)
        }
    }

    private fun ensureRemoteReadyAt(index: Int) {
        val item = _queue.value.getOrNull(index) as? PlayableItem.Remote ?: return
        if (!remoteNeedsResolve(item)) return
        resolvingTransitionJob?.cancel()
        resolvingTransitionJob = viewModelScope.launch {
            _resolvingRemote.value = true
            try {
                val resolvedItem = resolveRemote(item)
                if (resolvedItem == null) {
                    mediaController?.seekToNextMediaItem()
                    return@launch
                }
                updateQueueItem(index, resolvedItem)
                mediaController?.replaceMediaItem(index, playableToMediaItem(resolvedItem))
                mediaController?.prepare()
                mediaController?.play()
            } finally {
                _resolvingRemote.value = false
            }
        }
    }

    private fun prefetchAround(index: Int) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            val queue = _queue.value
            val targets = listOfNotNull(
                queue.getOrNull(index + 1),
                queue.getOrNull(index + 2)
            ).filterIsInstance<PlayableItem.Remote>()
                .filter { remoteNeedsResolve(it) }
            if (targets.isEmpty()) return@launch

            for (remote in targets) {
                val resolved = resolveRemote(remote) ?: continue
                val qi = _queue.value.indexOfFirst { it.mediaId == remote.mediaId }
                if (qi >= 0) {
                    updateQueueItem(qi, resolved)
                    // Keep player timeline in sync if this slot already exists
                    if (qi < (mediaController?.mediaItemCount ?: 0)) {
                        mediaController?.replaceMediaItem(qi, playableToMediaItem(resolved))
                    }
                }
            }
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        val index = mediaController?.currentMediaItemIndex ?: return
        val item = _queue.value.getOrNull(index) as? PlayableItem.Remote
        if (item == null) return

        val isHttpFailure = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED

        if (!isHttpFailure) {
            mediaController?.seekToNextMediaItem()
            return
        }

        if (remoteErrorRetryUsed) {
            remoteErrorRetryUsed = false
            mediaController?.seekToNextMediaItem()
            return
        }

        remoteErrorRetryUsed = true
        viewModelScope.launch {
            _resolvingRemote.value = true
            try {
                item.resolved?.videoId?.let { streamResolver.invalidate(it) }
                val refreshed = resolveRemote(item.copy(resolved = null))
                if (refreshed == null) {
                    mediaController?.seekToNextMediaItem()
                    return@launch
                }
                updateQueueItem(index, refreshed)
                mediaController?.replaceMediaItem(index, playableToMediaItem(refreshed))
                mediaController?.prepare()
                mediaController?.play()
            } finally {
                _resolvingRemote.value = false
            }
        }
    }

    /**
     * Builds a shuffled queue permutation. Optionally pins [keepItemFirst] at index 0,
     * or avoids starting with [avoidStartingWith] after a full cycle.
     */
    private fun applyShuffledQueue(
        items: List<PlayableItem>,
        keepItemFirst: PlayableItem?,
        avoidStartingWith: PlayableItem? = null,
        startPlaying: Boolean = true
    ) {
        if (items.isEmpty()) return
        fun sameItem(a: PlayableItem, b: PlayableItem): Boolean {
            if (a.mediaId == b.mediaId) return true
            if (a is PlayableItem.Local && b is PlayableItem.Local) {
                return a.song.id == b.song.id || a.song.uriString == b.song.uriString
            }
            return false
        }
        val shuffled = when {
            keepItemFirst != null -> {
                val rest = items.filter { !sameItem(it, keepItemFirst) }.shuffled()
                listOf(keepItemFirst) + rest
            }
            avoidStartingWith != null && items.size > 1 -> {
                var attempt = items.shuffled()
                var tries = 0
                while (tries < 5 && sameItem(attempt.first(), avoidStartingWith)) {
                    attempt = items.shuffled()
                    tries++
                }
                attempt
            }
            else -> items.shuffled()
        }

        suppressShuffleWrapDetection = true
        try {
            _queue.value = shuffled
            setCurrentItem(shuffled.first())
            _isShuffle.value = true
            lastMediaItemIndex = 0
            mediaController?.let { controller ->
                val resumePosition = if (keepItemFirst != null) {
                    controller.currentPosition.coerceAtLeast(0L)
                } else {
                    0L
                }
                controller.shuffleModeEnabled = false
                controller.setMediaItems(shuffled.map { playableToMediaItem(it) }, 0, resumePosition)
                controller.prepare()
                if (startPlaying) controller.play()
            }
            prefetchAround(0)
        } finally {
            suppressShuffleWrapDetection = false
        }
    }

    private var lastSeekTimestamp = 0L

    private fun startPositionTracker() {
        viewModelScope.launch {
            while (true) {
                mediaController?.let { controller ->
                    if (controller.isPlaying && System.currentTimeMillis() - lastSeekTimestamp > 600) {
                        _playbackPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
                        val dur = controller.duration
                        val curr = _currentItem.value
                        if (dur > 0 && curr != null && curr.durationMs <= 0) {
                            when (curr) {
                                is PlayableItem.Local -> {
                                    updateSongDuration(curr.song.id, dur)
                                    listenTracker.onDurationKnown(curr.song.id, dur)
                                }
                                is PlayableItem.Remote -> {
                                    val idx = controller.currentMediaItemIndex
                                    if (idx in _queue.value.indices) {
                                        updateQueueItem(idx, curr.copy(durationMs = dur))
                                    }
                                }
                            }
                        }
                        (curr as? PlayableItem.Remote)?.let { remote ->
                            val durationMs = when {
                                remote.durationMs > 0 -> remote.durationMs
                                dur > 0 -> dur
                                else -> controller.duration
                            }
                            maybeEnqueueSaveWhileListening(
                                remote = remote,
                                force = false,
                                durationMs = durationMs
                            )
                        }
                    }
                    listenTracker.onPlaybackTick(
                        isPlaying = controller.isPlaying,
                        elapsedRealtimeMs = SystemClock.elapsedRealtime()
                    )
                }
                delay(200)
            }
        }
    }

    /**
     * Background-download a remote into the library when "Guardar al escuchar" is on.
     * Does not pause or replace the playing MediaItem.
     * [force] = track ended → save regardless of percent (still requires pref ON).
     */
    private fun maybeEnqueueSaveWhileListening(
        remote: PlayableItem.Remote,
        force: Boolean,
        durationMs: Long = remote.durationMs
    ) {
        if (!listenBrainzSettings.value.saveWhileListening) return
        if (!force) {
            val knownDuration = durationMs.takeIf { it > 0 } ?: return
            val percent = listenBrainzSettings.value.saveWhileListeningPercent
                .coerceIn(MIN_SAVE_WHILE_LISTENING_PERCENT, MAX_SAVE_WHILE_LISTENING_PERCENT)
            val thresholdMs = knownDuration * percent / 100L
            if (_playbackPositionMs.value < thresholdMs) return
        }
        val key = MatchListenBrainzTracksUseCase.matchKey(remote.artist, remote.title)
        if (key.isEmpty() || key in saveWhileListeningAttempted) return
        saveWhileListeningAttempted.add(key)

        val track = OnlineCatalogTrack(
            id = remote.youtubeQueryOrId?.takeIf { it.isNotBlank() }
                ?: "${remote.artist} ${remote.title}",
            title = remote.title,
            artist = remote.artist,
            album = remote.album.orEmpty(),
            artworkUrl = remote.artworkUri,
            durationMs = remote.durationMs,
            audioUrl = "",
            provider = "YouTube"
        )

        viewModelScope.launch {
            val result = downloadTrack(track, onProgress = null)
            result.fold(
                onSuccess = { song ->
                    Toast.makeText(
                        getApplication(),
                        "«${song.title}» guardada en biblioteca",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { e ->
                    Toast.makeText(
                        getApplication(),
                        "No se pudo guardar «${remote.title}»: ${e.localizedMessage ?: "error"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    fun updateSongDuration(songId: Long, durationMs: Long) {
        viewModelScope.launch {
            repository.updateSongDuration(songId, durationMs)
        }
    }

    fun retryFetchLyrics(song: Song) {
        requestMetadataEnhancement(song, force = true)
    }

    fun enhanceSongMetadataAndLyrics(song: Song) {
        requestMetadataEnhancement(song, force = true)
    }

    private fun songNeedsMetadataEnhancement(song: Song): Boolean {
        val artMissing = song.artworkUri.isNullOrEmpty() || song.artworkUri?.startsWith("content://") == true
        val lyricsMissing = song.lyrics.isNullOrEmpty()
        val durationMissing = song.durationMs <= 0
        return artMissing || lyricsMissing || durationMissing
    }

    private fun requestMetadataEnhancement(song: Song, force: Boolean = false) {
        if (!force && !songNeedsMetadataEnhancement(song)) return
        viewModelScope.launch {
            repository.enhanceSongMetadataAndLyrics(song)
        }
    }

    private fun parseToMediaUri(uriStr: String?): Uri {
        if (uriStr.isNullOrBlank()) return Uri.EMPTY
        val trimmed = uriStr.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("content://")) {
            return Uri.parse(trimmed)
        }
        var cleanPath = trimmed
        while (cleanPath.startsWith("file:")) {
            cleanPath = cleanPath.removePrefix("file:")
        }
        cleanPath = "/" + cleanPath.trimStart('/')
        return Uri.fromFile(java.io.File(cleanPath))
    }

    private fun parseToArtworkUri(uriStr: String?): Uri? {
        if (uriStr.isNullOrBlank()) return null
        val trimmed = uriStr.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("content://")) {
            Uri.parse(trimmed)
        } else {
            null
        }
    }



    fun playSong(song: Song, playlistOrQueue: List<Song> = emptyList()) {
        _catalogPreviewKey.value = null
        val baseList = if (playlistOrQueue.isNotEmpty()) playlistOrQueue else songsState.value
        val indexInBase = baseList.indexOfFirst { it.id == song.id || it.uriString == song.uriString }

        val targetQueue = if (indexInBase != -1) baseList else listOf(song)
        val index = if (indexInBase != -1) indexInBase else 0
        playPlayableCollection(targetQueue.toPlayableItems(), index)
    }

    fun playPlayableCollection(
        items: List<PlayableItem>,
        startIndex: Int = 0,
        fromRadio: Boolean = false
    ) {
        if (items.isEmpty()) return
        if (!fromRadio) clearRadioSession()
        val validIndex = startIndex.coerceIn(0, items.size - 1)
        viewModelScope.launch {
            var working = items.toMutableList()
            val startItem = working[validIndex]
            if (startItem is PlayableItem.Remote && remoteNeedsResolve(startItem)) {
                _resolvingRemote.value = true
                try {
                    val resolved = resolveRemote(startItem)
                    if (resolved == null) {
                        // Try next items
                        val nextRemote = working.withIndex()
                            .filter { it.index != validIndex && it.value is PlayableItem.Remote }
                        var played = false
                        for ((idx, remote) in nextRemote) {
                            val r = resolveRemote(remote as PlayableItem.Remote) ?: continue
                            working[idx] = r
                            finishPlayPlayableCollection(working, idx)
                            played = true
                            break
                        }
                        if (!played) return@launch
                        return@launch
                    }
                    working[validIndex] = resolved
                } finally {
                    _resolvingRemote.value = false
                }
            }
            finishPlayPlayableCollection(working, validIndex)
            prefetchAround(validIndex)
            if (fromRadio && _radioActive.value) {
                maybeRefillRadio(validIndex)
            }
        }
    }

    private fun finishPlayPlayableCollection(items: List<PlayableItem>, index: Int) {
        _queue.value = items
        setCurrentItem(items[index])
        lastMediaItemIndex = index
        _isShuffle.value = false
        remoteErrorRetryUsed = false
        bumpQueueFocus()

        mediaController?.let { controller ->
            controller.shuffleModeEnabled = false
            controller.setMediaItems(items.map { playableToMediaItem(it) }, index, 0L)
            controller.prepare()
            controller.play()
        }
    }

    fun catalogPreviewKeyFor(track: OnlineCatalogTrack): String {
        return track.id.takeIf { it.isNotBlank() }
            ?: "${track.artist.trim().lowercase()}|${track.title.trim().lowercase()}"
    }

    fun playOnlineCatalogTrackAsStream(track: OnlineCatalogTrack) {
        val key = catalogPreviewKeyFor(track)
        if (_catalogPreviewKey.value == key && _currentItem.value != null) {
            togglePlayPause()
            return
        }
        _catalogPreviewKey.value = key
        val queryOrId = track.id.takeIf { it.isNotBlank() }
            ?: track.audioUrl.takeIf {
                it.contains("youtube", ignoreCase = true) ||
                    it.contains("youtu.be", ignoreCase = true) ||
                    it.length == 11
            }
            ?: "${track.artist} ${track.title}".trim()
        val remote = PlayableItem.Remote(
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkUri = track.artworkUrl,
            durationMs = track.durationMs,
            youtubeQueryOrId = queryOrId
        )
        playPlayableCollection(listOf(remote), 0)
    }

    fun clearCatalogPreview() {
        _catalogPreviewKey.value = null
    }

    // Unified Collection / Group Pipeline ("Everything is a Playlist")
    fun playCollection(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val validIndex = startIndex.coerceIn(0, songs.size - 1)
        playSong(songs[validIndex], songs)
    }

    fun playCollection(songs: List<Song>, startSong: Song) {
        playSong(startSong, songs)
    }

    fun setAlbumArtwork(albumName: String, artworkUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val savedUri = repository.savePlaylistCoverImage(artworkUri) ?: artworkUri
            val albumSongs = songsState.value.filter { it.album.equals(albumName, ignoreCase = true) }
            albumSongs.forEach { song ->
                repository.enhanceSongMetadataAndLyrics(song.copy(artworkUri = savedUri))
            }
        }
    }

    fun shuffleCollection(songs: List<Song>) {
        if (songs.isEmpty()) return
        applyShuffledQueue(songs.toPlayableItems(), keepItemFirst = null)
        bumpQueueFocus()
    }

    fun enqueueCollection(songs: List<Song>) {
        addToQueueBatch(songs)
    }

    fun togglePlayPause() {
        mediaController?.let { controller ->
            if (controller.isPlaying) {
                controller.pause()
            } else {
                controller.play()
            }
        }
    }

    fun skipToNext() {
        bumpQueueFocus()
        mediaController?.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        bumpQueueFocus()
        mediaController?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        lastSeekTimestamp = System.currentTimeMillis()
        _playbackPositionMs.value = positionMs
        mediaController?.seekTo(positionMs)
    }

    fun toggleRepeatMode() {
        val nextMode = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _repeatMode.value = nextMode

        mediaController?.let { controller ->
            controller.repeatMode = when (nextMode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
        }
    }

    fun toggleShuffle() {
        val newShuffle = !_isShuffle.value
        if (newShuffle) {
            val queue = _queue.value
            val current = _currentItem.value
            if (queue.isEmpty()) {
                _isShuffle.value = true
                mediaController?.shuffleModeEnabled = false
                return
            }
            applyShuffledQueue(queue, keepItemFirst = current)
            bumpQueueFocus()
        } else {
            _isShuffle.value = false
            mediaController?.shuffleModeEnabled = false
        }
    }

    // Queue Management
    fun addToQueue(song: Song) {
        addToQueueBatch(listOf(song))
    }

    fun addToQueueBatch(songs: List<Song>) {
        if (songs.isEmpty()) return
        addPlayableBatch(songs.toPlayableItems())
    }

    fun addPlayableBatch(items: List<PlayableItem>) {
        if (items.isEmpty()) return
        val currentList = _queue.value.toMutableList()
        currentList.addAll(items)
        _queue.value = currentList

        mediaController?.let { controller ->
            controller.addMediaItems(items.map { playableToMediaItem(it) })
        }
    }

    fun setRadioPreferredMode(mode: RadioMode) {
        radioPreferredMode = mode
        if (mode == RadioMode.EASY) {
            _radioForceOnline.value = false
        }
    }

    fun setRadioForceOnline(force: Boolean) {
        _radioForceOnline.value = force
        if (force) {
            radioPreferredMode = RadioMode.EXPLORE
        }
    }

    fun stopRadio() {
        radioRefillJob?.cancel()
        radioRefillJob = null
        _radioActive.value = false
        _radioForceOnline.value = false
        playedInRadioSession.clear()
        _radioMode.value = RadioMode.EASY
        updateRadioStatusLabel()
    }

    fun startRadio(
        seedSong: Song? = null,
        mode: RadioMode? = null,
        auto: Boolean = false,
        announceMode: Boolean = false
    ) {
        val seed: PlayableItem = seedSong?.toPlayable()
            ?: _currentItem.value
            ?: run {
                if (!auto) {
                    Toast.makeText(
                        getApplication(),
                        "Necesitás una canción con artista y título para Radio",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }
        if (seed.artist.isBlank() || seed.title.isBlank()) {
            if (!auto) {
                Toast.makeText(
                    getApplication(),
                    "Necesitás una canción con artista y título para Radio",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        if (_radioLoading.value) return

        if (mode != null) {
            setRadioPreferredMode(mode)
            if (mode == RadioMode.EXPLORE && !_radioForceOnline.value) {
                // explicit Online without force
            }
        }

        val settings = listenBrainzSettings.value
        val canUseLb = settings.enabled &&
            settings.userToken.isNotBlank() &&
            connectivityObserver.isCurrentlyOnline()

        var force = _radioForceOnline.value
        var resolvedMode = when {
            force -> RadioMode.EXPLORE
            mode != null -> mode
            radioPreferredMode != null -> radioPreferredMode!!
            canUseLb -> RadioMode.EXPLORE
            else -> RadioMode.EASY
        }

        if (force && !canUseLb) {
            if (!auto) {
                Toast.makeText(
                    getApplication(),
                    "Radio online no disponible, pasando a offline",
                    Toast.LENGTH_SHORT
                ).show()
            }
            _radioForceOnline.value = false
            force = false
            resolvedMode = RadioMode.EASY
            radioPreferredMode = RadioMode.EASY
        }

        val keepCurrentPlaying = !auto && shouldKeepCurrentWhenStartingRadio()
        val toastMode = announceMode

        viewModelScope.launch {
            _radioLoading.value = true
            try {
                val library = rawSongs.first()
                val exclude = playedInRadioSession.toMutableSet()
                val seedKey = MatchListenBrainzTracksUseCase.matchKey(seed.artist, seed.title)
                if (seedKey.isNotEmpty()) exclude.add(seedKey)
                exclude.add(seed.mediaId)
                _currentItem.value?.let { current ->
                    val currentKey = MatchListenBrainzTracksUseCase.matchKey(current.artist, current.title)
                    if (currentKey.isNotEmpty()) exclude.add(currentKey)
                    exclude.add(current.mediaId)
                }
                if (!keepCurrentPlaying) {
                    for (item in _queue.value) {
                        val key = MatchListenBrainzTracksUseCase.matchKey(item.artist, item.title)
                        if (key.isNotEmpty()) exclude.add(key)
                        exclude.add(item.mediaId)
                    }
                }

                var effectiveMode = resolvedMode
                var batch = radioEngine.suggest(
                    seed = seed,
                    library = library,
                    mode = effectiveMode,
                    excludeKeys = exclude,
                    limit = RADIO_BATCH_SIZE,
                    lbToken = settings.userToken.takeIf { it.isNotBlank() },
                    lbAvailable = canUseLb
                )

                if (force && batch.listenBrainzFailed) {
                    if (!auto) {
                        Toast.makeText(
                            getApplication(),
                            "Radio online no disponible, pasando a offline",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    _radioForceOnline.value = false
                    force = false
                    effectiveMode = RadioMode.EASY
                    radioPreferredMode = RadioMode.EASY
                    batch = radioEngine.suggest(
                        seed = seed,
                        library = library,
                        mode = RadioMode.EASY,
                        excludeKeys = exclude,
                        limit = RADIO_BATCH_SIZE,
                        lbToken = null,
                        lbAvailable = false
                    )
                }

                val suggestions = batch.items
                if (suggestions.isEmpty()) {
                    if (!auto) {
                        Toast.makeText(
                            getApplication(),
                            "No encontré canciones parecidas",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val previousPlayed = if (_radioActive.value) playedInRadioSession.toSet() else emptySet()
                clearRadioSessionKeepPreference()
                _radioMode.value = effectiveMode
                _radioForceOnline.value = force
                playedInRadioSession.addAll(previousPlayed)
                playedInRadioSession.addAll(exclude)
                rememberRadioPlayed(seed)
                _radioActive.value = true
                updateRadioStatusLabel()

                if (toastMode && !auto) {
                    val label = if (effectiveMode == RadioMode.EXPLORE) "Radio online" else "Radio offline"
                    Toast.makeText(getApplication(), label, Toast.LENGTH_SHORT).show()
                }

                if (keepCurrentPlaying) {
                    replaceUpcomingWithRadio(suggestions)
                    Toast.makeText(
                        getApplication(),
                        "Se agregaron canciones de la radio a la cola",
                        Toast.LENGTH_SHORT
                    ).show()
                    val idx = mediaController?.currentMediaItemIndex ?: lastMediaItemIndex
                    if (idx >= 0) prefetchAround(idx)
                } else {
                    playPlayableCollection(suggestions, startIndex = 0, fromRadio = true)
                }
            } finally {
                _radioLoading.value = false
            }
        }
    }

    private fun shouldKeepCurrentWhenStartingRadio(): Boolean {
        val controller = mediaController ?: return false
        if (_queue.value.isEmpty()) return false
        val index = controller.currentMediaItemIndex
        if (index < 0 || index >= _queue.value.size) return false
        val state = controller.playbackState
        return state != Player.STATE_ENDED && state != Player.STATE_IDLE
    }

    /**
     * Keeps the current track playing; replaces everything after it with [suggestions].
     */
    private fun replaceUpcomingWithRadio(suggestions: List<PlayableItem>) {
        val controller = mediaController
        val currentIndex = (controller?.currentMediaItemIndex ?: lastMediaItemIndex).coerceAtLeast(0)
        val currentList = _queue.value
        if (currentIndex !in currentList.indices) {
            playPlayableCollection(suggestions, startIndex = 0, fromRadio = true)
            return
        }

        val kept = currentList.subList(0, currentIndex + 1).toMutableList()
        kept.addAll(suggestions)
        _queue.value = kept

        controller?.let { c ->
            val nextIndex = currentIndex + 1
            if (nextIndex < c.mediaItemCount) {
                c.removeMediaItems(nextIndex, c.mediaItemCount)
            }
            c.addMediaItems(suggestions.map { playableToMediaItem(it) })
        }
    }

    /**
     * When the queue finishes naturally and repeat is off, continue with Radio
     * seeded from the last played item.
     */
    private fun maybeAutoStartRadioOnQueueEnd() {
        if (_repeatMode.value != RepeatMode.OFF) return
        if (_radioLoading.value) return
        val seed = _currentItem.value ?: return
        if (seed.artist.isBlank() || seed.title.isBlank()) return
        startRadio(auto = true)
    }

    /** Clears active radio flags but keeps preferred mode for the next start. */
    private fun clearRadioSessionKeepPreference() {
        radioRefillJob?.cancel()
        radioRefillJob = null
        _radioActive.value = false
        playedInRadioSession.clear()
        updateRadioStatusLabel()
    }

    private fun clearRadioSession() {
        stopRadio()
        radioPreferredMode = null
    }

    private fun updateRadioStatusLabel() {
        if (!_radioActive.value) {
            _radioStatusLabel.value = null
            return
        }
        _radioStatusLabel.value = when {
            _radioForceOnline.value && _radioMode.value == RadioMode.EXPLORE ->
                "Radio · Online (forzado)"
            _radioMode.value == RadioMode.EXPLORE ->
                "Radio · Online"
            else ->
                "Radio · Offline"
        }
    }

    private fun rememberRadioPlayed(item: PlayableItem) {
        val key = MatchListenBrainzTracksUseCase.matchKey(item.artist, item.title)
        if (key.isNotEmpty()) playedInRadioSession.add(key)
        playedInRadioSession.add(item.mediaId)
    }

    private fun buildRadioExcludeKeys(seed: PlayableItem): MutableSet<String> {
        val exclude = playedInRadioSession.toMutableSet()
        val seedKey = MatchListenBrainzTracksUseCase.matchKey(seed.artist, seed.title)
        if (seedKey.isNotEmpty()) exclude.add(seedKey)
        exclude.add(seed.mediaId)
        for (item in _queue.value) {
            val key = MatchListenBrainzTracksUseCase.matchKey(item.artist, item.title)
            if (key.isNotEmpty()) exclude.add(key)
            exclude.add(item.mediaId)
        }
        return exclude
    }

    private fun maybeRefillRadio(currentIndex: Int) {
        if (!_radioActive.value) return
        if (radioRefillJob?.isActive == true) return
        val remaining = _queue.value.size - currentIndex - 1
        if (remaining >= RADIO_REFILL_THRESHOLD) return
        val seed = _currentItem.value ?: return

        radioRefillJob = viewModelScope.launch {
            val settings = listenBrainzSettings.value
            val canUseLb = settings.enabled &&
                settings.userToken.isNotBlank() &&
                connectivityObserver.isCurrentlyOnline()
            var force = _radioForceOnline.value
            var mode = when {
                force -> RadioMode.EXPLORE
                else -> _radioMode.value
            }

            if (force && !canUseLb) {
                Toast.makeText(
                    getApplication(),
                    "Radio online no disponible, pasando a offline",
                    Toast.LENGTH_SHORT
                ).show()
                _radioForceOnline.value = false
                force = false
                mode = RadioMode.EASY
                _radioMode.value = RadioMode.EASY
                radioPreferredMode = RadioMode.EASY
                updateRadioStatusLabel()
            }

            val library = rawSongs.first()
            val exclude = buildRadioExcludeKeys(seed)
            var batch = radioEngine.suggest(
                seed = seed,
                library = library,
                mode = mode,
                excludeKeys = exclude,
                limit = RADIO_BATCH_SIZE,
                lbToken = settings.userToken.takeIf { it.isNotBlank() },
                lbAvailable = canUseLb
            )

            if (force && batch.listenBrainzFailed) {
                Toast.makeText(
                    getApplication(),
                    "Radio online no disponible, pasando a offline",
                    Toast.LENGTH_SHORT
                ).show()
                _radioForceOnline.value = false
                mode = RadioMode.EASY
                _radioMode.value = RadioMode.EASY
                radioPreferredMode = RadioMode.EASY
                updateRadioStatusLabel()
                batch = radioEngine.suggest(
                    seed = seed,
                    library = library,
                    mode = RadioMode.EASY,
                    excludeKeys = exclude,
                    limit = RADIO_BATCH_SIZE,
                    lbToken = null,
                    lbAvailable = false
                )
            }

            if (batch.items.isNotEmpty() && _radioActive.value) {
                if (mode == RadioMode.EXPLORE) {
                    _radioMode.value = RadioMode.EXPLORE
                    updateRadioStatusLabel()
                }
                addPlayableBatch(batch.items)
            }
        }
    }

    fun playNextInQueue(song: Song) {
        playNextBatch(listOf(song))
    }

    fun playNextBatch(songs: List<Song>) {
        if (songs.isEmpty()) return
        val playables = songs.toPlayableItems()
        val currentList = _queue.value.toMutableList()
        val currentIndex = (mediaController?.currentMediaItemIndex ?: 0).coerceAtLeast(0)
        val insertIndex = (currentIndex + 1).coerceAtMost(currentList.size)

        currentList.addAll(insertIndex, playables)
        _queue.value = currentList

        mediaController?.let { controller ->
            controller.addMediaItems(insertIndex, playables.map { playableToMediaItem(it) })
        }
    }

    fun addSongsToPlaylist(playlistId: Long, songs: List<Song>) {
        viewModelScope.launch {
            songs.forEach { song ->
                repository.addSongToPlaylist(playlistId, song.id)
            }
        }
    }

    @JvmName("addSongsToPlaylistByIds")
    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        viewModelScope.launch {
            songIds.forEach { id ->
                repository.addSongToPlaylist(playlistId, id)
            }
        }
    }

    fun deleteSongsFromApp(songs: List<Song>) {
        viewModelScope.launch {
            repository.deleteSongsFromApp(songs)
        }
    }

    fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String
    ) {
        viewModelScope.launch {
            repository.updateSongMetadata(songId, title, artist, album, genre)
        }
    }

    fun deleteSongsFromDevice(songs: List<Song>) {
        viewModelScope.launch {
            repository.deleteSongsFromDevice(songs)
        }
    }

    fun removeFromQueue(index: Int) {
        if (index in 0 until _queue.value.size) {
            val currentList = _queue.value.toMutableList()
            currentList.removeAt(index)
            _queue.value = currentList
            mediaController?.removeMediaItem(index)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val list = _queue.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices && fromIndex != toIndex) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _queue.value = list
            mediaController?.moveMediaItem(fromIndex, toIndex)
        }
    }

    fun skipToQueueIndex(index: Int) {
        if (index in 0 until _queue.value.size) {
            bumpQueueFocus()
            lastMediaItemIndex = index
            val item = _queue.value[index]
            if (item is PlayableItem.Remote && remoteNeedsResolve(item)) {
                viewModelScope.launch {
                    _resolvingRemote.value = true
                    try {
                        val resolved = resolveRemote(item)
                        if (resolved == null) {
                            mediaController?.seekToNextMediaItem()
                            return@launch
                        }
                        updateQueueItem(index, resolved)
                        mediaController?.replaceMediaItem(index, playableToMediaItem(resolved))
                        mediaController?.seekTo(index, 0L)
                        mediaController?.prepare()
                        mediaController?.play()
                        prefetchAround(index)
                    } finally {
                        _resolvingRemote.value = false
                    }
                }
            } else {
                mediaController?.seekTo(index, 0L)
                prefetchAround(index)
            }
        }
    }


    // Search and Sort
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOption(option: SortOption) {
        _sortOption.value = option
    }

    // SAF Import
    fun importFolder(treeUri: Uri) {
        viewModelScope.launch {
            repository.scanFolderUri(treeUri)
        }
    }

    // Playlists
    fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> {
        return repository.getPlaylistSongsFlow(playlistId)
    }

    fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> {
        return repository.getPlaylistDetailsFlow(playlistId)
    }

    fun createPlaylist(
        name: String,
        description: String? = null,
        coverUri: String? = null,
        onCreated: ((Long) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val id = repository.createPlaylist(name, description, coverUri)
            onCreated?.invoke(id)
        }
    }

    fun updatePlaylist(
        id: Long,
        name: String,
        description: String? = null,
        coverUri: String? = null
    ) {
        viewModelScope.launch {
            repository.updatePlaylist(id, name, description, coverUri)
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(id)
        }
    }

    fun addSongToPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch {
            repository.addSongToPlaylist(playlistId, song.id)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, songId)
        }
    }

    // Theme Actions
    fun selectThemePreset(presetId: String) {
        viewModelScope.launch {
            themeRepository.selectPreset(presetId)
        }
    }

    fun saveCustomTheme(colors: ColorSchemeData) {
        viewModelScope.launch {
            themeRepository.saveCustomColors(colors)
        }
    }

    // ListenBrainz Actions
    fun setListenBrainzEnabled(enabled: Boolean) {
        viewModelScope.launch {
            listenBrainzPreferences.setEnabled(enabled)
            if (enabled) listenSyncCoordinator.requestSync()
            if (!enabled) clearDiscoverState()
        }
    }

    fun setListenBrainzDiscoverEnabled(enabled: Boolean) {
        viewModelScope.launch {
            listenBrainzPreferences.setDiscoverEnabled(enabled)
            if (enabled) {
                refreshListenBrainzDiscoverPlaylists()
            } else {
                clearDiscoverState()
            }
        }
    }

    fun setListenBrainzSaveWhileListening(enabled: Boolean) {
        viewModelScope.launch {
            listenBrainzPreferences.setSaveWhileListening(enabled)
        }
    }

    fun setListenBrainzSaveWhileListeningPercent(percent: Int) {
        viewModelScope.launch {
            listenBrainzPreferences.setSaveWhileListeningPercent(percent)
        }
    }

    fun saveListenBrainzToken(token: String) {
        viewModelScope.launch {
            listenBrainzPreferences.setToken(token)
            _tokenValidationState.value = TokenValidationUiState.Idle
        }
    }

    fun validateListenBrainzToken(token: String = listenBrainzSettings.value.userToken) {
        viewModelScope.launch {
            val trimmed = token.trim()
            if (trimmed.isBlank()) {
                _tokenValidationState.value = TokenValidationUiState.Error("Token vacío")
                return@launch
            }
            _tokenValidationState.value = TokenValidationUiState.Validating
            listenBrainzPreferences.setToken(trimmed)
            val result = ListenBrainzClient.validateToken(trimmed)
            if (result.valid && !result.username.isNullOrBlank()) {
                listenBrainzPreferences.setUsername(result.username)
                listenBrainzPreferences.setEnabled(true)
                _tokenValidationState.value = TokenValidationUiState.Success(result.username)
                listenSyncCoordinator.requestSync()
                if (listenBrainzSettings.value.discoverEnabled) {
                    refreshListenBrainzDiscoverPlaylists()
                }
            } else {
                listenBrainzPreferences.setUsername(null)
                _tokenValidationState.value = TokenValidationUiState.Error(
                    result.message ?: "Token inválido"
                )
            }
        }
    }

    fun clearListenBrainz() {
        viewModelScope.launch {
            listenBrainzPreferences.clear()
            _tokenValidationState.value = TokenValidationUiState.Idle
            clearDiscoverState()
        }
    }

    fun refreshListenBrainzDiscoverPlaylists() {
        viewModelScope.launch {
            val settings = listenBrainzPreferences.settingsFlow.first()
            if (!settings.showDiscoverPlaylists) {
                clearDiscoverState()
                return@launch
            }
            val username = settings.username ?: return@launch
            _lbDiscoverListState.value = LbDiscoverListUiState.Loading
            when (
                val result = ListenBrainzClient.fetchCreatedForPlaylists(
                    username = username,
                    token = settings.userToken
                )
            ) {
                is LbApiResult.Success -> {
                    _lbDiscoverPlaylists.value = result.data
                    _lbDiscoverListState.value = LbDiscoverListUiState.Success
                }
                is LbApiResult.Failure -> {
                    _lbDiscoverListState.value = LbDiscoverListUiState.Error(result.message)
                }
            }
        }
    }

    fun openListenBrainzPlaylist(mbid: String) {
        viewModelScope.launch {
            val settings = listenBrainzPreferences.settingsFlow.first()
            if (!settings.showDiscoverPlaylists || mbid.isBlank()) return@launch
            _lbPlaylistDetailState.value = LbPlaylistDetailUiState.Loading
            _selectedLbPlaylist.value = null
            when (
                val result = ListenBrainzClient.fetchPlaylist(
                    playlistMbid = mbid,
                    token = settings.userToken
                )
            ) {
                is LbApiResult.Success -> {
                    val library = rawSongs.first()
                    _selectedLbPlaylist.value = matchListenBrainzTracksUseCase.execute(result.data, library)
                    _lbPlaylistDetailState.value = LbPlaylistDetailUiState.Success
                }
                is LbApiResult.Failure -> {
                    _lbPlaylistDetailState.value = LbPlaylistDetailUiState.Error(result.message)
                }
            }
        }
    }

    fun closeListenBrainzPlaylist() {
        _selectedLbPlaylist.value = null
        _lbPlaylistDetailState.value = LbPlaylistDetailUiState.Idle
    }

    fun playListenBrainzPlaylist() {
        val items = _selectedLbPlaylist.value?.toPlayableItems().orEmpty()
        if (items.isEmpty()) return
        playPlayableCollection(items, startIndex = 0)
    }

    fun shuffleListenBrainzPlaylist() {
        val items = _selectedLbPlaylist.value?.toPlayableItems().orEmpty()
        if (items.isEmpty()) return
        applyShuffledQueue(items, keepItemFirst = null)
    }

    fun playListenBrainzPlaylistAt(index: Int) {
        val items = _selectedLbPlaylist.value?.toPlayableItems().orEmpty()
        if (items.isEmpty() || index !in items.indices) return
        playPlayableCollection(items, startIndex = index)
    }

    private fun clearDiscoverState() {
        _lbDiscoverPlaylists.value = emptyList()
        _lbDiscoverListState.value = LbDiscoverListUiState.Idle
        closeListenBrainzPlaylist()
    }

    // Online Catalog & Link Downloader Actions
    private var lastCatalogQuery = ""

    fun setCatalogCategory(category: CatalogCategory) {
        _catalogCategory.value = category
        searchCatalog(lastCatalogQuery)
    }

    fun searchCatalog(query: String) {
        lastCatalogQuery = query
        val cleanQ = query.trim()
        viewModelScope.launch {
            _isSearchingCatalog.value = true
            when (_catalogCategory.value) {
                CatalogCategory.SONGS -> {
                    _catalogSearchResults.value = MetadataFetcher.searchOnlineCatalog(cleanQ)
                }
                CatalogCategory.ALBUMS -> {
                    _albumSearchResults.value = MetadataFetcher.searchAlbums(cleanQ)
                }
                CatalogCategory.PLAYLISTS -> {
                    _playlistSearchResults.value = MetadataFetcher.searchPlaylists(cleanQ)
                }
            }
            _isSearchingCatalog.value = false
        }
    }


    fun searchOnlineCatalog(query: String) {
        searchCatalog(query)
    }

    fun selectAlbumForInspection(album: CatalogAlbum) {
        viewModelScope.launch {
            _isLoadingCollection.value = true
            _selectedCollectionTitle.value = album.title
            val candidates = MetadataFetcher.fetchAlbumTrackCandidates(album.id, album.title, album.artist, album.coverUrl)
            _activeTrackCandidates.value = candidates
            _isLoadingCollection.value = false
        }
    }

    fun selectPlaylistForInspection(playlist: CatalogPlaylist) {
        viewModelScope.launch {
            _isLoadingCollection.value = true
            _selectedCollectionTitle.value = playlist.title
            val candidates = MetadataFetcher.fetchPlaylistTrackCandidates(playlist.id, playlist.title)
            _activeTrackCandidates.value = candidates
            _isLoadingCollection.value = false
        }
    }

    fun cycleTrackCandidate(index: Int) {
        val list = _activeTrackCandidates.value.toMutableList()
        if (index in list.indices) {
            val item = list[index]
            val wasPreviewing = isPreviewingCandidate(item)
            viewModelScope.launch {
                var candidatesList = item.candidates
                if (candidatesList.size <= 1) {
                    val searchResults = YouTubeExtractor.searchYouTube("${item.artist} ${item.trackTitle}")
                    if (searchResults.isNotEmpty()) {
                        candidatesList = searchResults
                    }
                }
                if (candidatesList.isNotEmpty()) {
                    val nextIndex = (item.currentCandidateIndex + 1) % candidatesList.size
                    val updated = item.copy(candidates = candidatesList, currentCandidateIndex = nextIndex)
                    list[index] = updated
                    _activeTrackCandidates.value = list
                    if (wasPreviewing) {
                        updated.currentTrack?.let { playOnlineCatalogTrackAsStream(it) }
                    }
                }
            }
        }
    }

    /** Cycle YouTube match for a song result in the catalog songs list ("Buscar otro"). */
    fun cycleSongCatalogResult(index: Int) {
        val list = _catalogSearchResults.value.toMutableList()
        if (index !in list.indices) return
        val current = list[index]
        val wasPreviewing = _catalogPreviewKey.value == catalogPreviewKeyFor(current)
        viewModelScope.launch {
            val query = "${current.artist} ${current.title}".trim().ifBlank { current.title }
            val searchResults = YouTubeExtractor.searchYouTube(query)
            if (searchResults.isEmpty()) return@launch

            val currentIdx = searchResults.indexOfFirst { it.id == current.id }
            val next = searchResults[(currentIdx + 1).coerceAtLeast(0) % searchResults.size]
            // Keep catalog album metadata when YouTube only says "YouTube"
            list[index] = next.copy(
                album = current.album.takeIf { it.isNotBlank() && !it.equals("YouTube", ignoreCase = true) }
                    ?: next.album,
                artworkUrl = next.artworkUrl ?: current.artworkUrl
            )
            _catalogSearchResults.value = list
            if (wasPreviewing) {
                playOnlineCatalogTrackAsStream(list[index])
            }
        }
    }

    private fun isPreviewingCandidate(item: CatalogTrackCandidate): Boolean {
        val key = _catalogPreviewKey.value ?: return false
        val current = item.currentTrack ?: return false
        if (catalogPreviewKeyFor(current) == key) return true
        return item.candidates.any { catalogPreviewKeyFor(it) == key }
    }

    fun toggleTrackSelection(index: Int) {
        val list = _activeTrackCandidates.value.toMutableList()
        if (index in list.indices) {
            val item = list[index]
            list[index] = item.copy(isSelected = !item.isSelected)
            _activeTrackCandidates.value = list
        }
    }

    fun clearSelectedCollection() {
        _selectedCollectionTitle.value = null
        _activeTrackCandidates.value = emptyList()
        _isLoadingCollection.value = false
    }

    private fun updateCandidateState(
        trackTitle: String,
        state: CandidateDownloadState,
        percent: Int = 0,
        error: String? = null
    ) {
        val list = _activeTrackCandidates.value.toMutableList()
        val index = list.indexOfFirst { it.trackTitle == trackTitle }
        if (index != -1) {
            list[index] = list[index].copy(
                downloadState = state,
                downloadProgressPercent = percent,
                errorMessage = error
            )
            _activeTrackCandidates.value = list
        }
    }

    private fun mapDownloadError(e: Throwable): String = when {
        e.message?.contains("403") == true ->
            "Error HTTP 403 Forbidden: Enlace o firma expirada de YouTube."
        e.message?.contains("YouTube") == true ->
            e.message ?: "No se pudo obtener audio de YouTube."
        else ->
            "Falló la descarga: ${e.localizedMessage ?: "Error de red."}"
    }

    private suspend fun downloadTrack(
        track: OnlineCatalogTrack,
        onProgress: ((String) -> Unit)? = null
    ): Result<Song> = downloadAudioTrackUseCase.execute(track, onProgress)

    fun downloadSingleCandidate(index: Int) {
        val list = _activeTrackCandidates.value
        if (index !in list.indices) return
        val candidate = list[index]
        val track = candidate.currentTrack ?: return

        viewModelScope.launch {
            updateCandidateState(candidate.trackTitle, CandidateDownloadState.DOWNLOADING, percent = 20)
            updateCandidateState(candidate.trackTitle, CandidateDownloadState.DOWNLOADING, percent = 50)
            val result = downloadTrack(track) { msg ->
                if (msg.contains("Descargando audio", ignoreCase = true)) {
                    updateCandidateState(candidate.trackTitle, CandidateDownloadState.DOWNLOADING, percent = 75)
                }
            }
            result.fold(
                onSuccess = {
                    updateCandidateState(candidate.trackTitle, CandidateDownloadState.SUCCESS, percent = 100)
                },
                onFailure = { e ->
                    e.printStackTrace()
                    updateCandidateState(
                        candidate.trackTitle,
                        CandidateDownloadState.ERROR,
                        percent = 0,
                        error = mapDownloadError(e)
                    )
                }
            )
        }
    }

    fun downloadSelectedCandidatesBatch() {
        val selected = _activeTrackCandidates.value.filter { it.isSelected && it.currentTrack != null }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            _downloadStatus.value = DownloadStatus.Downloading("Iniciando descarga de ${selected.size} canciones...")
            val total = selected.size
            val completedCount = AtomicInteger(0)
            val successCount = AtomicInteger(0)

            val semaphore = Semaphore(3)

            val jobs = selected.map { candidate ->
                async {
                    semaphore.withPermit {
                        val track = candidate.currentTrack ?: return@withPermit
                        val currentProgress = completedCount.incrementAndGet()
                        _downloadStatus.value = DownloadStatus.Downloading("Descargando ($currentProgress/$total): ${track.title}...")

                        updateCandidateState(candidate.trackTitle, CandidateDownloadState.DOWNLOADING, percent = 20)
                        updateCandidateState(candidate.trackTitle, CandidateDownloadState.DOWNLOADING, percent = 50)

                        val result = downloadTrack(track) { msg ->
                            if (msg.contains("Descargando audio", ignoreCase = true)) {
                                updateCandidateState(candidate.trackTitle, CandidateDownloadState.DOWNLOADING, percent = 75)
                            }
                        }
                        result.fold(
                            onSuccess = {
                                updateCandidateState(candidate.trackTitle, CandidateDownloadState.SUCCESS, percent = 100)
                                successCount.incrementAndGet()
                            },
                            onFailure = { e ->
                                e.printStackTrace()
                                updateCandidateState(
                                    candidate.trackTitle,
                                    CandidateDownloadState.ERROR,
                                    percent = 0,
                                    error = mapDownloadError(e)
                                )
                            }
                        )
                    }
                }
            }
            jobs.awaitAll()

            _downloadStatus.value = DownloadStatus.Success(
                song = Song(uriString = "", title = "Descarga completada"),
                message = "¡${successCount.get()} de $total canciones procesadas!"
            )
        }
    }

    fun downloadFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        // Single YouTube extract happens inside downloadAndSaveOnlineTrack
        downloadOnlineTrack(
            OnlineCatalogTrack(
                id = trimmed,
                title = "",
                artist = "",
                album = "",
                artworkUrl = null,
                durationMs = 0L,
                audioUrl = trimmed,
                provider = "YouTube"
            )
        )
    }

    fun downloadOnlineTrack(track: OnlineCatalogTrack) {
        viewModelScope.launch {
            _downloadStatus.value = DownloadStatus.Downloading("Iniciando descarga...")
            val result = downloadTrack(track) { progressMsg ->
                _downloadStatus.value = DownloadStatus.Downloading(progressMsg)
            }
            result.fold(
                onSuccess = { song ->
                    _downloadStatus.value = DownloadStatus.Success(song, "¡${song.title} agregada a la biblioteca!")
                },
                onFailure = { e ->
                    _downloadStatus.value = DownloadStatus.Error("Error al descargar la canción: ${e.localizedMessage}")
                }
            )
        }
    }

    fun resetDownloadStatus() {
        _downloadStatus.value = DownloadStatus.Idle
    }

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }

    companion object {
        private const val RADIO_BATCH_SIZE = 30
        private const val RADIO_REFILL_THRESHOLD = 5
    }
}

