package com.bestiapop.android.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
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
import com.bestiapop.android.data.listenbrainz.rematchLocals
import com.bestiapop.android.data.model.*
import com.bestiapop.android.data.network.ConnectivityObserver
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.data.preferences.ActiveDownloadsStore
import com.bestiapop.android.data.preferences.DownloadPreferencesRepository
import com.bestiapop.android.data.preferences.DownloadSettings
import com.bestiapop.android.data.preferences.IdentifyReviewStore
import com.bestiapop.android.data.preferences.PersistedIdentifyReviewQueue
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.LibraryUiPreferencesCodec
import com.bestiapop.android.data.preferences.NAV_DOWNLOADS
import com.bestiapop.android.data.preferences.NAV_PLAYLISTS
import com.bestiapop.android.data.preferences.NAV_SETTINGS
import com.bestiapop.android.data.preferences.UiNavSnapshot
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.MAX_SAVE_WHILE_LISTENING_PERCENT
import com.bestiapop.android.data.preferences.MIN_SAVE_WHILE_LISTENING_PERCENT
import com.bestiapop.android.data.preferences.PlaybackModeClear
import com.bestiapop.android.data.preferences.PlaybackModeRestore
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.playback.PlaybackQueueOrder
import com.bestiapop.android.data.preferences.HydratedQueue
import com.bestiapop.android.data.preferences.PlaybackHydration
import com.bestiapop.android.data.preferences.PlaybackSessionStore
import com.bestiapop.android.data.preferences.QueueSnapshot
import com.bestiapop.android.data.preferences.QueueSnapshotCodec
import com.bestiapop.android.data.preferences.ThemePreferencesRepository
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.domain.radio.CfRecommendationsRadio
import com.bestiapop.android.domain.radio.DeezerSimilarRadio
import com.bestiapop.android.domain.radio.ListenBrainzRadio
import com.bestiapop.android.domain.radio.LocalMetadataRadio
import com.bestiapop.android.domain.radio.RadioEngine
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.radio.RadioSuggestResult
import com.bestiapop.android.domain.usecase.BuildSimilarPlaylistPreviewUseCase
import com.bestiapop.android.domain.usecase.FetchAndMatchCfRecommendationsUseCase
import com.bestiapop.android.domain.usecase.ImportListenBrainzPlaylistUseCase
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase
import com.bestiapop.android.domain.util.IdentifyAlbumGroup
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.clusterIdentifyAlbumGroups
import com.bestiapop.android.domain.util.findAlbumMergeTarget
import com.bestiapop.android.domain.util.isTrackNumberLabel
import com.bestiapop.android.domain.util.normalizeAlbumName
import com.bestiapop.android.service.DownloadNotificationHelper
import com.bestiapop.android.service.MusicService
import com.bestiapop.android.service.StreamPlaybackTag
import com.bestiapop.android.service.WebServerService
import com.bestiapop.android.ui.state.DiscoverPlaybackOrigin
import com.bestiapop.android.ui.state.IdentifyReviewItem
import com.bestiapop.android.ui.state.IdentifyReviewPhase
import com.bestiapop.android.ui.state.IdentifyReviewState
import com.bestiapop.android.ui.state.hasMediumSuggestion
import com.bestiapop.android.ui.state.identifyReviewFromPersisted
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryViewMode
import com.bestiapop.android.ui.state.PlaylistDetailNav
import com.bestiapop.android.ui.state.SimilarPlaylistPreviewState
import com.bestiapop.android.ui.state.kindName
import com.bestiapop.android.ui.state.lbMbidOrNull
import com.bestiapop.android.ui.state.localIdOrNull
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

enum class SortDirection {
    ASC,
    DESC;

    companion object {
        fun defaultFor(option: SortOption): SortDirection =
            when (option) {
                SortOption.DATE_ADDED -> DESC
                else -> ASC
            }
    }
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

@OptIn(UnstableApi::class)
@kotlin.OptIn(FlowPreview::class)
class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val streamResolver = repository.streamResolver
    private val audioStore = MusicFileStore(application)
    private val themeRepository = ThemePreferencesRepository(application)
    private val listenBrainzPreferences = ListenBrainzPreferencesRepository(application)
    private val playbackPreferences = PlaybackPreferencesRepository(application)
    private val downloadPreferences = DownloadPreferencesRepository(application)
    private val libraryPreferences = LibraryPreferencesRepository(application)
    private val activeDownloadsStore = ActiveDownloadsStore(application)
    private val identifyReviewStore = IdentifyReviewStore(application)
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

