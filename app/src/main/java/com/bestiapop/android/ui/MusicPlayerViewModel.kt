package com.bestiapop.android.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.MatchedCfRecommendations
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.listenbrainz.rematchLocals
import com.bestiapop.android.data.model.*
import com.bestiapop.android.data.network.ConnectivityObserver
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.data.preferences.DownloadPreferencesRepository
import com.bestiapop.android.data.preferences.DownloadSettings
import com.bestiapop.android.data.preferences.IdentifyReviewStore
import com.bestiapop.android.data.preferences.PersistedIdentifyReviewQueue
import com.bestiapop.android.ui.identify.IdentifyReviewCoordinator
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.LibraryTagWritePreferencesRepository
import com.bestiapop.android.data.preferences.LibraryTagWriteSettings
import com.bestiapop.android.data.preferences.LibraryUiPreferencesCodec
import com.bestiapop.android.data.preferences.LibraryStackLookups
import com.bestiapop.android.data.preferences.DEFAULT_STREAM_SKIP_GRACE_SECONDS
import com.bestiapop.android.data.preferences.NAV_DISCOVER
import com.bestiapop.android.data.preferences.NAV_DOWNLOADS
import com.bestiapop.android.data.preferences.NAV_LIBRARY
import com.bestiapop.android.data.preferences.NAV_PLAYLISTS
import com.bestiapop.android.data.preferences.NAV_SETTINGS
import com.bestiapop.android.data.preferences.SearchHistoryPreferencesRepository
import com.bestiapop.android.domain.usecase.GetDiscoverRecommendationsUseCase
import com.bestiapop.android.domain.usecase.DiscoverFeed
import com.bestiapop.android.domain.usecase.RelatedAlbumItem
import com.bestiapop.android.domain.usecase.TopRelatedFeed
import com.bestiapop.android.data.model.toListenBrainzCatalogTrack
import com.bestiapop.android.domain.usecase.GetTopRelatedItemsUseCase
import com.bestiapop.android.data.preferences.DiscoverSourcePreference
import com.bestiapop.android.data.preferences.UiNavSnapshot
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.ThemePreferencesRepository
import com.bestiapop.android.data.system.BACKGROUND_RESTRICTION_CONFIRM_MS
import com.bestiapop.android.data.system.BackgroundExecutionProbe
import com.bestiapop.android.data.system.BackgroundExecutionStatus
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.PlaybackDiagnostics
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.radio.RadioEngine
import com.bestiapop.android.domain.usecase.BuildSimilarPlaylistPreviewUseCase
import com.bestiapop.android.domain.usecase.FetchAndMatchCfRecommendationsUseCase
import com.bestiapop.android.domain.usecase.ImportListenBrainzPlaylistUseCase
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.domain.util.IdentifyAlbumGroup
import com.bestiapop.android.domain.util.IdentifyCatalogQuery
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.albumArtistKey
import com.bestiapop.android.domain.util.albumGroupKey
import com.bestiapop.android.domain.util.albumIdentityKey
import com.bestiapop.android.domain.util.albumNamesMatch
import com.bestiapop.android.domain.util.assignUniqueKnownAlbumMatches
import com.bestiapop.android.domain.util.clusterIdentifyAlbumGroups
import com.bestiapop.android.domain.util.findAlbumMergeTarget
import com.bestiapop.android.domain.util.gapApplyFields
import com.bestiapop.android.domain.util.isTrackNumberLabel
import com.bestiapop.android.domain.util.KnownAlbumMatch
import com.bestiapop.android.domain.util.knownAlbumQueryOf
import com.bestiapop.android.domain.util.needsGapIdentify
import com.bestiapop.android.domain.util.normalizeAlbumName
import com.bestiapop.android.domain.util.toIdentifyCandidate
import com.bestiapop.android.service.PlaybackRuntime
import com.bestiapop.android.service.ProcessDownloadEvent
import com.bestiapop.android.service.ProcessDownloadRequest
import com.bestiapop.android.service.ProcessIdentifyEvent
import com.bestiapop.android.service.WebServerService
import com.bestiapop.android.ui.state.CatalogCollectionKind
import com.bestiapop.android.ui.state.ItemLibraryStatus
import com.bestiapop.android.ui.state.CatalogCollectionUiState
import com.bestiapop.android.ui.state.CatalogSearchUiState
import com.bestiapop.android.ui.state.toPlayableItems
import com.bestiapop.android.ui.state.IdentifyReviewItem
import com.bestiapop.android.ui.state.IdentifyReviewPhase
import com.bestiapop.android.ui.state.IdentifyReviewState
import com.bestiapop.android.ui.state.IdentifySetupState
import com.bestiapop.android.ui.state.attachKnownAlbumMatches
import com.bestiapop.android.ui.state.hasMediumSuggestion
import com.bestiapop.android.ui.state.IdentifyPersistEcho
import com.bestiapop.android.ui.state.identifyPersistEcho
import com.bestiapop.android.ui.state.identifyReviewFromPersisted
import com.bestiapop.android.ui.state.identifySearchDraft
import com.bestiapop.android.ui.state.identifySearchFilterAlbum
import com.bestiapop.android.ui.state.identifySearchFilterArtist
import com.bestiapop.android.ui.state.identifySearchFilterYear
import com.bestiapop.android.ui.state.leftoverIdentifyReview
import com.bestiapop.android.ui.state.mergeIncomingReviewItems
import com.bestiapop.android.ui.state.seedIdentifySearch
import com.bestiapop.android.ui.state.withGapApplyFields
import com.bestiapop.android.ui.state.withItemSearchChrome
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryListModel
import com.bestiapop.android.ui.state.LibraryProjectionState
import com.bestiapop.android.ui.state.LibraryViewMode
import com.bestiapop.android.ui.state.LoadableUiState
import com.bestiapop.android.ui.state.PlaylistDetailNav
import com.bestiapop.android.ui.state.SimilarPlaylistPreviewState
import com.bestiapop.android.ui.state.UiNavigationState
import com.bestiapop.android.ui.state.lbMbidOrNull
import com.bestiapop.android.ui.state.mapToUiState
import com.bestiapop.android.ui.state.stateInUi
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
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import android.content.Context
import android.media.AudioManager
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

