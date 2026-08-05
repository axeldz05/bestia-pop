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
import com.bestiapop.android.data.listenbrainz.MatchedCfRecommendations
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.model.*
import com.bestiapop.android.data.network.ConnectivityObserver
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.data.preferences.ActiveDownloadsStore
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.MAX_SAVE_WHILE_LISTENING_PERCENT
import com.bestiapop.android.data.preferences.MIN_SAVE_WHILE_LISTENING_PERCENT
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.PlaybackHydration
import com.bestiapop.android.data.preferences.PlaybackSessionStore
import com.bestiapop.android.data.preferences.ThemePreferencesRepository
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.data.stream.StreamResolver
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.domain.radio.CfRecommendationsRadio
import com.bestiapop.android.domain.radio.DeezerSimilarRadio
import com.bestiapop.android.domain.radio.ListenBrainzRadio
import com.bestiapop.android.domain.radio.LocalMetadataRadio
import com.bestiapop.android.domain.radio.RadioEngine
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.radio.RadioSuggestResult
import com.bestiapop.android.domain.usecase.FetchAndMatchCfRecommendationsUseCase
import com.bestiapop.android.domain.usecase.ImportListenBrainzPlaylistUseCase
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.findAlbumMergeTarget
import com.bestiapop.android.domain.util.normalizeAlbumName
import com.bestiapop.android.service.DownloadNotificationHelper
import com.bestiapop.android.service.MusicService
import com.bestiapop.android.service.StreamPlaybackTag
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryViewMode
import com.bestiapop.android.ui.theme.ThemePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

sealed class CfRecommendationsUiState {
    data object Idle : CfRecommendationsUiState()
    data object Loading : CfRecommendationsUiState()
    data object Success : CfRecommendationsUiState()
    data class Error(val message: String) : CfRecommendationsUiState()
}