    val downloadSettings: StateFlow<DownloadSettings> =
        downloadPreferences.settingsFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadSettings())

    val downloadOnMeteredNetwork: StateFlow<Boolean> =
        downloadSettings
            .map { it.downloadOnMeteredNetwork }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val volumeBoostEnabled: StateFlow<Boolean> = playbackPref(false) { it.volumeBoostEnabled }
    val stereoLeftGain: StateFlow<Float> = playbackPref(1f) { it.stereoLeftGain }
    val stereoRightGain: StateFlow<Float> = playbackPref(1f) { it.stereoRightGain }
    val rememberShuffleOnLaunch: StateFlow<Boolean> = playbackPref(true) { it.rememberShuffleOnLaunch }
    val rememberRepeatOnLaunch: StateFlow<Boolean> = playbackPref(true) { it.rememberRepeatOnLaunch }
    val autoplayOnLaunch: StateFlow<Boolean> = playbackPref(false) { it.autoplayOnLaunch }
    val clearShuffleOnManualPlay: StateFlow<Boolean> =
        playbackPref(true) { it.clearShuffleOnManualPlay }
    val clearRepeatAllOnManualPlay: StateFlow<Boolean> =
        playbackPref(false) { it.clearRepeatAllOnManualPlay }
    val clearRepeatOneOnManualPlay: StateFlow<Boolean> =
        playbackPref(true) { it.clearRepeatOneOnManualPlay }
    val clearShuffleOnSkip: StateFlow<Boolean> = playbackPref(false) { it.clearShuffleOnSkip }
    val clearRepeatOneOnSkip: StateFlow<Boolean> = playbackPref(true) { it.clearRepeatOneOnSkip }

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

    private val _sortDirection = MutableStateFlow(SortDirection.defaultFor(SortOption.TITLE))
    val sortDirection = _sortDirection.asStateFlow()

    private val _libraryViewMode = MutableStateFlow(LibraryViewMode.ALBUM_GROUPS)
    val libraryViewMode = _libraryViewMode.asStateFlow()

    private val _selectedNavIndex = MutableStateFlow(0)
    val selectedNavIndex = _selectedNavIndex.asStateFlow()

    private val _libraryBrowseFilter = MutableStateFlow(LibraryBrowseFilter.SONGS)
    val libraryBrowseFilter = _libraryBrowseFilter.asStateFlow()

    private val _libraryArtistName = MutableStateFlow<String?>(null)
    val libraryArtistName = _libraryArtistName.asStateFlow()

    private val _libraryAlbumName = MutableStateFlow<String?>(null)
    val libraryAlbumName = _libraryAlbumName.asStateFlow()

    private val _libraryGenreName = MutableStateFlow<String?>(null)
    val libraryGenreName = _libraryGenreName.asStateFlow()

    private val _playlistDetail = MutableStateFlow<PlaylistDetailNav>(PlaylistDetailNav.None)
    val playlistDetail = _playlistDetail.asStateFlow()

    private val _discoverPlaybackOrigin =
        MutableStateFlow<DiscoverPlaybackOrigin>(DiscoverPlaybackOrigin.None)
    val discoverPlaybackOrigin = _discoverPlaybackOrigin.asStateFlow()

    private var uiPrefsHydrated = false

    private val getLibrarySongsUseCase = com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase()
    private val downloadAudioTrackUseCase =
        com.bestiapop.android.domain.usecase.DownloadAudioTrackUseCase(repository)

    val songsState: StateFlow<List<Song>> = combine(
        rawSongs,
        _searchQuery,
        _sortOption,
        _sortDirection
    ) { list, query, sort, direction ->
        getLibrarySongsUseCase.execute(list, query, sort, direction)
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

    fun sortSongsWithinAlbum(songs: List<Song>): List<Song> =
        getLibrarySongsUseCase.sortSongsWithinAlbum(songs)

    fun songsForAlbum(songs: List<Song>, albumName: String): List<Song> =
        sortSongsWithinAlbum(songs.filter { it.album.equals(albumName, ignoreCase = true) })

    fun songsForArtist(songs: List<Song>, artistName: String): List<Song> =
        songs.filter { it.artist.equals(artistName, ignoreCase = true) }

    fun songsForGenre(songs: List<Song>, genreName: String): List<Song> =
        getLibrarySongsUseCase.songsMatchingGenre(songs, genreName)

    fun songsFromLibraryListItems(items: List<LibraryListItem>): List<Song> =
        getLibrarySongsUseCase.songsFromListItems(items)

    fun songsForBrowseProjection(
        filter: LibraryBrowseFilter,
        songs: List<Song>,
        viewMode: LibraryViewMode,
        albums: List<Album>,
        artists: List<Artist>,
        genres: List<GenreGroup>
    ): List<Song> = getLibrarySongsUseCase.songsForBrowseProjection(
        filter = filter,
        songs = songs,
        viewMode = viewMode,
        albums = albums,
        artists = artists,
        genres = genres
    )

    val albumsState: StateFlow<List<Album>> = combine(
        songsState,
        repository.albumOverridesFlow,
        _sortOption,
        _sortDirection
    ) { songs, overrides, sortOption, sortDirection ->
        getLibrarySongsUseCase.extractAlbums(
            songs,
            overrides.associateBy { it.albumKey },
            sortOption,
            sortDirection
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _artistPhotos = MutableStateFlow<Map<String, String>>(emptyMap())

    val artistsState: StateFlow<List<Artist>> = combine(
        songsState,
        _artistPhotos,
        _sortOption,
        _sortDirection
    ) { songs: List<Song>, photoMap: Map<String, String>, sortOption, sortDirection ->
        getLibrarySongsUseCase.extractArtists(songs, photoMap, sortOption, sortDirection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val genresState: StateFlow<List<GenreGroup>> = combine(
        songsState,
        _sortOption,
        _sortDirection
    ) { songs, sortOption, sortDirection ->
        getLibrarySongsUseCase.extractGenres(songs, sortOption, sortDirection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Player State

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

    /**
     * Optional legacy index permutation over [_queue]. Prefer physically reordering
     * [_queue] to play order (see [permuteQueueToPlayOrder]) so Cola and next/prev match
     * without Media3 [ShuffleOrder] races. When non-null, [displayQueue] remaps for UI.
     */
    private val _shufflePlayOrder = MutableStateFlow<List<Int>?>(null)
    /** Timeline before the last physical shuffle; restored when turning shuffle off. */
    private var preShuffleQueueBackup: List<PlayableItem>? = null
    val displayQueue: StateFlow<List<PlayableItem>> = combine(
        _queue,
        _isShuffle,
        _shufflePlayOrder
    ) { q, shuffle, order ->
        if (!shuffle) q else PlaybackQueueOrder.applyPlayOrder(q, order)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
    /** Serializes play/shuffle collection so overlapping Mezclar cannot race setMediaItems vs shuffle order. */
    private var playCollectionJob: Job? = null
    /** Applies shuffle order only after player timeline size matches the queue. */
    private var shuffleApplyJob: Job? = null
    /** Caps silent skip cascades when remotes/locals fail in a row. */
    private var consecutiveUnplayableSkips = 0
    private val playedInRadioSession = linkedSetOf<String>()
    /** Last user-chosen mode (session); auto-start / tap reuse this when set. */
    private var radioPreferredMode: RadioMode? = null

    /** True after UI was rebuilt from a live MediaController timeline. */
    private var liveSessionHydrated = false
    private var idleSeedDone = false
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
    private val buildSimilarPlaylistPreviewUseCase =
        BuildSimilarPlaylistPreviewUseCase(radioEngine, repository)

    private val _similarPlaylistPreview = MutableStateFlow<SimilarPlaylistPreviewState?>(null)
    val similarPlaylistPreview = _similarPlaylistPreview.asStateFlow()
    private var similarPreviewJob: Job? = null
    private var similarPreviewSeeds: List<PlayableItem> = emptyList()

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

    private val _catalogGenres = MutableStateFlow<List<CatalogGenre>>(emptyList())
    val catalogGenres = _catalogGenres.asStateFlow()

    private val _selectedCollectionTitle = MutableStateFlow<String?>(null)
    val selectedCollectionTitle = _selectedCollectionTitle.asStateFlow()

    private enum class CatalogCollectionKind { ALBUM, PLAYLIST, GENRE }
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
    private val _libraryJobProgress = MutableStateFlow<LibraryJobProgress?>(null)
    val libraryJobProgress: StateFlow<LibraryJobProgress?> = _libraryJobProgress.asStateFlow()
    private val _identifyReview = MutableStateFlow(IdentifyReviewState())
    val identifyReview: StateFlow<IdentifyReviewState> = _identifyReview.asStateFlow()
    private val identifyMutex = Mutex()
    private val identifiedWifiSongIds = mutableSetOf<Long>()
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
            persistPlayback { setVolumeBoostAmount(boostAmount) }
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

    fun setDownloadOnMeteredNetwork(enabled: Boolean) {
        viewModelScope.launch {
            downloadPreferences.setDownloadOnMeteredNetwork(enabled)
        }
    }

    private val _pendingSettingsSection = MutableStateFlow<String?>(null)
    val pendingSettingsSection = _pendingSettingsSection.asStateFlow()

    fun openDownloadSettings() {
        _pendingSettingsSection.value = "downloads"
        setSelectedNavIndex(NAV_SETTINGS)
    }

    fun consumePendingSettingsSection(): String? {
        val v = _pendingSettingsSection.value
        _pendingSettingsSection.value = null
        return v
    }

    fun setStereoLeftGain(gain: Float) {
        persistPlayback { setStereoLeftGain(gain) }
    }

    fun setStereoRightGain(gain: Float) {
        persistPlayback { setStereoRightGain(gain) }
    }

    fun resetStereoBalance() {
        persistPlayback { resetStereoBalance() }
    }

    fun setRememberShuffleOnLaunch(enabled: Boolean) {
        persistPlayback { setRememberShuffleOnLaunch(enabled) }
    }

    fun setRememberRepeatOnLaunch(enabled: Boolean) {
        persistPlayback { setRememberRepeatOnLaunch(enabled) }
    }

    fun setAutoplayOnLaunch(enabled: Boolean) {
        persistPlayback { setAutoplayOnLaunch(enabled) }
    }

    fun setClearShuffleOnManualPlay(enabled: Boolean) {
        persistPlayback { setClearShuffleOnManualPlay(enabled) }
    }

    fun setClearRepeatAllOnManualPlay(enabled: Boolean) {
        persistPlayback { setClearRepeatAllOnManualPlay(enabled) }
    }

    fun setClearRepeatOneOnManualPlay(enabled: Boolean) {
        persistPlayback { setClearRepeatOneOnManualPlay(enabled) }
    }

    fun setClearShuffleOnSkip(enabled: Boolean) {
        persistPlayback { setClearShuffleOnSkip(enabled) }
    }

    fun setClearRepeatOneOnSkip(enabled: Boolean) {
        persistPlayback { setClearRepeatOneOnSkip(enabled) }
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

    private fun restorePlaybackModes() {
        viewModelScope.launch {
            val settings = playbackPreferences.settingsFlow.first()
            val controller = mediaController
            val hasLiveSession = (controller?.mediaItemCount ?: 0) > 0
            val liveRepeat = repeatModeFromPlayer(controller?.repeatMode ?: Player.REPEAT_MODE_OFF)
            val resolved = PlaybackModeRestore.resolve(settings, hasLiveSession, liveRepeat)
            _isShuffle.value = resolved.shuffle
            _repeatMode.value = resolved.repeat
            if (resolved.applyRepeatToPlayer) {
                applyRepeatModeToController(resolved.repeat)
            }
            syncShuffleToPlayerWhenReady()
        }
    }

    private fun setShuffleEnabled(enabled: Boolean) {
        val wasEnabled = _isShuffle.value
        _isShuffle.value = enabled
        persistPlayback { setLastShuffleEnabled(enabled) }
        if (!enabled && wasEnabled) {
            _shufflePlayOrder.value = null
            preShuffleQueueBackup = null
            sendShuffleOrderToPlayer(null)
            mediaController?.shuffleModeEnabled = false
        }
    }

    private fun displayToTimelineIndex(displayIndex: Int): Int {
        val size = _queue.value.size
        if (!_isShuffle.value) return displayIndex
        return PlaybackQueueOrder.toTimelineIndex(_shufflePlayOrder.value, displayIndex, size)
    }

    private fun sendShuffleOrderToPlayer(order: List<Int>?) {
        val controller = mediaController ?: return
        val extras = Bundle()
        if (order != null && order.isNotEmpty()) {
            extras.putIntArray(MusicService.EXTRA_SHUFFLE_ORDER, order.toIntArray())
        }
        controller.sendCustomCommand(
            SessionCommand(MusicService.ACTION_SET_SHUFFLE_ORDER, Bundle.EMPTY),
            extras
        )
    }

    /**
     * Keep ExoPlayer linear: play order lives in [_queue] (and optional legacy
     * [_shufflePlayOrder] for display only). Avoids Media3 ShuffleOrder size races.
     */
    private fun syncShuffleToPlayer() {
        shuffleApplyJob?.cancel()
        mediaController?.shuffleModeEnabled = false
        sendShuffleOrderToPlayer(null)
    }

    private fun syncShuffleToPlayerWhenReady() {
        shuffleApplyJob?.cancel()
        syncShuffleToPlayer()
    }

    /**
     * Rewrite [_queue] so index 0 is current and the rest are shuffled. Player timeline
     * matches Cola; next/prev follow the list without ExoPlayer shuffle mode.
     */
    private fun permuteQueueToPlayOrder(
        items: List<PlayableItem>,
        currentIndex: Int,
        backupSource: Boolean
    ): Pair<List<PlayableItem>, Int> {
        if (items.isEmpty()) return items to 0
        if (backupSource) preShuffleQueueBackup = items
        val order = PlaybackQueueOrder.shufflePlayOrder(items.size, currentIndex)
        val shuffled = PlaybackQueueOrder.applyPlayOrder(items, order)
        _shufflePlayOrder.value = null
        return shuffled to 0
    }

    private fun reloadPlayerTimeline(
        items: List<PlayableItem>,
        startIndex: Int,
        startPositionMs: Long,
        startPlaying: Boolean
    ) {
        val controller = mediaController ?: return
        controller.shuffleModeEnabled = false
        controller.setMediaItems(
            items.map { playableToMediaItem(it) },
            startIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)),
            startPositionMs.coerceAtLeast(0L)
        )
        controller.prepare()
        if (startPlaying) playWithForegroundService(controller)
        syncShuffleToPlayer()
    }

    /**
     * Rebuild ExoPlayer playlist to [newOrder] without interrupting the currently playing item
     * (no [Player.setMediaItems] / prepare). Used by shuffle toggle so NP doesn't hitch.
     * Returns false when seamless surgery isn't possible — caller should fall back to [reloadPlayerTimeline].
     */
    private fun rebuildPlayerQueueAroundCurrent(newOrder: List<PlayableItem>): Boolean {
        val controller = mediaController ?: return false
        if (newOrder.isEmpty() || controller.mediaItemCount <= 0) return false
        val currentId = _currentItem.value?.mediaId
            ?: controller.currentMediaItem?.mediaId
            ?: return false
        val playIndex = newOrder.indexOfFirst { it.mediaId == currentId }
            .takeIf { it >= 0 }
            ?: return false
        val playerIndex = controller.currentMediaItemIndex
        if (playerIndex !in 0 until controller.mediaItemCount) return false
        val playingId = controller.currentMediaItem?.mediaId
        if (playingId != null && playingId != currentId) return false

        suppressPlaylistMutationCallbacks = true
        try {
            if (playerIndex + 1 < controller.mediaItemCount) {
                controller.removeMediaItems(playerIndex + 1, controller.mediaItemCount)
            }
            if (playerIndex > 0) {
                controller.removeMediaItems(0, playerIndex)
            }
            // Only the current item remains at index 0.
            if (playIndex > 0) {
                controller.addMediaItems(
                    0,
                    newOrder.subList(0, playIndex).map { playableToMediaItem(it) }
                )
            }
            if (playIndex < newOrder.lastIndex) {
                controller.addMediaItems(
                    newOrder.subList(playIndex + 1, newOrder.size).map { playableToMediaItem(it) }
                )
            }
            lastMediaItemIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        } finally {
            suppressPlaylistMutationCallbacks = false
        }
        syncShuffleToPlayer()
        return true
    }

    private fun applyQueueReorder(
        newOrder: List<PlayableItem>,
        focusIndex: Int,
        positionMs: Long,
        startPlaying: Boolean
    ) {
        _queue.value = newOrder
        lastMediaItemIndex = focusIndex
        setCurrentItem(newOrder[focusIndex], persistLastPlayed = false)
        setPlaybackPositionUi(positionMs)
        if (!rebuildPlayerQueueAroundCurrent(newOrder)) {
            reloadPlayerTimeline(newOrder, focusIndex, positionMs, startPlaying = startPlaying)
        }
    }

    private fun updateShufflePlayOrder(transform: (List<Int>) -> List<Int>) {
        if (!_isShuffle.value) return
        val prev = _shufflePlayOrder.value ?: return
        _shufflePlayOrder.value = transform(prev)
        sendShuffleOrderToPlayer(_shufflePlayOrder.value)
    }

    private fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
        applyRepeatModeToController(mode)
        persistPlayback { setLastRepeatMode(mode) }
    }

    private fun applyResolvedModes(shuffle: Boolean, repeat: RepeatMode) {
        if (shuffle != _isShuffle.value) setShuffleEnabled(shuffle)
        if (repeat != _repeatMode.value) setRepeatMode(repeat)
    }

    private fun applyManualPlayModes() {
        val (shuffle, repeat) = PlaybackModeClear.afterManualPlay(
            shuffle = _isShuffle.value,
            repeat = _repeatMode.value,
            settings = playbackSettings.value
        )
        applyResolvedModes(shuffle, repeat)
    }

    private fun applySkipModes() {
        val (shuffle, repeat) = PlaybackModeClear.afterSkip(
            shuffle = _isShuffle.value,
            repeat = _repeatMode.value,
            settings = playbackSettings.value
        )
        applyResolvedModes(shuffle, repeat)
    }

    private fun persistPlayback(block: suspend PlaybackPreferencesRepository.() -> Unit) {
        viewModelScope.launch { playbackPreferences.block() }
    }

    private fun <T> playbackPref(
        initial: T,
        select: (PlaybackSettings) -> T
    ): StateFlow<T> = playbackSettings
        .map(select)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    private fun applyRepeatModeToController(mode: RepeatMode) {
        mediaController?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    private fun repeatModeFromPlayer(playerRepeatMode: Int): RepeatMode = when (playerRepeatMode) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    init {
        initMediaController()
        startPositionTracker()
        viewModelScope.launch {
            hydrateUiPreferences()
        }
        viewModelScope.launch {
            ensureInitialLibraryImport(showRecoveryToast = true)
        }
        viewModelScope.launch {
            _catalogSearchResults.value = MetadataFetcher.getFeaturedDemoCatalog()
            _albumSearchResults.value = MetadataFetcher.searchAlbums("")
            _playlistSearchResults.value = MetadataFetcher.searchPlaylists("")
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.migrateCanonicalAudioUris()
            repository.migrateLegacyYouTubeMusicSongs()
        }

        viewModelScope.launch {
            identifyMutex.withLock {
                val snap = withContext(Dispatchers.IO) { identifyReviewStore.load() }
                if (snap.proposals.isNotEmpty()) {
                    val songs = withContext(Dispatchers.IO) { repository.getAllSongsSync() }
                    _identifyReview.value = identifyReviewFromPersisted(
                        snap.proposals,
                        snap.phase,
                        songs
                    )
                }
            }
            identifyReview
                .map { state ->
                    PersistedIdentifyReviewQueue(
                        proposals = state.items.drop(state.currentIndex).map { it.proposal },
                        phase = state.phase.name
                    )
                }
                .distinctUntilChanged()
                .debounce(300)
                .collect { snap ->
                    withContext(Dispatchers.IO) {
                        identifyReviewStore.save(snap)
                    }
                }
        }

        viewModelScope.launch {
            WebServerService.transfers
                .debounce(400)
                .collect { list ->
                    val newIds = list.mapNotNull { transfer ->
                        val id = transfer.songId
                        if (transfer.state == WifiTransferState.DONE &&
                            id != null &&
                            id !in identifiedWifiSongIds
                        ) {
                            id
                        } else {
                            null
                        }
                    }
                    if (newIds.isEmpty()) return@collect
                    val idSet = newIds.toSet()
                    val songs = withContext(Dispatchers.IO) {
                        repository.getAllSongsSync().filter { it.id in idSet }
                    }
                    if (songs.isEmpty()) return@collect
                    identifiedWifiSongIds += songs.map { it.id }
                    identifyMutex.withLock {
                        runIdentifySongs(songs, force = true, showReview = false)
                    }
                }
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
                restorePlaybackModes()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private var lastMediaItemIndex: Int = -1
    private var suppressShuffleWrapDetection: Boolean = false
    /**
     * When true, playlist surgery (shuffle toggle) must not reset UI position / re-stamp
     * via [onMediaItemTransition] (PLAYLIST_CHANGED while the same track keeps playing).
     */
    private var suppressPlaylistMutationCallbacks: Boolean = false
    /** Dedupes Room lastPlayedAt writes when the same local track is re-set (timeline reload). */
    private var lastTouchedSongId: Long = -1L

    private fun setCurrentItem(item: PlayableItem?, persistLastPlayed: Boolean = true) {
        val previousMediaId = _currentItem.value?.mediaId
        val mediaChanged = item?.mediaId != previousMediaId
        _currentItem.value = item
        val localSong = (item as? PlayableItem.Local)?.song
        _currentSong.value = localSong
        if (localSong != null) {
            listenTracker.onTrackChanged(localSong)
            if (mediaChanged) {
                requestMetadataEnhancement(localSong)
            }
        } else {
            listenTracker.onTrackChanged(null)
        }
        if (persistLastPlayed) {
            persistPlaybackSession(force = true)
            if (mediaChanged) {
                maybeTouchSongLastPlayed(localSong)
            }
        }
    }

    private fun maybeTouchSongLastPlayed(song: Song?) {
        if (song == null || song.id <= 0L || song.id == lastTouchedSongId) return
        lastTouchedSongId = song.id
        viewModelScope.launch(Dispatchers.IO) {
            repository.touchSongLastPlayed(song.id)
        }
    }

    private fun currentQueueIndex(): Int {
        val queue = _queue.value
        if (queue.isEmpty()) return 0
        val fromController = mediaController?.currentMediaItemIndex
        if (fromController != null && fromController in queue.indices) return fromController
        if (lastMediaItemIndex in queue.indices) return lastMediaItemIndex
        val current = _currentItem.value ?: return 0
        return queue.indexOfFirst { it.mediaId == current.mediaId }.coerceAtLeast(0)
    }

    private fun persistPlaybackSession(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistedPositionAtMs < LAST_PLAYED_POSITION_SAVE_INTERVAL_MS) return
        lastPersistedPositionAtMs = now
        val positionMs = _playbackPositionMs.value
        val local = (_currentItem.value as? PlayableItem.Local)?.song
        val queue = _queue.value
        val index = currentQueueIndex()
        viewModelScope.launch(Dispatchers.IO) {
            val lastPlayed = local?.let { PlaybackHydration.snapshotFromSong(it, positionMs) }
            if (queue.isEmpty()) {
                playbackSessionStore.saveSession(lastPlayed = lastPlayed, clearQueue = true)
            } else {
                playbackSessionStore.saveSession(
                    lastPlayed = lastPlayed,
                    queue = queueSnapshotForPersist(queue, index, positionMs)
                )
            }
        }
    }

    /**
     * When shuffle is on and [preShuffleQueueBackup] still matches queue size, persist the
     * original order + play-order map so restart can restore both shuffled playback and unshuffle.
     */
    private fun queueSnapshotForPersist(
        queue: List<PlayableItem>,
        index: Int,
        positionMs: Long
    ): QueueSnapshot {
        val backup = preShuffleQueueBackup
        if (_isShuffle.value && backup != null && backup.size == queue.size) {
            val playOrder = queue.map { item ->
                backup.indexOfFirst { it.mediaId == item.mediaId }
            }
            val validOrder = PlaybackQueueOrder.validPlayOrderOrNull(playOrder, backup.size)
            if (validOrder != null) {
                val currentId = (_currentItem.value ?: queue.getOrNull(index))?.mediaId
                val backupIndex = when {
                    currentId != null ->
                        backup.indexOfFirst { it.mediaId == currentId }.takeIf { it >= 0 }
                    else -> null
                } ?: index.coerceIn(0, backup.lastIndex)
                val trimmed = PlaybackQueueOrder.trimHistory(
                    backup,
                    backupIndex,
                    shufflePlayOrder = validOrder
                )
                return QueueSnapshotCodec.fromPlayable(
                    items = trimmed.items,
                    currentIndex = trimmed.currentIndex,
                    positionMs = positionMs,
                    shufflePlayOrder = trimmed.shufflePlayOrder
                )
            }
        }
        val playOrder = if (_isShuffle.value) _shufflePlayOrder.value else null
        val trimmed = PlaybackQueueOrder.trimHistory(
            queue,
            index,
            shufflePlayOrder = playOrder
        )
        return QueueSnapshotCodec.fromPlayable(
            items = trimmed.items,
            currentIndex = trimmed.currentIndex,
            positionMs = positionMs,
            shufflePlayOrder = trimmed.shufflePlayOrder
        )
    }

    private fun persistCurrentPosition(force: Boolean = false) {
        persistPlaybackSession(force = force)
    }

    /**
     * Rebuild ViewModel queue / current item from a live MediaController session
     * (Activity recreate while MusicService keeps playing).
     */
    private fun syncUiFromController() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount <= 0) {
            if (_queue.value.isNotEmpty() && _currentItem.value != null) {
                loadHydratedQueueIntoController()
                return
            }
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
        val extrasOrder = controller.sessionExtras
            .getIntArray(MusicService.EXTRA_SHUFFLE_ORDER)
            ?.toList()
        val order = PlaybackQueueOrder.validPlayOrderOrNull(extrasOrder, rebuilt.size)
        if (order != null && controller.shuffleModeEnabled) {
            // Live session still using Media3 shuffle — fold into physical queue.
            preShuffleQueueBackup = rebuilt
            val shuffled = PlaybackQueueOrder.applyPlayOrder(rebuilt, order)
            val displayIdx = PlaybackQueueOrder.toDisplayIndex(order, index, rebuilt.size)
                .coerceIn(0, shuffled.lastIndex)
            _queue.value = shuffled
            lastMediaItemIndex = displayIdx
            _shufflePlayOrder.value = null
            setCurrentItem(shuffled[displayIdx], persistLastPlayed = false)
            reloadPlayerTimeline(
                shuffled,
                displayIdx,
                controller.currentPosition.coerceAtLeast(0L),
                startPlaying = controller.playWhenReady
            )
        } else {
            _shufflePlayOrder.value = null
            lastMediaItemIndex = index
            setCurrentItem(rebuilt[index], persistLastPlayed = false)
        }
        _isPlaying.value = controller.isPlaying
        _playbackPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
        _repeatMode.value = repeatModeFromPlayer(controller.repeatMode)

        ensureRemoteReadyAt(lastMediaItemIndex, startPlaying = controller.isPlaying)
        prefetchAround(lastMediaItemIndex)
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
            val videoId = YouTubeExtractor.extractYouTubeId(remoteKey)
            return if (videoId != null && videoId == remoteKey) {
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
                    youtubeQueryOrId = youtubeSearchQuery(artist, title).ifBlank { remoteKey }
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

    private fun loadHydratedQueueIntoController() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount > 0) return
        val items = _queue.value
        if (items.isEmpty()) return
        val idx = lastMediaItemIndex.takeIf { it in items.indices }
            ?: items.indexOfFirst { it.mediaId == _currentItem.value?.mediaId }.takeIf { it >= 0 }
            ?: 0
        val pos = _playbackPositionMs.value.coerceAtLeast(0L)
        controller.setMediaItems(items.map { playableToMediaItem(it) }, idx, pos)
        controller.prepare()
    }

    /** Keep UI progress in sync with the active media item (always write, including 0). */
    private fun setPlaybackPositionUi(positionMs: Long) {
        _playbackPositionMs.value = positionMs.coerceAtLeast(0L)
    }

    private fun applyHydratedQueue(hydrated: HydratedQueue) {
        clearDiscoverPlaybackOrigin()
        val order = PlaybackQueueOrder.validPlayOrderOrNull(
            hydrated.shufflePlayOrder,
            hydrated.items.size
        )
        if (order != null) {
            preShuffleQueueBackup = hydrated.items
            val shuffled = PlaybackQueueOrder.applyPlayOrder(hydrated.items, order)
            val displayIdx = PlaybackQueueOrder.toDisplayIndex(
                order,
                hydrated.currentIndex,
                hydrated.items.size
            ).coerceIn(0, shuffled.lastIndex)
            _queue.value = shuffled
            lastMediaItemIndex = displayIdx
            _shufflePlayOrder.value = null
            setCurrentItem(shuffled[displayIdx], persistLastPlayed = false)
        } else {
            preShuffleQueueBackup = null
            _queue.value = hydrated.items
            lastMediaItemIndex = hydrated.currentIndex
            _shufflePlayOrder.value = null
            setCurrentItem(hydrated.items[hydrated.currentIndex], persistLastPlayed = false)
        }
        setPlaybackPositionUi(hydrated.positionMs)
        _isPlaying.value = false
        loadHydratedQueueIntoController()
        syncShuffleToPlayer()
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
            val queueSnap = withContext(Dispatchers.IO) { playbackSessionStore.loadQueue() }
            val settings = playbackPreferences.settingsFlow.first()
            if (liveSessionHydrated || _currentItem.value != null) return@launch
            val library = songsState.value
            val hydrated = PlaybackHydration.hydrateQueue(queueSnap, library)
            if (hydrated != null && hydrated.items.isNotEmpty()) {
                if (liveSessionHydrated || _currentItem.value != null) return@launch
                applyHydratedQueue(hydrated)
                maybeAutoplayAfterIdleSeed(settings.autoplayOnLaunch)
                return@launch
            }
            val song = PlaybackHydration.resolveIdleSeed(library, last) ?: return@launch
            if (liveSessionHydrated || _currentItem.value != null) return@launch
            setCurrentItem(song.toPlayable(), persistLastPlayed = false)
            _playbackPositionMs.value = PlaybackHydration.resumePositionMs(song, last)
            _isPlaying.value = false
            maybeAutoplayAfterIdleSeed(settings.autoplayOnLaunch)
        }
    }

    private fun maybeAutoplayAfterIdleSeed(autoplay: Boolean) {
        if (!autoplay || liveSessionHydrated) return
        if (mediaController?.isPlaying == true) return
        if (_currentItem.value == null) return
        togglePlayPause()
    }

    private fun setupPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                _isPlaying.value = isPlayingNow
                if (isPlayingNow) {
                    consecutiveUnplayableSkips = 0
                } else {
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
                if (suppressPlaylistMutationCallbacks) {
                    lastMediaItemIndex = newIndex
                    return
                }
                val queueSize = _queue.value.size
                val playOrder = _shufflePlayOrder.value
                val wrappedShuffleCycle = !suppressShuffleWrapDetection &&
                    _isShuffle.value &&
                    _repeatMode.value == RepeatMode.ALL &&
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    lastMediaItemIndex >= 0 &&
                    queueSize > 1 &&
                    (
                        (
                            !playOrder.isNullOrEmpty() &&
                                lastMediaItemIndex == playOrder.last() &&
                                newIndex == playOrder.first()
                            ) ||
                            (
                                playOrder.isNullOrEmpty() &&
                                    lastMediaItemIndex == queueSize - 1 &&
                                    newIndex == 0
                                )
                        )

                mediaItem?.let { item ->
                    val idOrUri = item.mediaId
                    val playable = _queue.value.find { it.mediaId == idOrUri }
                        ?: _queue.value.find {
                            it is PlayableItem.Local &&
                                (it.song.uriString == idOrUri || it.song.id.toString() == idOrUri)
                        }
                        ?: mediaItemToPlayable(item, songsState.value)
                    // New item → progress must not keep the previous track's position.
                    setPlaybackPositionUi(0L)
                    setCurrentItem(playable)
                    remoteErrorRetryUsed = false
                    ensureRemoteReadyAt(
                        newIndex,
                        startPlaying = controller?.playWhenReady == true
                    )
                    prefetchAround(newIndex)
                    if (_radioActive.value) {
                        rememberRadioPlayed(playable)
                        maybeRefillRadio(newIndex)
                    }
                } ?: listenTracker.onTrackChanged(null)

                if (wrappedShuffleCycle) {
                    val avoidId = _queue.value.getOrNull(lastMediaItemIndex)?.mediaId
                    val source = _queue.value
                    val reshuffled = source.shuffled().toMutableList()
                    if (avoidId != null && reshuffled.size > 1 && reshuffled.first().mediaId == avoidId) {
                        val swapWith = reshuffled.indexOfFirst { it.mediaId != avoidId }
                            .takeIf { it > 0 } ?: 1
                        val tmp = reshuffled[0]
                        reshuffled[0] = reshuffled[swapWith]
                        reshuffled[swapWith] = tmp
                    }
                    _shufflePlayOrder.value = null
                    _queue.value = reshuffled
                    suppressShuffleWrapDetection = true
                    try {
                        reloadPlayerTimeline(
                            reshuffled,
                            0,
                            0L,
                            startPlaying = controller?.playWhenReady == true
                        )
                        setCurrentItem(reshuffled[0], persistLastPlayed = false)
                        lastMediaItemIndex = 0
                    } finally {
                        suppressShuffleWrapDetection = false
                    }
                    persistPlaybackSession(force = true)
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
            setCurrentItem(item, persistLastPlayed = false)
        }
    }

    private fun ensureRemoteReadyAt(index: Int, startPlaying: Boolean = true) {
        val item = _queue.value.getOrNull(index) as? PlayableItem.Remote ?: return
        if (!remoteNeedsResolve(item)) return
        resolvingTransitionJob?.cancel()
        resolvingTransitionJob = viewModelScope.launch {
            _resolvingRemote.value = true
            try {
                val resolvedItem = resolveRemote(item)
                if (resolvedItem == null) {
                    if (startPlaying) {
                        recoverAfterUnplayable("No se pudo resolver el audio online")
                    }
                    return@launch
                }
                consecutiveUnplayableSkips = 0
                updateQueueItem(index, resolvedItem)
                mediaController?.replaceMediaItem(index, playableToMediaItem(resolvedItem))
                mediaController?.prepare()
                if (startPlaying) {
                    mediaController?.play()
                }
            } finally {
                _resolvingRemote.value = false
            }
        }
    }

    /**
     * After a track cannot play: advance if possible, else pause and optionally start radio.
     * Shared by local errors, remote resolve failures, and exhausted HTTP retries.
     */
    private fun recoverAfterUnplayable(userMessage: String? = null) {
        userMessage?.let { toast(it) }
        val controller = mediaController ?: return
        if (controller.hasNextMediaItem()) {
            consecutiveUnplayableSkips++
            if (consecutiveUnplayableSkips >= MAX_CONSECUTIVE_UNPLAYABLE_SKIPS) {
                consecutiveUnplayableSkips = 0
                controller.pause()
                toast("Varias canciones no se pudieron reproducir; se pausó la cola")
                return
            }
            controller.seekToNextMediaItem()
            return
        }
        consecutiveUnplayableSkips = 0
        controller.pause()
        maybeAutoStartRadioOnQueueEnd()
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
        val queued = _queue.value.getOrNull(index)
        val item = queued as? PlayableItem.Remote
        if (item == null) {
            val title = (queued as? PlayableItem.Local)?.song?.title?.takeIf { it.isNotBlank() }
            recoverAfterUnplayable(
                if (title != null) "No se pudo reproducir «$title»"
                else "No se pudo reproducir"
            )
            return
        }

        val isHttpFailure = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED

        if (!isHttpFailure) {
            recoverAfterUnplayable()
            return
        }

        if (remoteErrorRetryUsed) {
            remoteErrorRetryUsed = false
            recoverAfterUnplayable()
            return
        }

        remoteErrorRetryUsed = true
        viewModelScope.launch {
            _resolvingRemote.value = true
            try {
                item.resolved?.videoId?.let { streamResolver.invalidate(it) }
                val refreshed = resolveRemote(item.copy(resolved = null))
                if (refreshed == null) {
                    recoverAfterUnplayable()
                    return@launch
                }
                consecutiveUnplayableSkips = 0
                updateQueueItem(index, refreshed)
                mediaController?.replaceMediaItem(index, playableToMediaItem(refreshed))
                mediaController?.prepare()
                mediaController?.play()
            } finally {
                _resolvingRemote.value = false
            }
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
                                        updateQueueItem(idx, curr.withIdentity { copy(durationMs = dur) })
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

        viewModelScope.launch {
            val result = enqueueRemoteDownload(remote, ActiveDownloadSource.SAVE_WHILE_LISTENING)
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
        return audioStore.playableUri(uriStr.trim())
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



    fun playSong(
        song: Song,
        playlistOrQueue: List<Song> = emptyList(),
        applyManualModes: Boolean = true
    ) {
        _catalogPreviewKey.value = null
        val baseList = if (playlistOrQueue.isNotEmpty()) playlistOrQueue else songsState.value
        val indexInBase = baseList.indexOfFirst { it.id == song.id || it.uriString == song.uriString }

        val targetQueue = if (indexInBase != -1) baseList else listOf(song)
        val index = if (indexInBase != -1) indexInBase else 0
        playPlayableCollection(targetQueue.toPlayableItems(), index, applyManualModes = applyManualModes)
    }

    fun playPlayableCollection(
        items: List<PlayableItem>,
        startIndex: Int = 0,
        fromRadio: Boolean = false,
        rotate: Boolean = true,
        applyManualModes: Boolean = true,
        startShuffled: Boolean = false,
        origin: DiscoverPlaybackOrigin = DiscoverPlaybackOrigin.None,
        resumeAtMs: Long? = null
    ) {
        if (items.isEmpty()) return
        _discoverPlaybackOrigin.value = origin
        if (!fromRadio) clearRadioSession()
        val validIndex = startIndex.coerceIn(0, items.size - 1)
        val shouldRotate = rotate && !fromRadio && validIndex > 0
        val ordered = if (shouldRotate) {
            PlaybackQueueOrder.rotateToStart(items, validIndex)
        } else {
            items
        }
        val startAt = if (shouldRotate) 0 else validIndex
        val shouldApplyManualModes = applyManualModes && !fromRadio
        // Shuffle / new collection ignore stale UI position unless caller opts in (idle resume).
        val resumePosition = if (startShuffled) null else resumeAtMs?.takeIf { it > 0L }
        playCollectionJob?.cancel()
        playCollectionJob = viewModelScope.launch {
            var working = ordered.toMutableList()
            val startItem = working[startAt]
            if (startItem is PlayableItem.Remote && remoteNeedsResolve(startItem)) {
                _resolvingRemote.value = true
                try {
                    val resolved = resolveRemote(startItem)
                    if (resolved == null) {
                        // Try next items (rest of wrapped circle after rotate)
                        val nextRemote = working.withIndex()
                            .filter { it.index != startAt && it.value is PlayableItem.Remote }
                        var played = false
                        for ((idx, remote) in nextRemote) {
                            val r = resolveRemote(remote as PlayableItem.Remote) ?: continue
                            working[idx] = r
                            finishPlayPlayableCollection(
                                working,
                                idx,
                                shouldApplyManualModes,
                                fromRadio,
                                startShuffled,
                                resumePosition
                            )
                            played = true
                            break
                        }
                        if (!played) return@launch
                        return@launch
                    }
                    working[startAt] = resolved
                } finally {
                    _resolvingRemote.value = false
                }
            }
            finishPlayPlayableCollection(
                working,
                startAt,
                shouldApplyManualModes,
                fromRadio,
                startShuffled,
                resumePosition
            )
            prefetchAround(startAt)
            if (fromRadio && _radioActive.value) {
                maybeRefillRadio(startAt)
            }
        }
    }

    private fun finishPlayPlayableCollection(
        items: List<PlayableItem>,
        index: Int,
        applyManualModes: Boolean,
        fromRadio: Boolean,
        startShuffled: Boolean = false,
        resumeAtMs: Long? = null
    ) {
        val (playItems, playIndex) = if (startShuffled) {
            permuteQueueToPlayOrder(items, index, backupSource = true)
        } else {
            if (!fromRadio && (applyManualModes || !_isShuffle.value)) {
                preShuffleQueueBackup = null
            }
            items to index
        }
        _queue.value = playItems
        lastMediaItemIndex = playIndex
        when {
            startShuffled -> {
                setShuffleEnabled(true)
                val (_, nextRepeat) = PlaybackModeClear.afterManualPlay(
                    shuffle = true,
                    repeat = _repeatMode.value,
                    settings = playbackSettings.value
                )
                if (nextRepeat != _repeatMode.value) setRepeatMode(nextRepeat)
            }
            applyManualModes -> applyManualPlayModes()
            fromRadio -> setShuffleEnabled(false)
        }
        setCurrentItem(playItems[playIndex], persistLastPlayed = false)
        remoteErrorRetryUsed = false
        liveSessionHydrated = true
        idleSeedDone = true
        bumpQueueFocus()

        val startPositionMs = when {
            startShuffled -> 0L
            else -> resumeAtMs?.coerceAtLeast(0L) ?: 0L
        }
        setPlaybackPositionUi(startPositionMs)

        val wasPlaying = true // collection play always starts
        reloadPlayerTimeline(playItems, playIndex, startPositionMs, startPlaying = wasPlaying)
        // Timeline reload triggers onMediaItemTransition → stamp once; if no controller, stamp here.
        if (mediaController == null) {
            maybeTouchSongLastPlayed((playItems.getOrNull(playIndex) as? PlayableItem.Local)?.song)
            persistPlaybackSession(force = true)
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
            identity = track.identity,
            youtubeQueryOrId = queryOrId
        )
        playPlayableCollection(listOf(remote), 0)
    }

    /** Preview local file while reviewing identify candidates (toggle if already current). */
    fun previewIdentifyLocalSong(song: Song) {
        val current = _currentItem.value
        if (current is PlayableItem.Local && current.song.id == song.id) {
            togglePlayPause()
            return
        }
        playSong(song)
    }

    /** Stream-preview a ranked identify candidate via YouTube (same path as catalog). */
    fun previewIdentifyCandidate(candidate: IdentifyCandidate) {
        playOnlineCatalogTrackAsStream(candidate.track)
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
            saveAlbumOverride(
                AlbumOverride(
                    albumKey = albumName,
                    displayName = existing?.displayName ?: albumName,
                    artist = existing?.artist,
                    genre = existing?.genre,
                    year = existing?.year ?: 0,
                    artworkUri = artworkUri
                ),
                propagateToSongs = true
            )
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
            saveAlbumOverride(
                AlbumOverride(
                    albumKey = source.name,
                    displayName = normalizedName,
                    artist = artist.takeIf { it.isNotBlank() },
                    genre = genre.takeIf { it.isNotBlank() },
                    year = year.coerceAtLeast(0),
                    artworkUri = artworkUri
                ),
                propagateToSongs = propagateToSongs
            )
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

    /** Persist [override]; [propagateToSongs] chooses songs bulk-update vs override-only. */
    private suspend fun saveAlbumOverride(
        override: AlbumOverride,
        propagateToSongs: Boolean
    ) {
        if (propagateToSongs) repository.updateAlbumMetadataPropagateToSongs(override)
        else repository.upsertAlbumOverride(override)
    }

    fun mergeAlbumInto(sourceAlbumKey: String, targetAlbumKey: String) {
        viewModelScope.launch {
            repository.mergeAlbumInto(sourceAlbumKey, targetAlbumKey)
            toast("Álbumes unidos")
        }
    }

    fun shuffleCollection(songs: List<Song>) {
        shufflePlayableCollection(songs.toPlayableItems())
    }

    private fun shufflePlayableCollection(
        items: List<PlayableItem>,
        origin: DiscoverPlaybackOrigin = DiscoverPlaybackOrigin.None
    ): Boolean {
        if (items.isEmpty()) return false
        playPlayableCollection(
            items,
            startIndex = items.indices.random(),
            rotate = false,
            applyManualModes = false,
            startShuffled = true,
            origin = origin
        )
        return true
    }

    fun enqueueCollection(songs: List<Song>) {
        addToQueueBatch(songs)
    }

    private fun playWithForegroundService(controller: MediaController) {
        // Media3 promotes MusicService to mediaPlayback FGS from onUpdateNotification
        // when play() runs while the Activity is visible. Do not call
        // startForegroundService here: a second start from cached uidState fails on
        // Android 12+ and Motorola demotes the existing FGS.
        controller.play()
    }

    fun togglePlayPause() {
        val controller = mediaController
        val current = _currentItem.value
        val queue = _queue.value
        val needsResolve = current is PlayableItem.Remote && remoteNeedsResolve(current)
        if (controller != null && controller.mediaItemCount > 0 && !needsResolve) {
            if (controller.isPlaying) {
                controller.pause()
            } else {
                playWithForegroundService(controller)
            }
            return
        }

        if (current == null) return
        val resumeMs = _playbackPositionMs.value.takeIf { it > 0L }
        if (queue.isNotEmpty()) {
            val index = queue.indexOfFirst { it.mediaId == current.mediaId }
                .takeIf { it >= 0 }
                ?: lastMediaItemIndex.coerceIn(0, queue.lastIndex)
            playPlayableCollection(
                queue,
                index,
                rotate = false,
                applyManualModes = false,
                resumeAtMs = resumeMs
            )
            return
        }
        when (current) {
            is PlayableItem.Local -> playPlayableCollection(
                listOf(current),
                0,
                applyManualModes = false,
                resumeAtMs = resumeMs
            )
            is PlayableItem.Remote -> playPlayableCollection(
                listOf(current),
                0,
                applyManualModes = false,
                resumeAtMs = resumeMs
            )
        }
    }

    fun skipToNext() {
        bumpQueueFocus()
        applySkipModes()
        mediaController?.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        bumpQueueFocus()
        applySkipModes()
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
        setRepeatMode(nextMode)
    }

    fun toggleShuffle() {
        val enabling = !_isShuffle.value
        val queue = _queue.value
        val pos = mediaController?.currentPosition?.coerceAtLeast(0L)
            ?: _playbackPositionMs.value.coerceAtLeast(0L)
        val wasPlaying = mediaController?.isPlaying == true || _isPlaying.value
        if (enabling) {
            setShuffleEnabled(true)
            if (queue.isNotEmpty()) {
                val (shuffled, startIdx) = permuteQueueToPlayOrder(
                    queue,
                    currentQueueIndex(),
                    backupSource = true
                )
                applyQueueReorder(shuffled, startIdx, pos, startPlaying = wasPlaying)
            } else {
                syncShuffleToPlayer()
            }
        } else {
            val backup = preShuffleQueueBackup
            val currentId = _currentItem.value?.mediaId
            setShuffleEnabled(false)
            _shufflePlayOrder.value = null
            if (backup != null && backup.isNotEmpty() && currentId != null) {
                val idx = backup.indexOfFirst { it.mediaId == currentId }
                    .takeIf { it >= 0 } ?: 0
                applyQueueReorder(backup, idx, pos, startPlaying = wasPlaying)
            } else {
                syncShuffleToPlayer()
            }
            preShuffleQueueBackup = null
        }
        bumpQueueFocus()
        persistPlaybackSession(force = true)
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
        val firstNew = currentList.size
        currentList.addAll(items)
        _queue.value = currentList
        mediaController?.let { controller ->
            controller.addMediaItems(items.map { playableToMediaItem(it) })
        }
        updateShufflePlayOrder { prev ->
            PlaybackQueueOrder.appendToPlayOrder(prev, firstNew, items.size)
        }
        persistPlaybackSession(force = true)
    }

    fun setRadioPreferredMode(mode: RadioMode) {
        radioPreferredMode = mode
    }

    private fun resolvePreferredRadioMode(
        mode: RadioMode?,
        networkOnline: Boolean = connectivityObserver.isCurrentlyOnline()
    ): RadioMode = when {
        mode != null -> mode
        radioPreferredMode != null -> radioPreferredMode!!
        networkOnline -> RadioMode.BOTH
        else -> RadioMode.KNOWN
    }

    /**
     * Multi-select → similares preview (does **not** mutate the playback queue / radio session).
     */
    fun previewSimilarFromSelection(songs: List<Song>, mode: RadioMode? = null) {
        val seeds = songs
            .asSequence()
            .map { it.toPlayable() }
            .filter { it.artist.isNotBlank() && it.title.isNotBlank() }
            .take(RadioEngine.MAX_SEEDS)
            .toList()
        if (seeds.isEmpty()) {
            toastRadioNeedsSeed()
            return
        }
        val networkOnline = connectivityObserver.isCurrentlyOnline()
        val resolvedMode = resolvePreferredRadioMode(mode, networkOnline)
        if (mode != null) {
            setRadioPreferredMode(mode)
        }
        similarPreviewSeeds = seeds
        val name = BuildSimilarPlaylistPreviewUseCase.defaultPlaylistName(seeds)
        _similarPlaylistPreview.value = SimilarPlaylistPreviewState(
            items = emptyList(),
            selectedKeys = emptySet(),
            mode = resolvedMode,
            loading = true,
            seedCount = seeds.size,
            playlistName = name
        )
        runSimilarPreview(resolvedMode)
    }

    fun dismissSimilarPreview() {
        similarPreviewJob?.cancel()
        similarPreviewJob = null
        similarPreviewSeeds = emptyList()
        _similarPlaylistPreview.value = null
    }

    fun toggleSimilarPreviewItem(key: String) {
        val state = _similarPlaylistPreview.value ?: return
        if (state.loading || key.isBlank()) return
        val next = state.selectedKeys.toMutableSet()
        if (!next.add(key)) next.remove(key)
        _similarPlaylistPreview.value = state.copy(selectedKeys = next)
    }

    fun setSimilarPreviewMode(mode: RadioMode) {
        val state = _similarPlaylistPreview.value ?: return
        if (state.mode == mode && !state.loading) return
        setRadioPreferredMode(mode)
        _similarPlaylistPreview.value = state.copy(mode = mode, loading = true)
        runSimilarPreview(mode)
    }

    fun setSimilarPreviewPlaylistName(name: String) {
        val state = _similarPlaylistPreview.value ?: return
        _similarPlaylistPreview.value = state.copy(playlistName = name)
    }

    fun confirmSimilarPreviewAsPlaylist(
        name: String? = null,
        downloadMissing: Boolean = false
    ) {
        val state = _similarPlaylistPreview.value ?: return
        if (state.loading) return
        val selected = state.selectedItems
        if (selected.isEmpty()) {
            toast("Seleccioná al menos una canción")
            return
        }
        val playlistName = (name ?: state.playlistName).ifBlank {
            BuildSimilarPlaylistPreviewUseCase.defaultPlaylistName(similarPreviewSeeds)
        }
        viewModelScope.launch {
            val playlistId = buildSimilarPlaylistPreviewUseCase.createPlaylistFromPlayables(
                name = playlistName,
                items = selected
            )
            if (playlistId == null) {
                toast("No se pudo crear la playlist")
                return@launch
            }
            val localCount = selected.count { it is PlayableItem.Local }
            val pendingCount = selected.count { it is PlayableItem.Remote }
            toastPlaylistSaved(localCount, pending = pendingCount)
            dismissSimilarPreview()
            setSelectedNavIndex(NAV_PLAYLISTS)
            openLocalPlaylist(playlistId)
            if (downloadMissing && pendingCount > 0) {
                downloadPlaylistPendingTracks(playlistId)
            }
        }
    }

    fun playSimilarPreview() {
        val state = _similarPlaylistPreview.value ?: return
        if (state.loading) return
        val selected = state.selectedItems
        if (selected.isEmpty()) {
            toast("Seleccioná al menos una canción")
            return
        }
        dismissSimilarPreview()
        playPlayableCollection(selected, startIndex = 0, rotate = false)
    }

    fun enqueueSimilarPreview() {
        val state = _similarPlaylistPreview.value ?: return
        if (state.loading) return
        val selected = state.selectedItems
        if (selected.isEmpty()) {
            toast("Seleccioná al menos una canción")
            return
        }
        addPlayableBatch(selected)
        toast("Agregadas a la cola (${selected.size})")
        dismissSimilarPreview()
    }

    private fun runSimilarPreview(mode: RadioMode) {
        val seeds = similarPreviewSeeds
        if (seeds.isEmpty()) return
        similarPreviewJob?.cancel()
        similarPreviewJob = viewModelScope.launch {
            val settings = listenBrainzSettings.value
            val networkOnline = connectivityObserver.isCurrentlyOnline()
            val canUseLb = settings.enabled &&
                settings.userToken.isNotBlank() &&
                networkOnline
            val library = rawSongs.first()
            val preview = buildSimilarPlaylistPreviewUseCase.execute(
                seeds = seeds,
                library = library,
                mode = mode,
                lbToken = settings.userToken.takeIf { it.isNotBlank() },
                lbAvailable = canUseLb,
                lbUsername = settings.username,
                networkAvailable = networkOnline
            )
            val current = _similarPlaylistPreview.value
            if (current == null) return@launch
            if (preview.items.isEmpty()) {
                val emptyMsg = when {
                    mode == RadioMode.NEW || preview.failedOnline ->
                        "Radio online no disponible"
                    else -> "No encontré canciones parecidas"
                }
                toast(emptyMsg)
            }
            _similarPlaylistPreview.value = current.copy(
                items = preview.items,
                selectedKeys = SimilarPlaylistPreviewState.keysOf(preview.items),
                mode = mode,
                loading = false,
                usedOnline = preview.usedOnline,
                failedOnline = preview.failedOnline
            )
        }
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

        val resolvedMode = resolvePreferredRadioMode(mode, networkOnline)

        val keepCurrentPlaying = !auto && shouldKeepCurrentWhenStartingRadio()
        val toastMode = announceMode

        viewModelScope.launch {
            _radioLoading.value = true
            try {
                val library = rawSongs.first()
                val exclude = buildRadioExcludeKeys(
                    seed = seed,
                    includeQueue = !keepCurrentPlaying,
                    extra = _currentItem.value
                )

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
                    clearDiscoverPlaybackOrigin()
                    replaceUpcomingWithRadio(suggestions)
                    toast("Se agregaron canciones de la radio a la cola")
                    val idx = mediaController?.currentMediaItemIndex ?: lastMediaItemIndex
                    if (idx >= 0) prefetchAround(idx)
                } else {
                    playPlayableCollection(suggestions, startIndex = 0, fromRadio = true, rotate = false)
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
            playPlayableCollection(suggestions, startIndex = 0, fromRadio = true, rotate = false)
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
        persistPlaybackSession(force = true)
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
        val key = TrackMatchKeys.matchKey(item.artist, item.title)
        if (key.isNotEmpty()) playedInRadioSession.add(key)
        playedInRadioSession.add(item.mediaId)
    }

    private fun buildRadioExcludeKeys(
        seed: PlayableItem,
        includeQueue: Boolean = true,
        extra: PlayableItem? = null
    ): MutableSet<String> {
        val exclude = playedInRadioSession.toMutableSet()
        fun remember(item: PlayableItem) {
            val key = TrackMatchKeys.matchKey(item.artist, item.title)
            if (key.isNotEmpty()) exclude.add(key)
            exclude.add(item.mediaId)
        }
        remember(seed)
        extra?.let { remember(it) }
        if (includeQueue) {
            for (item in _queue.value) remember(item)
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
        updateShufflePlayOrder { prev ->
            PlaybackQueueOrder.insertAfterCurrent(prev, currentIndex, insertIndex, playables.size)
        }
        persistPlaybackSession(force = true)
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

    suspend fun playlistsContainingSong(songId: Long): List<Playlist> {
        val ids = repository.getPlaylistIdsForSong(songId).toSet()
        if (ids.isEmpty()) return emptyList()
        return repository.playlistsFlow.first().filter { it.id in ids }
    }

    fun deleteSongsFromApp(songs: List<Song>) {
        viewModelScope.launch {
            repository.deleteSongsFromApp(songs)
            pruneIdentifyReview(songs.map { it.id }.toSet())
        }
    }

    fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int = 0,
        trackNumber: Int = 0
    ) {
        viewModelScope.launch {
            repository.updateSongMetadata(songId, title, artist, album, genre, year, trackNumber)
        }
    }

    fun deleteSongsFromDevice(songs: List<Song>) {
        viewModelScope.launch {
            repository.deleteSongsFromDevice(songs)
            pruneIdentifyReview(songs.map { it.id }.toSet())
        }
    }

    fun removeFromQueue(index: Int) {
        val timelineIndex = displayToTimelineIndex(index)
        if (timelineIndex in _queue.value.indices) {
            val currentList = _queue.value.toMutableList()
            currentList.removeAt(timelineIndex)
            _queue.value = currentList
            mediaController?.removeMediaItem(timelineIndex)
            updateShufflePlayOrder { prev ->
                PlaybackQueueOrder.removeFromPlayOrder(prev, timelineIndex)
            }
            persistPlaybackSession(force = true)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val list = _queue.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices && fromIndex != toIndex) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _queue.value = list
            mediaController?.moveMediaItem(fromIndex, toIndex)
            persistPlaybackSession(force = true)
        }
    }

    fun moveDisplayQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val order = _shufflePlayOrder.value
        if (_isShuffle.value && order != null) {
            if (fromIndex !in order.indices || toIndex !in order.indices) return
            _shufflePlayOrder.value = PlaybackQueueOrder.moveInPlayOrder(order, fromIndex, toIndex)
            // Legacy index map only — physical queue mode uses moveQueueItem below.
            persistPlaybackSession(force = true)
        } else {
            moveQueueItem(fromIndex, toIndex)
        }
    }

    fun skipToQueueIndex(index: Int) {
        val timelineIndex = displayToTimelineIndex(index)
        if (timelineIndex in _queue.value.indices) {
            bumpQueueFocus()
            lastMediaItemIndex = timelineIndex
            setPlaybackPositionUi(0L)
            val item = _queue.value[timelineIndex]
            if (item is PlayableItem.Remote && remoteNeedsResolve(item)) {
                viewModelScope.launch {
                    _resolvingRemote.value = true
                    try {
                        val resolved = resolveRemote(item)
                        if (resolved == null) {
                            mediaController?.seekToNextMediaItem()
                            return@launch
                        }
                        updateQueueItem(timelineIndex, resolved)
                        mediaController?.replaceMediaItem(timelineIndex, playableToMediaItem(resolved))
                        mediaController?.seekTo(timelineIndex, 0L)
                        mediaController?.prepare()
                        mediaController?.play()
                        prefetchAround(timelineIndex)
                    } finally {
                        _resolvingRemote.value = false
                    }
                }
            } else {
                mediaController?.seekTo(timelineIndex, 0L)
                prefetchAround(timelineIndex)
            }
            persistPlaybackSession(force = true)
        }
    }


    // Search and Sort
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOption(option: SortOption) {
        if (_sortOption.value == option) return
        _sortOption.value = option
        _sortDirection.value = SortDirection.defaultFor(option)
        viewModelScope.launch { libraryPreferences.setSortOptionName(option.name) }
    }

    fun setSortDirection(direction: SortDirection) {
        if (_sortDirection.value == direction) return
        _sortDirection.value = direction
        viewModelScope.launch {
            libraryPreferences.setSortDirectionName(direction.name, _sortOption.value.name)
        }
    }

    fun toggleSortDirection() {
        setSortDirection(
            if (_sortDirection.value == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
        )
    }

    fun setLibraryViewMode(mode: LibraryViewMode) {
        if (_libraryViewMode.value == mode) return
        _libraryViewMode.value = mode
        viewModelScope.launch { libraryPreferences.setViewModeName(mode.name) }
    }

    fun toggleLibraryViewMode() {
        val next = if (_libraryViewMode.value == LibraryViewMode.ALBUM_GROUPS) {
            LibraryViewMode.FLAT
        } else {
            LibraryViewMode.ALBUM_GROUPS
        }
        setLibraryViewMode(next)
    }

    fun setSelectedNavIndex(index: Int, persist: Boolean = true) {
        val sanitized = LibraryUiPreferencesCodec.sanitizeNavIndex(index)
        if (_selectedNavIndex.value == sanitized) {
            if (sanitized == NAV_PLAYLISTS) maybeRestoreDiscoverDetail()
            return
        }
        _selectedNavIndex.value = sanitized
        if (persist) persistNavSnapshot()
        if (sanitized == NAV_PLAYLISTS) maybeRestoreDiscoverDetail()
    }

    fun openDownloadsTabTransient() {
        _selectedNavIndex.value = NAV_DOWNLOADS
    }

    fun setLibraryBrowseFilter(filter: LibraryBrowseFilter) {
        if (_libraryBrowseFilter.value == filter) return
        _libraryBrowseFilter.value = filter
        persistNavSnapshot()
    }

    fun openLibraryAlbum(name: String, fromNestedParent: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (!fromNestedParent) {
            _libraryArtistName.value = null
            _libraryGenreName.value = null
        }
        _libraryAlbumName.value = trimmed
        persistNavSnapshot()
    }

    fun openLibraryArtist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _libraryArtistName.value = trimmed
        _libraryAlbumName.value = null
        _libraryGenreName.value = null
        persistNavSnapshot()
    }

    fun openLibraryGenre(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _libraryGenreName.value = trimmed
        _libraryAlbumName.value = null
        _libraryArtistName.value = null
        persistNavSnapshot()
    }

    fun closeLibraryAlbum() {
        if (_libraryAlbumName.value == null) return
        _libraryAlbumName.value = null
        persistNavSnapshot()
    }

    fun closeLibraryArtist() {
        if (_libraryArtistName.value == null && _libraryAlbumName.value == null) return
        _libraryArtistName.value = null
        _libraryAlbumName.value = null
        persistNavSnapshot()
    }

    fun closeLibraryGenre() {
        if (_libraryGenreName.value == null && _libraryAlbumName.value == null) return
        _libraryGenreName.value = null
        _libraryAlbumName.value = null
        persistNavSnapshot()
    }

    fun popLibraryNested() {
        when {
            _libraryAlbumName.value != null -> closeLibraryAlbum()
            _libraryArtistName.value != null -> closeLibraryArtist()
            _libraryGenreName.value != null -> closeLibraryGenre()
        }
    }

    fun renameRestoredLibraryAlbum(sourceKey: String, targetKey: String) {
        if (_libraryAlbumName.value.equals(sourceKey, ignoreCase = true)) {
            _libraryAlbumName.value = targetKey
            persistNavSnapshot()
        }
    }

    fun openLocalPlaylist(id: Long) {
        closeDiscoverSessionUi()
        _playlistDetail.value = PlaylistDetailNav.Local(id)
        persistNavSnapshot()
    }

    fun openListenBrainzPlaylistDetail(mbid: String) {
        closeDiscoverSessionUi()
        _playlistDetail.value = PlaylistDetailNav.ListenBrainz(mbid)
        persistNavSnapshot()
        openListenBrainzPlaylist(mbid)
    }

    fun openCfRecommendationsDetail() {
        closeDiscoverSessionUi()
        _playlistDetail.value = PlaylistDetailNav.CfRecommendations
        persistNavSnapshot()
        openCfRecommendations()
    }

    fun closePlaylistDetail() {
        closeDiscoverSessionUi()
        if (_playlistDetail.value is PlaylistDetailNav.None) return
        _playlistDetail.value = PlaylistDetailNav.None
        persistNavSnapshot()
    }

    fun dismissDiscoverDetails() {
        val detail = _playlistDetail.value
        if (detail is PlaylistDetailNav.ListenBrainz || detail is PlaylistDetailNav.CfRecommendations) {
            closePlaylistDetail()
        } else {
            closeDiscoverSessionUi()
        }
    }

    private fun closeDiscoverSessionUi() {
        closeListenBrainzPlaylist()
        closeCfRecommendations()
    }

    private suspend fun hydrateUiPreferences() {
        val display = libraryPreferences.displaySettingsFlow.first()
        _sortOption.value = parseSortOption(display.sortOptionName)
        _sortDirection.value = parseSortDirection(display.sortDirectionName)
        _libraryViewMode.value = parseLibraryViewMode(display.viewModeName)

        val nav = libraryPreferences.navSnapshotFlow.first()
        applyNavSnapshot(nav)
        uiPrefsHydrated = true

        pruneRestoredLibraryStack()
        pruneRestoredLocalPlaylist()
        if (_selectedNavIndex.value == NAV_PLAYLISTS) {
            restoreDiscoverDetailOrFallback()
        }
    }

    private fun applyNavSnapshot(nav: UiNavSnapshot) {
        _selectedNavIndex.value = nav.navIndex
        _libraryBrowseFilter.value = parseLibraryBrowseFilter(nav.browseFilterName)
        _libraryArtistName.value = nav.libraryArtistName
        _libraryAlbumName.value = nav.libraryAlbumName
        _libraryGenreName.value = nav.libraryGenreName
        _playlistDetail.value = PlaylistDetailNav.fromSnapshot(nav)
    }

    private fun persistNavSnapshot() {
        if (!uiPrefsHydrated) return
        val detail = _playlistDetail.value
        val snapshot = UiNavSnapshot(
            navIndex = _selectedNavIndex.value,
            browseFilterName = _libraryBrowseFilter.value.name,
            libraryArtistName = _libraryArtistName.value,
            libraryAlbumName = _libraryAlbumName.value,
            libraryGenreName = _libraryGenreName.value,
            playlistDetailKind = detail.kindName(),
            playlistLocalId = detail.localIdOrNull(),
            playlistLbMbid = detail.lbMbidOrNull()
        )
        viewModelScope.launch { libraryPreferences.setNavSnapshot(snapshot) }
    }

    private suspend fun pruneRestoredLibraryStack() {
        val songs = rawSongs.first()
        val pruned = LibraryUiPreferencesCodec.pruneLibraryStack(
            albumName = _libraryAlbumName.value,
            artistName = _libraryArtistName.value,
            genreName = _libraryGenreName.value,
            albumExists = { name -> songs.any { it.album.equals(name, ignoreCase = true) } },
            artistExists = { name -> songs.any { it.artist.equals(name, ignoreCase = true) } },
            genreExists = { name ->
                songs.any {
                    com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase.genreKey(it)
                        .equals(name, ignoreCase = true)
                }
            }
        )
        if (pruned.albumName != _libraryAlbumName.value ||
            pruned.artistName != _libraryArtistName.value ||
            pruned.genreName != _libraryGenreName.value
        ) {
            _libraryAlbumName.value = pruned.albumName
            _libraryArtistName.value = pruned.artistName
            _libraryGenreName.value = pruned.genreName
            persistNavSnapshot()
        }
    }

    private suspend fun pruneRestoredLocalPlaylist() {
        val detail = _playlistDetail.value as? PlaylistDetailNav.Local ?: return
        val lists = playlists.first()
        if (lists.none { it.id == detail.id }) {
            _playlistDetail.value = PlaylistDetailNav.None
            persistNavSnapshot()
        }
    }

    private fun maybeRestoreDiscoverDetail() {
        val detail = _playlistDetail.value
        if (detail !is PlaylistDetailNav.ListenBrainz && detail !is PlaylistDetailNav.CfRecommendations) {
            return
        }
        val needsFetch = when (detail) {
            is PlaylistDetailNav.ListenBrainz ->
                _selectedLbPlaylist.value == null &&
                    _lbPlaylistDetailState.value !is LbPlaylistDetailUiState.Loading
            PlaylistDetailNav.CfRecommendations ->
                _cfRecommendations.value == null &&
                    _cfListState.value !is CfRecommendationsUiState.Loading
            else -> false
        }
        if (!needsFetch) return
        viewModelScope.launch { restoreDiscoverDetailOrFallback() }
    }

    private suspend fun restoreDiscoverDetailOrFallback() {
        when (val detail = _playlistDetail.value) {
            is PlaylistDetailNav.ListenBrainz -> {
                val ok = loadListenBrainzPlaylist(detail.mbid, forRestore = true)
                if (!ok) fallbackDiscoverRestore(announce = true)
            }
            PlaylistDetailNav.CfRecommendations -> {
                val ok = loadCfRecommendationsForRestore()
                if (!ok) fallbackDiscoverRestore(announce = true)
            }
            else -> Unit
        }
    }

    private fun fallbackDiscoverRestore(announce: Boolean) {
        closePlaylistDetail()
        if (announce) toast("No se pudo abrir la playlist")
    }

    private fun parseSortOption(name: String): SortOption =
        SortOption.entries.find { it.name == name } ?: SortOption.TITLE

    private fun parseSortDirection(name: String): SortDirection =
        SortDirection.entries.find { it.name == name } ?: SortDirection.ASC

    private fun parseLibraryViewMode(name: String): LibraryViewMode =
        LibraryViewMode.entries.find { it.name == name } ?: LibraryViewMode.ALBUM_GROUPS

    private fun parseLibraryBrowseFilter(name: String): LibraryBrowseFilter =
        LibraryBrowseFilter.entries.find { it.name == name } ?: LibraryBrowseFilter.SONGS


    private fun reportLibraryProgress(
        kind: LibraryJobKind,
        done: Int,
        total: Int,
        label: String
    ) {
        _libraryJobProgress.value = LibraryJobProgress(kind, done, total, label)
    }

    private fun clearLibraryProgress() {
        _libraryJobProgress.value = null
    }

    private fun importScanProgress(): (Int, Int, String) -> Unit = { done, total, fileName ->
        reportLibraryProgress(LibraryJobKind.IMPORT, done, total, fileName)
    }

    // SAF Import
    fun importFolder(treeUri: Uri) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                repository.scanFolderUri(treeUri, importScanProgress())
            }
            clearLibraryProgress()
            toast(
                when {
                    count <= 0 -> "No se encontraron canciones nuevas en esa carpeta"
                    count == 1 -> "1 canción agregada a la biblioteca"
                    else -> "$count canciones agregadas a la biblioteca"
                }
            )
        }
    }

    /**
     * First-install (or post-uninstall) disk import: BestiaPop folder + MediaStore.
     * Skipped on later cold starts / updates once [LibraryPreferencesRepository] marks completed.
     * Room migrations still run independently via [AppDatabase].
     */
    fun ensureInitialLibraryImport(showRecoveryToast: Boolean = false) {
        viewModelScope.launch {
            if (libraryPreferences.isInitialScanCompleted()) return@launch
            if (!hasAudioPermission()) return@launch
            runLibraryDiskImport(showRecoveryToast = showRecoveryToast)
            libraryPreferences.setInitialScanCompleted(true)
        }
    }

    /**
     * Force reindex Music/BestiaPop + MediaStore (manual / recovery). Does not change the
     * initial-scan flag unless [markInitialScanCompleted] is true.
     */
    fun refreshLibraryFromDisk(
        showRecoveryToast: Boolean = false,
        markInitialScanCompleted: Boolean = false
    ) {
        viewModelScope.launch {
            runLibraryDiskImport(showRecoveryToast = showRecoveryToast)
            if (markInitialScanCompleted) {
                libraryPreferences.setInitialScanCompleted(true)
            }
        }
    }

    private suspend fun runLibraryDiskImport(showRecoveryToast: Boolean) {
        val recovered = withContext(Dispatchers.IO) {
            val n = repository.resyncAppManagedMusic(importScanProgress())
            repository.scanMediaStore(importScanProgress())
            n
        }
        clearLibraryProgress()
        if (showRecoveryToast && recovered > 0) {
            toast(
                if (recovered == 1) "Se recuperó 1 canción de Music/BestiaPop"
                else "Se recuperaron $recovered canciones de Music/BestiaPop"
            )
        }
    }

    private fun hasAudioPermission(): Boolean {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(app, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Online identify: Phase 1 lookup + score, auto-apply HIGH,
     * enqueue MEDIUM/LOW/NONE. [force] always looks up (WiFi). [showReview] opens the overlay.
     * Songs already pending review are skipped (no network).
     */
    fun identifySongs(
        songs: List<Song>,
        force: Boolean = false,
        showReview: Boolean = true
    ) {
        if (songs.isEmpty()) return
        viewModelScope.launch {
            identifyMutex.withLock {
                runIdentifySongs(songs, force, showReview)
            }
        }
    }

    /** Single-song identify from ⋮: open existing pending item, or force lookup. */
    fun identifySongForReview(song: Song) {
        val state = _identifyReview.value
        val pendingIndex = state.remaining.indexOfFirst { it.song.id == song.id }
        if (pendingIndex >= 0) {
            val absIndex = state.currentIndex + pendingIndex
            val item = state.items[absIndex]
            _identifyReview.value = state.copy(
                currentIndex = absIndex,
                phase = IdentifyReviewPhase.Item,
                openedFromOverview = state.phase == IdentifyReviewPhase.Overview ||
                    state.openedFromOverview,
                isVisible = true,
                selectedCandidateIndex = 0,
                searchQueryDraft = defaultSearchDraft(item),
                showSearchField = item.proposal.candidates.isEmpty(),
                isSearching = false
            )
            return
        }
        identifySongs(listOf(song), force = true, showReview = true)
    }

    private suspend fun runIdentifySongs(
        songs: List<Song>,
        force: Boolean,
        showReview: Boolean
    ) {
        val pendingIds = _identifyReview.value.pendingSongIds
        val alreadyQueued = songs.count { it.id in pendingIds }
        val toProcess = songs.filter { it.id !in pendingIds }
        if (toProcess.isEmpty()) {
            if (alreadyQueued > 0) {
                toast(
                    if (alreadyQueued == 1) "1 ya está en revisión"
                    else "$alreadyQueued ya están en revisión"
                )
                if (showReview) showIdentifyReview()
            }
            return
        }
        val lbToken = listenBrainzSettings.value
            .takeIf { it.enabled && it.userToken.isNotBlank() }
            ?.userToken
        val total = toProcess.size
        var updated = 0
        var skipped = 0
        var medium = 0
        var low = 0
        var none = 0
        var lbHits = 0
        val reviewItems = ArrayList<IdentifyReviewItem>()
        toProcess.forEachIndexed { index, song ->
            reportLibraryProgress(LibraryJobKind.IDENTIFY, index, total, song.title)
            val proposal = withContext(Dispatchers.IO) {
                repository.proposeSongIdentity(
                    song,
                    force = force,
                    listenBrainzToken = lbToken
                )
            }
            if (proposal.usedListenBrainz) lbHits++
            when {
                proposal.alreadyIdentified -> skipped++
                proposal.confidence == IdentifyConfidence.HIGH && proposal.suggested != null -> {
                    when (
                        withContext(Dispatchers.IO) {
                            repository.applySongIdentity(song.id, proposal.suggested)
                        }
                    ) {
                        is IdentifyResult.Updated -> updated++
                        else -> {
                            reviewItems.add(
                                IdentifyReviewItem(songForIdentifyReview(song, proposal), proposal)
                            )
                            medium++
                        }
                    }
                }
                else -> {
                    reviewItems.add(
                        IdentifyReviewItem(songForIdentifyReview(song, proposal), proposal)
                    )
                    when (proposal.confidence) {
                        IdentifyConfidence.MEDIUM -> medium++
                        IdentifyConfidence.LOW -> low++
                        else -> none++
                    }
                }
            }
        }
        clearLibraryProgress()
        reportIdentifyBatchTelemetry(
            high = updated,
            medium = medium,
            low = low,
            none = none,
            skipped = skipped,
            lbHits = lbHits
        )
        if (reviewItems.isNotEmpty()) {
            enqueueIdentifyReview(reviewItems, showReview)
        } else if (alreadyQueued > 0 && showReview) {
            showIdentifyReview()
        }
        toast(
            buildString {
                append(if (updated == 1) "1 actualizada" else "$updated actualizadas")
                if (reviewItems.isNotEmpty()) {
                    append(
                        if (reviewItems.size == 1) ", 1 para revisar"
                        else ", ${reviewItems.size} para revisar"
                    )
                }
                if (alreadyQueued > 0) {
                    append(
                        if (alreadyQueued == 1) ", 1 ya en revisión"
                        else ", $alreadyQueued ya en revisión"
                    )
                }
                if (skipped == 1) append(", 1 omitida")
                else if (skipped > 1) append(", $skipped omitidas")
            }
        )
    }

    private fun reportIdentifyBatchTelemetry(
        high: Int,
        medium: Int,
        low: Int,
        none: Int,
        skipped: Int,
        lbHits: Int
    ) {
        CrashReporter.setKey("identify_high", "$high")
        CrashReporter.setKey("identify_medium", "$medium")
        CrashReporter.setKey("identify_low", "$low")
        CrashReporter.setKey("identify_none", "$none")
        CrashReporter.setKey("identify_skipped", "$skipped")
        CrashReporter.setKey("identify_lb_hits", "$lbHits")
        CrashReporter.log(
            "identify_batch high=$high medium=$medium low=$low none=$none skipped=$skipped lb_hits=$lbHits"
        )
    }

    private fun enqueueIdentifyReview(items: List<IdentifyReviewItem>, showReview: Boolean) {
        if (items.isEmpty()) return
        val current = _identifyReview.value
        val existingIds = current.pendingSongIds
        val incoming = items.filter { it.song.id !in existingIds }
        if (current.items.isEmpty()) {
            presentIdentifyQueue(incoming, showReview = showReview)
            return
        }
        if (incoming.isEmpty()) {
            if (showReview) showIdentifyReview()
            return
        }
        val merged = current.items + incoming
        val visible = current.isVisible || showReview
        val phase = if (current.isVisible) {
            current.phase
        } else {
            reviewPhaseFor(merged.drop(current.currentIndex))
        }
        _identifyReview.value = current.copy(
            items = merged,
            isVisible = visible,
            phase = phase
        )
    }

    private fun reviewPhaseFor(items: List<IdentifyReviewItem>): IdentifyReviewPhase =
        if (clusterIdentifyAlbumGroups(items.map { it.proposal }).isNotEmpty()) {
            IdentifyReviewPhase.Overview
        } else {
            IdentifyReviewPhase.Item
        }

    private fun presentIdentifyQueue(
        items: List<IdentifyReviewItem>,
        showReview: Boolean,
        sessionApplied: Int = 0,
        sessionSkipped: Int = 0,
        openedFromOverview: Boolean = false
    ) {
        if (items.isEmpty()) {
            _identifyReview.value = IdentifyReviewState()
            clearCatalogPreview()
            return
        }
        val phase = reviewPhaseFor(items)
        val first = items.first()
        _identifyReview.value = IdentifyReviewState(
            items = items,
            currentIndex = 0,
            selectedCandidateIndex = 0,
            sessionApplied = sessionApplied,
            sessionSkipped = sessionSkipped,
            isVisible = showReview,
            phase = phase,
            openedFromOverview = openedFromOverview && phase == IdentifyReviewPhase.Item,
            searchQueryDraft = if (phase == IdentifyReviewPhase.Item) {
                defaultSearchDraft(first)
            } else {
                ""
            },
            showSearchField = phase == IdentifyReviewPhase.Item &&
                first.proposal.candidates.isEmpty()
        )
    }

    fun showIdentifyReview() {
        val state = _identifyReview.value
        if (state.items.isEmpty()) return
        val current = state.current ?: state.items.first()
        val itemPhase = state.phase == IdentifyReviewPhase.Item
        _identifyReview.value = state.copy(
            isVisible = true,
            searchQueryDraft = if (itemPhase && state.searchQueryDraft.isBlank()) {
                defaultSearchDraft(current)
            } else {
                state.searchQueryDraft
            },
            showSearchField = itemPhase &&
                (state.showSearchField || current.proposal.candidates.isEmpty())
        )
    }

    fun startIdentifyItemReview(groupKey: String? = null) {
        val state = _identifyReview.value
        val remaining = state.remaining
        if (remaining.isEmpty()) return
        val reordered = if (groupKey != null) {
            val groupIds = state.albumGroups.find { it.key == groupKey }?.songIds?.toSet()
                ?: return
            remaining.filter { it.song.id in groupIds } +
                remaining.filter { it.song.id !in groupIds }
        } else {
            remainingGroupedFirst(remaining, state.albumGroups)
        }
        val first = reordered.first()
        _identifyReview.value = state.copy(
            items = reordered,
            currentIndex = 0,
            phase = IdentifyReviewPhase.Item,
            openedFromOverview = true,
            isVisible = true,
            selectedCandidateIndex = 0,
            searchQueryDraft = defaultSearchDraft(first),
            showSearchField = first.proposal.candidates.isEmpty(),
            isSearching = false
        )
    }

    fun returnIdentifyReviewOverview() {
        val state = _identifyReview.value
        val remaining = state.remaining
        if (remaining.isEmpty()) {
            _identifyReview.value = IdentifyReviewState()
            clearCatalogPreview()
            return
        }
        val phase = reviewPhaseFor(remaining)
        val first = remaining.first()
        _identifyReview.value = state.copy(
            items = remaining,
            currentIndex = 0,
            phase = phase,
            openedFromOverview = false,
            selectedCandidateIndex = 0,
            isSearching = false,
            searchQueryDraft = if (phase == IdentifyReviewPhase.Item) {
                defaultSearchDraft(first)
            } else {
                ""
            },
            showSearchField = phase == IdentifyReviewPhase.Item &&
                first.proposal.candidates.isEmpty()
        )
    }

    fun applyIdentifyAlbumGroup(key: String) {
        viewModelScope.launch {
            identifyMutex.withLock {
                val state = _identifyReview.value
                val groupIds = state.albumGroups.find { it.key == key }?.songIds?.toSet()
                    ?: return@withLock
                val remaining = state.remaining
                val targets = remaining.filter { it.song.id in groupIds }
                if (targets.isEmpty()) return@withLock
                val appliedIds = applyIdentifyCandidates(targets) { it.proposal.suggested }
                val leftover = remaining.filter { it.song.id !in appliedIds }
                val applied = state.sessionApplied + appliedIds.size
                if (leftover.isEmpty()) {
                    presentIdentifyQueue(emptyList(), showReview = false)
                    toast(
                        if (appliedIds.size == 1) "1 aplicada en revisión"
                        else "${appliedIds.size} aplicadas en revisión"
                    )
                    return@withLock
                }
                presentIdentifyQueue(
                    leftover,
                    showReview = true,
                    sessionApplied = applied,
                    sessionSkipped = state.sessionSkipped
                )
                toast(
                    if (appliedIds.size == 1) "1 aplicada al álbum"
                    else "${appliedIds.size} aplicadas al álbum"
                )
            }
        }
    }

    private fun remainingGroupedFirst(
        remaining: List<IdentifyReviewItem>,
        groups: List<IdentifyAlbumGroup>
    ): List<IdentifyReviewItem> {
        if (groups.isEmpty()) return remaining
        val groupedIds = groups.flatMap { it.songIds }.toSet()
        val grouped = groups.flatMap { group ->
            val ids = group.songIds.toSet()
            remaining.filter { it.song.id in ids }
        }
        val ungrouped = remaining.filter { it.song.id !in groupedIds }
        return grouped + ungrouped
    }

    private fun pruneIdentifyReview(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val state = _identifyReview.value
        if (state.items.none { it.song.id in ids }) return
        val before = state.items.take(state.currentIndex).filter { it.song.id !in ids }
        val after = state.items.drop(state.currentIndex).filter { it.song.id !in ids }
        if (after.isEmpty()) {
            _identifyReview.value = IdentifyReviewState()
            clearCatalogPreview()
            return
        }
        val next = after.first()
        val phase = if (state.phase == IdentifyReviewPhase.Overview) {
            reviewPhaseFor(after)
        } else {
            state.phase
        }
        _identifyReview.value = state.copy(
            items = before + after,
            currentIndex = before.size,
            phase = phase,
            selectedCandidateIndex = 0,
            searchQueryDraft = if (phase == IdentifyReviewPhase.Item) {
                defaultSearchDraft(next)
            } else {
                state.searchQueryDraft
            },
            showSearchField = phase == IdentifyReviewPhase.Item &&
                next.proposal.candidates.isEmpty()
        )
    }

    private suspend fun applyIdentifyCandidates(
        targets: List<IdentifyReviewItem>,
        pick: (IdentifyReviewItem) -> IdentifyCandidate?
    ): Set<Long> {
        val appliedIds = LinkedHashSet<Long>()
        targets.forEachIndexed { index, item ->
            val candidate = pick(item) ?: return@forEachIndexed
            reportLibraryProgress(
                LibraryJobKind.IDENTIFY,
                index,
                targets.size,
                item.song.title
            )
            when (
                withContext(Dispatchers.IO) {
                    repository.applySongIdentity(item.song.id, candidate)
                }
            ) {
                is IdentifyResult.Updated -> appliedIds += item.song.id
                else -> Unit
            }
        }
        clearLibraryProgress()
        return appliedIds
    }

    private fun songForIdentifyReview(song: Song, proposal: IdentifyProposal): Song {
        val qTitle = proposal.queryTitle.trim()
        val qArtist = proposal.queryArtist.trim()
        return song.copy(
            title = qTitle.takeUnless { it.isEmpty() || looksLikeStoragePath(it) } ?: song.title,
            artist = when {
                qArtist.isNotEmpty() && !IdentifyRanking.isPlaceholderArtist(qArtist) -> qArtist
                IdentifyRanking.isPlaceholderArtist(song.artist) || isTrackNumberLabel(song.artist) ->
                    "Unknown Artist"
                else -> song.artist
            }
        )
    }

    private fun defaultSearchDraft(item: IdentifyReviewItem): String {
        val title = item.proposal.queryTitle.trim()
            .takeUnless { it.isBlank() || looksLikeStoragePath(it) }
            ?: item.song.title.trim().takeUnless { it.isBlank() || looksLikeStoragePath(it) }
            .orEmpty()
        val artist = item.proposal.queryArtist.trim().takeUnless {
            it.isBlank() || IdentifyRanking.isPlaceholderArtist(it) || looksLikeStoragePath(it)
        } ?: item.song.artist.trim().takeUnless {
            IdentifyRanking.isPlaceholderArtist(it) || looksLikeStoragePath(it)
        }
        val hints = item.proposal.sourceHints?.replace(" · ", " ")?.trim().orEmpty()
        return when {
            !artist.isNullOrBlank() && title.isNotBlank() -> youtubeSearchQuery(artist, title)
            title.isNotBlank() -> title
            hints.isNotBlank() && !looksLikeStoragePath(hints) -> hints
            else -> ""
        }
    }

    fun selectIdentifyCandidate(index: Int) {
        val state = _identifyReview.value
        val candidates = state.current?.proposal?.candidates.orEmpty()
        if (index !in candidates.indices) return
        _identifyReview.value = state.copy(selectedCandidateIndex = index)
    }

    fun setIdentifySearchDraft(query: String) {
        _identifyReview.value = _identifyReview.value.copy(searchQueryDraft = query)
    }

    fun toggleIdentifySearchField(show: Boolean? = null) {
        val state = _identifyReview.value
        val next = show ?: !state.showSearchField
        val draft = state.searchQueryDraft
        val shouldSeed = next && (draft.isBlank() || looksLikeStoragePath(draft))
        _identifyReview.value = state.copy(
            showSearchField = next,
            searchQueryDraft = if (shouldSeed) {
                state.current?.let { defaultSearchDraft(it) }.orEmpty()
            } else {
                draft
            }
        )
    }

    fun searchIdentifyCandidates() {
        val state = _identifyReview.value
        val item = state.current ?: return
        val query = state.searchQueryDraft.trim()
        if (query.isEmpty()) {
            toast("Escribí una búsqueda")
            return
        }
        viewModelScope.launch {
            _identifyReview.value = _identifyReview.value.copy(isSearching = true)
            val proposal = withContext(Dispatchers.IO) {
                repository.proposeSongIdentity(item.song, customQuery = query, force = true)
            }
            val latest = _identifyReview.value
            if (latest.current?.song?.id != item.song.id) {
                _identifyReview.value = latest.copy(isSearching = false)
                return@launch
            }
            val items = latest.items.toMutableList()
            items[latest.currentIndex] = item.copy(proposal = proposal)
            _identifyReview.value = latest.copy(
                items = items,
                selectedCandidateIndex = 0,
                isSearching = false,
                showSearchField = latest.showSearchField || proposal.candidates.isEmpty()
            )
            if (proposal.candidates.isEmpty()) {
                toast("Sin resultados para \"$query\"")
            }
        }
    }

    fun applySelectedIdentifyCandidate() {
        val state = _identifyReview.value
        val item = state.current ?: return
        val candidate = item.proposal.candidates.getOrNull(state.selectedCandidateIndex)
            ?: item.proposal.suggested
        if (candidate == null) {
            toast("Elegí un candidato o buscá otro")
            return
        }
        viewModelScope.launch {
            when (
                withContext(Dispatchers.IO) {
                    repository.applySongIdentity(item.song.id, candidate)
                }
            ) {
                is IdentifyResult.Updated -> advanceIdentifyReview(applied = true)
                else -> toast("No se pudo aplicar la identidad")
            }
        }
    }

    fun skipIdentifyReviewItem() {
        advanceIdentifyReview(applied = false)
    }

    fun dismissIdentifyReview() {
        val state = _identifyReview.value
        if (!state.isOpen) return
        _identifyReview.value = state.copy(isVisible = false)
        clearCatalogPreview()
    }

    fun skipAllIdentifyReview() {
        val pending = _identifyReview.value.pendingCount
        _identifyReview.value = IdentifyReviewState()
        clearCatalogPreview()
        if (pending > 0) {
            toast(if (pending == 1) "1 omitida" else "$pending omitidas")
        }
    }

    fun applyRemainingIdentifySuggestions() {
        viewModelScope.launch {
            identifyMutex.withLock {
                val state = _identifyReview.value
                val remaining = state.remaining
                if (remaining.isEmpty()) return@withLock
                val applyable = remaining.filter { it.proposal.hasMediumSuggestion }
                if (applyable.isEmpty()) {
                    toast("No hay sugerencias automáticas")
                    return@withLock
                }
                val appliedIds = applyIdentifyCandidates(applyable) { it.proposal.suggested }
                val leftover = remaining.filter { it.song.id !in appliedIds }
                val applied = state.sessionApplied + appliedIds.size
                if (leftover.isEmpty()) {
                    presentIdentifyQueue(emptyList(), showReview = false)
                    toast(
                        if (appliedIds.size == 1) "1 aplicada en revisión"
                        else "${appliedIds.size} aplicadas en revisión"
                    )
                    return@withLock
                }
                presentIdentifyQueue(
                    leftover,
                    showReview = true,
                    sessionApplied = applied,
                    sessionSkipped = state.sessionSkipped
                )
                toast(
                    buildString {
                        append(if (appliedIds.size == 1) "1 aplicada" else "${appliedIds.size} aplicadas")
                        append(
                            if (leftover.size == 1) ", 1 sin sugerencia"
                            else ", ${leftover.size} sin sugerencia"
                        )
                    }
                )
            }
        }
    }

    private fun advanceIdentifyReview(applied: Boolean) {
        val state = _identifyReview.value
        val nextApplied = state.sessionApplied + if (applied) 1 else 0
        val nextSkipped = state.sessionSkipped + if (applied) 0 else 1
        val nextIndex = state.currentIndex + 1
        if (nextIndex >= state.items.size) {
            _identifyReview.value = IdentifyReviewState()
            toast(
                buildString {
                    append(if (nextApplied == 1) "1 aplicada en revisión" else "$nextApplied aplicadas en revisión")
                    if (nextSkipped > 0) {
                        append(if (nextSkipped == 1) ", 1 omitida" else ", $nextSkipped omitidas")
                    }
                }
            )
            return
        }
        val nextItem = state.items[nextIndex]
        _identifyReview.value = state.copy(
            currentIndex = nextIndex,
            selectedCandidateIndex = 0,
            sessionApplied = nextApplied,
            sessionSkipped = nextSkipped,
            searchQueryDraft = defaultSearchDraft(nextItem),
            showSearchField = nextItem.proposal.candidates.isEmpty(),
            isSearching = false
        )
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
        val detail = _playlistDetail.value
        if (detail is PlaylistDetailNav.Local && detail.id == id) {
            closePlaylistDetail()
        }
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

    private suspend fun loadCfRecommendationsForRestore(): Boolean {
        val settings = listenBrainzPreferences.settingsFlow.first()
        if (!settings.showDiscoverPlaylists) return false
        refreshCfRecommendationsInternal(settings)
        val ok = _cfRecommendations.value != null &&
            _cfListState.value is CfRecommendationsUiState.Success
        if (ok) {
            _cfDetailOpen.value = true
            _cfDetailState.value = CfRecommendationsUiState.Success
        }
        return ok
    }

    private fun playMatchedCollection(
        items: List<PlayableItem>,
        startIndex: Int = 0,
        origin: DiscoverPlaybackOrigin = DiscoverPlaybackOrigin.None
    ): Boolean {
        if (items.isEmpty() || startIndex !in items.indices) return false
        playPlayableCollection(items, startIndex, origin = origin)
        return true
    }

    /** Play discover-matched tracks (CF / LB) with session origin. */
    fun playMatchedTracks(
        items: List<PlayableItem>,
        origin: DiscoverPlaybackOrigin,
        startIndex: Int = 0
    ) {
        playMatchedCollection(items, startIndex = startIndex, origin = origin)
    }

    fun shuffleMatchedTracks(
        items: List<PlayableItem>,
        origin: DiscoverPlaybackOrigin
    ) {
        shufflePlayableCollection(items, origin = origin)
    }

    fun openListenBrainzPlaylist(mbid: String) {
        viewModelScope.launch {
            loadListenBrainzPlaylist(mbid, forRestore = false)
        }
    }

    private suspend fun loadListenBrainzPlaylist(mbid: String, forRestore: Boolean): Boolean {
        val settings = listenBrainzPreferences.settingsFlow.first()
        if (!settings.showDiscoverPlaylists || mbid.isBlank()) {
            if (!forRestore) {
                _lbPlaylistDetailState.value = LbPlaylistDetailUiState.Error("ListenBrainz no disponible")
            }
            return false
        }
        _lbPlaylistDetailState.value = LbPlaylistDetailUiState.Loading
        _selectedLbPlaylist.value = null
        return when (
            val result = ListenBrainzClient.fetchPlaylist(
                playlistMbid = mbid,
                token = settings.userToken
            )
        ) {
            is LbApiResult.Success -> {
                val library = rawSongs.first()
                _selectedLbPlaylist.value = matchListenBrainzTracksUseCase.execute(result.data, library)
                _lbPlaylistDetailState.value = LbPlaylistDetailUiState.Success
                true
            }
            is LbApiResult.Failure -> {
                if (forRestore) {
                    _lbPlaylistDetailState.value = LbPlaylistDetailUiState.Idle
                } else {
                    _lbPlaylistDetailState.value = LbPlaylistDetailUiState.Error(result.message)
                }
                false
            }
        }
    }

    fun closeListenBrainzPlaylist() {
        _selectedLbPlaylist.value = null
        _lbPlaylistDetailState.value = LbPlaylistDetailUiState.Idle
    }

    private fun clearDiscoverPlaybackOrigin() {
        if (_discoverPlaybackOrigin.value != DiscoverPlaybackOrigin.None) {
            _discoverPlaybackOrigin.value = DiscoverPlaybackOrigin.None
        }
    }

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
            LibraryToastKind.SAVED -> DownloadMessages.songSaved(title)
            LibraryToastKind.ADDED -> DownloadMessages.songAdded(title)
            LibraryToastKind.ALREADY -> DownloadMessages.songAlready(title)
        }
        toast(message)
    }

    private fun toastDownloadsQueued(count: Int? = null, alreadyQueued: Boolean = false) {
        val message = when {
            alreadyQueued -> DownloadMessages.alreadyQueued
            count != null -> DownloadMessages.downloadsQueued(count)
            else -> DownloadMessages.downloadQueued
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
        val candidates: List<OnlineCatalogTrack> = listOf(track),
        val currentCandidateIndex: Int = 0,
        val idHint: String? = null,
        val lookupIdentity: TrackIdentity? = null
    )

    private suspend fun enqueueTrackedBatch(
        items: List<TrackedBatchItem>,
        source: ActiveDownloadSource,
        idStrategy: (TrackedBatchItem) -> String,
        playlistId: Long?,
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
                    candidates = candidates,
                    currentCandidateIndex = safeIndex,
                    targetPlaylistId = playlistId
                )
            )
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
                        existingCandidates = item.candidates,
                        currentCandidateIndex = safeIndex,
                        targetPlaylistId = playlistId,
                        lookupIdentity = item.lookupIdentity
                    )
                    if (result.isSuccess) successCount.incrementAndGet()
                }
            }.awaitAll()
        }

        toast(DownloadMessages.batchProcessed(successCount.get(), items.size))
    }

    private suspend fun enqueuePendingDownloads(
        playlistId: Long,
        tracks: List<OnlineCatalogTrack>,
        toastQueued: Boolean
    ) = enqueueTrackedBatch(
        items = tracks.map { TrackedBatchItem(track = it) },
        source = ActiveDownloadSource.LB_IMPORT,
        idStrategy = {
            TrackMatchKeys.downloadIdFor(it.track.artist, it.track.title)
        },
        playlistId = playlistId,
        toastQueued = toastQueued
    )

    private fun clearDiscoverState() {
        _lbDiscoverPlaylists.value = emptyList()
        _lbDiscoverListState.value = LbDiscoverListUiState.Idle
        closeListenBrainzPlaylist()
        clearCfState()
        val detail = _playlistDetail.value
        if (detail is PlaylistDetailNav.ListenBrainz || detail is PlaylistDetailNav.CfRecommendations) {
            _playlistDetail.value = PlaylistDetailNav.None
            persistNavSnapshot()
        }
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
                CatalogCategory.GENRES -> {
                    val genres = MetadataFetcher.listGenres()
                    _catalogGenres.value = if (cleanQ.isEmpty()) {
                        genres
                    } else {
                        genres.filter { TrackMatchKeys.containsNormalized(it.name, cleanQ) }
                    }
                }
                CatalogCategory.CHARTS -> {
                    _catalogSearchResults.value = MetadataFetcher.fetchChartTracks()
                }
            }
            _isSearchingCatalog.value = false
        }
    }


    fun searchOnlineCatalog(query: String) {
        searchCatalog(query)
    }

    fun selectAlbumForInspection(album: CatalogAlbum) {
        selectCollectionForInspection(
            title = album.title,
            kind = CatalogCollectionKind.ALBUM,
            coverUrl = album.coverUrl
        ) {
            MetadataFetcher.fetchAlbumTrackCandidates(album.id, album.title, album.artist, album.coverUrl)
        }
    }

    fun selectPlaylistForInspection(playlist: CatalogPlaylist) {
        selectCollectionForInspection(
            title = playlist.title,
            kind = CatalogCollectionKind.PLAYLIST,
            coverUrl = playlist.coverUrl
        ) {
            MetadataFetcher.fetchPlaylistTrackCandidates(playlist.id, playlist.title)
        }
    }

    fun selectGenreForInspection(genre: CatalogGenre) {
        selectCollectionForInspection(
            title = genre.name,
            kind = CatalogCollectionKind.GENRE,
            coverUrl = genre.pictureUrl
        ) {
            MetadataFetcher.searchTracksByGenre(genre.id, genre.name)
                .map { MetadataFetcher.toCatalogCandidate(it) }
        }
    }

    private fun selectCollectionForInspection(
        title: String,
        kind: CatalogCollectionKind,
        coverUrl: String?,
        fetch: suspend () -> List<CatalogTrackCandidate>
    ) {
        viewModelScope.launch {
            _isLoadingCollection.value = true
            _selectedCollectionTitle.value = title
            selectedCollectionKind = kind
            selectedCollectionCoverUrl = coverUrl
            _activeTrackCandidates.value = fetch()
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
            query = item.youtubeSearchQuery(),
            current = item.candidates,
            wasPreviewing = isPreviewingCandidate(item)
        ) { candidatesList ->
            val nextIndex = (item.currentCandidateIndex + 1) % candidatesList.size
            val merged = candidatesList.mapIndexed { i, t ->
                if (i != nextIndex) t
                else t.preferMetaFrom(item)
            }
            val updated = item.copy(candidates = merged, currentCandidateIndex = nextIndex)
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
            query = current.youtubeSearchQuery().ifBlank { current.title },
            current = listOf(current),
            wasPreviewing = wasPreviewing
        ) { searchResults ->
            if (searchResults.size == 1 && searchResults.first().id == current.id) return@launchCycleYouTubeMatch null

            val currentIdx = searchResults.indexOfFirst { it.id == current.id }
            val next = searchResults[(currentIdx + 1).coerceAtLeast(0) % searchResults.size]
            // Keep catalog album metadata when YouTube only says "YouTube"
            list[index] = next.preferMetaFrom(current)
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
        onProgress: ((DownloadPhase) -> Unit)? = null,
        conflictPolicy: DownloadConflictPolicy? = null
    ): Result<Song> = downloadAudioTrackUseCase.execute(track, onProgress, conflictPolicy)

    fun resolveDownloadConflictOverwrite(applyToRemainingBatch: Boolean = false) {
        resumeConflictDownload(
            policy = { DownloadConflictPolicy.Overwrite(it.existing.id) },
            applyToRemainingBatch = applyToRemainingBatch
        )
    }

    fun resolveDownloadConflictSaveAs(newTitle: String, applyToRemainingBatch: Boolean = false) {
        val conflict = _downloadConflict.value ?: return
        val title = newTitle.trim().ifBlank { "${conflict.existing.title} (2)" }
        resumeConflictDownload(
            policy = { DownloadConflictPolicy.SaveAs(title) },
            applyToRemainingBatch = applyToRemainingBatch,
            titleOverride = title,
            onApplyAll = {
                batchConflictPolicy = DownloadConflictPolicy.SaveAs(title)
                batchSaveAsCounter = 2
            }
        )
    }

    private fun resumeConflictDownload(
        policy: (DownloadConflict) -> DownloadConflictPolicy,
        applyToRemainingBatch: Boolean = false,
        titleOverride: String? = null,
        onApplyAll: (() -> Unit)? = null
    ) {
        val conflict = _downloadConflict.value ?: return
        val applyAll = applyToRemainingBatch || conflict.applyToRemainingBatch
        _downloadConflict.value = null
        val resolved = policy(conflict)
        if (applyAll) {
            if (onApplyAll != null) onApplyAll()
            else batchConflictPolicy = resolved
        }
        viewModelScope.launch {
            runTrackedDownload(
                downloadId = conflict.downloadId,
                source = conflict.source,
                track = conflict.track,
                existingCandidates = conflict.candidates,
                currentCandidateIndex = conflict.currentCandidateIndex,
                targetPlaylistId = conflict.targetPlaylistId,
                conflictPolicy = resolved,
                lookupIdentity = conflict.lookupIdentity,
                titleOverride = titleOverride
            )
        }
    }

    fun cancelDownloadConflict() {
        val conflict = _downloadConflict.value ?: return
        _downloadConflict.value = null
        removeActiveDownload(conflict.downloadId)
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
        activeTrack: OnlineCatalogTrack,
        lookup: TrackIdentity?
    ): Song? {
        val artist = lookup?.artist?.ifBlank { null } ?: activeTrack.artist
        val title = lookup?.title?.ifBlank { null } ?: activeTrack.title
        return repository.findSongByArtistTitle(artist, title)
    }

    private suspend fun applyBatchPolicy(
        activeTrack: OnlineCatalogTrack,
        lookup: TrackIdentity?
    ): DownloadConflictPolicy? {
        val cached = batchConflictPolicy ?: return null
        return when (cached) {
            is DownloadConflictPolicy.Overwrite -> {
                resolveExistingSong(activeTrack, lookup)?.let { DownloadConflictPolicy.Overwrite(it.id) }
            }
            is DownloadConflictPolicy.SaveAs -> {
                batchSaveAsCounter++
                val base = lookup?.title?.ifBlank { null } ?: activeTrack.title.ifBlank { "Track" }
                DownloadConflictPolicy.SaveAs("$base ($batchSaveAsCounter)")
            }
        }
    }

    private fun emitDownloadConflict(
        downloadId: String,
        source: ActiveDownloadSource,
        activeTrack: OnlineCatalogTrack,
        existing: Song,
        candidates: List<OnlineCatalogTrack>,
        safeIndex: Int,
        targetPlaylistId: Long?,
        lookupIdentity: TrackIdentity?
    ) {
        val isBatch = source == ActiveDownloadSource.BATCH || source == ActiveDownloadSource.LB_IMPORT
        _downloadConflict.value = DownloadConflict(
            downloadId = downloadId,
            source = source,
            track = activeTrack,
            existing = existing,
            candidates = candidates,
            currentCandidateIndex = safeIndex,
            targetPlaylistId = targetPlaylistId,
            applyToRemainingBatch = isBatch,
            lookupIdentity = lookupIdentity
        )
    }

    private fun markDownloadConflict(
        downloadId: String,
        source: ActiveDownloadSource,
        activeTrack: OnlineCatalogTrack,
        existing: Song,
        candidates: List<OnlineCatalogTrack>,
        safeIndex: Int,
        targetPlaylistId: Long?,
        lookupIdentity: TrackIdentity?,
        titleOverride: String?
    ) {
        emitDownloadConflict(
            downloadId = downloadId,
            source = source,
            activeTrack = activeTrack,
            existing = existing,
            candidates = candidates,
            safeIndex = safeIndex,
            targetPlaylistId = targetPlaylistId,
            lookupIdentity = lookupIdentity
        )
        upsertActiveDownload(
            ActiveDownload.conflict(
                id = downloadId,
                source = source,
                candidates = candidates,
                currentCandidateIndex = safeIndex,
                targetPlaylistId = targetPlaylistId,
                titleOverride = titleOverride
            )
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
        existingCandidates: List<OnlineCatalogTrack>? = null,
        currentCandidateIndex: Int = 0,
        targetPlaylistId: Long? = null,
        conflictPolicy: DownloadConflictPolicy? = null,
        lookupIdentity: TrackIdentity? = null,
        titleOverride: String? = null
    ): Result<Song> {
        val candidates = existingCandidates?.takeIf { it.isNotEmpty() } ?: listOf(track)
        val safeIndex = currentCandidateIndex.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
        val lookup = lookupIdentity ?: track.identity

        val existing = _activeDownloads.value.find { it.id == downloadId }
        val override = titleOverride
            ?: (conflictPolicy as? DownloadConflictPolicy.SaveAs)?.newTitle
            ?: existing?.titleOverride

        val blockedMessage = ensureDownloadNetworkAllowed()
        if (blockedMessage != null) {
            upsertActiveDownload(
                ActiveDownload.error(
                    id = downloadId,
                    source = source,
                    candidates = candidates,
                    errorMessage = blockedMessage,
                    currentCandidateIndex = safeIndex,
                    targetPlaylistId = targetPlaylistId,
                    titleOverride = override
                )
            )
            return Result.failure(IllegalStateException(blockedMessage))
        }

        if (existing == null ||
            existing.state != CandidateDownloadState.DOWNLOADING
        ) {
            upsertActiveDownload(
                ActiveDownload.queued(
                    id = downloadId,
                    source = source,
                    candidates = candidates,
                    currentCandidateIndex = safeIndex,
                    targetPlaylistId = targetPlaylistId,
                    resultSongId = existing?.resultSongId,
                    titleOverride = override
                )
            )
        }

        return downloadSemaphore.withPermit {
            runTrackedDownloadLocked(
                downloadId = downloadId,
                source = source,
                track = track,
                candidates = candidates,
                safeIndex = safeIndex,
                targetPlaylistId = targetPlaylistId,
                conflictPolicy = conflictPolicy,
                lookupIdentity = lookup,
                titleOverride = override
            )
        }
    }

    /** Null if download may proceed; otherwise Spanish error for ActiveDownload ERROR. */
    private suspend fun ensureDownloadNetworkAllowed(): String? {
        if (!connectivityObserver.isMetered()) return null
        val allowMetered = downloadPreferences.settingsFlow.first().downloadOnMeteredNetwork
        if (allowMetered) return null
        return DownloadMessages.blockedOnMetered
    }

    private suspend fun runTrackedDownloadLocked(
        downloadId: String,
        source: ActiveDownloadSource,
        track: OnlineCatalogTrack,
        candidates: List<OnlineCatalogTrack>,
        safeIndex: Int,
        targetPlaylistId: Long?,
        conflictPolicy: DownloadConflictPolicy?,
        lookupIdentity: TrackIdentity?,
        titleOverride: String?
    ): Result<Song> {
        val activeTrack = candidates.getOrNull(safeIndex) ?: track

        var resolvedPolicy = conflictPolicy ?: applyBatchPolicy(activeTrack, lookupIdentity)

        // Wait if another download is already showing the conflict dialog (batch).
        if (resolvedPolicy == null) {
            var waited = 0
            while (_downloadConflict.value != null && batchConflictPolicy == null && waited < 120) {
                delay(250)
                waited++
                if (batchConflictPolicy != null) {
                    resolvedPolicy = applyBatchPolicy(activeTrack, lookupIdentity)
                }
            }
        }

        val displayOverride = titleOverride
            ?: (resolvedPolicy as? DownloadConflictPolicy.SaveAs)?.newTitle

        if (resolvedPolicy == null) {
            val existing = resolveExistingSong(activeTrack, lookupIdentity)
            if (existing != null) {
                markDownloadConflict(
                    downloadId = downloadId,
                    source = source,
                    activeTrack = activeTrack,
                    existing = existing,
                    candidates = candidates,
                    safeIndex = safeIndex,
                    targetPlaylistId = targetPlaylistId,
                    lookupIdentity = lookupIdentity,
                    titleOverride = displayOverride
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
                candidates = candidates,
                currentCandidateIndex = safeIndex,
                targetPlaylistId = targetPlaylistId,
                titleOverride = displayOverride
            )
        )
        val trackForDownload = when (val policy = resolvedPolicy) {
            is DownloadConflictPolicy.SaveAs -> activeTrack.withIdentity { copy(title = policy.newTitle) }
            else -> activeTrack
        }

        val result = downloadTrack(
            track = trackForDownload,
            onProgress = { phase ->
                updateActiveDownload(downloadId) {
                    it.copy(
                        state = CandidateDownloadState.DOWNLOADING,
                        progressMessage = phase.userMessage,
                        progressPercent = phase.percent
                    )
                }
            },
            conflictPolicy = resolvedPolicy
        )

        // Late conflict after YouTube metadata resolve (e.g. blank title on LINK)
        val duplicate = result.exceptionOrNull() as? DuplicateSongException
        if (duplicate != null && resolvedPolicy == null) {
            markDownloadConflict(
                downloadId = downloadId,
                source = source,
                activeTrack = duplicate.track,
                existing = duplicate.existing,
                candidates = candidates,
                safeIndex = safeIndex,
                targetPlaylistId = targetPlaylistId,
                lookupIdentity = lookupIdentity,
                titleOverride = displayOverride
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
                        candidates = candidates,
                        currentCandidateIndex = safeIndex,
                        targetPlaylistId = targetPlaylistId
                    )
                )
                val bytes = runCatching {
                    SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
                        ?.let { java.io.File(it) }
                        ?.takeIf { it.isFile }
                        ?.length()
                }.getOrNull() ?: 0L
                downloadPreferences.addDownloadedBytes(
                    bytes,
                    metered = connectivityObserver.isMetered()
                )
                if (targetPlaylistId != null) {
                    repository.addSongToPlaylist(targetPlaylistId, song.id)
                    repository.removePlaylistPendingTrack(
                        targetPlaylistId,
                        activeTrack.artist,
                        activeTrack.title
                    )
                }
                rematchDiscoverAfterLibraryChange(extraSong = song)
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
                CrashReporter.recordNonFatal(
                    e,
                    mapOf(
                        "download_phase" to "tracked_download",
                        "download_source" to source.name,
                        "download_id" to downloadId,
                        "track_title" to activeTrack.title,
                        "track_artist" to activeTrack.artist
                    )
                )
                val error = mapDownloadError(e)
                updateActiveDownload(downloadId) {
                    it.copy(
                        state = CandidateDownloadState.ERROR,
                        progressMessage = null,
                        progressPercent = 0,
                        errorMessage = error
                    )
                }
            }
        )
        return result
    }

    private suspend fun rematchDiscoverAfterLibraryChange(extraSong: Song? = null) {
        val library = libraryWithExtra(extraSong)
        _selectedLbPlaylist.value?.let { current ->
            _selectedLbPlaylist.value = current.copy(matches = current.matches.rematchLocals(library))
        }
        _cfRecommendations.value?.let { current ->
            _cfRecommendations.value = current.copy(matches = current.matches.rematchLocals(library))
        }
    }

    private suspend fun libraryWithExtra(extraSong: Song?): List<Song> =
        rawSongs.first().let { list ->
            if (extraSong == null || list.any { it.id == extraSong.id }) list
            else list + extraSong
        }

    /**
     * Manual download of a streamed remote (Para Ti / Recomendados / Now Playing) into the library.
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
                    rematchDiscoverAfterLibraryChange()
                }
                return
            }
            else -> Unit
        }

        viewModelScope.launch {
            toastDownloadsQueued()
            enqueueRemoteDownload(remote, ActiveDownloadSource.DISCOVER)
        }
    }

    private suspend fun enqueueRemoteDownload(
        remote: PlayableItem.Remote,
        source: ActiveDownloadSource
    ): Result<Song> {
        val key = TrackMatchKeys.downloadIdFor(remote.artist, remote.title)
        val track = remote.toOnlineCatalogTrack(provider = "YouTube")
        return runTrackedDownload(downloadId = key, source = source, track = track)
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
        val query = download.youtubeSearchQuery()
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
            explicitId = TrackMatchKeys.batchDownloadIdFor(candidate.artist, candidate.title)
        )

        viewModelScope.launch {
            val targetPlaylistId = ensureCatalogPlaylistForBatch()
            runTrackedDownload(
                downloadId = downloadId,
                source = ActiveDownloadSource.BATCH,
                track = track,
                existingCandidates = candidate.candidates,
                currentCandidateIndex = candidate.currentCandidateIndex,
                targetPlaylistId = targetPlaylistId,
                lookupIdentity = candidate.identity
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
                    candidates = candidate.candidates,
                    currentCandidateIndex = candidate.currentCandidateIndex,
                    idHint = TrackMatchKeys.batchDownloadIdFor(candidate.artist, candidate.title),
                    lookupIdentity = candidate.identity
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
                    playlistId = targetPlaylistId
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
                artworkUri = null,
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
                track = track
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
        private const val MAX_CONSECUTIVE_UNPLAYABLE_SKIPS = 5
    }
}