@OptIn(UnstableApi::class)
@kotlin.OptIn(FlowPreview::class)
class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BestiaPopApplication
    private val repository = app.musicRepository
    private val playbackRuntime: PlaybackRuntime = app.playbackRuntime
    private val processDownloadRuntime = app.processDownloadRuntime
    private val processIdentifyRuntime = app.processIdentifyRuntime
    private val themeRepository = ThemePreferencesRepository(application)
    private val listenBrainzPreferences = ListenBrainzPreferencesRepository(application)
    private val playbackPreferences = PlaybackPreferencesRepository(application)
    private val downloadPreferences = DownloadPreferencesRepository(application)
    private val libraryTagWritePreferences = LibraryTagWritePreferencesRepository(application)
    private val libraryPreferences = LibraryPreferencesRepository(application)
    private val identifyReviewStore = IdentifyReviewStore(application)
    private val pendingListenDao = AppDatabase.getDatabase(application).pendingListenDao()
    private val connectivityObserver = ConnectivityObserver(application)

    // Theme state
    val currentThemeState: StateFlow<CustomTheme> = themeRepository.selectedThemeFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, themeRepository.initialTheme)

    // ListenBrainz state
    val listenBrainzSettings: StateFlow<ListenBrainzSettings> =
        listenBrainzPreferences.settingsFlow
            .stateInUi(viewModelScope, ListenBrainzSettings())

    private val playbackSettings: StateFlow<PlaybackSettings> = playbackRuntime.playbackSettings

    val downloadSettings: StateFlow<DownloadSettings> =
        downloadPreferences.settingsFlow
            .stateInUi(viewModelScope, DownloadSettings())

    private val _backgroundExecutionStatus = MutableStateFlow(BackgroundExecutionStatus())
    val backgroundExecutionStatus: StateFlow<BackgroundExecutionStatus> =
        _backgroundExecutionStatus.asStateFlow()
    private var backgroundExecutionConfirmJob: Job? = null

    val oemScreenOffCleanupHintDismissed: StateFlow<Boolean> =
        playbackPreferences.oemScreenOffCleanupHintDismissed.stateInUi(viewModelScope, true)

    val libraryTagWriteSettings: StateFlow<LibraryTagWriteSettings> =
        libraryTagWritePreferences.settingsFlow
            .stateInUi(viewModelScope, LibraryTagWriteSettings())

    val downloadOnMeteredNetwork: StateFlow<Boolean> =
        downloadSettings.mapToUiState(viewModelScope, true) { it.downloadOnMeteredNetwork }

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
    val streamSkipGraceSeconds: StateFlow<Int> =
        playbackPref(DEFAULT_STREAM_SKIP_GRACE_SECONDS) { it.streamSkipGraceSeconds }

    val pendingListenCount: StateFlow<Int> = pendingListenDao.countFlow()
        .stateInUi(viewModelScope, 0)

    private val _tokenValidation = MutableStateFlow(LoadableUiState<String?>(null))
    val tokenValidation = _tokenValidation.asStateFlow()

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

    private val _lbDiscover =
        MutableStateFlow(LoadableUiState<List<LbPlaylistSummary>>(emptyList()))
    val lbDiscover = _lbDiscover.asStateFlow()

    private val _lbPlaylistDetail =
        MutableStateFlow(LoadableUiState<MatchedLbPlaylist?>(null))
    val lbPlaylistDetail = _lbPlaylistDetail.asStateFlow()

    private val _cfRecommendations =
        MutableStateFlow(LoadableUiState<MatchedCfRecommendations?>(null))
    val cfRecommendations = _cfRecommendations.asStateFlow()

    // Raw songs & playlists
    val rawSongs = repository.allSongsFlow
        .stateInUi(
            viewModelScope,
            emptyList()
        )
    val identifyCoordinator = IdentifyReviewCoordinator(
        scope = viewModelScope,
        repository = repository,
        identifyReviewStore = identifyReviewStore,
        processIdentifyRuntime = processIdentifyRuntime,
        rawSongs = rawSongs,
        awaitCatalogLoaded = { awaitFirstCatalogLoaded() },
        clearCatalogPreview = { clearCatalogPreview() },
        toast = { toast(it) },
        uiAttached = { uiAttached.get() }
    )
    val identifyReview: StateFlow<IdentifyReviewState> = identifyCoordinator.identifyReview
    val identifySetup: StateFlow<IdentifySetupState?> = identifyCoordinator.identifySetup
    val localLibraryIndex: StateFlow<Map<String, Song>> = rawSongs
        .map { songs ->
            val localOnly = songs.filter { !it.isRemote }
            TrackMatchKeys.buildLibraryIndex(localOnly)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )
    val allLibrarySongsIndex: StateFlow<Map<String, Song>> = rawSongs
        .map { songs ->
            TrackMatchKeys.buildLibraryIndex(songs)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )
    val allSongsById: StateFlow<Map<Long, Song>> = rawSongs
        .map { songs -> songs.associateBy(Song::id) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )

    /** Level 2: Resolves an iterable of song IDs into the corresponding Songs in O(1) per song. */
    fun songsForIds(ids: Iterable<Long>): List<Song> {
        val index = allSongsById.value
        return ids.mapNotNull { index[it] }
    }

    private data class SavedAlbumIndices(
        val byArtistAndAlbum: Map<String, List<Song>> = emptyMap(),
        val byAlbumTitle: Map<String, List<Song>> = emptyMap()
    )

    private val savedAlbumsIndices: StateFlow<SavedAlbumIndices> = rawSongs
        .map { songs ->
            val byArtist = HashMap<String, MutableList<Song>>()
            val byTitle = HashMap<String, MutableList<Song>>()
            for (song in songs) {
                val albumKey = albumIdentityKey(song.album)
                byArtist.getOrPut(albumArtistKey(song.artist, song.album)) { ArrayList() }.add(song)
                byTitle.getOrPut(albumKey) { ArrayList() }.add(song)
            }
            SavedAlbumIndices(byArtist, byTitle)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SavedAlbumIndices()
        )
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

    private val _navigation = MutableStateFlow(UiNavigationState())
    val navigation = _navigation.asStateFlow()
    private val _selectedNavIndex = MutableStateFlow(NAV_LIBRARY)
    val selectedNavIndex = _selectedNavIndex.asStateFlow()

    private var uiPrefsHydrated = false

    /** Tab to persist. Transient jumps move the live index without touching this. */
    private var persistedNavIndex = NAV_LIBRARY

    /** Tab to come back to after a transient jump into Settings. */
    private var navIndexBeforeTransient: Int? = null

    private val _libraryPrefsReady = MutableStateFlow(true)
    private val getLibrarySongsUseCase = com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase()
    private val _artistPhotos = MutableStateFlow<Map<String, String>>(emptyMap())
    val libraryProjection = LibraryProjectionState(
        scope = viewModelScope,
        rawSongs = repository.allSongsFlow,
        albumOverrides = repository.albumOverridesFlow,
        searchQuery = searchQuery,
        sortOption = sortOption,
        sortDirection = sortDirection,
        artistPhotos = _artistPhotos,
        useCase = getLibrarySongsUseCase,
        overlayOpen = identifyReview.map { it.isOpen }.distinctUntilChanged(),
        viewMode = _libraryViewMode,
        browseFilter = navigation.map { it.libraryBrowseFilter }.distinctUntilChanged(),
        playStats = repository.songPlayStatsFlow,
        prefsReady = _libraryPrefsReady
    )

    data class PendingAlbumMerge(
        val source: Album,
        val target: Album
    )

    private val _pendingAlbumMerge = MutableStateFlow<PendingAlbumMerge?>(null)
    val pendingAlbumMerge: StateFlow<PendingAlbumMerge?> = _pendingAlbumMerge.asStateFlow()

    fun buildLibraryListModel(
        songs: List<Song>,
        viewMode: LibraryViewMode,
        sortOption: SortOption = this.sortOption.value,
        sortDirection: SortDirection = this.sortDirection.value
    ): LibraryListModel =
        libraryProjection.buildListModel(songs, viewMode, sortOption, sortDirection)

    fun buildLibraryListItems(
        songs: List<Song>,
        viewMode: LibraryViewMode,
        sortOption: SortOption = this.sortOption.value,
        sortDirection: SortDirection = this.sortDirection.value
    ): List<LibraryListItem> =
        libraryProjection.buildListItems(songs, viewMode, sortOption, sortDirection)

    fun sortSongsWithinAlbum(songs: List<Song>): List<Song> =
        getLibrarySongsUseCase.sortSongsWithinAlbum(songs)

    fun songsForAlbum(songs: List<Song>, albumName: String): List<Song> =
        getLibrarySongsUseCase.songsForAlbum(songs, albumName)

    fun songsForArtist(songs: List<Song>, artistName: String): List<Song> =
        songs.filter { it.artist.equals(artistName, ignoreCase = true) }

    fun songsForGenre(songs: List<Song>, genreName: String): List<Song> =
        getLibrarySongsUseCase.songsMatchingGenre(songs, genreName)

    fun songsFromLibraryListItems(items: List<LibraryListItem>): List<Song> =
        getLibrarySongsUseCase.songsFromListItems(items)

    fun songsInOrder(pool: List<Song>, ids: List<Long>): List<Song> =
        getLibrarySongsUseCase.songsInOrder(pool, ids)

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
        genres = genres,
        sortOption = sortOption.value,
        sortDirection = sortDirection.value
    )

    fun playCurrentLibraryBrowse(shuffle: Boolean) {
        val filter = _navigation.value.libraryBrowseFilter
        val songs = libraryProjection.songs.value
        val queue = when (filter) {
            LibraryBrowseFilter.SONGS -> libraryProjection.songList.value.songsVisual
            LibraryBrowseFilter.RECENT -> libraryProjection.recentSongs.value
            else -> songsForBrowseProjection(
                filter = filter,
                songs = songs,
                viewMode = _libraryViewMode.value,
                albums = libraryProjection.albums.value,
                artists = libraryProjection.artists.value,
                genres = libraryProjection.genres.value
            )
        }
        if (queue.isEmpty()) return
        if (shuffle) shuffleCollection(queue) else playCollection(queue)
    }

    // Process-owned playback state. ViewModel only exposes/observes it.
    val currentItem = playbackRuntime.currentItem
    val currentSong = playbackRuntime.currentSong
    val currentSongId: StateFlow<Long?> = currentSong.map { it?.id }.stateInUi(viewModelScope, null)
    val isPlaying = playbackRuntime.isPlaying
    val playbackPositionMs = playbackRuntime.playbackPositionMs
    val repeatMode = playbackRuntime.repeatMode
    val isShuffle = playbackRuntime.isShuffle
    val queue = playbackRuntime.queue
    val displayQueue = playbackRuntime.displayQueue
    val discoverPlaybackOrigin = playbackRuntime.discoverPlaybackOrigin
    val resolvingRemote = playbackRuntime.resolvingRemote

    /** Artists already looked up this session (hit or miss) — a miss must not be retried forever. */
    private val artistPhotoAttempted = mutableSetOf<String>()

    /** Song ids already passed to the background metadata/lyrics pass this session. */
    private val metadataEnhanceAttempted = mutableSetOf<Long>()

    val radioActive = playbackRuntime.radioActive
    val radioLoading = playbackRuntime.radioLoading
    val radioMode = playbackRuntime.radioMode
    val radioStatusLabel = playbackRuntime.radioStatusLabel

    /** Stable key of the catalog track being previewed inside Add Music (null = no catalog preview). */
    private val _catalogPreviewKey = MutableStateFlow<String?>(null)
    val catalogPreviewKey = _catalogPreviewKey.asStateFlow()

    /** Held so opening another Discover playlist cancels the previous fetch. */
    private var lbDetailJob: Job? = null
    private val radioEngine = app.radioEngine
    private val buildSimilarPlaylistPreviewUseCase =
        BuildSimilarPlaylistPreviewUseCase(radioEngine, repository)

    private val _similarPlaylistPreview = MutableStateFlow<SimilarPlaylistPreviewState?>(null)
    val similarPlaylistPreview = _similarPlaylistPreview.asStateFlow()
    private var similarPreviewJob: Job? = null
    private var similarPreviewSeeds: List<PlayableItem> = emptyList()

    val queueFocusEpoch = playbackRuntime.queueFocusEpoch

    private val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _volumeLevel = MutableStateFlow(getDeviceVolumeRatio())
    val volumeLevel = _volumeLevel.asStateFlow()

    private val searchHistoryPreferences = SearchHistoryPreferencesRepository(application)
    private val getDiscoverRecommendationsUseCase = GetDiscoverRecommendationsUseCase()

    val recentSearches: StateFlow<List<String>> = searchHistoryPreferences.recentSearchesFlow
        .stateInUi(viewModelScope, emptyList())

    val discoverSource: StateFlow<DiscoverSourcePreference> =
        libraryPreferences.discoverSourceFlow
            .stateInUi(viewModelScope, DiscoverSourcePreference.BOTH)

    private val _discoverFeed = MutableStateFlow(DiscoverFeed())
    val discoverFeed = _discoverFeed.asStateFlow()

    private val _isLoadingDiscoverFeed = MutableStateFlow(false)
    val isLoadingDiscoverFeed = _isLoadingDiscoverFeed.asStateFlow()

    private val getTopRelatedItemsUseCase = GetTopRelatedItemsUseCase()
    private val _topRelatedFeed = MutableStateFlow(TopRelatedFeed())
    val topRelatedFeed: StateFlow<TopRelatedFeed> = _topRelatedFeed.asStateFlow()

    private val _isLoadingTopRelatedFeed = MutableStateFlow(false)
    val isLoadingTopRelatedFeed: StateFlow<Boolean> = _isLoadingTopRelatedFeed.asStateFlow()

    private val _catalogSearch = MutableStateFlow(CatalogSearchUiState())
    val catalogSearch = _catalogSearch.asStateFlow()

    private val _catalogCollection = MutableStateFlow(CatalogCollectionUiState())
    val catalogCollection = _catalogCollection.asStateFlow()

    private data class CatalogBatchPlaylistTarget(
        val selectionKey: String,
        val playlistId: Long
    )

    /** Local playlist created when batch-downloading a catalog playlist (reuse across single/batch). */
    private var catalogBatchPlaylistTarget: CatalogBatchPlaylistTarget? = null
    private val catalogBatchPlaylistMutex = Mutex()
    private var catalogCollectionJob: Job? = null
    private var catalogCollectionGeneration = 0L
    private var catalogSearchJob: Job? = null
    private var catalogSearchGeneration = 0L

    val activeDownloads: StateFlow<List<ActiveDownload>> = processDownloadRuntime.downloads
    val downloadConflict: StateFlow<DownloadConflict?> = processDownloadRuntime.downloadConflict

    /** Set when MainActivity should switch to Descargas (notification / dialog deep-link). */
    private val _pendingOpenDownloads = MutableStateFlow(false)
    private val _pendingOpenIdentifyReview = MutableStateFlow(false)
    private val _localLibraryJobProgress = MutableStateFlow<LibraryJobProgress?>(null)
    val libraryJobProgress: StateFlow<LibraryJobProgress?> =
        combine(_localLibraryJobProgress, processIdentifyRuntime.progress) { local, identify ->
            identify ?: local
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val uiAttached = AtomicBoolean(false)

    /** Serializes the first-launch disk import: two callers race the completed-flag check. */
    private val initialImportMutex = Mutex()
    private val identifiedWifiSongIds = mutableSetOf<Long>()
    val pendingOpenDownloads = _pendingOpenDownloads.asStateFlow()
    val pendingOpenIdentifyReview = _pendingOpenIdentifyReview.asStateFlow()

    fun requestOpenDownloads() {
        _pendingOpenDownloads.value = true
    }

    fun requestOpenIdentifyReview() {
        setSelectedNavIndex(NAV_LIBRARY)
        _pendingOpenIdentifyReview.value = true
    }

    fun onUiAttached() {
        uiAttached.set(true)
    }

    fun warmupPlayback() {
        playbackRuntime.warmup()
    }

    fun attachPlaybackUi() {
        if (uiAttached.get()) playbackRuntime.attachUi()
    }

    fun onUiDetached() {
        uiAttached.set(false)
        playbackRuntime.detachUi()
    }

    fun dismissOemScreenOffCleanupHint() {
        persistPlayback { dismissOemScreenOffCleanupHint() }
    }

    fun onAppForeground() {
        onUiAttached()
        viewModelScope.launch(Dispatchers.IO) {
            delay(150L) // Ceder prioridad inmediata al render del primer frame y carga de catálogo
            val snapshot = BackgroundExecutionProbe.current(getApplication())
            withContext(Dispatchers.Main.immediate) {
                publishBackgroundExecutionStatus(snapshot)
            }
            processIdentifyRuntime.resumeInterrupted()
            if (app.shouldAutoResumeDownloads) {
                processDownloadRuntime.resumeInterrupted()
            }
        }
    }

    private fun publishBackgroundExecutionStatus(
        snapshot: BackgroundExecutionStatus = BackgroundExecutionProbe.current(getApplication())
    ) {
        backgroundExecutionConfirmJob?.cancel()
        val current = _backgroundExecutionStatus.value
        val raisingAlarm =
            (snapshot.blocksBackgroundPlayback && !current.blocksBackgroundPlayback) ||
                    (snapshot.oemScreenOffCleanupActive && !current.oemScreenOffCleanupActive)
        if (!raisingAlarm) {
            _backgroundExecutionStatus.value = snapshot
            return
        }
        _backgroundExecutionStatus.value = snapshot.copy(
            runAnyInBackgroundIgnored = current.runAnyInBackgroundIgnored,
            oemScreenOffCleanupEnabled = current.oemScreenOffCleanupEnabled
        )
        backgroundExecutionConfirmJob = viewModelScope.launch {
            delay(BACKGROUND_RESTRICTION_CONFIRM_MS)
            _backgroundExecutionStatus.value = BackgroundExecutionProbe.current(getApplication())
        }
    }

    fun consumeOpenDownloads() {
        _pendingOpenDownloads.value = false
    }

    fun consumeOpenIdentifyReview() {
        _pendingOpenIdentifyReview.value = false
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

    fun setAutoWriteTagsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            libraryTagWritePreferences.setAutoWriteTagsEnabled(enabled)
        }
    }

    fun syncLibraryTagsToFiles() {
        if (_localLibraryJobProgress.value != null || processIdentifyRuntime.progress.value != null) {
            toast("Ya hay una tarea de biblioteca en curso")
            return
        }
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                repository.syncTagsToFiles { done, total, fileName ->
                    reportLibraryProgress(LibraryJobKind.TAG_WRITE, done, total, fileName)
                }
            }
            clearLibraryProgress()
            toast(
                buildString {
                    append(
                        if (summary.updated == 1) "1 archivo actualizado"
                        else "${summary.updated} archivos actualizados"
                    )
                    if (summary.skipped > 0) {
                        append(
                            if (summary.skipped == 1) ", 1 omitido"
                            else ", ${summary.skipped} omitidos"
                        )
                    }
                    if (summary.errors > 0) {
                        append(
                            if (summary.errors == 1) ", 1 error"
                            else ", ${summary.errors} errores"
                        )
                    }
                }
            )
        }
    }

    private val _pendingSettingsSection = MutableStateFlow<String?>(null)
    val pendingSettingsSection = _pendingSettingsSection.asStateFlow()

    /**
     * Transient jump to Ajustes → Descargas: does not persist the tab, and remembers where the user
     * came from so closing the section brings them back instead of stranding them on Settings.
     */
    fun openDownloadSettings() {
        openSettingsSection("downloads")
    }

    fun openPlaybackSettings() {
        openSettingsSection("playback")
    }

    private fun openSettingsSection(section: String) {
        navIndexBeforeTransient = _navigation.value.selectedNavIndex
        _pendingSettingsSection.value = section
        updateNavigation { it.copy(selectedNavIndex = NAV_SETTINGS) }
    }

    /** True when it consumed a pending transient jump and restored the previous tab. */
    fun returnFromTransientSettings(): Boolean {
        val previous = navIndexBeforeTransient ?: return false
        navIndexBeforeTransient = null
        updateNavigation { it.copy(selectedNavIndex = previous) }
        return true
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

    fun setStreamSkipGraceSeconds(seconds: Int) {
        persistPlayback { setStreamSkipGraceSeconds(seconds) }
    }

    private suspend fun restoreVolumeBoostIfNeeded() {
        val settings = playbackRuntime.awaitPlaybackSettings()
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

    private fun persistPlayback(block: suspend PlaybackPreferencesRepository.() -> Unit) {
        viewModelScope.launch { playbackPreferences.block() }
    }

    private fun <T> playbackPref(
        initial: T,
        select: (PlaybackSettings) -> T
    ): StateFlow<T> = playbackSettings.mapToUiState(viewModelScope, initial, transform = select)

    init {
        viewModelScope.launch {
            restoreVolumeBoostIfNeeded()
        }
        viewModelScope.launch {
            playbackRuntime.events.collect { toast(it) }
        }
        viewModelScope.launch {
            listenBrainzPreferences.settingsFlow
                .distinctUntilChangedBy { Triple(it.userToken, it.username, it.enabled) }
                .drop(1)
                .collect { settings ->
                    if (settings.enabled && !settings.username.isNullOrBlank()) {
                        refreshDiscoverFeed()
                        refreshTopRelatedFeed()
                    }
                }
        }
        viewModelScope.launch {
            hydrateUiPreferences()
            awaitFirstCatalogLoaded()
            pruneRestoredLibraryStack()
        }

        viewModelScope.launch {
            ensureInitialLibraryImport(showRecoveryToast = true)
        }

        viewModelScope.launch {
            warnIfDatabaseWasDowngraded()
        }

        viewModelScope.launch(Dispatchers.IO) {
            awaitFirstCatalogLoaded()
            if (!libraryPreferences.isCanonicalAudioUrisMigrated()) {
                repository.migrateCanonicalAudioUris()
                libraryPreferences.setCanonicalAudioUrisMigrated()
            }
            if (!libraryPreferences.isDeviceDateAddedMigrated()) {
                repository.migrateDateAddedFromDevice()
                libraryPreferences.setDeviceDateAddedMigrated()
            }
            if (!libraryPreferences.isEmbeddedFileTagsMigrated()) {
                val leftover = repository.migrateEmbeddedFileTags()
                libraryPreferences.setEmbeddedFileTagsMigrated()
                identifyReviewStore.removeSongIds(leftover.map { it.id }.toSet())
                identifyCoordinator.identifyImportedGaps(leftover)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            awaitFirstLibraryIdle()
            // One-shot: the migration leaves the album untouched below HIGH confidence, so without a
            // flag every cold start re-queried the same 'YouTube Music' rows over the network forever.
            if (!libraryPreferences.isLegacyYouTubeMusicMigrated()) {
                repository.migrateLegacyYouTubeMusicSongs()
                libraryPreferences.setLegacyYouTubeMusicMigrated()
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
                    identifyImportedGaps(songs)
                }
        }

        viewModelScope.launch {
            processDownloadRuntime.events.collect { event ->
                when (event) {
                    is ProcessDownloadEvent.Completed -> {
                        rematchDiscoverAfterLibraryChange(extraSong = event.song)
                        if (event.source == ActiveDownloadSource.CATALOG ||
                            event.source == ActiveDownloadSource.LINK ||
                            event.source == ActiveDownloadSource.DISCOVER
                        ) {
                            toastSongInLibrary(event.song.title, LibraryToastKind.ADDED)
                        }
                    }
                }
            }
        }

        // Autosave can finish while no ViewModel exists. A retained/persisted SUCCESS triggers
        // rematching as soon as LB or CF detail is present again.
        viewModelScope.launch {
            combine(
                activeDownloads.map { rows ->
                    rows.asSequence()
                        .filter {
                            it.source.lane == DownloadLane.AUTOSAVE &&
                                    it.state == CandidateDownloadState.SUCCESS
                        }
                        .mapNotNull { it.resultSongId }
                        .toSet()
                },
                lbPlaylistDetail.map { it.data != null },
                cfRecommendations.map { it.data != null }
            ) { savedSongIds, hasLbDetail, hasCfDetail ->
                Triple(savedSongIds, hasLbDetail, hasCfDetail)
            }
                .distinctUntilChanged()
                .collect { (savedSongIds, hasLbDetail, hasCfDetail) ->
                    if (savedSongIds.isNotEmpty() && (hasLbDetail || hasCfDetail)) {
                        rematchDiscoverAfterLibraryChange()
                    }
                }
        }

        // rawSongs, not libraryProjection.songs: the latter also re-emits on every search keystroke and sort
        // change, which restarted these network passes over the whole library each time.
        viewModelScope.launch(Dispatchers.IO) {
            awaitFirstLibraryIdle()
            rawSongs.map { songs -> songs.map { it.artist }.distinct() }
                .distinctUntilChanged()
                .collect { artists ->
                    val newPhotos = mutableMapOf<String, String>()
                    val unattempted = artists.filter {
                        !IdentifyRanking.isPlaceholderArtist(it) && it !in artistPhotoAttempted
                    }
                    for (artist in unattempted) {
                        artistPhotoAttempted.add(artist)
                        val photoUrl = MetadataFetcher.fetchArtistPhotoUrl(artist)
                        if (!photoUrl.isNullOrEmpty()) {
                            newPhotos[artist] = photoUrl
                        }
                    }
                    if (newPhotos.isNotEmpty()) {
                        _artistPhotos.update { it + newPhotos }
                    }
                }
        }

        viewModelScope.launch(Dispatchers.IO) {
            awaitFirstLibraryIdle()
            rawSongs.map { songs -> songs.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect {
                    val songs = repository.allSongsFlow.first()
                    val unenhanced = songs.filter {
                        !SongPathNormalizer.hasUsableArtwork(it.artworkUri) &&
                                it.id !in metadataEnhanceAttempted
                    }
                    val batch = unenhanced.take(METADATA_ENHANCE_BATCH)
                    if (batch.isEmpty()) return@collect
                    batch.forEach { metadataEnhanceAttempted.add(it.id) }
                    repository.enhanceSongMetadataAndLyricsBatch(batch)
                }
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
        val durationMissing = song.durationMs <= 0
        return artMissing || durationMissing
    }

    private fun requestMetadataEnhancement(song: Song, force: Boolean = false) {
        if (!force && !songNeedsMetadataEnhancement(song)) return
        viewModelScope.launch {
            repository.enhanceSongMetadataAndLyrics(song)
        }
    }

    fun playSong(
        song: Song,
        playlistOrQueue: List<Song> = emptyList(),
        applyManualModes: Boolean = true
    ) {
        _catalogPreviewKey.value = null
        val baseList = when {
            playlistOrQueue.isNotEmpty() -> playlistOrQueue
            else -> libraryProjection.songList.value.songsVisual.ifEmpty {
                libraryProjection.songs.value
            }
        }
        val indexInBase = baseList.indexOfFirst { it.id == song.id || it.uriString == song.uriString }

        val targetQueue = if (indexInBase != -1) baseList else listOf(song)
        val index = if (indexInBase != -1) indexInBase else 0
        playPlayableCollection(targetQueue.toPlayableItemsWithFreshIds(), index, applyManualModes = applyManualModes)
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
        playbackRuntime.playPlayableCollection(
            items = items,
            startIndex = startIndex,
            fromRadio = fromRadio,
            rotate = rotate,
            applyManualModes = applyManualModes,
            startShuffled = startShuffled,
            origin = origin,
            resumeAtMs = resumeAtMs
        )
    }

    fun catalogPreviewKeyFor(track: OnlineCatalogTrack): String {
        return track.id.takeIf { it.isNotBlank() }
            ?: "${track.artist.trim().lowercase()}|${track.title.trim().lowercase()}"
    }

    fun playOnlineCatalogTrackAsStream(track: OnlineCatalogTrack) {
        val key = catalogPreviewKeyFor(track)
        if (_catalogPreviewKey.value == key && currentItem.value != null) {
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

    /** Returns matched local (non-remote) Song from library index in O(1) time. */
    fun findLocalSongFor(meta: TrackMeta): Song? {
        val song = TrackMatchKeys.lookupLocalSong(localLibraryIndex.value, meta)
        return if (song != null && !song.isRemote) song else null
    }

    /** Plays local version if available in library; otherwise falls back to online stream. */
    fun playCatalogOrLocalTrack(track: OnlineCatalogTrack) {
        val local = findLocalSongFor(track.identity)
        if (local != null) {
            playSong(local)
        } else {
            playOnlineCatalogTrackAsStream(track)
        }
    }

    /** Plays collection of candidates, resolving any available local tracks to avoid streaming. */
    fun playCatalogCandidates(
        candidates: List<CatalogTrackCandidate>,
        startIndex: Int = 0,
        startShuffled: Boolean = false
    ) {
        val playables = candidates.toPlayableItems(localLibraryIndex.value)
        playPlayableCollection(playables, startIndex = startIndex, startShuffled = startShuffled)
    }

    /** Plays a single catalog candidate using local file if present, or streaming. */
    fun playCatalogCandidate(candidate: CatalogTrackCandidate) {
        playCatalogOrLocalTrack(candidate.effectiveTrack)
    }

    /** Downloads a single catalog candidate using YouTube extraction and tracking. */
    fun downloadCatalogCandidate(
        candidate: CatalogTrackCandidate,
        source: ActiveDownloadSource = ActiveDownloadSource.CATALOG
    ) {
        downloadOnlineTrack(candidate.effectiveTrack, source = source)
    }

    /** Returns status of track in library (DOWNLOADED, SAVED_REMOTE, or NOT_IN_LIBRARY) in O(1). */
    fun getTrackLibraryStatus(meta: TrackMeta): ItemLibraryStatus {
        val song = TrackMatchKeys.lookupLocalSong(allLibrarySongsIndex.value, meta)
            ?: return ItemLibraryStatus.NOT_IN_LIBRARY
        return if (song.isRemote) ItemLibraryStatus.SAVED_REMOTE else ItemLibraryStatus.DOWNLOADED
    }

    /** Returns status of album in library (DOWNLOADED, SAVED_REMOTE, or NOT_IN_LIBRARY) in O(1). */
    fun getAlbumLibraryStatus(albumTitle: String, artistName: String): ItemLibraryStatus {
        val indices = savedAlbumsIndices.value
        val albumKey = albumIdentityKey(albumTitle)
        val key = albumArtistKey(artistName, albumTitle)
        val songs = indices.byArtistAndAlbum[key] ?: indices.byAlbumTitle[albumKey]
        if (songs.isNullOrEmpty()) return ItemLibraryStatus.NOT_IN_LIBRARY
        return if (songs.any { !it.isRemote }) ItemLibraryStatus.DOWNLOADED else ItemLibraryStatus.SAVED_REMOTE
    }

    /** Preview local file while reviewing identify candidates (toggle if already current). */
    fun previewIdentifyLocalSong(song: Song) {
        val current = currentItem.value
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
    fun playCollection(songs: List<Song>, startIndex: Int = 0, startShuffled: Boolean = false) {
        if (songs.isEmpty()) return
        if (startShuffled) {
            shuffleCollection(songs)
        } else {
            val validIndex = startIndex.coerceIn(0, songs.size - 1)
            playSong(songs[validIndex], songs)
        }
    }

    fun playCollection(songs: List<Song>, startShuffled: Boolean) {
        playCollection(songs, startIndex = 0, startShuffled = startShuffled)
    }

    fun playCollection(songs: List<Song>, startSong: Song) {
        playSong(startSong, songs)
    }

    fun setAlbumArtwork(albumName: String, artworkUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setAlbumArtwork(albumName, artworkUri)
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

    fun togglePlayPause() {
        playbackRuntime.togglePlayPause()
    }

    fun skipToNext() {
        playbackRuntime.skipToNext()
    }

    fun skipToPrevious() {
        playbackRuntime.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        playbackRuntime.seekTo(positionMs)
    }

    fun seekToAndPlay(positionMs: Long) {
        playbackRuntime.seekToAndPlay(positionMs)
    }

    fun toggleRepeatMode() {
        playbackRuntime.toggleRepeatMode()
    }

    fun toggleShuffle() {
        playbackRuntime.toggleShuffle()
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
        playbackRuntime.addPlayableBatch(items)
    }

    fun setRadioPreferredMode(mode: RadioMode) {
        playbackRuntime.setRadioPreferredMode(mode)
    }

    private fun resolvePreferredRadioMode(
        mode: RadioMode?,
        networkOnline: Boolean = connectivityObserver.isCurrentlyOnline()
    ): RadioMode = when {
        mode != null -> mode
        playbackRuntime.preferredRadioModeOrNull() != null ->
            playbackRuntime.preferredRadioModeOrNull()!!

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

    /**
     * Preview-local: the mode lives in [SimilarPlaylistPreviewState], not in `radioPreferredMode`.
     * Writing the global one made picking "Solo nuevos" here silently change what a later tap on the
     * Radio button would do.
     */
    fun setSimilarPreviewMode(mode: RadioMode) {
        val state = _similarPlaylistPreview.value ?: return
        if (state.mode == mode && !state.loading) return
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

    private inline fun withSelectedSimilarPreviewItems(action: (List<PlayableItem>) -> Unit) {
        val state = _similarPlaylistPreview.value ?: return
        if (state.loading) return
        val selected = state.selectedItems
        if (selected.isEmpty()) {
            toast("Seleccioná al menos una canción")
            return
        }
        action(selected)
        dismissSimilarPreview()
    }

    fun playSimilarPreview() {
        withSelectedSimilarPreviewItems { selected ->
            playPlayableCollection(selected, startIndex = 0, rotate = false)
        }
    }

    fun enqueueSimilarPreview() {
        withSelectedSimilarPreviewItems { selected ->
            addPlayableBatch(selected)
            toast("Agregadas a la cola (${selected.size})")
        }
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
            val library = repository.allSongsFlow.first()
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
                // Gated on failedOnline only, like the dialog body: keying it on mode == NEW made the
                // toast claim the online radio was down (e.g. offline, where failedOnline is false)
                // while the dialog underneath said "No encontré canciones parecidas".
                toast(
                    if (preview.failedOnline) "Radio online no disponible"
                    else "No encontré canciones parecidas"
                )
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
        playbackRuntime.stopRadio()
    }

    fun startRadio(
        seedSong: Song? = null,
        mode: RadioMode? = null,
        auto: Boolean = false,
        announceMode: Boolean = false
    ) {
        playbackRuntime.startRadio(
            seedSong = seedSong,
            mode = mode,
            auto = auto,
            announceMode = announceMode
        )
    }

    fun playNextInQueue(song: Song) {
        playNextBatch(listOf(song))
    }

    fun playNextBatch(songs: List<Song>) {
        playbackRuntime.playNextBatch(songs.toPlayableItems())
    }

    fun addSongsToPlaylist(playlistId: Long, songs: List<Song>) =
        addSongsToPlaylist(playlistId, songs.map { it.id })

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

    fun updateSongLyrics(songId: Long, lyrics: String?) {
        viewModelScope.launch {
            repository.updateSongLyrics(songId, lyrics)
        }
    }

    suspend fun songById(id: Long): Song? = repository.getSongById(id)

    fun fetchSongLyrics(song: Song, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(repository.fetchSongLyrics(song))
        }
    }

    fun deleteSongsFromDevice(songs: List<Song>) {
        viewModelScope.launch {
            repository.deleteSongsFromDevice(songs)
            pruneIdentifyReview(songs.map { it.id }.toSet())
        }
    }

    fun removeFromQueue(index: Int) {
        playbackRuntime.removeFromQueue(index)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        playbackRuntime.moveQueueItem(fromIndex, toIndex)
    }

    fun moveDisplayQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        moveQueueItem(fromIndex, toIndex)
    }

    fun skipToQueueIndex(index: Int) {
        playbackRuntime.skipToQueueIndex(index)
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

    private fun updateNavigation(
        transform: (UiNavigationState) -> UiNavigationState
    ): Boolean {
        while (true) {
            val current = _navigation.value
            val updated = transform(current)
            if (updated == current) return false
            if (_navigation.compareAndSet(current, updated)) {
                if (_selectedNavIndex.value != updated.selectedNavIndex) {
                    _selectedNavIndex.value = updated.selectedNavIndex
                }
                return true
            }
        }
    }

    fun setSelectedNavIndex(index: Int, persist: Boolean = true) {
        val sanitized = LibraryUiPreferencesCodec.sanitizeNavIndex(index)
        navIndexBeforeTransient = null
        if (_selectedNavIndex.value == sanitized && _navigation.value.selectedNavIndex == sanitized) {
            if (sanitized == NAV_PLAYLISTS) maybeRestoreDiscoverDetail()
            return
        }
        _selectedNavIndex.value = sanitized
        updateNavigation { it.copy(selectedNavIndex = sanitized) }
        if (persist) {
            persistedNavIndex = sanitized
            persistNavSnapshot()
        }
        if (sanitized == NAV_PLAYLISTS) maybeRestoreDiscoverDetail()
    }

    fun openDownloadsTabTransient() {
        updateNavigation { it.copy(selectedNavIndex = NAV_DOWNLOADS) }
    }

    fun setLibraryBrowseFilter(filter: LibraryBrowseFilter) {
        if (updateNavigation { it.copy(libraryBrowseFilter = filter) }) persistNavSnapshot()
    }

    fun openLibraryAlbum(name: String, fromNestedParent: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (updateNavigation {
                it.copy(libraryStack = it.libraryStack.openAlbum(trimmed, fromNestedParent))
            }
        ) {
            persistNavSnapshot()
        }
    }

    fun openLibraryArtist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.openArtist(trimmed)) }) {
            persistNavSnapshot()
        }
    }

    fun openLibraryGenre(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.openGenre(trimmed)) }) {
            persistNavSnapshot()
        }
    }

    fun closeLibraryAlbum() {
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.closeAlbum()) }) {
            persistNavSnapshot()
        }
    }

    fun closeLibraryArtist() {
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.closeArtist()) }) {
            persistNavSnapshot()
        }
    }

    fun closeLibraryGenre() {
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.closeGenre()) }) {
            persistNavSnapshot()
        }
    }

    fun popLibraryNested() {
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.pop()) }) {
            persistNavSnapshot()
        }
    }

    fun renameRestoredLibraryAlbum(sourceKey: String, targetKey: String) {
        if (updateNavigation {
                it.copy(libraryStack = it.libraryStack.renameAlbum(sourceKey, targetKey))
            }
        ) {
            persistNavSnapshot()
        }
    }

    fun openLocalPlaylist(id: Long) {
        closeDiscoverSessionUi()
        updateNavigation { it.copy(playlistDetail = PlaylistDetailNav.Local(id)) }
        persistNavSnapshot()
    }

    fun openListenBrainzPlaylistDetail(mbid: String) {
        closeDiscoverSessionUi()
        updateNavigation { it.copy(playlistDetail = PlaylistDetailNav.ListenBrainz(mbid)) }
        persistNavSnapshot()
        openListenBrainzPlaylist(mbid)
    }

    fun openCfRecommendationsDetail() {
        closeDiscoverSessionUi()
        updateNavigation { it.copy(playlistDetail = PlaylistDetailNav.CfRecommendations) }
        persistNavSnapshot()
        openCfRecommendations()
    }

    fun closePlaylistDetail() {
        closeDiscoverSessionUi()
        if (updateNavigation { it.copy(playlistDetail = PlaylistDetailNav.None) }) {
            persistNavSnapshot()
        }
    }

    fun dismissDiscoverDetails() {
        val detail = _navigation.value.playlistDetail
        if (detail is PlaylistDetailNav.ListenBrainz || detail is PlaylistDetailNav.CfRecommendations) {
            closePlaylistDetail()
        } else {
            closeDiscoverSessionUi()
        }
    }

    private fun closeDiscoverSessionUi() {
        closeListenBrainzPlaylist()
    }

    private suspend fun hydrateUiPreferences() {
        val startedAt = System.nanoTime()
        val display = libraryPreferences.displaySettingsFlow.first()
        _sortOption.value = parseSortOption(display.sortOptionName)
        _sortDirection.value = parseSortDirection(display.sortDirectionName)
        _libraryViewMode.value = parseLibraryViewMode(display.viewModeName)

        val nav = libraryPreferences.navSnapshotFlow.first()
        applyNavSnapshot(nav)
        uiPrefsHydrated = true
        _libraryPrefsReady.value = true
        val ms = (System.nanoTime() - startedAt) / 1_000_000L
        PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "prefsReady in ${ms}ms")

        pruneRestoredLocalPlaylist()
        if (_navigation.value.selectedNavIndex == NAV_PLAYLISTS) {
            restoreDiscoverDetailOrFallback()
        }
    }

    private fun applyNavSnapshot(nav: UiNavSnapshot) {
        _navigation.value = UiNavigationState.fromSnapshot(nav)
        _selectedNavIndex.value = nav.navIndex
        persistedNavIndex = nav.navIndex
    }

    private fun persistNavSnapshot() {
        if (!uiPrefsHydrated) return
        // persistedNavIndex, not the live one: a transient tab (download notification deep link,
        // the Ajustes → Descargas shortcut) would otherwise become the next cold-start tab.
        val snapshot = _navigation.value.toSnapshot(persistedNavIndex)
        viewModelScope.launch(Dispatchers.IO) { libraryPreferences.setNavSnapshot(snapshot) }
    }

    private suspend fun pruneRestoredLibraryStack() {
        val songs = libraryProjection.songs.value.ifEmpty {
            repository.allSongsFlow.first()
        }
        val lookups = LibraryStackLookups.fromSongs(songs)
        val stack = _navigation.value.libraryStack
        val pruned = LibraryUiPreferencesCodec.pruneLibraryStack(
            albumName = stack.albumName,
            artistName = stack.artistName,
            genreName = stack.genreName,
            albumExists = lookups.albumExists,
            artistExists = lookups.artistExists,
            genreExists = lookups.genreExists
        )
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.applyPruned(pruned)) }) {
            persistNavSnapshot()
        }
    }

    private suspend fun pruneRestoredLocalPlaylist() {
        val detail = _navigation.value.playlistDetail as? PlaylistDetailNav.Local ?: return
        val lists = playlists.first()
        if (lists.none { it.id == detail.id }) {
            updateNavigation { it.copy(playlistDetail = PlaylistDetailNav.None) }
            persistNavSnapshot()
        }
    }

    private fun maybeRestoreDiscoverDetail() {
        val detail = _navigation.value.playlistDetail
        if (detail !is PlaylistDetailNav.ListenBrainz && detail !is PlaylistDetailNav.CfRecommendations) {
            return
        }
        val needsFetch = when (detail) {
            is PlaylistDetailNav.ListenBrainz ->
                _lbPlaylistDetail.value.data == null &&
                        !_lbPlaylistDetail.value.isLoading

            PlaylistDetailNav.CfRecommendations ->
                _cfRecommendations.value.data == null &&
                        !_cfRecommendations.value.isLoading

            else -> false
        }
        if (!needsFetch) return
        viewModelScope.launch { restoreDiscoverDetailOrFallback() }
    }

    private suspend fun restoreDiscoverDetailOrFallback() {
        when (val detail = _navigation.value.playlistDetail) {
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

    private fun reportLibraryProgress(
        kind: LibraryJobKind,
        done: Int,
        total: Int,
        label: String
    ) {
        _localLibraryJobProgress.value = LibraryJobProgress(kind, done, total, label)
    }

    private fun clearLibraryProgress() {
        _localLibraryJobProgress.value = null
    }

    private fun importScanProgress(): (Int, Int, String) -> Unit = { done, total, fileName ->
        reportLibraryProgress(LibraryJobKind.IMPORT, done, total, fileName)
    }

    // SAF Import
    fun importFolder(treeUri: Uri) {
        viewModelScope.launch {
            // Show the banner before the SAF tree walk; listing a big folder can take
            // minutes with no files indexed yet, which looked like a dead tap.
            reportLibraryProgress(LibraryJobKind.IMPORT, 0, 0, "Buscando archivos…")
            // finally: a stuck banner blocks every later library job (see the guard in
            // syncLibraryTagsToFiles), so it must clear even if the scan throws.
            val inserted = try {
                withContext(Dispatchers.IO) {
                    repository.scanFolderUri(treeUri, importScanProgress())
                }
            } catch (e: Exception) {
                CrashReporter.recordNonFatal(e, mapOf("scan_phase" to "folder_import"))
                toast("No se pudo leer esa carpeta")
                return@launch
            } finally {
                clearLibraryProgress()
            }
            val count = inserted.size
            toast(
                when {
                    count <= 0 -> "No se encontraron canciones nuevas en esa carpeta"
                    count == 1 -> "1 canción agregada a la biblioteca"
                    else -> "$count canciones agregadas a la biblioteca"
                }
            )
            identifyImportedGaps(inserted)
        }
    }

    /**
     * Installing an older APK makes Room drop every table (playlists, album overrides). Without this
     * the user just found them gone with no explanation.
     */
    private suspend fun warnIfDatabaseWasDowngraded() {
        val seen = libraryPreferences.highestDbVersionSeen()
        if (seen > AppDatabase.VERSION) {
            toast(
                "Instalaste una versión más vieja de BestiaPop: se reinició la base " +
                        "(playlists y datos de álbumes). Tus archivos de música siguen en Music/BestiaPop."
            )
        }
        if (seen < AppDatabase.VERSION) {
            libraryPreferences.setHighestDbVersionSeen(AppDatabase.VERSION)
        }
    }

    /**
     * First-install (or post-uninstall) disk import: BestiaPop folder + MediaStore.
     * Skipped on later cold starts / updates once [LibraryPreferencesRepository] marks completed.
     * Room migrations still run independently via [AppDatabase].
     */
    fun ensureInitialLibraryImport(showRecoveryToast: Boolean = false) {
        viewModelScope.launch {
            // Serialized: the VM init and MainActivity's permission callback both call this and both
            // used to pass the check-then-act before either wrote the flag, running the whole disk
            // import twice in parallel (duplicated work plus row-id churn).
            initialImportMutex.withLock {
                if (libraryPreferences.isInitialScanCompleted()) return@launch
                if (!hasAudioPermission()) return@launch
                try {
                    runLibraryDiskImport(showRecoveryToast = showRecoveryToast)
                } catch (e: Exception) {
                    e.printStackTrace()
                    com.bestiapop.android.data.util.CrashReporter.recordNonFatal(
                        e,
                        mapOf("phase" to "ensureInitialLibraryImport")
                    )
                } finally {
                    libraryPreferences.setInitialScanCompleted(true)
                }
            }
        }
    }

    private suspend fun runLibraryDiskImport(showRecoveryToast: Boolean) {
        val (recovered, inserted) = try {
            withContext(Dispatchers.IO) {
                val fromManaged = repository.resyncAppManagedMusic(importScanProgress())
                val fromMedia = repository.scanMediaStore(importScanProgress())
                fromManaged to (fromManaged + fromMedia)
            }
        } finally {
            clearLibraryProgress()
        }
        if (showRecoveryToast && recovered.isNotEmpty()) {
            toast(
                if (recovered.size == 1) "Se recuperó 1 canción de Music/BestiaPop"
                else "Se recuperaron ${recovered.size} canciones de Music/BestiaPop"
            )
        }
        identifyImportedGaps(inserted)
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
     * enqueue MEDIUM/LOW/NONE. [force] always looks up (manual setup). [showReview] opens the overlay.
     * Songs already pending review are skipped (no network).
     */
    fun identifySongs(
        songs: List<Song>,
        force: Boolean = false,
        showReview: Boolean = true,
        fields: IdentifyApplyFields = IdentifyApplyFields.ALL,
        fillGapsOnly: Boolean = false
    ) = identifyCoordinator.identifySongs(songs, force, showReview, fields, fillGapsOnly)

    /** Import/WiFi: identify only songs with missing/placeholder tags; do not open overlay. */
    fun identifyImportedGaps(songs: List<Song>) = identifyCoordinator.identifyImportedGaps(songs)

    /** Single-song identify: open existing pending item, or open setup configuration dialog. */
    fun identifySongForReview(song: Song) = identifyCoordinator.identifySongForReview(song)

    fun openIdentifySetup(songs: List<Song>, contextTitle: String = "") =
        identifyCoordinator.openIdentifySetup(songs, contextTitle)

    fun setIdentifySetupFields(fields: IdentifyApplyFields) =
        identifyCoordinator.setIdentifySetupFields(fields)

    fun setIdentifyReviewApplyFields(fields: IdentifyApplyFields) =
        identifyCoordinator.setIdentifyReviewApplyFields(fields)

    fun dismissIdentifySetup() = identifyCoordinator.dismissIdentifySetup()

    fun confirmIdentifySetup() = identifyCoordinator.confirmIdentifySetup()

    fun showIdentifyReview() = identifyCoordinator.showIdentifyReview()

    fun startIdentifyItemReview(groupKey: String? = null) =
        identifyCoordinator.startIdentifyItemReview(groupKey)

    fun returnIdentifyReviewOverview() = identifyCoordinator.returnIdentifyReviewOverview()

    fun applyIdentifyAlbumGroup(key: String) = identifyCoordinator.applyIdentifyAlbumGroup(key)

    private fun pruneIdentifyReview(ids: Set<Long>) = identifyCoordinator.pruneIdentifyReview(ids)

    fun selectIdentifyCandidate(index: Int) = identifyCoordinator.selectIdentifyCandidate(index)

    fun setIdentifySearchDraft(query: String) = identifyCoordinator.setIdentifySearchDraft(query)

    fun setIdentifySearchFilterArtist(value: String) =
        identifyCoordinator.setIdentifySearchFilterArtist(value)

    fun setIdentifySearchFilterAlbum(value: String) =
        identifyCoordinator.setIdentifySearchFilterAlbum(value)

    fun setIdentifySearchFilterYear(value: String) =
        identifyCoordinator.setIdentifySearchFilterYear(value)

    fun toggleIdentifySearchField(show: Boolean? = null) =
        identifyCoordinator.toggleIdentifySearchField(show)

    fun toggleIdentifySearchFilters(show: Boolean? = null) =
        identifyCoordinator.toggleIdentifySearchFilters(show)

    fun searchIdentifyCandidates() = identifyCoordinator.searchIdentifyCandidates()

    fun loadMoreIdentifyCandidates() = identifyCoordinator.loadMoreIdentifyCandidates()

    fun applySelectedIdentifyCandidate() = identifyCoordinator.applySelectedIdentifyCandidate()

    fun skipIdentifyReviewItem() = identifyCoordinator.skipIdentifyReviewItem()

    fun dismissIdentifyReview() = identifyCoordinator.dismissIdentifyReview()

    fun skipAllIdentifyReview() = identifyCoordinator.skipAllIdentifyReview()

    fun applyRemainingIdentifySuggestions() =
        identifyCoordinator.applyRemainingIdentifySuggestions()

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
        val detail = _navigation.value.playlistDetail
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
            if (enabled) playbackRuntime.requestListenSync()
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
            _tokenValidation.value = _tokenValidation.value.idle(data = null)
        }
    }

    fun validateListenBrainzToken(token: String = listenBrainzSettings.value.userToken) {
        viewModelScope.launch {
            val trimmed = token.trim()
            if (trimmed.isBlank()) {
                _tokenValidation.value = _tokenValidation.value.failure("Token vacío", data = null)
                return@launch
            }
            _tokenValidation.value = _tokenValidation.value.loading(data = null)
            listenBrainzPreferences.setToken(trimmed)
            val result = ListenBrainzClient.validateToken(trimmed)
            if (result.valid && !result.username.isNullOrBlank()) {
                listenBrainzPreferences.setUsername(result.username)
                listenBrainzPreferences.setEnabled(true)
                _tokenValidation.value = _tokenValidation.value.success(result.username)
                playbackRuntime.requestListenSync()
                if (listenBrainzSettings.value.discoverEnabled) {
                    refreshListenBrainzDiscoverPlaylists()
                }
            } else {
                listenBrainzPreferences.setUsername(null)
                _tokenValidation.value = _tokenValidation.value.failure(
                    message = result.message ?: "Token inválido",
                    data = null
                )
            }
        }
    }

    fun clearListenBrainz() {
        viewModelScope.launch {
            listenBrainzPreferences.clear()
            _tokenValidation.value = _tokenValidation.value.idle(data = null)
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
            _lbDiscover.update { it.loading() }
            when (
                val result = ListenBrainzClient.fetchCreatedForPlaylists(
                    username = username,
                    token = settings.userToken
                )
            ) {
                is LbApiResult.Success -> {
                    _lbDiscover.update { it.success(result.data) }
                }

                is LbApiResult.Failure -> {
                    _lbDiscover.update { it.failure(result.message) }
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
        _cfRecommendations.update { it.loading() }
        val library = repository.allSongsFlow.first()
        when (
            val result = fetchAndMatchCfRecommendationsUseCase.execute(
                username = username,
                token = settings.userToken.takeIf { it.isNotBlank() },
                library = library,
                artistType = FetchAndMatchCfRecommendationsUseCase.ARTIST_TYPE_TOP
            )
        ) {
            is LbApiResult.Success -> {
                _cfRecommendations.update { it.success(result.data) }
            }

            is LbApiResult.Failure -> {
                _cfRecommendations.update { it.failure(result.message) }
            }
        }
    }

    fun openCfRecommendations() {
        val current = _cfRecommendations.value
        if (current.data == null && !current.isLoading) {
            refreshCfRecommendations()
        }
    }

    private suspend fun loadCfRecommendationsForRestore(): Boolean {
        val settings = listenBrainzPreferences.settingsFlow.first()
        if (!settings.showDiscoverPlaylists) return false
        refreshCfRecommendationsInternal(settings)
        return _cfRecommendations.value.data != null && _cfRecommendations.value.isLoaded
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
        // Tracked so opening A then B cannot leave A's late response rendered under B's route.
        lbDetailJob?.cancel()
        lbDetailJob = viewModelScope.launch {
            loadListenBrainzPlaylist(mbid, forRestore = false)
        }
    }

    private fun isListenBrainzDetailCurrent(mbid: String): Boolean =
        _navigation.value.playlistDetail.lbMbidOrNull() == mbid

    private suspend fun loadListenBrainzPlaylist(mbid: String, forRestore: Boolean): Boolean {
        val settings = listenBrainzPreferences.settingsFlow.first()
        if (!settings.showDiscoverPlaylists || mbid.isBlank()) {
            if (!forRestore) {
                _lbPlaylistDetail.update {
                    it.failure("ListenBrainz no disponible", data = null)
                }
            }
            return false
        }
        _lbPlaylistDetail.update { it.loading(data = null) }
        return when (
            val result = ListenBrainzClient.fetchPlaylist(
                playlistMbid = mbid,
                token = settings.userToken
            )
        ) {
            is LbApiResult.Success -> {
                val library = repository.allSongsFlow.first()
                val matched = matchListenBrainzTracksUseCase.execute(result.data, library)
                // Only publish if this mbid is still the one on screen.
                if (!forRestore && !isListenBrainzDetailCurrent(mbid)) return false
                _lbPlaylistDetail.update { it.success(matched) }
                true
            }

            is LbApiResult.Failure -> {
                if (forRestore) {
                    _lbPlaylistDetail.update { it.idle(data = null) }
                } else {
                    _lbPlaylistDetail.update { it.failure(result.message, data = null) }
                }
                false
            }
        }
    }

    fun closeListenBrainzPlaylist() {
        _lbPlaylistDetail.update { it.idle(data = null) }
    }

    /** Saves matched locals + unmatched as pending metadata (no download yet). */
    fun saveListenBrainzPlaylistAsLocal(onCreated: ((Long) -> Unit)? = null) {
        val matched = _lbPlaylistDetail.value.data ?: return
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
        val matched = _lbPlaylistDetail.value.data ?: return
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

    fun toast(message: String) {
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
        val batchId = "${source.name}:${System.nanoTime()}"

        val queued = items.mapNotNull { item ->
            val downloadId = idStrategy(item)
            if (downloadId.isBlank()) return@mapNotNull null
            val lookup = item.lookupIdentity ?: item.track.identity
            if (processDownloadRuntime.isRunning(downloadId, lookup.artist, lookup.title)) {
                // Handoff and the claim completion are linearized by the coordinator. If the owner
                // completed between this hint and the attach, continue into execute() instead of
                // dropping the pending destination.
                val attached = playlistId != null &&
                        processDownloadRuntime.attachPlaylistDestination(
                            downloadId = downloadId,
                            artist = lookup.artist,
                            title = lookup.title,
                            destination = DownloadPlaylistDestination(
                                playlistId = playlistId,
                                identity = lookup
                            )
                        )
                if (playlistId == null || attached) return@mapNotNull null
            }
            val candidates = item.candidates.ifEmpty { listOf(item.track) }
            val safeIndex = item.currentCandidateIndex.coerceIn(0, candidates.lastIndex)
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
                        lookupIdentity = item.lookupIdentity,
                        batchId = batchId
                    )
                    if (result.isSuccess) successCount.incrementAndGet()
                }
            }.awaitAll()
        }

        // Denominator is what this batch actually ran: counting items already downloading under
        // another job read as "9 de 10 procesadas" with nothing to explain the missing one.
        toast(DownloadMessages.batchProcessed(successCount.get(), queued.size))
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
        _lbDiscover.update { it.idle(emptyList()) }
        closeListenBrainzPlaylist()
        clearCfState()
        val detail = _navigation.value.playlistDetail
        if (detail is PlaylistDetailNav.ListenBrainz || detail is PlaylistDetailNav.CfRecommendations) {
            updateNavigation { it.copy(playlistDetail = PlaylistDetailNav.None) }
            persistNavSnapshot()
        }
    }

    private fun clearCfState() {
        _cfRecommendations.update { it.idle(data = null) }
    }

    // Online Catalog & Link Downloader Actions
    private var lastCatalogQuery = ""
    private var lastCatalogFilters = IdentifySearchFilters()

    fun setCatalogCategory(category: CatalogCategory) {
        _catalogSearch.update { it.copy(category = category) }
        searchCatalog(lastCatalogQuery, lastCatalogFilters)
    }

    fun setCatalogSearchDraft(query: String) {
        _catalogSearch.update { it.copy(searchQueryDraft = query) }
    }

    fun setCatalogSearchFilterArtist(artist: String) {
        _catalogSearch.update { it.copy(searchFilterArtist = artist) }
    }

    fun setCatalogSearchFilterAlbum(album: String) {
        _catalogSearch.update { it.copy(searchFilterAlbum = album) }
    }

    fun setCatalogSearchFilterYear(year: String) {
        _catalogSearch.update { it.copy(searchFilterYear = year) }
    }

    /** Level 2: Update all catalog search filters at once using [IdentifySearchFilters]. */
    fun setCatalogSearchFilters(filters: IdentifySearchFilters) {
        _catalogSearch.update { it.withSearchFilters(filters) }
    }

    fun toggleCatalogSearchFilters(show: Boolean? = null) {
        _catalogSearch.update { state ->
            val next = show ?: !state.showSearchFilters
            state.copy(showSearchFilters = next)
        }
    }

    fun clearCatalogSearchFilters() {
        _catalogSearch.update {
            it.copy(
                searchFilterArtist = "",
                searchFilterAlbum = "",
                searchFilterYear = ""
            )
        }
    }

    fun setDiscoverSource(source: DiscoverSourcePreference) {
        viewModelScope.launch {
            libraryPreferences.setDiscoverSourcePreference(source)
            refreshDiscoverFeed()
        }
    }

    fun refreshDiscoverFeed(forceRefresh: Boolean = false) {
        if (forceRefresh) {
            ListenBrainzClient.clearUserStatsCache()
        }
        viewModelScope.launch {
            _isLoadingDiscoverFeed.value = true
            try {
                val songs = repository.allSongsFlow.first()
                val stats = repository.songPlayStatsFlow.first()
                val lbSettings = listenBrainzSettings.value
                val currentSource = discoverSource.value
                val preloadedLbTracks = _cfRecommendations.value.data?.matches?.mapNotNull { match ->
                    match.recordingMbid?.let { mbid -> match.identity.toListenBrainzCatalogTrack(mbid) }
                }.orEmpty()
                val feed = getDiscoverRecommendationsUseCase.execute(
                    librarySongs = songs,
                    playStats = stats,
                    userToken = lbSettings.userToken,
                    username = lbSettings.username,
                    sourcePreference = currentSource,
                    preloadedLbTracks = preloadedLbTracks
                )
                _discoverFeed.value = feed
            } catch (_: Exception) {
            } finally {
                _isLoadingDiscoverFeed.value = false
            }
            if (discoverSource.value != DiscoverSourcePreference.DEEZER) {
                refreshListenBrainzDiscoverPlaylists()
            }
        }
    }

    fun refreshTopRelatedFeed(forceRefresh: Boolean = false) {
        if (forceRefresh) {
            ListenBrainzClient.clearUserStatsCache()
        }
        viewModelScope.launch {
            _isLoadingTopRelatedFeed.value = true
            try {
                val songs = repository.allSongsFlow.first()
                val stats = repository.songPlayStatsFlow.first()
                val lbSettings = listenBrainzSettings.value
                val feed = getTopRelatedItemsUseCase.execute(
                    librarySongs = songs,
                    playStats = stats,
                    username = lbSettings.username,
                    token = lbSettings.userToken
                )
                _topRelatedFeed.value = feed
            } catch (_: Exception) {
            } finally {
                _isLoadingTopRelatedFeed.value = false
            }
        }
    }

    fun addRecentSearch(query: String) {
        viewModelScope.launch {
            searchHistoryPreferences.addSearchQuery(query)
        }
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch {
            searchHistoryPreferences.removeSearchQuery(query)
        }
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            searchHistoryPreferences.clearSearchHistory()
        }
    }

    fun saveAlbumToLibrary(album: CatalogAlbum, candidates: List<CatalogTrackCandidate> = emptyList()) {
        viewModelScope.launch {
            try {
                val effectiveCandidates = if (candidates.isNotEmpty()) {
                    candidates
                } else {
                    MetadataFetcher.fetchAlbumTrackCandidates(
                        albumId = album.id,
                        albumTitle = album.title,
                        artistName = album.artist,
                        albumCoverUrl = album.coverUrl
                    )
                }
                repository.saveAlbumTracksToLibrary(
                    albumTitle = album.title,
                    artistName = album.artist,
                    coverUrl = album.coverUrl,
                    year = album.releaseYear.toIntOrNull() ?: 0,
                    genre = Song.UNKNOWN_GENRE,
                    tracks = effectiveCandidates
                )
                toast("Álbum guardado en la biblioteca")
            } catch (e: Exception) {
                toast("Error al guardar álbum: ${e.message}")
            }
        }
    }

    fun removeSavedAlbum(albumName: String, artistName: String) {
        viewModelScope.launch {
            try {
                val removed = repository.removeSavedAlbumFromLibrary(albumName, artistName)
                toast(if (removed > 0) "Álbum eliminado de la biblioteca" else "No se encontraron pistas para eliminar")
            } catch (e: Exception) {
                toast("Error al eliminar álbum: ${e.message}")
            }
        }
    }

    private inline fun updateCatalogSearchIfCurrent(
        generation: Long,
        crossinline transform: (CatalogSearchUiState) -> CatalogSearchUiState
    ) {
        if (generation == catalogSearchGeneration) {
            _catalogSearch.update { transform(it) }
        }
    }

    private var catalogDebounceJob: kotlinx.coroutines.Job? = null

    /** Level 2: Debounced search for live typing in search bars without spamming HTTP or canceling early. */
    fun searchCatalogDebounced(
        query: String = _catalogSearch.value.searchQueryDraft,
        filters: IdentifySearchFilters = _catalogSearch.value.searchFilters,
        debounceMs: Long = 350L
    ) {
        catalogDebounceJob?.cancel()
        val cleanQ = query.trim()
        if (cleanQ.isEmpty()) {
            searchCatalog(query = "", filters = filters, saveToRecent = false)
            return
        }
        catalogDebounceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(debounceMs)
            searchCatalog(query = cleanQ, filters = filters, saveToRecent = false)
        }
    }

    /** Level 2: Submits an explicit catalog search (e.g. on keyboard Enter or suggestion tap), saving to recent searches. */
    fun submitCatalogSearch(
        query: String = _catalogSearch.value.searchQueryDraft,
        filters: IdentifySearchFilters = _catalogSearch.value.searchFilters
    ) {
        searchCatalog(query = query, filters = filters, saveToRecent = true)
    }

    fun searchCatalog(
        query: String = _catalogSearch.value.searchQueryDraft,
        filters: IdentifySearchFilters = _catalogSearch.value.searchFilters,
        saveToRecent: Boolean = false
    ) {
        catalogDebounceJob?.cancel()
        lastCatalogQuery = query
        lastCatalogFilters = filters
        val cleanQ = query.trim()
        if (saveToRecent && cleanQ.isNotBlank()) {
            addRecentSearch(cleanQ)
        }
        val normalizedFilters = filters.normalized()
        val effectiveQuery = IdentifyCatalogQuery.build(cleanQ, normalizedFilters)
        val generation = ++catalogSearchGeneration
        val category = _catalogSearch.value.category
        catalogSearchJob?.cancel()
        catalogSearchJob = viewModelScope.launch {
            _catalogSearch.update { it.copy(isSearching = true) }
            when (category) {
                CatalogCategory.SONGS -> {
                    val results = if (effectiveQuery.isEmpty() && !normalizedFilters.hasAny) {
                        MetadataFetcher.getFeaturedDemoCatalog()
                    } else {
                        MetadataFetcher.searchOnlineCatalog(effectiveQuery)
                    }
                    updateCatalogSearchIfCurrent(generation) { it.copy(tracks = results) }
                }

                CatalogCategory.ALBUMS -> {
                    val results = MetadataFetcher.searchAlbums(effectiveQuery.ifEmpty { cleanQ })
                    updateCatalogSearchIfCurrent(generation) { it.copy(albums = results) }
                }

                CatalogCategory.PLAYLISTS -> {
                    val results = MetadataFetcher.searchPlaylists(cleanQ.ifEmpty { effectiveQuery })
                    updateCatalogSearchIfCurrent(generation) { it.copy(playlists = results) }
                }

                CatalogCategory.GENRES -> {
                    val genres = MetadataFetcher.listGenres()
                    val results = if (cleanQ.isEmpty()) {
                        genres
                    } else {
                        genres.filter { TrackMatchKeys.containsNormalized(it.name, cleanQ) }
                    }
                    updateCatalogSearchIfCurrent(generation) { it.copy(genres = results) }
                }

                CatalogCategory.CHARTS -> {
                    val results = MetadataFetcher.fetchChartTracks()
                    updateCatalogSearchIfCurrent(generation) { it.copy(tracks = results) }
                }
            }
            updateCatalogSearchIfCurrent(generation) { it.copy(isSearching = false) }
            // The fetchers degrade to an empty list on any transport error, so an empty list reads as
            // "this song does not exist". Say it out loud when the reason is simply no connection.
            if (_catalogSearch.value.currentResultsAreEmpty() && !connectivityObserver.isCurrentlyOnline()) {
                toast("Sin conexión: no se pudo buscar en el catálogo")
            }
        }
    }

    fun searchOnlineCatalog(query: String) {
        searchCatalog(query)
    }

    /** Level 1: Low-level primitive album inspection with explicit title, artist and cover. */
    fun selectAlbumForInspection(
        title: String,
        artist: String,
        coverUrl: String? = null,
        albumId: String = ""
    ) {
        val key = if (albumId.isNotBlank()) "album:$albumId" else "album:$artist:$title"
        selectCollectionForInspection(
            selectionKey = key,
            title = title,
            kind = CatalogCollectionKind.ALBUM,
            coverUrl = coverUrl
        ) {
            MetadataFetcher.fetchAlbumTrackCandidates(albumId, title, artist, coverUrl)
        }
    }

    /** Level 2: Inspect a [CatalogAlbum]. */
    fun selectAlbumForInspection(album: CatalogAlbum) {
        selectAlbumForInspection(
            title = album.title,
            artist = album.artist,
            coverUrl = album.coverUrl,
            albumId = album.id
        )
    }

    /** Level 2: Inspect a [RelatedAlbumItem] without converting to a dummy [CatalogAlbum]. */
    fun selectAlbumForInspection(album: RelatedAlbumItem) {
        selectAlbumForInspection(
            title = album.title,
            artist = album.artist,
            coverUrl = album.artworkUri,
            albumId = ""
        )
    }

    fun selectPlaylistForInspection(playlist: CatalogPlaylist) {
        selectCollectionForInspection(
            selectionKey = "playlist:${playlist.id}",
            title = playlist.title,
            kind = CatalogCollectionKind.PLAYLIST,
            coverUrl = playlist.coverUrl
        ) {
            MetadataFetcher.fetchPlaylistTrackCandidates(playlist.id, playlist.title)
        }
    }

    fun selectGenreForInspection(genre: CatalogGenre) {
        selectCollectionForInspection(
            selectionKey = "genre:${genre.id}",
            title = genre.name,
            kind = CatalogCollectionKind.GENRE,
            coverUrl = genre.pictureUrl
        ) {
            MetadataFetcher.searchTracksByGenre(genre.id, genre.name)
                .map { MetadataFetcher.toCatalogCandidate(it) }
        }
    }

    private fun updateCatalogCollection(
        selectionKey: String,
        transform: (CatalogCollectionUiState) -> CatalogCollectionUiState
    ): Boolean {
        while (true) {
            val current = _catalogCollection.value
            if (current.selectionKey != selectionKey) return false
            val updated = transform(current)
            if (updated == current) return true
            if (_catalogCollection.compareAndSet(current, updated)) return true
        }
    }

    private fun selectCollectionForInspection(
        selectionKey: String,
        title: String,
        kind: CatalogCollectionKind,
        coverUrl: String?,
        fetch: suspend () -> List<CatalogTrackCandidate>
    ) {
        val requestKey = "$selectionKey#${++catalogCollectionGeneration}"
        catalogCollectionJob?.cancel()
        catalogBatchPlaylistTarget = null
        _catalogCollection.value = CatalogCollectionUiState(
            selectionKey = requestKey,
            title = title,
            kind = kind,
            coverUrl = coverUrl,
            isLoading = true
        )
        catalogCollectionJob = viewModelScope.launch {
            val candidates = fetch()
            updateCatalogCollection(requestKey) { current ->
                current.copy(candidates = candidates, isLoading = false)
            }
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
        val collection = _catalogCollection.value
        val selectionKey = collection.selectionKey ?: return
        val list = collection.candidates.toMutableList()
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
            if (updateCatalogCollection(selectionKey) { it.copy(candidates = list) }) {
                updated.currentTrack
            } else {
                null
            }
        }
    }

    /** Cycle YouTube match for a song result in the catalog songs list ("Buscar otro"). */
    fun cycleSongCatalogResult(index: Int) {
        val list = _catalogSearch.value.tracks.toMutableList()
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
            _catalogSearch.update { it.copy(tracks = list) }
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
        val collection = _catalogCollection.value
        val selectionKey = collection.selectionKey ?: return
        val list = collection.candidates.toMutableList()
        if (index in list.indices) {
            val item = list[index]
            list[index] = item.copy(isSelected = !item.isSelected)
            updateCatalogCollection(selectionKey) { it.copy(candidates = list) }
        }
    }

    fun clearSelectedCollection() {
        catalogCollectionJob?.cancel()
        catalogCollectionJob = null
        catalogBatchPlaylistTarget = null
        _catalogCollection.value = CatalogCollectionUiState()
    }

    fun resolveDownloadConflictOverwrite(applyToRemainingBatch: Boolean = false) {
        processDownloadRuntime.resolveConflictOverwrite(applyToRemainingBatch)
    }

    fun resolveDownloadConflictSaveAs(newTitle: String, applyToRemainingBatch: Boolean = false) {
        processDownloadRuntime.resolveConflictSaveAs(newTitle, applyToRemainingBatch)
    }

    fun cancelDownloadConflict() {
        processDownloadRuntime.cancelConflict()
    }

    fun clearBatchConflictPolicy() {
        processDownloadRuntime.clearBatchConflictPolicy()
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

    /** Submit to the process runtime; cancelling this caller only stops waiting for the result. */
    private suspend fun runTrackedDownload(
        downloadId: String,
        source: ActiveDownloadSource,
        track: OnlineCatalogTrack,
        existingCandidates: List<OnlineCatalogTrack>? = null,
        currentCandidateIndex: Int = 0,
        targetPlaylistId: Long? = null,
        conflictPolicy: DownloadConflictPolicy? = null,
        lookupIdentity: TrackIdentity? = null,
        batchId: String? = null,
        titleOverride: String? = null
    ): Result<Song> = processDownloadRuntime.submit(
        ProcessDownloadRequest(
            downloadId = downloadId,
            source = source,
            track = track,
            candidates = existingCandidates?.takeIf { it.isNotEmpty() } ?: listOf(track),
            currentCandidateIndex = currentCandidateIndex,
            targetPlaylistId = targetPlaylistId,
            conflictPolicy = conflictPolicy,
            lookupIdentity = lookupIdentity,
            batchId = batchId,
            titleOverride = titleOverride
        )
    ).await()

    private suspend fun rematchDiscoverAfterLibraryChange(extraSong: Song? = null) {
        val library = libraryWithExtra(extraSong)
        _lbPlaylistDetail.value.data?.let { current ->
            _lbPlaylistDetail.update {
                it.copy(data = current.copy(matches = current.matches.rematchLocals(library)))
            }
        }
        _cfRecommendations.value.data?.let { current ->
            _cfRecommendations.update {
                it.copy(data = current.copy(matches = current.matches.rematchLocals(library)))
            }
        }
    }

    private suspend fun libraryWithExtra(extraSong: Song?): List<Song> =
        repository.allSongsFlow.first().let { list ->
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
            toast(DownloadMessages.missingArtistOrTitle)
            return
        }
        val existing = processDownloadRuntime.findClaimedDownload(
            key,
            remote.artist,
            remote.title
        )
        if (processDownloadRuntime.isRunning(key, remote.artist, remote.title)) {
            toastDownloadsQueued(alreadyQueued = true)
            return
        }
        when (existing?.state) {
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
        processDownloadRuntime.retry(id)
    }

    fun resumeAllDownloads() {
        processDownloadRuntime.resumeAllErrors()
    }

    fun cycleActiveDownload(id: String) {
        val download = activeDownloads.value.find { it.id == id } ?: return
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
            processDownloadRuntime.upsertRow(cycled)
            cycled.currentTrack
        }
    }

    fun previewActiveDownload(id: String) {
        val track = activeDownloads.value.find { it.id == id }?.currentTrack ?: return
        playOnlineCatalogTrackAsStream(track)
    }

    fun playActiveDownload(id: String) {
        val download = activeDownloads.value.find { it.id == id } ?: return
        val songId = download.resultSongId ?: return
        viewModelScope.launch {
            val song = repository.getSongById(songId) ?: return@launch
            playSong(song)
        }
    }

    fun dismissActiveDownload(id: String) {
        processDownloadRuntime.dismiss(id)
    }

    fun dismissAllActiveDownloads() {
        processDownloadRuntime.dismissAll()
    }

    fun downloadSingleCandidate(index: Int) {
        val collection = _catalogCollection.value
        val list = collection.candidates
        if (index !in list.indices) return
        val candidate = list[index]
        val track = candidate.currentTrack ?: return
        val downloadId = activeDownloadIdFor(
            track,
            ActiveDownloadSource.BATCH,
            explicitId = TrackMatchKeys.batchDownloadIdFor(candidate.artist, candidate.title)
        )

        viewModelScope.launch {
            val targetPlaylistId = ensureCatalogPlaylistForBatch(collection)
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
        val collection = _catalogCollection.value
        val selected = collection.candidates.filter {
            it.isSelected && it.currentTrack != null
        }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            clearBatchConflictPolicy()
            val targetPlaylistId = ensureCatalogPlaylistForBatch(collection)
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
        }
    }

    /**
     * When downloading from a catalog playlist inspection, create a matching local playlist
     * once per batch session and return its id for [ActiveDownload.targetPlaylistId].
     */
    private suspend fun ensureCatalogPlaylistForBatch(
        collection: CatalogCollectionUiState
    ): Long? = catalogBatchPlaylistMutex.withLock {
        val selectionKey = collection.selectionKey ?: return@withLock null
        if (collection.kind != CatalogCollectionKind.PLAYLIST) return@withLock null
        catalogBatchPlaylistTarget
            ?.takeIf { it.selectionKey == selectionKey }
            ?.let { return@withLock it.playlistId }
        val name = collection.title?.takeIf { it.isNotBlank() } ?: "Playlist"
        val id = repository.createPlaylist(name, coverUri = collection.coverUrl)
        if (_catalogCollection.value.selectionKey == selectionKey) {
            catalogBatchPlaylistTarget = CatalogBatchPlaylistTarget(selectionKey, id)
        }
        id
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

    override fun onCleared() {
        playbackRuntime.detachUi()
        super.onCleared()
    }

    private suspend fun awaitFirstCatalogLoaded() {
        libraryProjection.catalogLoaded.first { it }
    }

    private suspend fun awaitFirstLibraryIdle() {
        libraryProjection.songList.first { !it.isEmpty }
        delay(FIRST_LIBRARY_IDLE_MS)
    }

    companion object {
        const val RADIO_LOADING_LABEL = "Armando radio…"
        private const val METADATA_ENHANCE_BATCH = 20
        private const val FIRST_LIBRARY_IDLE_MS = 1_500L
    }
}