@OptIn(UnstableApi::class, FlowPreview::class)
class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val themeRepository = ThemePreferencesRepository(application)
    private val listenBrainzPreferences = ListenBrainzPreferencesRepository(application)
    private val playbackPreferences = PlaybackPreferencesRepository(application)
    private val activeDownloadsStore = ActiveDownloadsStore(application)
    private val playbackSessionStore = PlaybackSessionStore(application)
    private val downloadNotificationHelper = DownloadNotificationHelper(application)
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

    private val playbackSettings: StateFlow<PlaybackSettings> =
        playbackPreferences.settingsFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSettings())

    val volumeBoostEnabled: StateFlow<Boolean> =
        playbackSettings
            .map { it.volumeBoostEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val stereoLeftGain: StateFlow<Float> =
        playbackSettings
            .map { it.stereoLeftGain }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)

    val stereoRightGain: StateFlow<Float> =
        playbackSettings
            .map { it.stereoRightGain }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)

    val pendingListenCount: StateFlow<Int> = pendingListenDao.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _tokenValidationState = MutableStateFlow<TokenValidationUiState>(TokenValidationUiState.Idle)
    val tokenValidationState = _tokenValidationState.asStateFlow()

    private val matchListenBrainzTracksUseCase = MatchListenBrainzTracksUseCase()
    private val importListenBrainzPlaylistUseCase = ImportListenBrainzPlaylistUseCase(repository)
    private val fetchAndMatchCfRecommendationsUseCase = FetchAndMatchCfRecommendationsUseCase(
        fetchCf = { username, token, count, offset, artistType ->
            ListenBrainzClient.fetchCfRecordingRecommendations(
                username = username,
                token = token,
                count = count,
                offset = offset,
                artistType = artistType
            )
        },
        fetchRecordingMetadata = { mbids, token ->
            ListenBrainzClient.fetchRecordingMetadata(mbids, token)
        }
    )

    private val _lbDiscoverPlaylists = MutableStateFlow<List<LbPlaylistSummary>>(emptyList())
    val lbDiscoverPlaylists = _lbDiscoverPlaylists.asStateFlow()

    private val _lbDiscoverListState = MutableStateFlow<LbDiscoverListUiState>(LbDiscoverListUiState.Idle)
    val lbDiscoverListState = _lbDiscoverListState.asStateFlow()

    private val _selectedLbPlaylist = MutableStateFlow<MatchedLbPlaylist?>(null)
    val selectedLbPlaylist = _selectedLbPlaylist.asStateFlow()

    private val _lbPlaylistDetailState = MutableStateFlow<LbPlaylistDetailUiState>(LbPlaylistDetailUiState.Idle)
    val lbPlaylistDetailState = _lbPlaylistDetailState.asStateFlow()

    private val _cfRecommendations = MutableStateFlow<MatchedCfRecommendations?>(null)
    val cfRecommendations = _cfRecommendations.asStateFlow()

    private val _cfListState = MutableStateFlow<CfRecommendationsUiState>(CfRecommendationsUiState.Idle)
    val cfListState = _cfListState.asStateFlow()

    private val _cfDetailOpen = MutableStateFlow(false)
    val cfDetailOpen = _cfDetailOpen.asStateFlow()

    private val _cfDetailState = MutableStateFlow<CfRecommendationsUiState>(CfRecommendationsUiState.Idle)
    val cfDetailState = _cfDetailState.asStateFlow()

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

    data class PendingAlbumMerge(
        val source: Album,
        val target: Album
    )

    private val _pendingAlbumMerge = MutableStateFlow<PendingAlbumMerge?>(null)
    val pendingAlbumMerge: StateFlow<PendingAlbumMerge?> = _pendingAlbumMerge.asStateFlow()

    fun buildLibraryListItems(
        songs: List<Song>,
        viewMode: LibraryViewMode
    ): List<LibraryListItem> =
        getLibrarySongsUseCase.buildListItems(songs, viewMode)

    val albumsState: StateFlow<List<Album>> = combine(
        songsState,
        repository.albumOverridesFlow
    ) { songs, overrides ->
        getLibrarySongsUseCase.extractAlbums(
            songs,
            overrides.associateBy { it.albumKey }
        )
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

    private val _radioMode = MutableStateFlow(RadioMode.KNOWN)
    val radioMode = _radioMode.asStateFlow()

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
    /** Last user-chosen mode (session); auto-start / tap reuse this when set. */
    private var radioPreferredMode: RadioMode? = null

    /** True after UI was rebuilt from a live MediaController timeline. */
    private var liveSessionHydrated = false
    private var idleSeedDone = false
    /** Seek target applied once on the next [finishPlayPlayableCollection] (idle resume). */
    private var pendingResumePositionMs: Long? = null
    private var lastPersistedPositionAtMs = 0L

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
        ),
        cfRecommendationsRadio = CfRecommendationsRadio(
            fetchCf = { username, token, count, offset, artistType ->
                ListenBrainzClient.fetchCfRecordingRecommendations(
                    username = username,
                    token = token,
                    count = count,
                    offset = offset,
                    artistType = artistType
                )
            },
            fetchRecordingMetadata = { mbids, token ->
                ListenBrainzClient.fetchRecordingMetadata(mbids, token)
            }
        ),
        similarProviders = listOf(
            DeezerSimilarRadio(
                resolveArtistId = { MetadataFetcher.resolveDeezerArtistId(it) },
                fetchArtistRadio = { MetadataFetcher.fetchDeezerArtistRadio(it) },
                fetchRelatedArtistIds = { id, limit ->
                    MetadataFetcher.fetchDeezerRelatedArtistIds(id, limit)
                },
                fetchArtistTop = { id, limit -> MetadataFetcher.fetchDeezerArtistTop(id, limit) },
                fetchItunesArtistSongs = { artist, limit ->
                    MetadataFetcher.fetchItunesArtistSongs(artist, limit)
                }
            )
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

    private enum class CatalogCollectionKind { ALBUM, PLAYLIST }
    private var selectedCollectionKind: CatalogCollectionKind? = null
    private var selectedCollectionCoverUrl: String? = null
    /** Local playlist created when batch-downloading a catalog playlist (reuse across single/batch). */
    private var catalogBatchPlaylistId: Long? = null

    private val _activeTrackCandidates = MutableStateFlow<List<CatalogTrackCandidate>>(emptyList())
    val activeTrackCandidates = _activeTrackCandidates.asStateFlow()

    private val _isLoadingCollection = MutableStateFlow(false)
    val isLoadingCollection = _isLoadingCollection.asStateFlow()

    private val _isSearchingCatalog = MutableStateFlow(false)
    val isSearchingCatalog = _isSearchingCatalog.asStateFlow()

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus = _downloadStatus.asStateFlow()

    private val _activeDownloads = MutableStateFlow<List<ActiveDownload>>(emptyList())
    val activeDownloads = _activeDownloads.asStateFlow()

    private val _downloadConflict = MutableStateFlow<DownloadConflict?>(null)
    val downloadConflict = _downloadConflict.asStateFlow()

    /** Caps concurrent online downloads across all sources. */
    private val downloadSemaphore = Semaphore(3)

    /** When set during a batch, subsequent conflicts reuse this policy without another dialog. */
    private var batchConflictPolicy: DownloadConflictPolicy? = null
    private var batchSaveAsCounter: Int = 1

    /** Set when MainActivity should switch to Descargas (notification / dialog deep-link). */
    private val _pendingOpenDownloads = MutableStateFlow(false)
    val pendingOpenDownloads = _pendingOpenDownloads.asStateFlow()

    fun requestOpenDownloads() {
        _pendingOpenDownloads.value = true
    }

    fun consumeOpenDownloads() {
        _pendingOpenDownloads.value = false
    }

    private fun getDeviceVolumeRatio(): Float {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    private fun setSystemVolumeRatio(systemRatio: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVolume = (systemRatio.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setVolume(ratio: Float) {
        val boostEnabled = volumeBoostEnabled.value
        val clamped = if (boostEnabled) ratio.coerceIn(0f, 2f) else ratio.coerceIn(0f, 1f)
        val systemRatio = clamped.coerceAtMost(1f)
        setSystemVolumeRatio(systemRatio)

        val boostAmount = if (boostEnabled && clamped > 1f) (clamped - 1f).coerceIn(0f, 1f) else 0f
        if (boostEnabled) {
            _volumeLevel.value = clamped
            viewModelScope.launch {
                playbackPreferences.setVolumeBoostAmount(boostAmount)
            }
        } else {
            _volumeLevel.value = systemRatio
        }
    }

    fun setVolumeBoostEnabled(enabled: Boolean) {
        viewModelScope.launch {
            playbackPreferences.setVolumeBoostEnabled(enabled)
            if (enabled) {
                val amount = playbackSettings.value.volumeBoostAmount.coerceIn(0f, 1f)
                if (amount > 0f) {
                    setSystemVolumeRatio(1f)
                    _volumeLevel.value = 1f + amount
                } else {
                    _volumeLevel.value = getDeviceVolumeRatio().coerceAtMost(1f)
                }
            } else {
                // Clearing amount in prefs is intentionally skipped so re-enable restores boost.
                // Force MusicService to drop gain while disabled by writing amount unchanged + enabled flag.
                _volumeLevel.value = getDeviceVolumeRatio().coerceAtMost(1f)
            }
        }
    }

    fun setStereoLeftGain(gain: Float) {
        viewModelScope.launch {
            playbackPreferences.setStereoLeftGain(gain)
        }
    }

    fun setStereoRightGain(gain: Float) {
        viewModelScope.launch {
            playbackPreferences.setStereoRightGain(gain)
        }
    }

    fun resetStereoBalance() {
        viewModelScope.launch {
            playbackPreferences.resetStereoBalance()
        }
    }

    private fun restoreVolumeBoostIfNeeded() {
        val settings = playbackSettings.value
        if (!settings.volumeBoostEnabled) {
            if (_volumeLevel.value > 1f) {
                _volumeLevel.value = getDeviceVolumeRatio().coerceAtMost(1f)
            }
            return
        }
        val amount = settings.volumeBoostAmount.coerceIn(0f, 1f)
        if (amount > 0f) {
            setSystemVolumeRatio(1f)
            _volumeLevel.value = 1f + amount
        } else {
            _volumeLevel.value = getDeviceVolumeRatio().coerceAtMost(1f)
        }
    }

    init {
        initMediaController()
        startPositionTracker()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.scanMediaStore()
            }
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
            val restored = withContext(Dispatchers.IO) { activeDownloadsStore.load() }
            if (restored.isNotEmpty()) {
                _activeDownloads.value = restored
            }
            activeDownloads
                .debounce(300)
                .collect { list ->
                    withContext(Dispatchers.IO) {
                        activeDownloadsStore.save(list)
                    }
                }
        }

        viewModelScope.launch {
            activeDownloads.collect { list ->
                downloadNotificationHelper.sync(list)
            }
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
                            setCurrentItem(PlayableItem.Local(updated), persistLastPlayed = false)
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
                maybeSeedIdlePlayer()
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            songsState.collect { songs ->
                val unenhanced = songs.filter {
                    !SongPathNormalizer.hasUsableArtwork(it.artworkUri)
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
                syncUiFromController()
                restoreVolumeBoostIfNeeded()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private var lastMediaItemIndex: Int = -1
    private var suppressShuffleWrapDetection: Boolean = false

    private fun setCurrentItem(item: PlayableItem?, persistLastPlayed: Boolean = true) {
        _currentItem.value = item
        val localSong = (item as? PlayableItem.Local)?.song
        _currentSong.value = localSong
        if (localSong != null) {
            listenTracker.onTrackChanged(localSong)
            requestMetadataEnhancement(localSong)
            if (persistLastPlayed) {
                saveLastPlayed(localSong, _playbackPositionMs.value)
            }
        } else {
            listenTracker.onTrackChanged(null)
        }
    }

    private fun saveLastPlayed(song: Song, positionMs: Long) {
        val snapshot = PlaybackHydration.snapshotFromSong(song, positionMs)
        viewModelScope.launch(Dispatchers.IO) {
            playbackSessionStore.save(snapshot)
        }
        lastPersistedPositionAtMs = System.currentTimeMillis()
    }

    private fun persistCurrentPosition(force: Boolean = false) {
        val song = (_currentItem.value as? PlayableItem.Local)?.song ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistedPositionAtMs < LAST_PLAYED_POSITION_SAVE_INTERVAL_MS) return
        saveLastPlayed(song, _playbackPositionMs.value)
    }

    /**
     * Rebuild ViewModel queue / current item from a live MediaController session
     * (Activity recreate while MusicService keeps playing).
     */
    private fun syncUiFromController() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount <= 0) {
            maybeSeedIdlePlayer()
            return
        }

        liveSessionHydrated = true
        idleSeedDone = true

        val library = songsState.value
        val rebuilt = buildList {
            for (i in 0 until controller.mediaItemCount) {
                val mediaItem = controller.getMediaItemAt(i)
                add(mediaItemToPlayable(mediaItem, library))
            }
        }
        if (rebuilt.isEmpty()) {
            maybeSeedIdlePlayer()
            return
        }

        val index = controller.currentMediaItemIndex.coerceIn(0, rebuilt.lastIndex)
        _queue.value = rebuilt
        setCurrentItem(rebuilt[index])
        lastMediaItemIndex = index
        _isPlaying.value = controller.isPlaying
        _playbackPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
        _isShuffle.value = controller.shuffleModeEnabled
        _repeatMode.value = when (controller.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }

        ensureRemoteReadyAt(index)
        prefetchAround(index)
    }

    private fun mediaItemToPlayable(mediaItem: MediaItem, library: List<Song>): PlayableItem {
        val id = mediaItem.mediaId
        val meta = mediaItem.mediaMetadata
        val title = meta.title?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown"
        val artist = meta.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        val album = meta.albumTitle?.toString()?.takeIf { it.isNotBlank() }
        val artwork = meta.artworkUri?.toString()
        val durationMs = 0L

        if (id.startsWith("remote:")) {
            val remoteKey = id.removePrefix("remote:")
            val looksLikeVideoId = remoteKey.length == 11 &&
                remoteKey.all { it.isLetterOrDigit() || it == '_' || it == '-' }
            return if (looksLikeVideoId) {
                // Preserve mediaId via resolved.videoId; stale timestamp forces re-resolve.
                PlayableItem.remoteFrom(
                    artist = artist,
                    title = title,
                    album = album,
                    artworkUri = artwork,
                    durationMs = durationMs,
                    youtubeQueryOrId = remoteKey,
                    resolved = ResolvedStream(
                        audioUrl = "",
                        userAgent = "",
                        videoId = remoteKey,
                        resolvedAtEpochMs = 0L
                    )
                )
            } else {
                PlayableItem.remoteFrom(
                    artist = artist,
                    title = title,
                    album = album,
                    artworkUri = artwork,
                    durationMs = durationMs,
                    youtubeQueryOrId = "$artist $title".trim().ifBlank { remoteKey }
                )
            }
        }

        val local = library.find { it.uriString == id }
            ?: library.find { it.id.toString() == id }
        if (local != null) return local.toPlayable()

        return PlayableItem.Local(
            Song(
                id = id.toLongOrNull() ?: 0L,
                uriString = id,
                title = title,
                artist = artist,
                album = album ?: "Unknown Album",
                artworkUri = artwork,
                durationMs = durationMs
            )
        )
    }

    private fun maybeSeedIdlePlayer() {
        if (liveSessionHydrated || idleSeedDone || _currentItem.value != null) return
        val songs = songsState.value
        if (songs.isEmpty()) return
        // Wait until MediaController connect attempt finished when possible.
        if (mediaController == null && controllerFuture != null &&
            !(controllerFuture?.isDone == true)
        ) {
            return
        }
        if (mediaController != null && (mediaController?.mediaItemCount ?: 0) > 0) {
            return
        }

        idleSeedDone = true
        viewModelScope.launch {
            val last = withContext(Dispatchers.IO) { playbackSessionStore.load() }
            if (liveSessionHydrated || _currentItem.value != null) return@launch
            val library = songsState.value
            val song = PlaybackHydration.resolveIdleSeed(library, last) ?: return@launch
            if (liveSessionHydrated || _currentItem.value != null) return@launch
            setCurrentItem(song.toPlayable(), persistLastPlayed = false)
            _playbackPositionMs.value = PlaybackHydration.resumePositionMs(song, last)
            _isPlaying.value = false
        }
    }

    private fun setupPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                _isPlaying.value = isPlayingNow
                if (!isPlayingNow) {
                    listenTracker.onStopped()
                    persistCurrentPosition(force = true)
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
                        ?: mediaItemToPlayable(item, songsState.value)
                    setCurrentItem(playable)
                    remoteErrorRetryUsed = false
                    ensureRemoteReadyAt(newIndex)
                    prefetchAround(newIndex)
                    if (_radioActive.value) {
                        rememberRadioPlayed(playable)
                        maybeRefillRadio(newIndex)
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
    fun applyShuffledQueue(
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
                        persistCurrentPosition(force = false)
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
                    } else if (!controller.isPlaying && controller.mediaItemCount > 0) {
                        _playbackPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
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
        val key = TrackMatchKeys.downloadIdFor(remote.artist, remote.title)
        if (key.isEmpty() || key in saveWhileListeningAttempted) return
        // Block auto re-enqueue while downloading or already succeeded this session.
        // ERROR clears the key so background can try again later; manual retry always works.
        val existing = _activeDownloads.value.find { it.id == key }
        if (existing != null && existing.state == CandidateDownloadState.DOWNLOADING) return
        saveWhileListeningAttempted.add(key)

        val track = remote.toOnlineCatalogTrack(provider = "YouTube")

        viewModelScope.launch {
            val result = runTrackedDownload(
                downloadId = key,
                source = ActiveDownloadSource.SAVE_WHILE_LISTENING,
                track = track,
                displayTitle = remote.title,
                displayArtist = remote.artist,
                artworkUrl = remote.artworkUri
            )
            result.fold(
                onSuccess = { song ->
                    toastSongInLibrary(song.title, LibraryToastKind.SAVED)
                },
                onFailure = { e ->
                    saveWhileListeningAttempted.remove(key)
                    toast("No se pudo guardar «${remote.title}»: ${e.localizedMessage ?: "error"}")
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
        val artMissing = !SongPathNormalizer.hasUsableArtwork(song.artworkUri)
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
        liveSessionHydrated = true
        idleSeedDone = true
        bumpQueueFocus()

        val startPositionMs = pendingResumePositionMs?.coerceAtLeast(0L) ?: 0L
        pendingResumePositionMs = null

        mediaController?.let { controller ->
            controller.shuffleModeEnabled = false
            controller.setMediaItems(items.map { playableToMediaItem(it) }, index, startPositionMs)
            controller.prepare()
            controller.play()
        }
        if (startPositionMs > 0L) {
            _playbackPositionMs.value = startPositionMs
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
        val queryOrId = YouTubeExtractor.resolveYouTubeQueryOrId(track)
        val remote = PlayableItem.remoteFrom(
            artist = track.artist,
            title = track.title,
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
            val existing = repository.getAlbumOverride(albumName)
            val override = AlbumOverride(
                albumKey = albumName,
                displayName = existing?.displayName ?: albumName,
                artist = existing?.artist,
                genre = existing?.genre,
                year = existing?.year ?: 0,
                artworkUri = artworkUri
            )
            repository.updateAlbumMetadataPropagateToSongs(override)
        }
    }

    /**
     * Save album metadata, or set [pendingAlbumMerge] when [displayName] collides with
     * another album (checked against Room, not the search-filtered UI list).
     */
    fun requestSaveAlbumMetadata(
        source: Album,
        displayName: String,
        artist: String,
        genre: String,
        year: Int,
        artworkUri: String?,
        propagateToSongs: Boolean
    ) {
        viewModelScope.launch {
            val songs = repository.getAllSongsSync()
            val overrides = repository.albumOverridesFlow.first()
            val albums = getLibrarySongsUseCase.extractAlbums(
                songs,
                overrides.associateBy { it.albumKey }
            )
            val conflict = findAlbumMergeTarget(albums, source.name, displayName)
            if (conflict != null) {
                _pendingAlbumMerge.value = PendingAlbumMerge(source = source, target = conflict)
                return@launch
            }
            val normalizedName = normalizeAlbumName(displayName).ifBlank { source.name }
            val override = AlbumOverride(
                albumKey = source.name,
                displayName = normalizedName,
                artist = artist.takeIf { it.isNotBlank() },
                genre = genre.takeIf { it.isNotBlank() },
                year = year.coerceAtLeast(0),
                artworkUri = artworkUri
            )
            if (propagateToSongs) {
                repository.updateAlbumMetadataPropagateToSongs(override)
            } else {
                repository.upsertAlbumOverride(override)
            }
        }
    }

    fun confirmPendingAlbumMerge() {
        val pending = _pendingAlbumMerge.value ?: return
        viewModelScope.launch {
            repository.mergeAlbumInto(pending.source.name, pending.target.name)
            _pendingAlbumMerge.value = null
            toast("Álbumes unidos")
        }
    }

    fun dismissPendingAlbumMerge() {
        _pendingAlbumMerge.value = null
    }

    fun saveAlbumMetadata(
        albumKey: String,
        displayName: String,
        artist: String,
        genre: String,
        year: Int,
        artworkUri: String?,
        propagateToSongs: Boolean
    ) {
        viewModelScope.launch {
            val normalizedName = normalizeAlbumName(displayName).ifBlank { albumKey }
            val override = AlbumOverride(
                albumKey = albumKey,
                displayName = normalizedName,
                artist = artist.takeIf { it.isNotBlank() },
                genre = genre.takeIf { it.isNotBlank() },
                year = year.coerceAtLeast(0),
                artworkUri = artworkUri
            )
            if (propagateToSongs) {
                repository.updateAlbumMetadataPropagateToSongs(override)
            } else {
                repository.upsertAlbumOverride(override)
            }
        }
    }

    fun mergeAlbumInto(sourceAlbumKey: String, targetAlbumKey: String) {
        viewModelScope.launch {
            repository.mergeAlbumInto(sourceAlbumKey, targetAlbumKey)
            toast("Álbumes unidos")
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
        val controller = mediaController
        if (controller != null && controller.mediaItemCount > 0) {
            if (controller.isPlaying) {
                controller.pause()
            } else {
                controller.play()
            }
            return
        }

        val current = _currentItem.value ?: return
        val resumeMs = _playbackPositionMs.value
        pendingResumePositionMs = resumeMs.takeIf { it > 0L }
        when (current) {
            is PlayableItem.Local -> playSong(current.song)
            is PlayableItem.Remote -> playPlayableCollection(listOf(current), 0)
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
    }

    fun stopRadio() {
        radioRefillJob?.cancel()
        radioRefillJob = null
        _radioActive.value = false
        playedInRadioSession.clear()
        _radioMode.value = RadioMode.KNOWN
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
                if (!auto) toastRadioNeedsSeed()
                return
            }
        if (seed.artist.isBlank() || seed.title.isBlank()) {
            if (!auto) toastRadioNeedsSeed()
            return
        }
        if (_radioLoading.value) return

        if (mode != null) {
            setRadioPreferredMode(mode)
        }

        val settings = listenBrainzSettings.value
        val networkOnline = connectivityObserver.isCurrentlyOnline()

        val resolvedMode = when {
            mode != null -> mode
            radioPreferredMode != null -> radioPreferredMode!!
            // Online usable = network (Deezer) and/or LB; no token required for BOTH default
            networkOnline -> RadioMode.BOTH
            else -> RadioMode.KNOWN
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

                val effectiveMode = resolvedMode
                val coPlaylistIds = resolveCoPlaylistSongIds(seed)
                val batch = suggestRadioWithRetry(
                    seed = seed,
                    library = library,
                    mode = effectiveMode,
                    excludeKeys = exclude,
                    settings = settings,
                    coPlaylistSongIds = coPlaylistIds
                )

                val suggestions = batch.items
                if (suggestions.isEmpty()) {
                    if (!auto) {
                        val emptyMsg = when {
                            effectiveMode == RadioMode.NEW ->
                                "Radio online no disponible"
                            else -> "No encontré canciones parecidas"
                        }
                        toast(emptyMsg)
                    }
                    return@launch
                }

                val previousPlayed = if (_radioActive.value) playedInRadioSession.toSet() else emptySet()
                clearRadioSessionKeepPreference()
                _radioMode.value = effectiveMode
                playedInRadioSession.addAll(previousPlayed)
                playedInRadioSession.addAll(exclude)
                rememberRadioPlayed(seed)
                _radioActive.value = true
                updateRadioStatusLabel()

                if (toastMode && !auto) {
                    toast(radioModeLabel(effectiveMode))
                }

                if (keepCurrentPlaying) {
                    replaceUpcomingWithRadio(suggestions)
                    toast("Se agregaron canciones de la radio a la cola")
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

    /**
     * For [RadioMode.NEW], retries online providers (LB/CF/Deezer) until Remotes or timeout.
     * Other modes run once (BOTH already falls back to locales).
     */
    private suspend fun suggestRadioWithRetry(
        seed: PlayableItem,
        library: List<Song>,
        mode: RadioMode,
        excludeKeys: Set<String>,
        settings: ListenBrainzSettings,
        timeoutMs: Long = RADIO_ONLINE_RETRY_TIMEOUT_MS,
        coPlaylistSongIds: Set<Long> = emptySet()
    ): RadioSuggestResult {
        fun networkNow(): Boolean = connectivityObserver.isCurrentlyOnline()

        fun canUseLbNow(): Boolean =
            settings.enabled &&
                settings.userToken.isNotBlank() &&
                networkNow()

        suspend fun once() = radioEngine.suggest(
            seed = seed,
            library = library,
            mode = mode,
            excludeKeys = excludeKeys,
            limit = RADIO_BATCH_SIZE,
            lbToken = settings.userToken.takeIf { it.isNotBlank() },
            lbAvailable = canUseLbNow(),
            lbUsername = settings.username,
            networkAvailable = networkNow(),
            coPlaylistSongIds = coPlaylistSongIds
        )

        if (mode != RadioMode.NEW) {
            return once()
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        var last = once()
        while (last.items.isEmpty() && System.currentTimeMillis() < deadline) {
            attempt++
            val backoff = minOf(1_000L * attempt, RADIO_ONLINE_RETRY_MAX_BACKOFF_MS)
            delay(backoff)
            last = once()
        }
        return last
    }

    private suspend fun resolveCoPlaylistSongIds(seed: PlayableItem): Set<Long> {
        val local = seed as? PlayableItem.Local ?: return emptySet()
        return runCatching { repository.getCoPlaylistSongIds(local.song.id) }
            .getOrDefault(emptySet())
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
        _radioStatusLabel.value = radioModeLabel(_radioMode.value)
    }

    private fun radioModeLabel(mode: RadioMode): String = when (mode) {
        RadioMode.KNOWN -> "Radio · Solo conocidos"
        RadioMode.NEW -> "Radio · Solo nuevos"
        RadioMode.BOTH -> "Radio · Ambos"
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
            val mode = _radioMode.value
            val library = rawSongs.first()
            val exclude = buildRadioExcludeKeys(seed)
            val coPlaylistIds = resolveCoPlaylistSongIds(seed)
            val batch = suggestRadioWithRetry(
                seed = seed,
                library = library,
                mode = mode,
                excludeKeys = exclude,
                settings = settings,
                timeoutMs = RADIO_ONLINE_REFILL_RETRY_TIMEOUT_MS,
                coPlaylistSongIds = coPlaylistIds
            )

            if (batch.items.isNotEmpty() && _radioActive.value) {
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
        genre: String,
        year: Int = 0
    ) {
        viewModelScope.launch {
            repository.updateSongMetadata(songId, title, artist, album, genre, year)
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

    fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrack>> {
        return repository.getPlaylistPendingTracksFlow(playlistId)
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
            refreshCfRecommendationsInternal(settings)
        }
    }

    fun refreshCfRecommendations() {
        viewModelScope.launch {
            val settings = listenBrainzPreferences.settingsFlow.first()
            refreshCfRecommendationsInternal(settings)
        }
    }

    private suspend fun refreshCfRecommendationsInternal(settings: ListenBrainzSettings) {
        if (!settings.showDiscoverPlaylists) {
            clearCfState()
            return
        }
        val username = settings.username
        if (username.isNullOrBlank()) {
            clearCfState()
            return
        }
        _cfListState.value = CfRecommendationsUiState.Loading
        val library = rawSongs.first()
        when (
            val result = fetchAndMatchCfRecommendationsUseCase.execute(
                username = username,
                token = settings.userToken.takeIf { it.isNotBlank() },
                library = library,
                artistType = FetchAndMatchCfRecommendationsUseCase.ARTIST_TYPE_TOP
            )
        ) {
            is LbApiResult.Success -> {
                _cfRecommendations.value = result.data
                _cfListState.value = CfRecommendationsUiState.Success
                if (_cfDetailOpen.value) {
                    _cfDetailState.value = CfRecommendationsUiState.Success
                }
            }
            is LbApiResult.Failure -> {
                _cfListState.value = CfRecommendationsUiState.Error(result.message)
                if (_cfDetailOpen.value) {
                    _cfDetailState.value = CfRecommendationsUiState.Error(result.message)
                }
            }
        }
    }

    fun openCfRecommendations() {
        _cfDetailOpen.value = true
        val current = _cfRecommendations.value
        val listState = _cfListState.value
        when {
            current != null && listState is CfRecommendationsUiState.Success -> {
                _cfDetailState.value = CfRecommendationsUiState.Success
            }
            listState is CfRecommendationsUiState.Loading -> {
                _cfDetailState.value = CfRecommendationsUiState.Loading
            }
            listState is CfRecommendationsUiState.Error -> {
                _cfDetailState.value = CfRecommendationsUiState.Error(listState.message)
            }
            else -> {
                _cfDetailState.value = CfRecommendationsUiState.Loading
                refreshCfRecommendations()
            }
        }
    }

    fun closeCfRecommendations() {
        _cfDetailOpen.value = false
        _cfDetailState.value = CfRecommendationsUiState.Idle
    }

    private fun playMatchedCollection(items: List<PlayableItem>, startIndex: Int = 0) {
        if (items.isEmpty() || startIndex !in items.indices) return
        playPlayableCollection(items, startIndex)
    }

    private fun shuffleMatchedCollection(items: List<PlayableItem>) {
        if (items.isEmpty()) return
        applyShuffledQueue(items, keepItemFirst = null)
    }

    fun playCfRecommendations() =
        playMatchedCollection(_cfRecommendations.value?.toPlayableItems().orEmpty())

    fun shuffleCfRecommendations() =
        shuffleMatchedCollection(_cfRecommendations.value?.toPlayableItems().orEmpty())

    fun playCfAt(index: Int) =
        playMatchedCollection(_cfRecommendations.value?.toPlayableItems().orEmpty(), index)

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

    fun playListenBrainzPlaylist() =
        playMatchedCollection(_selectedLbPlaylist.value?.toPlayableItems().orEmpty())

    fun shuffleListenBrainzPlaylist() =
        shuffleMatchedCollection(_selectedLbPlaylist.value?.toPlayableItems().orEmpty())

    fun playListenBrainzPlaylistAt(index: Int) =
        playMatchedCollection(_selectedLbPlaylist.value?.toPlayableItems().orEmpty(), index)

    /** Saves matched locals + unmatched as pending metadata (no download yet). */
    fun saveListenBrainzPlaylistAsLocal(onCreated: ((Long) -> Unit)? = null) {
        val matched = _selectedLbPlaylist.value ?: return
        if (matched.matchedCount == 0 && matched.streamCount == 0) return
        viewModelScope.launch {
            val playlistId = importListenBrainzPlaylistUseCase.createLocalFromMatched(matched)
                ?: return@launch
            toastPlaylistSaved(matched.matchedCount, pending = matched.streamCount)
            onCreated?.invoke(playlistId)
        }
    }

    /**
     * Creates a local playlist with matched + pending metadata, then enqueues unmatched
     * downloads via [runTrackedDownload] ([ActiveDownloadSource.LB_IMPORT]).
     */
    fun importListenBrainzPlaylistWithDownloads(onCreated: ((Long) -> Unit)? = null) {
        val matched = _selectedLbPlaylist.value ?: return
        val unmatched = importListenBrainzPlaylistUseCase.unmatchedCatalogTracks(matched)
        if (unmatched.isEmpty() && matched.matchedCount == 0) return

        viewModelScope.launch {
            val playlistId = importListenBrainzPlaylistUseCase.createLocalFromMatched(
                matched = matched,
                allowEmpty = unmatched.isNotEmpty()
            ) ?: return@launch

            onCreated?.invoke(playlistId)

            if (unmatched.isEmpty()) {
                toastPlaylistSaved(matched.matchedCount)
                return@launch
            }

            enqueuePendingDownloads(
                playlistId = playlistId,
                tracks = unmatched,
                toastQueued = true
            )
        }
    }

    /** Downloads pending metadata tracks for an already-saved local playlist. */
    fun downloadPlaylistPendingTracks(playlistId: Long) {
        if (playlistId <= 0L) return
        viewModelScope.launch {
            val pending = repository.getPlaylistPendingTracksFlow(playlistId).first()
            if (pending.isEmpty()) {
                toast("No hay canciones pendientes")
                return@launch
            }
            enqueuePendingDownloads(
                playlistId = playlistId,
                tracks = pending.map { it.toOnlineCatalogTrack() },
                toastQueued = true
            )
        }
    }

    private enum class LibraryToastKind { SAVED, ADDED, ALREADY }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    private fun toastSongInLibrary(title: String, kind: LibraryToastKind) {
        val message = when (kind) {
            LibraryToastKind.SAVED -> "«$title» guardada en biblioteca"
            LibraryToastKind.ADDED -> "¡$title agregada a la biblioteca!"
            LibraryToastKind.ALREADY -> "«$title» ya está en la biblioteca"
        }
        toast(message)
    }

    private fun toastDownloadsQueued(count: Int? = null, alreadyQueued: Boolean = false) {
        val message = when {
            alreadyQueued -> "Ya está en cola — ver Descargas"
            count != null -> "$count descargas en cola — ver Descargas"
            else -> "Descarga en cola — ver Descargas"
        }
        toast(message)
    }

    private fun toastPlaylistSaved(matchedCount: Int, pending: Int = 0) {
        val message = if (pending > 0) {
            "Playlist guardada ($matchedCount en lib · $pending pendientes)"
        } else {
            "Playlist guardada ($matchedCount canciones)"
        }
        toast(message)
    }

    private fun toastRadioNeedsSeed() {
        toast("Necesitás una canción con artista y título para Radio")
    }

    private data class TrackedBatchItem(
        val track: OnlineCatalogTrack,
        val displayTitle: String = track.title,
        val displayArtist: String = track.artist,
        val artworkUrl: String? = track.artworkUrl,
        val candidates: List<OnlineCatalogTrack> = listOf(track),
        val currentCandidateIndex: Int = 0,
        val mirrorCandidateTitle: String? = null,
        val idHint: String? = null
    )

    private suspend fun enqueueTrackedBatch(
        items: List<TrackedBatchItem>,
        source: ActiveDownloadSource,
        idStrategy: (TrackedBatchItem) -> String,
        playlistId: Long?,
        mirrorCandidateState: Boolean,
        toastQueued: Boolean = false
    ) {
        if (items.isEmpty()) return
        if (toastQueued) {
            toastDownloadsQueued(count = items.size)
        }

        val queued = items.mapNotNull { item ->
            val downloadId = idStrategy(item)
            if (downloadId.isBlank()) return@mapNotNull null
            val existing = _activeDownloads.value.find { it.id == downloadId }
            if (existing?.state == CandidateDownloadState.DOWNLOADING ||
                existing?.state == CandidateDownloadState.QUEUED
            ) {
                return@mapNotNull null
            }
            val candidates = item.candidates.ifEmpty { listOf(item.track) }
            val safeIndex = item.currentCandidateIndex.coerceIn(0, candidates.lastIndex)
            upsertActiveDownload(
                ActiveDownload.queued(
                    id = downloadId,
                    source = source,
                    displayTitle = item.displayTitle,
                    displayArtist = item.displayArtist,
                    artworkUrl = item.artworkUrl,
                    candidates = candidates,
                    currentCandidateIndex = safeIndex,
                    targetPlaylistId = playlistId
                )
            )
            if (mirrorCandidateState) {
                item.mirrorCandidateTitle?.let {
                    updateCandidateState(it, CandidateDownloadState.QUEUED, percent = 0)
                }
            }
            Triple(item, downloadId, safeIndex)
        }

        val successCount = AtomicInteger(0)
        coroutineScope {
            queued.map { (item, downloadId, safeIndex) ->
                async {
                    val result = runTrackedDownload(
                        downloadId = downloadId,
                        source = source,
                        track = item.track,
                        displayTitle = item.displayTitle,
                        displayArtist = item.displayArtist,
                        artworkUrl = item.artworkUrl,
                        existingCandidates = item.candidates,
                        currentCandidateIndex = safeIndex,
                        mirrorCandidateTitle = item.mirrorCandidateTitle.takeIf {
                            mirrorCandidateState
                        },
                        targetPlaylistId = playlistId
                    )
                    if (result.isSuccess) successCount.incrementAndGet()
                }
            }.awaitAll()
        }

        toast("¡${successCount.get()} de ${items.size} canciones procesadas!")
    }

    private suspend fun enqueuePendingDownloads(
        playlistId: Long,
        tracks: List<OnlineCatalogTrack>,
        toastQueued: Boolean
    ) = enqueueTrackedBatch(
        items = tracks.map { TrackedBatchItem(track = it) },
        source = ActiveDownloadSource.LB_IMPORT,
        idStrategy = {
            ImportListenBrainzPlaylistUseCase.downloadIdFor(it.track.artist, it.track.title)
        },
        playlistId = playlistId,
        mirrorCandidateState = false,
        toastQueued = toastQueued
    )

    private fun clearDiscoverState() {
        _lbDiscoverPlaylists.value = emptyList()
        _lbDiscoverListState.value = LbDiscoverListUiState.Idle
        closeListenBrainzPlaylist()
        clearCfState()
    }

    private fun clearCfState() {
        _cfRecommendations.value = null
        _cfListState.value = CfRecommendationsUiState.Idle
        closeCfRecommendations()
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
            selectedCollectionKind = CatalogCollectionKind.ALBUM
            selectedCollectionCoverUrl = album.coverUrl
            val candidates = MetadataFetcher.fetchAlbumTrackCandidates(album.id, album.title, album.artist, album.coverUrl)
            _activeTrackCandidates.value = candidates
            _isLoadingCollection.value = false
        }
    }

    fun selectPlaylistForInspection(playlist: CatalogPlaylist) {
        viewModelScope.launch {
            _isLoadingCollection.value = true
            _selectedCollectionTitle.value = playlist.title
            selectedCollectionKind = CatalogCollectionKind.PLAYLIST
            selectedCollectionCoverUrl = playlist.coverUrl
            val candidates = MetadataFetcher.fetchPlaylistTrackCandidates(playlist.id, playlist.title)
            _activeTrackCandidates.value = candidates
            _isLoadingCollection.value = false
        }
    }

    private suspend fun expandCandidates(
        query: String,
        current: List<OnlineCatalogTrack>
    ): List<OnlineCatalogTrack> {
        if (current.size > 1 || query.isBlank()) return current
        return YouTubeExtractor.searchYouTube(query).ifEmpty { current }
    }

    /**
     * Shared "Buscar otro" skeleton: expand YT matches → apply mutation → optional re-preview.
     * Callers keep domain-specific list updates in [apply].
     */
    private fun launchCycleYouTubeMatch(
        query: String,
        current: List<OnlineCatalogTrack>,
        wasPreviewing: Boolean,
        apply: suspend (expanded: List<OnlineCatalogTrack>) -> OnlineCatalogTrack?
    ) {
        viewModelScope.launch {
            val expanded = expandCandidates(query, current)
            if (expanded.isEmpty()) return@launch
            val previewTrack = apply(expanded)
            if (wasPreviewing && previewTrack != null) {
                playOnlineCatalogTrackAsStream(previewTrack)
            }
        }
    }

    fun cycleTrackCandidate(index: Int) {
        val list = _activeTrackCandidates.value.toMutableList()
        if (index !in list.indices) return
        val item = list[index]
        launchCycleYouTubeMatch(
            query = "${item.artist} ${item.trackTitle}".trim(),
            current = item.candidates,
            wasPreviewing = isPreviewingCandidate(item)
        ) { candidatesList ->
            val nextIndex = (item.currentCandidateIndex + 1) % candidatesList.size
            val updated = item.copy(candidates = candidatesList, currentCandidateIndex = nextIndex)
            list[index] = updated
            _activeTrackCandidates.value = list
            updated.currentTrack
        }
    }

    /** Cycle YouTube match for a song result in the catalog songs list ("Buscar otro"). */
    fun cycleSongCatalogResult(index: Int) {
        val list = _catalogSearchResults.value.toMutableList()
        if (index !in list.indices) return
        val current = list[index]
        val wasPreviewing = _catalogPreviewKey.value == catalogPreviewKeyFor(current)
        launchCycleYouTubeMatch(
            query = "${current.artist} ${current.title}".trim().ifBlank { current.title },
            current = listOf(current),
            wasPreviewing = wasPreviewing
        ) { searchResults ->
            if (searchResults.size == 1 && searchResults.first().id == current.id) return@launchCycleYouTubeMatch null

            val currentIdx = searchResults.indexOfFirst { it.id == current.id }
            val next = searchResults[(currentIdx + 1).coerceAtLeast(0) % searchResults.size]
            // Keep catalog album metadata when YouTube only says "YouTube"
            list[index] = next.copy(
                album = current.album.takeIf { it.isNotBlank() && !it.equals("YouTube", ignoreCase = true) }
                    ?: next.album,
                artworkUrl = next.artworkUrl ?: current.artworkUrl
            )
            _catalogSearchResults.value = list
            list[index]
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
        selectedCollectionKind = null
        selectedCollectionCoverUrl = null
        catalogBatchPlaylistId = null
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
        e is DuplicateSongException ->
            "Ya existe en la biblioteca: ${e.existing.artist} — ${e.existing.title}"
        e.message?.contains("403") == true ->
            "Error HTTP 403 Forbidden: Enlace o firma expirada de YouTube."
        e.message?.contains("YouTube") == true ->
            e.message ?: "No se pudo obtener audio de YouTube."
        else ->
            "Falló la descarga: ${e.localizedMessage ?: "Error de red."}"
    }

    private suspend fun downloadTrack(
        track: OnlineCatalogTrack,
        onProgress: ((String) -> Unit)? = null,
        conflictPolicy: DownloadConflictPolicy? = null
    ): Result<Song> = downloadAudioTrackUseCase.execute(track, onProgress, conflictPolicy)

    fun resolveDownloadConflictOverwrite(applyToRemainingBatch: Boolean = false) {
        val conflict = _downloadConflict.value ?: return
        val applyAll = applyToRemainingBatch || conflict.applyToRemainingBatch
        _downloadConflict.value = null
        val policy = DownloadConflictPolicy.Overwrite(conflict.existing.id)
        if (applyAll) {
            batchConflictPolicy = DownloadConflictPolicy.Overwrite(conflict.existing.id)
        }
        viewModelScope.launch {
            runTrackedDownload(
                downloadId = conflict.downloadId,
                source = conflict.source,
                track = conflict.track,
                displayTitle = conflict.displayTitle,
                displayArtist = conflict.displayArtist,
                artworkUrl = conflict.artworkUrl,
                existingCandidates = conflict.candidates,
                currentCandidateIndex = conflict.currentCandidateIndex,
                mirrorCandidateTitle = conflict.mirrorCandidateTitle,
                targetPlaylistId = conflict.targetPlaylistId,
                conflictPolicy = policy
            )
        }
    }

    fun resolveDownloadConflictSaveAs(newTitle: String, applyToRemainingBatch: Boolean = false) {
        val conflict = _downloadConflict.value ?: return
        val title = newTitle.trim().ifBlank { "${conflict.existing.title} (2)" }
        val applyAll = applyToRemainingBatch || conflict.applyToRemainingBatch
        _downloadConflict.value = null
        val policy = DownloadConflictPolicy.SaveAs(title)
        if (applyAll) {
            batchConflictPolicy = DownloadConflictPolicy.SaveAs(title)
            batchSaveAsCounter = 2
        }
        viewModelScope.launch {
            runTrackedDownload(
                downloadId = conflict.downloadId,
                source = conflict.source,
                track = conflict.track.copy(title = title),
                displayTitle = title,
                displayArtist = conflict.displayArtist,
                artworkUrl = conflict.artworkUrl,
                existingCandidates = conflict.candidates,
                currentCandidateIndex = conflict.currentCandidateIndex,
                mirrorCandidateTitle = conflict.mirrorCandidateTitle,
                targetPlaylistId = conflict.targetPlaylistId,
                conflictPolicy = policy
            )
        }
    }

    fun cancelDownloadConflict() {
        val conflict = _downloadConflict.value ?: return
        _downloadConflict.value = null
        removeActiveDownload(conflict.downloadId)
        conflict.mirrorCandidateTitle?.let { title ->
            updateCandidateState(title, CandidateDownloadState.IDLE, percent = 0)
        }
    }

    fun clearBatchConflictPolicy() {
        batchConflictPolicy = null
        batchSaveAsCounter = 1
    }

    private fun activeDownloadIdFor(
        track: OnlineCatalogTrack,
        source: ActiveDownloadSource,
        explicitId: String? = null
    ): String {
        explicitId?.takeIf { it.isNotBlank() }?.let { return it }
        val match = TrackMatchKeys.downloadIdFor(track.artist, track.title)
        if (match.isNotEmpty()) return match
        return catalogPreviewKeyFor(track).ifBlank { track.audioUrl.ifBlank { track.id } }
    }

    private suspend fun resolveExistingSong(
        displayArtist: String,
        displayTitle: String,
        activeTrack: OnlineCatalogTrack
    ): Song? = repository.findSongByArtistTitle(
        displayArtist.ifBlank { activeTrack.artist },
        displayTitle.ifBlank { activeTrack.title }
    ) ?: repository.findSongByArtistTitle(activeTrack.artist, activeTrack.title)

    private suspend fun applyBatchPolicy(
        displayArtist: String,
        displayTitle: String,
        activeTrack: OnlineCatalogTrack
    ): DownloadConflictPolicy? {
        val cached = batchConflictPolicy ?: return null
        return when (cached) {
            is DownloadConflictPolicy.Overwrite -> {
                resolveExistingSong(displayArtist, displayTitle, activeTrack)
                    ?.let { DownloadConflictPolicy.Overwrite(it.id) }
            }
            is DownloadConflictPolicy.SaveAs -> {
                batchSaveAsCounter++
                val base = displayTitle.ifBlank { activeTrack.title }.ifBlank { "Track" }
                DownloadConflictPolicy.SaveAs("$base ($batchSaveAsCounter)")
            }
        }
    }

    private fun emitDownloadConflict(
        downloadId: String,
        source: ActiveDownloadSource,
        activeTrack: OnlineCatalogTrack,
        existing: Song,
        displayTitle: String,
        displayArtist: String,
        artworkUrl: String?,
        candidates: List<OnlineCatalogTrack>,
        safeIndex: Int,
        mirrorCandidateTitle: String?,
        targetPlaylistId: Long?
    ) {
        val isBatch = source == ActiveDownloadSource.BATCH || source == ActiveDownloadSource.LB_IMPORT
        _downloadConflict.value = DownloadConflict(
            downloadId = downloadId,
            source = source,
            track = activeTrack,
            existing = existing,
            displayTitle = displayTitle.ifBlank { activeTrack.title }.ifBlank { existing.title },
            displayArtist = displayArtist.ifBlank { activeTrack.artist }.ifBlank { existing.artist },
            artworkUrl = artworkUrl,
            candidates = candidates,
            currentCandidateIndex = safeIndex,
            mirrorCandidateTitle = mirrorCandidateTitle,
            targetPlaylistId = targetPlaylistId,
            applyToRemainingBatch = isBatch
        )
    }

    private fun upsertActiveDownload(download: ActiveDownload) {
        val list = _activeDownloads.value.toMutableList()
        val index = list.indexOfFirst { it.id == download.id }
        if (index >= 0) list[index] = download else list.add(0, download)
        _activeDownloads.value = list
    }

    private fun updateActiveDownload(
        id: String,
        transform: (ActiveDownload) -> ActiveDownload
    ) {
        val list = _activeDownloads.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = transform(list[index])
        _activeDownloads.value = list
    }

    private fun removeActiveDownload(id: String) {
        _activeDownloads.value = _activeDownloads.value.filterNot { it.id == id }
    }

    /**
     * Registers/updates [ActiveDownload] (QUEUED → DOWNLOADING) and runs the download
     * under the global [downloadSemaphore] (max 3 concurrent).
     * On success the job stays as SUCCESS with [ActiveDownload.resultSongId]; on failure as ERROR.
     * When [targetPlaylistId] is set, the saved song is added to that playlist.
     */
    private suspend fun runTrackedDownload(
        downloadId: String,
        source: ActiveDownloadSource,
        track: OnlineCatalogTrack,
        displayTitle: String = track.title.ifBlank { "Descarga" },
        displayArtist: String = track.artist,
        artworkUrl: String? = track.artworkUrl,
        existingCandidates: List<OnlineCatalogTrack>? = null,
        currentCandidateIndex: Int = 0,
        mirrorCandidateTitle: String? = null,
        targetPlaylistId: Long? = null,
        conflictPolicy: DownloadConflictPolicy? = null
    ): Result<Song> {
        val candidates = existingCandidates?.takeIf { it.isNotEmpty() } ?: listOf(track)
        val safeIndex = currentCandidateIndex.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
        val resolvedTitle = displayTitle.ifBlank { track.title }.ifBlank { "Descarga" }

        val existing = _activeDownloads.value.find { it.id == downloadId }
        if (existing == null ||
            existing.state != CandidateDownloadState.DOWNLOADING
        ) {
            upsertActiveDownload(
                ActiveDownload.queued(
                    id = downloadId,
                    source = source,
                    displayTitle = resolvedTitle,
                    displayArtist = displayArtist,
                    artworkUrl = artworkUrl,
                    candidates = candidates,
                    currentCandidateIndex = safeIndex,
                    targetPlaylistId = targetPlaylistId,
                    resultSongId = existing?.resultSongId
                )
            )
            if (mirrorCandidateTitle != null) {
                updateCandidateState(mirrorCandidateTitle, CandidateDownloadState.QUEUED, percent = 0)
            }
        }

        return downloadSemaphore.withPermit {
            runTrackedDownloadLocked(
                downloadId = downloadId,
                source = source,
                track = track,
                displayTitle = resolvedTitle,
                displayArtist = displayArtist,
                artworkUrl = artworkUrl,
                candidates = candidates,
                safeIndex = safeIndex,
                mirrorCandidateTitle = mirrorCandidateTitle,
                targetPlaylistId = targetPlaylistId,
                conflictPolicy = conflictPolicy
            )
        }
    }

    private suspend fun runTrackedDownloadLocked(
        downloadId: String,
        source: ActiveDownloadSource,
        track: OnlineCatalogTrack,
        displayTitle: String,
        displayArtist: String,
        artworkUrl: String?,
        candidates: List<OnlineCatalogTrack>,
        safeIndex: Int,
        mirrorCandidateTitle: String?,
        targetPlaylistId: Long?,
        conflictPolicy: DownloadConflictPolicy?
    ): Result<Song> {
        val activeTrack = candidates.getOrNull(safeIndex) ?: track

        var resolvedPolicy = conflictPolicy ?: applyBatchPolicy(displayArtist, displayTitle, activeTrack)

        // Wait if another download is already showing the conflict dialog (batch).
        if (resolvedPolicy == null) {
            var waited = 0
            while (_downloadConflict.value != null && batchConflictPolicy == null && waited < 120) {
                delay(250)
                waited++
                if (batchConflictPolicy != null) {
                    resolvedPolicy = applyBatchPolicy(displayArtist, displayTitle, activeTrack)
                }
            }
        }

        if (resolvedPolicy == null) {
            val existing = resolveExistingSong(displayArtist, displayTitle, activeTrack)
            if (existing != null) {
                emitDownloadConflict(
                    downloadId = downloadId,
                    source = source,
                    activeTrack = activeTrack,
                    existing = existing,
                    displayTitle = displayTitle,
                    displayArtist = displayArtist,
                    artworkUrl = artworkUrl,
                    candidates = candidates,
                    safeIndex = safeIndex,
                    mirrorCandidateTitle = mirrorCandidateTitle,
                    targetPlaylistId = targetPlaylistId
                )
                upsertActiveDownload(
                    ActiveDownload.conflict(
                        id = downloadId,
                        source = source,
                        displayTitle = displayTitle,
                        displayArtist = displayArtist,
                        artworkUrl = artworkUrl,
                        candidates = candidates,
                        currentCandidateIndex = safeIndex,
                        targetPlaylistId = targetPlaylistId
                    )
                )
                return Result.failure(
                    DuplicateSongException(existing, activeTrack)
                )
            }
        }

        upsertActiveDownload(
            ActiveDownload.downloading(
                id = downloadId,
                source = source,
                displayTitle = displayTitle,
                displayArtist = displayArtist,
                artworkUrl = artworkUrl,
                candidates = candidates,
                currentCandidateIndex = safeIndex,
                targetPlaylistId = targetPlaylistId
            )
        )
        if (mirrorCandidateTitle != null) {
            updateCandidateState(mirrorCandidateTitle, CandidateDownloadState.DOWNLOADING, percent = 20)
            updateCandidateState(mirrorCandidateTitle, CandidateDownloadState.DOWNLOADING, percent = 50)
        }

        val trackForDownload = when (val policy = resolvedPolicy) {
            is DownloadConflictPolicy.SaveAs -> activeTrack.copy(title = policy.newTitle)
            else -> activeTrack
        }

        val result = downloadTrack(
            track = trackForDownload,
            onProgress = { progressMsg ->
                val percent = when {
                    progressMsg.contains("Descargando audio", ignoreCase = true) -> 75
                    progressMsg.contains("Guardando", ignoreCase = true) -> 90
                    progressMsg.contains("Buscando", ignoreCase = true) -> 40
                    else -> 50
                }
                updateActiveDownload(downloadId) {
                    it.copy(
                        state = CandidateDownloadState.DOWNLOADING,
                        progressMessage = progressMsg,
                        progressPercent = percent
                    )
                }
                if (mirrorCandidateTitle != null && percent >= 75) {
                    updateCandidateState(mirrorCandidateTitle, CandidateDownloadState.DOWNLOADING, percent = 75)
                }
            },
            conflictPolicy = resolvedPolicy
        )

        // Late conflict after YouTube metadata resolve (e.g. blank title on LINK)
        val duplicate = result.exceptionOrNull() as? DuplicateSongException
        if (duplicate != null && resolvedPolicy == null) {
            emitDownloadConflict(
                downloadId = downloadId,
                source = source,
                activeTrack = duplicate.track,
                existing = duplicate.existing,
                displayTitle = displayTitle,
                displayArtist = displayArtist,
                artworkUrl = artworkUrl,
                candidates = candidates,
                safeIndex = safeIndex,
                mirrorCandidateTitle = mirrorCandidateTitle,
                targetPlaylistId = targetPlaylistId
            )
            upsertActiveDownload(
                ActiveDownload.conflict(
                    id = downloadId,
                    source = source,
                    displayTitle = displayTitle,
                    displayArtist = displayArtist,
                    artworkUrl = artworkUrl,
                    candidates = candidates,
                    currentCandidateIndex = safeIndex,
                    targetPlaylistId = targetPlaylistId
                )
            )
            return result
        }

        result.fold(
            onSuccess = { song ->
                upsertActiveDownload(
                    ActiveDownload.success(
                        id = downloadId,
                        source = source,
                        song = song,
                        displayTitle = displayTitle,
                        displayArtist = displayArtist,
                        artworkUrl = artworkUrl,
                        candidates = candidates,
                        currentCandidateIndex = safeIndex,
                        targetPlaylistId = targetPlaylistId
                    )
                )
                if (mirrorCandidateTitle != null) {
                    updateCandidateState(mirrorCandidateTitle, CandidateDownloadState.SUCCESS, percent = 100)
                }
                if (targetPlaylistId != null) {
                    repository.addSongToPlaylist(targetPlaylistId, song.id)
                    repository.removePlaylistPendingTrack(
                        targetPlaylistId,
                        activeTrack.artist,
                        activeTrack.title
                    )
                }
                rematchSelectedLbPlaylist(extraSong = song)
                rematchCfRecommendations(extraSong = song)
                if (source == ActiveDownloadSource.CATALOG ||
                    source == ActiveDownloadSource.LINK ||
                    source == ActiveDownloadSource.DISCOVER
                ) {
                    toastSongInLibrary(song.title, LibraryToastKind.ADDED)
                }
            },
            onFailure = { e ->
                if (e is DuplicateSongException) return@fold
                e.printStackTrace()
                val error = mapDownloadError(e)
                updateActiveDownload(downloadId) {
                    it.copy(
                        state = CandidateDownloadState.ERROR,
                        progressMessage = null,
                        progressPercent = 0,
                        errorMessage = error
                    )
                }
                if (mirrorCandidateTitle != null) {
                    updateCandidateState(
                        mirrorCandidateTitle,
                        CandidateDownloadState.ERROR,
                        percent = 0,
                        error = error
                    )
                }
            }
        )
        return result
    }

    private suspend fun rematchSelectedLbPlaylist(extraSong: Song? = null) {
        val current = _selectedLbPlaylist.value ?: return
        val library = libraryWithExtra(extraSong)
        _selectedLbPlaylist.value = matchListenBrainzTracksUseCase.execute(current.detail, library)
    }

    private suspend fun rematchCfRecommendations(extraSong: Song? = null) {
        val current = _cfRecommendations.value ?: return
        val index = MatchListenBrainzTracksUseCase.buildLibraryIndex(libraryWithExtra(extraSong))
        _cfRecommendations.value = current.copy(
            matches = current.matches.map { match ->
                if (match.localSong != null) match
                else {
                    val key = MatchListenBrainzTracksUseCase.matchKey(match.artist, match.title)
                    match.copy(localSong = if (key.isNotEmpty()) index[key] else null)
                }
            }
        )
    }

    private suspend fun libraryWithExtra(extraSong: Song?): List<Song> =
        rawSongs.first().let { list ->
            if (extraSong == null || list.any { it.id == extraSong.id }) list
            else list + extraSong
        }

    /**
     * Manual download of a streamed remote (Para Ti / Recomendados) into the library.
     * Enqueues via [runTrackedDownload] ([ActiveDownloadSource.DISCOVER]); progress in Descargas.
     */
    fun downloadRemoteItem(remote: PlayableItem.Remote) {
        val key = TrackMatchKeys.downloadIdFor(remote.artist, remote.title)
        if (key.isEmpty()) {
            toast("No se puede descargar: faltan artista o título")
            return
        }
        val existing = _activeDownloads.value.find { it.id == key }
        when (existing?.state) {
            CandidateDownloadState.QUEUED,
            CandidateDownloadState.DOWNLOADING -> {
                toastDownloadsQueued(alreadyQueued = true)
                return
            }
            CandidateDownloadState.SUCCESS -> {
                toastSongInLibrary(remote.title, LibraryToastKind.ALREADY)
                viewModelScope.launch {
                    rematchSelectedLbPlaylist()
                    rematchCfRecommendations()
                }
                return
            }
            else -> Unit
        }

        val track = remote.toOnlineCatalogTrack(provider = "YouTube")

        viewModelScope.launch {
            toastDownloadsQueued()
            runTrackedDownload(
                downloadId = key,
                source = ActiveDownloadSource.DISCOVER,
                track = track,
                displayTitle = remote.title,
                displayArtist = remote.artist,
                artworkUrl = remote.artworkUri
            )
        }
    }

    fun retryActiveDownload(id: String) {
        val download = _activeDownloads.value.find { it.id == id } ?: return
        val track = download.currentTrack ?: return
        if (download.source == ActiveDownloadSource.SAVE_WHILE_LISTENING) {
            saveWhileListeningAttempted.add(id)
        }
        viewModelScope.launch {
            val result = runTrackedDownload(
                downloadId = download.id,
                source = download.source,
                track = track,
                displayTitle = download.displayTitle,
                displayArtist = download.displayArtist,
                artworkUrl = download.artworkUrl,
                existingCandidates = download.candidates,
                currentCandidateIndex = download.currentCandidateIndex,
                targetPlaylistId = download.targetPlaylistId
            )
            if (result.isFailure && download.source == ActiveDownloadSource.SAVE_WHILE_LISTENING) {
                saveWhileListeningAttempted.remove(id)
            }
        }
    }

    fun cycleActiveDownload(id: String) {
        val download = _activeDownloads.value.find { it.id == id } ?: return
        val current = download.currentTrack ?: return
        val wasPreviewing = _catalogPreviewKey.value == catalogPreviewKeyFor(current) ||
            download.candidates.any { catalogPreviewKeyFor(it) == _catalogPreviewKey.value }
        val query = "${download.displayArtist} ${download.displayTitle}".trim()
            .ifBlank { current.title.trim() }
            .ifBlank { current.id.ifBlank { current.audioUrl } }
        if (query.isBlank()) return

        launchCycleYouTubeMatch(
            query = query,
            current = download.candidates,
            wasPreviewing = wasPreviewing
        ) { candidatesList ->
            val cycled = ActiveDownload.withCycledCandidate(download, candidatesList)
            upsertActiveDownload(cycled)
            cycled.currentTrack
        }
    }

    fun previewActiveDownload(id: String) {
        val track = _activeDownloads.value.find { it.id == id }?.currentTrack ?: return
        playOnlineCatalogTrackAsStream(track)
    }

    fun playActiveDownload(id: String) {
        val download = _activeDownloads.value.find { it.id == id } ?: return
        val songId = download.resultSongId ?: return
        viewModelScope.launch {
            val song = rawSongs.first().find { it.id == songId } ?: return@launch
            playSong(song)
        }
    }

    fun dismissActiveDownload(id: String) {
        removeActiveDownload(id)
        saveWhileListeningAttempted.remove(id)
    }

    fun dismissAllActiveDownloads() {
        val ids = _activeDownloads.value.map { it.id }
        _activeDownloads.value = emptyList()
        ids.forEach { saveWhileListeningAttempted.remove(it) }
    }

    fun downloadSingleCandidate(index: Int) {
        val list = _activeTrackCandidates.value
        if (index !in list.indices) return
        val candidate = list[index]
        val track = candidate.currentTrack ?: return
        val downloadId = activeDownloadIdFor(
            track,
            ActiveDownloadSource.BATCH,
            explicitId = "batch:${candidate.artist}|${candidate.trackTitle}"
        )

        viewModelScope.launch {
            val targetPlaylistId = ensureCatalogPlaylistForBatch()
            runTrackedDownload(
                downloadId = downloadId,
                source = ActiveDownloadSource.BATCH,
                track = track,
                displayTitle = candidate.trackTitle.ifBlank { track.title },
                displayArtist = candidate.artist.ifBlank { track.artist },
                artworkUrl = candidate.coverUrl ?: track.artworkUrl,
                existingCandidates = candidate.candidates,
                currentCandidateIndex = candidate.currentCandidateIndex,
                mirrorCandidateTitle = candidate.trackTitle,
                targetPlaylistId = targetPlaylistId
            )
        }
    }

    fun downloadSelectedCandidatesBatch() {
        val selected = _activeTrackCandidates.value.filter { it.isSelected && it.currentTrack != null }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            clearBatchConflictPolicy()
            val targetPlaylistId = ensureCatalogPlaylistForBatch()
            val items = selected.mapNotNull { candidate ->
                val track = candidate.currentTrack ?: return@mapNotNull null
                TrackedBatchItem(
                    track = track,
                    displayTitle = candidate.trackTitle.ifBlank { track.title },
                    displayArtist = candidate.artist.ifBlank { track.artist },
                    artworkUrl = candidate.coverUrl ?: track.artworkUrl,
                    candidates = candidate.candidates,
                    currentCandidateIndex = candidate.currentCandidateIndex,
                    mirrorCandidateTitle = candidate.trackTitle,
                    idHint = "batch:${candidate.artist}|${candidate.trackTitle}"
                )
            }
            try {
                enqueueTrackedBatch(
                    items = items,
                    source = ActiveDownloadSource.BATCH,
                    idStrategy = {
                        activeDownloadIdFor(
                            it.track,
                            ActiveDownloadSource.BATCH,
                            explicitId = it.idHint
                        )
                    },
                    playlistId = targetPlaylistId,
                    mirrorCandidateState = true
                )
            } finally {
                clearBatchConflictPolicy()
            }
        }
    }

    /**
     * When downloading from a catalog playlist inspection, create a matching local playlist
     * once per batch session and return its id for [ActiveDownload.targetPlaylistId].
     */
    private suspend fun ensureCatalogPlaylistForBatch(): Long? {
        if (selectedCollectionKind != CatalogCollectionKind.PLAYLIST) return null
        catalogBatchPlaylistId?.let { return it }
        val name = _selectedCollectionTitle.value?.takeIf { it.isNotBlank() } ?: "Playlist"
        val cover = selectedCollectionCoverUrl
        val id = repository.createPlaylist(name, coverUri = cover)
        catalogBatchPlaylistId = id
        return id
    }

    fun downloadFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
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
            ),
            source = ActiveDownloadSource.LINK
        )
    }

    fun downloadOnlineTrack(
        track: OnlineCatalogTrack,
        source: ActiveDownloadSource = ActiveDownloadSource.CATALOG
    ) {
        val downloadId = activeDownloadIdFor(track, source)
        viewModelScope.launch {
            runTrackedDownload(
                downloadId = downloadId,
                source = source,
                track = track,
                displayTitle = track.title.ifBlank {
                    if (source == ActiveDownloadSource.LINK) "Enlace YouTube" else "Descarga"
                },
                displayArtist = track.artist,
                artworkUrl = track.artworkUrl
            )
        }
    }

    fun resetDownloadStatus() {
        _downloadStatus.value = DownloadStatus.Idle
    }

    override fun onCleared() {
        downloadNotificationHelper.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }

    companion object {
        const val RADIO_LOADING_LABEL = "Armando radio…"
        private const val RADIO_BATCH_SIZE = 30
        private const val RADIO_REFILL_THRESHOLD = 5
        /** How long Solo nuevos keeps retrying LB/CF before giving up. */
        private const val RADIO_ONLINE_RETRY_TIMEOUT_MS = 45_000L
        private const val RADIO_ONLINE_REFILL_RETRY_TIMEOUT_MS = 20_000L
        private const val RADIO_ONLINE_RETRY_MAX_BACKOFF_MS = 5_000L
        private const val LAST_PLAYED_POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}

