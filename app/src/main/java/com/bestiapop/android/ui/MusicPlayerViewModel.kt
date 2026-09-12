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
import com.bestiapop.android.data.preferences.DEFAULT_CROSSFADE_DURATION_SECONDS
import com.bestiapop.android.data.preferences.DEFAULT_STREAM_SKIP_GRACE_SECONDS
import com.bestiapop.android.data.preferences.NAV_DISCOVER
import com.bestiapop.android.data.preferences.NAV_DOWNLOADS
import com.bestiapop.android.data.preferences.NAV_LIBRARY
import com.bestiapop.android.data.preferences.NAV_PLAYLISTS
import com.bestiapop.android.data.preferences.NAV_SETTINGS
import com.bestiapop.android.data.preferences.activeDownloadBadgeCount
import com.bestiapop.android.data.preferences.SearchHistoryPreferencesRepository
import com.bestiapop.android.domain.usecase.GetDiscoverRecommendationsUseCase
import com.bestiapop.android.domain.usecase.GetTopRelatedItemsUseCase
import com.bestiapop.android.domain.usecase.DiscoverFeed
import com.bestiapop.android.domain.usecase.RelatedAlbumItem
import com.bestiapop.android.domain.usecase.TopRelatedFeed
import com.bestiapop.android.data.model.toListenBrainzCatalogTrack
import com.bestiapop.android.data.preferences.DiscoverSourcePreference
import com.bestiapop.android.data.preferences.FastScrollSettings
import com.bestiapop.android.data.preferences.FastScrollSide
import com.bestiapop.android.data.preferences.SubmenuGestureSettings
import com.bestiapop.android.data.preferences.SubmenuSwipeAction
import com.bestiapop.android.data.preferences.LibraryBlobsSettings
import com.bestiapop.android.data.preferences.UiNavSnapshot
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.LyricsPreferencesRepository
import com.bestiapop.android.data.preferences.LyricsSettings
import com.bestiapop.android.data.preferences.JapanesePhoneticMode
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.TelemetryPreferencesRepository
import com.bestiapop.android.data.preferences.ThemePreferencesRepository
import com.bestiapop.android.data.network.LyricsTranslationSource
import com.bestiapop.android.data.system.BACKGROUND_RESTRICTION_CONFIRM_MS
import com.bestiapop.android.data.system.BackgroundExecutionProbe
import com.bestiapop.android.data.system.BackgroundExecutionStatus
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.PlaybackDiagnostics
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
import com.bestiapop.android.ui.state.LibraryBrowseStack
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryListModel
import com.bestiapop.android.ui.state.LibraryProjectionState
import com.bestiapop.android.ui.state.LibraryViewMode
import com.bestiapop.android.ui.state.LoadableUiState
import com.bestiapop.android.ui.state.DiscoverFeedCoordinator
import com.bestiapop.android.ui.state.LibraryEditCoordinator
import com.bestiapop.android.ui.state.PendingAlbumMerge
import com.bestiapop.android.ui.state.LyricsCoordinator
import com.bestiapop.android.ui.state.LyricsTranslationState
import com.bestiapop.android.ui.state.AudioVolumeCoordinator
import com.bestiapop.android.ui.state.CatalogDownloadCoordinator
import com.bestiapop.android.ui.state.CatalogInspectionCoordinator
import com.bestiapop.android.ui.state.PlaylistCoordinator
import com.bestiapop.android.ui.state.PlaylistDetailNav
import com.bestiapop.android.ui.state.RadioPlaybackState
import com.bestiapop.android.ui.state.SimilarPlaylistCoordinator
import com.bestiapop.android.ui.state.SimilarPlaylistPreviewState
import com.bestiapop.android.ui.state.SubmenuActionCoordinator
import com.bestiapop.android.ui.state.UiNavigationState
import com.bestiapop.android.ui.state.lbMbidOrNull
import com.bestiapop.android.ui.state.mapToUiState
import com.bestiapop.android.ui.state.stateInUi
import com.bestiapop.android.ui.theme.ThemePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.bestiapop.android.ui.theme.DynamicThemeEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
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
@kotlin.OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
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
    private val lyricsPreferences = LyricsPreferencesRepository(application)
    private val telemetryPreferences = TelemetryPreferencesRepository(application)
    private val identifyReviewStore = IdentifyReviewStore(application)
    private val pendingListenDao = AppDatabase.getDatabase(application).pendingListenDao()
    private val connectivityObserver = ConnectivityObserver(application)

    // Theme state
    val configuredThemeState: StateFlow<CustomTheme> = themeRepository.selectedThemeFlow
        .stateInUi(viewModelScope, themeRepository.initialTheme)

    val currentThemeState: StateFlow<CustomTheme> = themeRepository.selectedThemeFlow
        .flatMapLatest { selectedTheme ->
            if (selectedTheme.id == ThemePresets.DYNAMIC_THEME_ID) {
                val initialState = DynamicThemeEngine.DynamicThemeState(
                    theme = selectedTheme,
                    artworkUri = themeRepository.lastDynamicArtworkUri
                )
                DynamicThemeEngine.dynamicThemeFlow(
                    context = application,
                    artworkUriFlow = playbackRuntime.currentItem.map { it?.artworkUri },
                    initialState = initialState,
                    isDark = selectedTheme.isDark,
                    onThemeChanged = { nextState ->
                        themeRepository.saveDynamicTheme(nextState.theme, nextState.artworkUri)
                    }
                )
            } else {
                flowOf(selectedTheme)
            }
        }
        .stateInUi(viewModelScope, themeRepository.initialTheme)

    // ListenBrainz state
    val listenBrainzSettings: StateFlow<ListenBrainzSettings> =
        listenBrainzPreferences.settingsFlow
            .stateInUi(viewModelScope, ListenBrainzSettings())

    val playbackSettings: StateFlow<PlaybackSettings> = playbackRuntime.playbackSettings

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

    val telemetryEnabled: StateFlow<Boolean> =
        telemetryPreferences.telemetryEnabledFlow
            .stateInUi(viewModelScope, telemetryPreferences.initialTelemetryEnabled)

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
    val openNowPlayingOnPlay: StateFlow<Boolean> = playbackPref(true) { it.openNowPlayingOnPlay }
    val crossfadeEnabled: StateFlow<Boolean> = playbackPref(false) { it.crossfadeEnabled }
    val crossfadeDurationSeconds: StateFlow<Int> =
        playbackPref(DEFAULT_CROSSFADE_DURATION_SECONDS) { it.crossfadeDurationSeconds }

    private val _openNowPlayingEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openNowPlayingEvents: SharedFlow<Unit> = _openNowPlayingEvents.asSharedFlow()

    val pendingListenCount: StateFlow<Int> = pendingListenDao.countFlow()
        .stateInUi(viewModelScope, 0)

    private val _tokenValidation = MutableStateFlow(LoadableUiState<String?>(null))
    val tokenValidation = _tokenValidation.asStateFlow()

    private val matchListenBrainzTracksUseCase = MatchListenBrainzTracksUseCase()
    private val importListenBrainzPlaylistUseCase = ImportListenBrainzPlaylistUseCase(repository)
    private val fetchAndMatchCfRecommendationsUseCase = FetchAndMatchCfRecommendationsUseCase()

    private val _lbPlaylistDetail =
        MutableStateFlow(LoadableUiState<MatchedLbPlaylist?>(null))
    val lbPlaylistDetail = _lbPlaylistDetail.asStateFlow()

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
    private data class LibraryLookupIndex(
        val localSongsByMatchKey: Map<String, Song> = emptyMap(),
        val allSongsByMatchKey: Map<String, Song> = emptyMap(),
        val allSongsById: Map<Long, Song> = emptyMap(),
        val albumStatusByArtistAndAlbum: Map<String, ItemLibraryStatus> = emptyMap(),
        val albumStatusByTitle: Map<String, ItemLibraryStatus> = emptyMap()
    )

    private val libraryLookupIndex: StateFlow<LibraryLookupIndex> = rawSongs
        .map { songs ->
            val byId = HashMap<Long, Song>(songs.size)
            val allByMatchKey = HashMap<String, Song>(songs.size)
            val localByMatchKey = HashMap<String, Song>(songs.size)
            val albumStatusByArtistAndAlbum = HashMap<String, ItemLibraryStatus>()
            val albumStatusByTitle = HashMap<String, ItemLibraryStatus>()

            for (song in songs) {
                if (song.id > 0L) {
                    byId[song.id] = song
                }
                val matchKey = TrackMatchKeys.matchKey(song.artist, song.title)
                if (matchKey.isNotEmpty()) {
                    allByMatchKey.putIfAbsent(matchKey, song)
                    if (!song.isRemote) {
                        localByMatchKey.putIfAbsent(matchKey, song)
                    }
                }
                val artistAlbumKey = albumArtistKey(song.artist, song.album)
                val status = if (!song.isRemote) ItemLibraryStatus.DOWNLOADED else ItemLibraryStatus.SAVED_REMOTE
                if (albumStatusByArtistAndAlbum[artistAlbumKey] != ItemLibraryStatus.DOWNLOADED) {
                    albumStatusByArtistAndAlbum[artistAlbumKey] = status
                }
                val albumKey = albumIdentityKey(song.album)
                if (albumStatusByTitle[albumKey] != ItemLibraryStatus.DOWNLOADED) {
                    albumStatusByTitle[albumKey] = status
                }
            }

            LibraryLookupIndex(
                localSongsByMatchKey = localByMatchKey,
                allSongsByMatchKey = allByMatchKey,
                allSongsById = byId,
                albumStatusByArtistAndAlbum = albumStatusByArtistAndAlbum,
                albumStatusByTitle = albumStatusByTitle
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LibraryLookupIndex()
        )

    /** Level 2: Resolves an iterable of song IDs into the corresponding Songs in O(1) per song. */
    fun songsForIds(ids: Iterable<Long>): List<Song> {
        val index = libraryLookupIndex.value.allSongsById
        return ids.mapNotNull { index[it] }
    }
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
        getLibrarySongsUseCase.songsForArtist(songs, artistName)

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
        if (filter == LibraryBrowseFilter.PLAYLISTS) {
            viewModelScope.launch {
                val detailId = (_navigation.value.playlistDetail as? PlaylistDetailNav.Local)?.id
                val songsToPlay = if (detailId != null) {
                    repository.getPlaylistSongsOrdered(detailId)
                } else {
                    val currentPlaylists = playlists.first()
                    currentPlaylists.flatMap { repository.getPlaylistSongsOrdered(it.id) }
                }
                if (songsToPlay.isEmpty()) return@launch
                if (shuffle) shuffleCollection(songsToPlay) else playCollection(songsToPlay)
            }
            return
        }
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

    val radioActive = playbackRuntime.radioActive
    val radioLoading = playbackRuntime.radioLoading
    val radioMode = playbackRuntime.radioMode
    val radioStatusLabel = playbackRuntime.radioStatusLabel
    val radioState: StateFlow<RadioPlaybackState> = combine(
        radioActive,
        radioLoading,
        radioMode,
        radioStatusLabel
    ) { active, loading, mode, statusLabel ->
        RadioPlaybackState(
            active = active,
            loading = loading,
            mode = mode,
            statusLabel = statusLabel
        )
    }.stateInUi(viewModelScope, RadioPlaybackState())

    /** Stable key of the catalog track being previewed inside Add Music (null = no catalog preview). */
    private val _catalogPreviewKey = MutableStateFlow<String?>(null)
    val catalogPreviewKey = _catalogPreviewKey.asStateFlow()

    /** Held so opening another Discover playlist cancels the previous fetch. */
    private var lbDetailJob: Job? = null
    private val radioEngine = app.radioEngine
    private val buildSimilarPlaylistPreviewUseCase =
        BuildSimilarPlaylistPreviewUseCase(radioEngine, repository)

    private val similarPlaylistCoordinator = SimilarPlaylistCoordinator(
        scope = viewModelScope,
        useCase = buildSimilarPlaylistPreviewUseCase,
        isNetworkOnline = { connectivityObserver.isCurrentlyOnline() },
        resolvePreferredRadioMode = ::resolvePreferredRadioMode,
        getListenBrainzSettings = { listenBrainzSettings.value },
        getAllSongs = { repository.allSongsFlow.first() },
        onPlaylistCreated = { playlistId, localCount, pendingCount, downloadMissing ->
            toastPlaylistSaved(localCount, pending = pendingCount)
            setSelectedNavIndex(NAV_PLAYLISTS)
            openLocalPlaylist(playlistId)
            if (downloadMissing && pendingCount > 0) {
                downloadPlaylistPendingTracks(playlistId)
            }
        },
        playPlayableCollection = { playPlayableCollection(it, startIndex = 0, rotate = false) },
        addPlayableBatch = ::addPlayableBatch,
        toast = ::toast
    )
    val similarPlaylistPreview: StateFlow<SimilarPlaylistPreviewState?> = similarPlaylistCoordinator.state

    val queueFocusEpoch = playbackRuntime.queueFocusEpoch

    private val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioVolumeCoordinator = AudioVolumeCoordinator(
        audioManager = audioManager,
        playbackPreferences = playbackPreferences,
        scope = viewModelScope,
        isBoostPrefEnabled = { volumeBoostEnabled.value },
        getPlaybackSettings = { playbackSettings.value }
    )
    val volumeLevel: StateFlow<Float> = audioVolumeCoordinator.volumeLevel
    val volumeBoostHudVisible: StateFlow<Boolean> = audioVolumeCoordinator.volumeBoostHudVisible

    val lyricsCoordinator = LyricsCoordinator(
        scope = viewModelScope,
        repository = repository,
        lyricsPreferences = lyricsPreferences,
        updateCurrentSongLyrics = { songId, lyrics -> playbackRuntime.updateCurrentSongLyrics(songId, lyrics) },
        updateCurrentItemLyrics = { lyrics -> playbackRuntime.updateCurrentItemLyrics(lyrics) }
    )
    val lyricsSettings: StateFlow<LyricsSettings> = lyricsCoordinator.settings
    val lyricsTranslationState: StateFlow<LyricsTranslationState> = lyricsCoordinator.translationState
    val isFetchingLyrics: StateFlow<Boolean> = lyricsCoordinator.isFetching
    val lyricsFetchError: StateFlow<String?> = lyricsCoordinator.fetchError

    private val searchHistoryPreferences = SearchHistoryPreferencesRepository(application)

    val recentSearches: StateFlow<List<String>> = searchHistoryPreferences.recentSearchesFlow
        .stateInUi(viewModelScope, emptyList())

    val discoverSource: StateFlow<DiscoverSourcePreference> =
        libraryPreferences.discoverSourceFlow
            .stateInUi(viewModelScope, DiscoverSourcePreference.BOTH)

    val fastScrollSettings: StateFlow<FastScrollSettings> =
        libraryPreferences.fastScrollSettingsFlow
            .stateInUi(viewModelScope, FastScrollSettings())

    val submenuGestureSettings: StateFlow<SubmenuGestureSettings> =
        libraryPreferences.submenuGestureSettingsFlow
            .stateInUi(viewModelScope, SubmenuGestureSettings())

    val libraryBlobsSettings: StateFlow<LibraryBlobsSettings> =
        libraryPreferences.libraryBlobsSettingsFlow
            .stateInUi(viewModelScope, LibraryBlobsSettings())

    private val discoverFeedCoordinator = DiscoverFeedCoordinator(
        scope = viewModelScope,
        repository = repository,
        listenBrainzPreferences = listenBrainzPreferences,
        libraryPreferences = libraryPreferences,
        onClearExternalState = {
            closeListenBrainzPlaylist()
            val detail = _navigation.value.playlistDetail
            if (detail is PlaylistDetailNav.ListenBrainz || detail is PlaylistDetailNav.CfRecommendations) {
                updateNavigation { it.copy(playlistDetail = PlaylistDetailNav.None) }
                persistNavSnapshot()
            }
        }
    )
    val discoverFeed: StateFlow<DiscoverFeed> = discoverFeedCoordinator.discoverFeed
    val isLoadingDiscoverFeed: StateFlow<Boolean> = discoverFeedCoordinator.isLoadingDiscoverFeed
    val topRelatedFeed: StateFlow<TopRelatedFeed> = discoverFeedCoordinator.topRelatedFeed
    val isLoadingTopRelatedFeed: StateFlow<Boolean> = discoverFeedCoordinator.isLoadingTopRelatedFeed
    val lbDiscover: StateFlow<LoadableUiState<List<LbPlaylistSummary>>> = discoverFeedCoordinator.lbDiscover
    val cfRecommendations: StateFlow<LoadableUiState<MatchedCfRecommendations?>> = discoverFeedCoordinator.cfRecommendations

    private val libraryEditCoordinator = LibraryEditCoordinator(
        scope = viewModelScope,
        repository = repository,
        updateAlbumArtworkInQueue = { albumName, artworkUri -> playbackRuntime.updateAlbumArtworkInQueue(albumName, artworkUri) },
        onSongsDeleted = { ids -> pruneIdentifyReview(ids) },
        toast = ::toast
    )
    val pendingAlbumMerge: StateFlow<PendingAlbumMerge?> = libraryEditCoordinator.pendingAlbumMerge

    private val catalogSearchCoordinator = com.bestiapop.android.ui.state.CatalogSearchCoordinator(
        scope = viewModelScope,
        isOnline = { connectivityObserver.isCurrentlyOnline() },
        onNotifyToast = ::toast,
        onSaveRecentSearch = ::addRecentSearch
    )
    val catalogSearch: StateFlow<CatalogSearchUiState> = catalogSearchCoordinator.state

    private val playlistCoordinator = PlaylistCoordinator(
        scope = viewModelScope,
        repository = repository,
        onPlaylistDeleted = { id ->
            val detail = _navigation.value.playlistDetail
            if (detail is PlaylistDetailNav.Local && detail.id == id) {
                closePlaylistDetail()
            }
        }
    )

    private val catalogInspectionCoordinator: CatalogInspectionCoordinator = CatalogInspectionCoordinator(
        scope = viewModelScope,
        playOnlineCatalogTrackAsStream = ::playOnlineCatalogTrackAsStream,
        onResetBatchPlaylistTarget = { catalogDownloadCoordinator.resetBatchPlaylistTarget() }
    )
    val catalogCollection: StateFlow<CatalogCollectionUiState> = catalogInspectionCoordinator.catalogCollection

    private val catalogDownloadCoordinator: CatalogDownloadCoordinator = CatalogDownloadCoordinator(
        scope = viewModelScope,
        processDownloadRuntime = processDownloadRuntime,
        repository = repository,
        toast = ::toast,
        toastDownloadsQueued = { alreadyQueued, count ->
            toastDownloadsQueued(count = count.takeIf { it > 1 }, alreadyQueued = alreadyQueued)
        },
        toastSongAlreadyInLibrary = { toastSongInLibrary(it, LibraryToastKind.ALREADY) },
        playOnlineCatalogTrackAsStream = ::playOnlineCatalogTrackAsStream,
        playSong = ::playSong,
        rematchDiscover = ::rematchDiscoverAfterLibraryChange,
        launchCycleYouTubeMatch = { query, current, wasPreviewing, apply ->
            catalogInspectionCoordinator.launchCycleYouTubeMatch(query, current, wasPreviewing, apply)
        }
    )

    private val submenuActionCoordinator = SubmenuActionCoordinator(
        scope = viewModelScope,
        addPlayableBatch = ::addPlayableBatch,
        playNextPlayableBatch = ::playNextPlayableBatch,
        startRadioForSong = { startRadio(it) },
        startRadioForStream = { startRadio() },
        playPlayableCollection = { items, index -> playPlayableCollection(items, startIndex = index) },
        searchCatalog = ::searchCatalog,
        navigateToDiscover = { setSelectedNavIndex(NAV_DISCOVER) },
        toast = ::toast,
        getLibrarySongs = { libraryProjection.songs.value },
        resolveAlbumArtwork = { libraryProjection.resolveAlbumArtwork(it) },
        getLocalSongsByMatchKey = { libraryLookupIndex.value.localSongsByMatchKey },
        getAllSongsByMatchKey = { libraryLookupIndex.value.allSongsByMatchKey },
        getCatalogCollection = { catalogInspectionCoordinator.catalogCollection.value },
        findLocalSongFor = ::findLocalSongFor
    )

    val activeDownloads: StateFlow<List<ActiveDownload>> = processDownloadRuntime.downloads
    val activeDownloadBadgeCount: StateFlow<Int> = activeDownloads
        .map { activeDownloadBadgeCount(it) }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = activeDownloadBadgeCount(activeDownloads.value)
        )
    val downloadConflict: StateFlow<DownloadConflict?> = processDownloadRuntime.downloadConflict

    /** Set when MainActivity should switch to Descargas (notification / dialog deep-link). */
    private val _pendingOpenDownloads = MutableStateFlow(false)
    private val _pendingOpenIdentifyReview = MutableStateFlow(false)
    private val _pendingOpenNowPlaying = MutableStateFlow(false)
    private val _localLibraryJobProgress = MutableStateFlow<LibraryJobProgress?>(null)
    val libraryJobProgress: StateFlow<LibraryJobProgress?> =
        combine(_localLibraryJobProgress, processIdentifyRuntime.progress) { local, identify ->
            identify ?: local
        }.stateInUi(viewModelScope, null)
    private val uiAttached = AtomicBoolean(false)

    /** Serializes the first-launch disk import: two callers race the completed-flag check. */
    private val initialImportMutex = Mutex()
    private val identifiedWifiSongIds = mutableSetOf<Long>()
    val pendingOpenDownloads = _pendingOpenDownloads.asStateFlow()
    val pendingOpenIdentifyReview = _pendingOpenIdentifyReview.asStateFlow()
    val pendingOpenNowPlaying = _pendingOpenNowPlaying.asStateFlow()

    fun requestOpenDownloads() {
        _pendingOpenDownloads.value = true
    }

    fun requestOpenIdentifyReview() {
        setSelectedNavIndex(NAV_LIBRARY)
        _pendingOpenIdentifyReview.value = true
    }

    fun requestOpenNowPlaying() {
        _pendingOpenNowPlaying.value = true
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

    private fun publishBackgroundExecutionStatus(snapshot: BackgroundExecutionStatus) {
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

    fun consumeOpenNowPlaying() {
        _pendingOpenNowPlaying.value = false
    }

    fun setVolume(ratio: Float) = audioVolumeCoordinator.setVolume(ratio)
    fun setVolumeBoostEnabled(enabled: Boolean) = audioVolumeCoordinator.setVolumeBoostEnabled(enabled)
    fun showVolumeBoostHud() = audioVolumeCoordinator.showVolumeBoostHud()
    fun hideVolumeBoostHud() = audioVolumeCoordinator.hideVolumeBoostHud()
    fun handleVolumeUp(): Boolean = audioVolumeCoordinator.handleVolumeUp()
    fun handleVolumeDown(): Boolean = audioVolumeCoordinator.handleVolumeDown()
    fun consumeVolumeDownUpAction(): Boolean = audioVolumeCoordinator.consumeVolumeDownUpAction()
    fun isVolumeBoostActive(): Boolean = audioVolumeCoordinator.isVolumeBoostActive()

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

    fun setTelemetryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            telemetryPreferences.setTelemetryEnabled(enabled)
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

    fun setStereoLeftGain(gain: Float) = audioVolumeCoordinator.setStereoLeftGain(gain)
    fun setStereoRightGain(gain: Float) = audioVolumeCoordinator.setStereoRightGain(gain)
    fun resetStereoBalance() = audioVolumeCoordinator.resetStereoBalance()

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

    fun setOpenNowPlayingOnPlay(enabled: Boolean) {
        persistPlayback { setOpenNowPlayingOnPlay(enabled) }
    }

    fun setCrossfadeEnabled(enabled: Boolean) {
        persistPlayback { setCrossfadeEnabled(enabled) }
    }

    fun setCrossfadeDurationSeconds(seconds: Int) {
        persistPlayback { setCrossfadeDurationSeconds(seconds) }
    }

    private suspend fun restoreVolumeBoostIfNeeded() {
        val settings = playbackRuntime.awaitPlaybackSettings()
        audioVolumeCoordinator.restoreVolumeBoost(settings)
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
            val pruned = repository.pruneUnplayableCorruptSongs()
            if (pruned.isNotEmpty()) {
                identifyReviewStore.removeSongIds(pruned.map { it.id }.toSet())
            }
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
                    val songs = withContext(Dispatchers.IO) {
                        repository.getSongsByIds(newIds)
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
            rawSongs.map { songs -> songs.mapTo(LinkedHashSet()) { it.artist } }
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
    }

    fun updateSongDuration(songId: Long, durationMs: Long) {
        viewModelScope.launch {
            repository.updateSongDuration(songId, durationMs)
        }
    }

    val isTranslationActive: StateFlow<Boolean> = lyricsCoordinator.isTranslationActive
    val isFetchingTranslation: StateFlow<Boolean> = lyricsCoordinator.isFetchingTranslation
    val translationSource: StateFlow<LyricsTranslationSource?> = lyricsCoordinator.translationSource
    val pendingGoogleTranslatePrompt: StateFlow<Boolean> = lyricsCoordinator.pendingGoogleTranslatePrompt
    val romanizationVersion: StateFlow<Int> = lyricsCoordinator.romanizationVersion
    val translationVersion: StateFlow<Int> = lyricsCoordinator.translationVersion

    fun setPhoneticGuideEnabled(enabled: Boolean) {
        lyricsCoordinator.setPhoneticGuideEnabled(enabled)
    }

    fun setJapanesePhoneticMode(mode: JapanesePhoneticMode) {
        lyricsCoordinator.setJapanesePhoneticMode(mode)
    }

    fun setAskBeforeGoogleTranslate(ask: Boolean) {
        lyricsCoordinator.setAskBeforeGoogleTranslate(ask)
    }

    fun cancelGoogleTranslatePrompt() {
        lyricsCoordinator.cancelGoogleTranslatePrompt()
    }

    fun confirmGoogleTranslate(song: Song, lines: List<String>) {
        lyricsCoordinator.confirmGoogleTranslate(song, lines)
    }

    fun toggleLyricsTranslation(song: Song, lines: List<String>) {
        lyricsCoordinator.toggleLyricsTranslation(song, lines)
    }

    fun ensureRomanization(songId: Long, lines: List<String>) {
        lyricsCoordinator.ensureRomanization(songId, lines)
    }

    fun getTranslatedLines(songId: Long): List<String>? = lyricsCoordinator.getTranslatedLines(songId)

    fun getRomanizedLines(songId: Long): List<String>? = lyricsCoordinator.getRomanizedLines(songId)

    fun clearLyricsFetchError() {
        lyricsCoordinator.clearFetchError()
    }

    fun ensureLyrics(song: Song, force: Boolean = false) {
        lyricsCoordinator.ensureLyrics(song, force)
    }

    fun retryFetchLyrics(song: Song) {
        lyricsCoordinator.retryFetchLyrics(song)
    }

    fun enhanceSongMetadataAndLyrics(song: Song) {
        lyricsCoordinator.enhanceSongMetadataAndLyrics(song)
    }

    fun playSong(
        song: Song,
        playlistOrQueue: List<Song> = emptyList(),
        applyManualModes: Boolean = true,
        openNowPlaying: Boolean = true
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
        playPlayableCollection(
            targetQueue.toPlayableItemsWithFreshIds { libraryProjection.resolveAlbumArtwork(it) },
            index,
            applyManualModes = applyManualModes,
            openNowPlaying = openNowPlaying
        )
    }

    fun playPlayableCollection(
        items: List<PlayableItem>,
        startIndex: Int = 0,
        fromRadio: Boolean = false,
        rotate: Boolean = true,
        applyManualModes: Boolean = true,
        startShuffled: Boolean = false,
        origin: DiscoverPlaybackOrigin = DiscoverPlaybackOrigin.None,
        resumeAtMs: Long? = null,
        openNowPlaying: Boolean = true
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
        if (openNowPlaying && items.isNotEmpty() && !fromRadio && playbackSettings.value.openNowPlayingOnPlay) {
            _openNowPlayingEvents.tryEmit(Unit)
        }
    }

    fun catalogPreviewKeyFor(track: OnlineCatalogTrack): String =
        com.bestiapop.android.data.model.catalogPreviewKeyFor(track)

    fun playOnlineCatalogTrackAsStream(
        track: OnlineCatalogTrack,
        openNowPlaying: Boolean = true
    ) {
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
        playPlayableCollection(listOf(remote), 0, openNowPlaying = openNowPlaying)
    }

    /** Returns matched local (non-remote) Song from library index in O(1) time. */
    fun findLocalSongFor(meta: TrackMeta): Song? {
        val song = TrackMatchKeys.lookupLocalSong(libraryLookupIndex.value.localSongsByMatchKey, meta)
        return if (song != null && !song.isRemote) song else null
    }

    /** Plays local version if available in library; otherwise falls back to online stream. */
    fun playCatalogOrLocalTrack(
        track: OnlineCatalogTrack,
        openNowPlaying: Boolean = true
    ) {
        val local = findLocalSongFor(track.identity)
        if (local != null) {
            playSong(local, openNowPlaying = openNowPlaying)
        } else {
            playOnlineCatalogTrackAsStream(track, openNowPlaying = openNowPlaying)
        }
    }

    /** Plays collection of candidates, resolving any available local tracks to avoid streaming. */
    fun playCatalogCandidates(
        candidates: List<CatalogTrackCandidate>,
        startIndex: Int = 0,
        startShuffled: Boolean = false,
        openNowPlaying: Boolean = true
    ) {
        _catalogPreviewKey.value = null
        val playables = candidates.toPlayableItems(libraryLookupIndex.value.localSongsByMatchKey)
        playPlayableCollection(
            playables,
            startIndex = startIndex,
            startShuffled = startShuffled,
            openNowPlaying = openNowPlaying
        )
    }

    /** Plays a single catalog candidate using local file if present, or streaming. */
    fun playCatalogCandidate(
        candidate: CatalogTrackCandidate,
        openNowPlaying: Boolean = true
    ) {
        playCatalogOrLocalTrack(candidate.effectiveTrack, openNowPlaying = openNowPlaying)
    }

    /** Level 2: Downloads a single catalog candidate preserving all candidate matches and identity. */
    fun downloadCatalogCandidate(
        candidate: CatalogTrackCandidate,
        source: ActiveDownloadSource = ActiveDownloadSource.CATALOG,
        targetPlaylistId: Long? = null
    ) {
        val targetTrack = candidate.currentTrack ?: candidate.effectiveTrack
        val resolvedPlaylistId = targetPlaylistId ?: (catalogCollection.value.takeIf { it.kind == CatalogCollectionKind.PLAYLIST }?.let {
            catalogDownloadCoordinator.currentBatchPlaylistId
        })
        val explicitId = if (source == ActiveDownloadSource.BATCH) {
            TrackMatchKeys.batchDownloadIdFor(candidate.artist, candidate.title)
        } else null
        downloadOnlineTrack(
            track = targetTrack,
            source = source,
            targetPlaylistId = resolvedPlaylistId,
            existingCandidates = candidate.candidates,
            currentCandidateIndex = candidate.currentCandidateIndex,
            lookupIdentity = candidate.identity,
            explicitId = explicitId
        )
    }

    /** Returns status of track in library (DOWNLOADED, SAVED_REMOTE, or NOT_IN_LIBRARY) in O(1). */
    fun getTrackLibraryStatus(meta: TrackMeta): ItemLibraryStatus {
        val song = TrackMatchKeys.lookupLocalSong(libraryLookupIndex.value.allSongsByMatchKey, meta)
            ?: return ItemLibraryStatus.NOT_IN_LIBRARY
        return if (song.isRemote) ItemLibraryStatus.SAVED_REMOTE else ItemLibraryStatus.DOWNLOADED
    }

    /** Returns status of album in library (DOWNLOADED, SAVED_REMOTE, or NOT_IN_LIBRARY) in O(1). */
    fun getAlbumLibraryStatus(albumTitle: String, artistName: String): ItemLibraryStatus {
        val indices = libraryLookupIndex.value
        val key = albumArtistKey(artistName, albumTitle)
        val albumKey = albumIdentityKey(albumTitle)
        return indices.albumStatusByArtistAndAlbum[key]
            ?: indices.albumStatusByTitle[albumKey]
            ?: ItemLibraryStatus.NOT_IN_LIBRARY
    }

    /** Level 2: Returns status of album in library for a [CatalogAlbum]. */
    fun getAlbumLibraryStatus(album: CatalogAlbum): ItemLibraryStatus =
        getAlbumLibraryStatus(album.title, album.artist)

    /** Level 2: Returns status of album in library for a local [Album]. */
    fun getAlbumLibraryStatus(album: Album): ItemLibraryStatus =
        getAlbumLibraryStatus(album.name, album.artist)

    /** Preview local file while reviewing identify candidates (toggle if already current). */
    fun previewIdentifyLocalSong(song: Song) {
        _catalogPreviewKey.value = null
        val current = currentItem.value
        if (current is PlayableItem.Local && current.song.id == song.id) {
            togglePlayPause()
            return
        }
        val local = PlayableItem.Local(
            song = song,
            resolvedArtworkUri = libraryProjection.resolveAlbumArtwork(song)
        )
        playPlayableCollection(
            items = listOf(local),
            startIndex = 0,
            rotate = false,
            openNowPlaying = false
        )
    }

    /** Stream-preview a ranked identify candidate via YouTube (same path as catalog). */
    fun previewIdentifyCandidate(candidate: IdentifyCandidate) {
        playOnlineCatalogTrackAsStream(candidate.track, openNowPlaying = false)
    }

    fun clearCatalogPreview() {
        _catalogPreviewKey.value = null
    }

    /** Stops active catalog preview and pauses playback if currently playing. */
    fun stopCatalogPreview() {
        if (_catalogPreviewKey.value != null) {
            _catalogPreviewKey.value = null
            if (isPlaying.value) {
                togglePlayPause()
            }
        }
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

    enum class GroupPlaybackAction { PLAY, PLAY_SHUFFLED, PLAY_NEXT, ENQUEUE }

    /**
     * Level 1: Core pipeline for executing playback actions on any collection of songs.
     */
    fun executeGroupPlayback(songs: List<Song>, action: GroupPlaybackAction) {
        if (songs.isEmpty()) return
        when (action) {
            GroupPlaybackAction.PLAY -> playCollection(songs, startShuffled = false)
            GroupPlaybackAction.PLAY_SHUFFLED -> shuffleCollection(songs)
            GroupPlaybackAction.PLAY_NEXT -> playNextBatch(songs)
            GroupPlaybackAction.ENQUEUE -> enqueueCollection(songs)
        }
    }

    // Unified Group / Aggregate Actions ("Everything is a Collection")
    fun playAlbum(albumName: String, startShuffled: Boolean = false) {
        executeGroupPlayback(
            songsForAlbum(libraryProjection.songs.value, albumName),
            if (startShuffled) GroupPlaybackAction.PLAY_SHUFFLED else GroupPlaybackAction.PLAY
        )
    }

    fun playAlbum(album: Album, startShuffled: Boolean = false) = playAlbum(album.name, startShuffled)

    fun playAlbumNext(albumName: String) {
        executeGroupPlayback(songsForAlbum(libraryProjection.songs.value, albumName), GroupPlaybackAction.PLAY_NEXT)
    }

    fun playAlbumNext(album: Album) = playAlbumNext(album.name)

    fun enqueueAlbum(albumName: String) {
        executeGroupPlayback(songsForAlbum(libraryProjection.songs.value, albumName), GroupPlaybackAction.ENQUEUE)
    }

    fun enqueueAlbum(album: Album) = enqueueAlbum(album.name)

    fun playArtist(artistName: String, startShuffled: Boolean = false) {
        executeGroupPlayback(
            songsForArtist(libraryProjection.songs.value, artistName),
            if (startShuffled) GroupPlaybackAction.PLAY_SHUFFLED else GroupPlaybackAction.PLAY
        )
    }

    fun playArtistNext(artistName: String) {
        executeGroupPlayback(songsForArtist(libraryProjection.songs.value, artistName), GroupPlaybackAction.PLAY_NEXT)
    }

    fun enqueueArtist(artistName: String) {
        executeGroupPlayback(songsForArtist(libraryProjection.songs.value, artistName), GroupPlaybackAction.ENQUEUE)
    }

    fun playGenre(genreName: String, startShuffled: Boolean = false) {
        executeGroupPlayback(
            songsForGenre(libraryProjection.songs.value, genreName),
            if (startShuffled) GroupPlaybackAction.PLAY_SHUFFLED else GroupPlaybackAction.PLAY
        )
    }

    fun playGenreNext(genreName: String) {
        executeGroupPlayback(songsForGenre(libraryProjection.songs.value, genreName), GroupPlaybackAction.PLAY_NEXT)
    }

    fun enqueueGenre(genreName: String) {
        executeGroupPlayback(songsForGenre(libraryProjection.songs.value, genreName), GroupPlaybackAction.ENQUEUE)
    }

    fun identifyAlbum(album: Album) {
        val albumSongs = songsForAlbum(libraryProjection.songs.value, album.name)
        if (albumSongs.isNotEmpty()) {
            openIdentifySetup(albumSongs, contextTitle = "Álbum: ${album.displayName}")
        }
    }

    fun identifyAlbum(albumName: String) {
        val album = libraryProjection.albums.value.firstOrNull { albumNamesMatch(it.name, albumName) }
        if (album != null) {
            identifyAlbum(album)
        } else {
            val albumSongs = songsForAlbum(libraryProjection.songs.value, albumName)
            if (albumSongs.isNotEmpty()) {
                openIdentifySetup(albumSongs, contextTitle = "Álbum: $albumName")
            }
        }
    }

    fun resolveAlbumArtwork(song: Song): String? = libraryProjection.resolveAlbumArtwork(song)

    fun setAlbumArtwork(albumName: String, artworkUri: String) {
        libraryEditCoordinator.setAlbumArtwork(albumName, artworkUri)
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
        libraryEditCoordinator.requestSaveAlbumMetadata(
            source = source,
            displayName = displayName,
            artist = artist,
            genre = genre,
            year = year,
            artworkUri = artworkUri,
            propagateToSongs = propagateToSongs
        )
    }

    fun confirmPendingAlbumMerge() {
        libraryEditCoordinator.confirmPendingAlbumMerge()
    }

    fun dismissPendingAlbumMerge() {
        libraryEditCoordinator.dismissPendingAlbumMerge()
    }

    fun mergeAlbumInto(sourceAlbumKey: String, targetAlbumKey: String) {
        libraryEditCoordinator.mergeAlbumInto(sourceAlbumKey, targetAlbumKey)
    }

    fun shuffleCollection(songs: List<Song>) {
        shufflePlayableCollection(songs.toPlayableItems { libraryProjection.resolveAlbumArtwork(it) })
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
        addPlayableBatch(songs.toPlayableItems { libraryProjection.resolveAlbumArtwork(it) })
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
        similarPlaylistCoordinator.open(seeds, mode)
    }

    fun openSimilarPreview(seeds: List<PlayableItem>, mode: RadioMode? = null) =
        similarPlaylistCoordinator.open(seeds, mode)

    fun dismissSimilarPreview() = similarPlaylistCoordinator.dismiss()
    fun toggleSimilarPreviewItem(key: String) = similarPlaylistCoordinator.toggleItem(key)
    fun setSimilarPreviewMode(mode: RadioMode) = similarPlaylistCoordinator.setMode(mode)
    fun setSimilarPreviewPlaylistName(name: String) = similarPlaylistCoordinator.setPlaylistName(name)
    fun confirmSimilarPreviewAsPlaylist(
        name: String? = null,
        downloadMissing: Boolean = false
    ) = similarPlaylistCoordinator.confirmAsPlaylist(name, downloadMissing)
    fun playSimilarPreview() = similarPlaylistCoordinator.play()
    fun enqueueSimilarPreview() = similarPlaylistCoordinator.enqueue()

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
        playbackRuntime.playNextBatch(songs.toPlayableItems { libraryProjection.resolveAlbumArtwork(it) })
    }

    fun playNextPlayableBatch(items: List<PlayableItem>) {
        playbackRuntime.playNextBatch(items)
    }

    fun addSongsToPlaylist(playlistId: Long, songs: List<Song>) =
        addSongsToPlaylist(playlistId, songs.map { it.id })

    @JvmName("addSongsToPlaylistByIds")
    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>, onAdded: (() -> Unit)? = null) {
        viewModelScope.launch {
            repository.addSongsToPlaylist(playlistId, songIds)
            onAdded?.invoke()
        }
    }

    suspend fun playlistsContainingSong(songId: Long): List<Playlist> {
        val ids = repository.getPlaylistIdsForSong(songId).toSet()
        if (ids.isEmpty()) return emptyList()
        return repository.playlistsFlow.first().filter { it.id in ids }
    }

    fun deleteSongsFromApp(songs: List<Song>) {
        libraryEditCoordinator.deleteSongsFromApp(songs)
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
        libraryEditCoordinator.updateSongMetadata(songId, title, artist, album, genre, year, trackNumber)
    }

    fun updateSongLyrics(songId: Long, lyrics: String?) {
        lyricsCoordinator.updateSongLyrics(songId, lyrics)
    }

    suspend fun songById(id: Long): Song? = repository.getSongById(id)

    fun fetchSongLyrics(song: Song, onResult: (String?) -> Unit) {
        lyricsCoordinator.fetchSongLyrics(song, onResult)
    }

    fun deleteSongsFromDevice(songs: List<Song>) {
        libraryEditCoordinator.deleteSongsFromDevice(songs)
    }

    fun removeFromQueue(index: Int) {
        playbackRuntime.removeFromQueue(index)
    }

    fun removeFromQueue(queueEntryId: String): Boolean =
        playbackRuntime.removeFromQueue(queueEntryId)

    fun clearQueue() {
        playbackRuntime.clearQueue()
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
        if (updateNavigation { it.copy(libraryBrowseFilter = filter, libraryStack = LibraryBrowseStack.EMPTY) }) persistNavSnapshot()
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
        setSearchQuery("")
        persistedNavIndex = NAV_LIBRARY
        _selectedNavIndex.value = NAV_LIBRARY
        updateNavigation {
            it.copy(
                selectedNavIndex = NAV_LIBRARY,
                libraryBrowseFilter = LibraryBrowseFilter.PLAYLISTS,
                libraryStack = LibraryBrowseStack(),
                playlistDetail = PlaylistDetailNav.Local(id)
            )
        }
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
                cfRecommendations.value.data == null &&
                        !cfRecommendations.value.isLoading

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
        if (announce) toast(PlaylistMessages.openFailed)
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

    fun setIdentifySetupOnlyGaps(onlyGaps: Boolean) =
        identifyCoordinator.setIdentifySetupOnlyGaps(onlyGaps)

    fun setIdentifyReviewApplyFields(fields: IdentifyApplyFields) =
        identifyCoordinator.setIdentifyReviewApplyFields(fields)

    fun dismissIdentifySetup() = identifyCoordinator.dismissIdentifySetup()

    fun confirmIdentifySetup() = identifyCoordinator.confirmIdentifySetup()

    fun cancelIdentify() = identifyCoordinator.cancelIdentify()

    fun showIdentifyReview() = identifyCoordinator.showIdentifyReview()

    fun startIdentifyItemReview(groupKey: String? = null) =
        identifyCoordinator.startIdentifyItemReview(groupKey)

    fun returnIdentifyReviewOverview() = identifyCoordinator.returnIdentifyReviewOverview()

    fun applyIdentifyAlbumGroup(key: String) = identifyCoordinator.applyIdentifyAlbumGroup(key)

    fun searchAlbumCandidates(groupKey: String, query: String) =
        identifyCoordinator.searchAlbumCandidates(groupKey, query)

    fun selectAlbumCandidate(groupKey: String, index: Int) =
        identifyCoordinator.selectAlbumCandidate(groupKey, index)

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
    fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>> =
        playlistCoordinator.getPlaylistSongsFlow(playlistId)

    fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?> =
        playlistCoordinator.getPlaylistDetailsFlow(playlistId)

    fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrack>> =
        playlistCoordinator.getPlaylistPendingTracksFlow(playlistId)

    fun createPlaylist(
        name: String,
        description: String? = null,
        coverUri: String? = null,
        initialSongIds: List<Long> = emptyList(),
        onCreated: ((Long) -> Unit)? = null
    ) = playlistCoordinator.createPlaylist(name, description, coverUri, initialSongIds, onCreated)

    fun updatePlaylist(
        id: Long,
        name: String,
        description: String? = null,
        coverUri: String? = null
    ) = playlistCoordinator.updatePlaylist(id, name, description, coverUri)

    fun deletePlaylist(id: Long) = playlistCoordinator.deletePlaylist(id)

    fun addSongToPlaylist(playlistId: Long, song: Song) =
        playlistCoordinator.addSongToPlaylist(playlistId, song)

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) =
        playlistCoordinator.removeSongFromPlaylist(playlistId, songId)

    fun reorderPlaylistSongs(playlistId: Long, songIds: List<Long>) =
        playlistCoordinator.reorderPlaylistSongs(playlistId, songIds)

    private fun runWithPlaylistSongs(playlistId: Long, action: (List<Song>) -> Unit) =
        playlistCoordinator.runWithPlaylistSongs(playlistId, action)

    fun playPlaylist(playlistId: Long, startShuffled: Boolean = false) {
        runWithPlaylistSongs(playlistId) { songs ->
            executeGroupPlayback(songs, if (startShuffled) GroupPlaybackAction.PLAY_SHUFFLED else GroupPlaybackAction.PLAY)
        }
    }

    fun playPlaylistNext(playlistId: Long) {
        runWithPlaylistSongs(playlistId) { songs ->
            executeGroupPlayback(songs, GroupPlaybackAction.PLAY_NEXT)
        }
    }

    fun enqueuePlaylist(playlistId: Long) {
        runWithPlaylistSongs(playlistId) { songs ->
            executeGroupPlayback(songs, GroupPlaybackAction.ENQUEUE)
        }
    }

    // Theme Actions
    fun selectThemePreset(presetId: String) {
        viewModelScope.launch {
            themeRepository.selectPreset(presetId)
        }
    }

    fun enableDynamicTheme() {
        viewModelScope.launch {
            themeRepository.enableDynamicTheme()
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
        discoverFeedCoordinator.refreshListenBrainzDiscoverPlaylists()
    }

    fun refreshCfRecommendations() {
        discoverFeedCoordinator.refreshCfRecommendations()
    }

    fun openCfRecommendations() {
        discoverFeedCoordinator.openCfRecommendations()
    }

    private suspend fun loadCfRecommendationsForRestore(): Boolean =
        discoverFeedCoordinator.loadCfRecommendationsForRestore()

    private fun playMatchedCollection(
        items: List<PlayableItem>,
        startIndex: Int = 0,
        origin: DiscoverPlaybackOrigin = DiscoverPlaybackOrigin.None
    ): Boolean {
        if (items.isEmpty() || startIndex !in items.indices) return false
        _catalogPreviewKey.value = null
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
                toast(DownloadMessages.noPendingTracks)
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
        toast(DownloadMessages.playlistSaved(matchedCount, pending))
    }

    private fun toastRadioNeedsSeed() {
        toast(DownloadMessages.radioNeedsSeed)
    }
    private suspend fun enqueuePendingDownloads(
        playlistId: Long,
        tracks: List<OnlineCatalogTrack>,
        toastQueued: Boolean
    ) = catalogDownloadCoordinator.enqueuePendingDownloads(playlistId, tracks, toastQueued)

    private fun clearDiscoverState() {
        discoverFeedCoordinator.clearDiscoverState()
    }

    private fun clearCfState() {
        discoverFeedCoordinator.clearCfState()
    }

    // Online Catalog & Link Downloader Actions
    fun setCatalogCategory(category: CatalogCategory) {
        catalogSearchCoordinator.setCategory(category)
    }

    fun setCatalogSearchDraft(query: String) {
        catalogSearchCoordinator.setDraft(query)
    }

    fun setCatalogSearchFilterArtist(artist: String) {
        catalogSearchCoordinator.setFilterArtist(artist)
    }

    fun setCatalogSearchFilterAlbum(album: String) {
        catalogSearchCoordinator.setFilterAlbum(album)
    }

    fun setCatalogSearchFilterYear(year: String) {
        catalogSearchCoordinator.setFilterYear(year)
    }

    /** Level 2: Update all catalog search filters at once using [IdentifySearchFilters]. */
    fun setCatalogSearchFilters(filters: IdentifySearchFilters) {
        catalogSearchCoordinator.setFilters(filters)
    }

    fun toggleCatalogSearchFilters(show: Boolean? = null) {
        catalogSearchCoordinator.toggleFilters(show)
    }

    fun clearCatalogSearchFilters() {
        catalogSearchCoordinator.clearFilters()
    }

    fun setDiscoverSource(source: DiscoverSourcePreference) {
        viewModelScope.launch {
            libraryPreferences.setDiscoverSourcePreference(source)
            refreshDiscoverFeed()
        }
    }

    fun setFastScrollEnabled(enabled: Boolean) {
        viewModelScope.launch { libraryPreferences.setFastScrollEnabled(enabled) }
    }

    fun setFastScrollSide(side: FastScrollSide) {
        viewModelScope.launch { libraryPreferences.setFastScrollSide(side) }
    }

    fun setSubmenuSwipeBackEnabled(enabled: Boolean) {
        viewModelScope.launch { libraryPreferences.setSubmenuSwipeBackEnabled(enabled) }
    }

    fun setSubmenuSwipeLeftAction(action: SubmenuSwipeAction) {
        viewModelScope.launch { libraryPreferences.setSubmenuSwipeLeftAction(action) }
    }


    fun executeSubmenuActionForPlayables(
        action: SubmenuSwipeAction,
        items: List<PlayableItem>,
        onAddToPlaylist: ((List<PlayableItem>) -> Unit)? = null
    ) = submenuActionCoordinator.executeForPlayables(action, items, onAddToPlaylist)

    fun executeSubmenuActionForSongs(
        action: SubmenuSwipeAction,
        songs: List<Song>,
        onAddToPlaylist: ((List<Song>) -> Unit)? = null
    ) = submenuActionCoordinator.executeForSongs(action, songs, onAddToPlaylist)

    fun executeSubmenuActionForCandidates(
        action: SubmenuSwipeAction,
        candidates: List<CatalogTrackCandidate>,
        onAddToPlaylist: ((List<CatalogTrackCandidate>) -> Unit)? = null
    ) = submenuActionCoordinator.executeForCandidates(action, candidates, onAddToPlaylist)

    fun executeSubmenuActionForTrack(
        action: SubmenuSwipeAction,
        track: TrackMeta,
        onAddToPlaylist: ((Song) -> Unit)? = null
    ) = submenuActionCoordinator.executeForTrack(action, track, onAddToPlaylist)

    /** Level 2: Execute submenu action for a [CatalogAlbum]. */
    fun executeSubmenuActionForAlbum(
        action: SubmenuSwipeAction,
        album: CatalogAlbum,
        onAddToPlaylist: ((List<Song>) -> Unit)? = null
    ) = submenuActionCoordinator.executeForAlbum(action, album, onAddToPlaylist)

    /** Level 2: Execute submenu action for a local [Album]. */
    fun executeSubmenuActionForAlbum(
        action: SubmenuSwipeAction,
        album: Album,
        onAddToPlaylist: ((List<Song>) -> Unit)? = null
    ) = submenuActionCoordinator.executeForAlbum(action, album, onAddToPlaylist)

    /** Level 1: Execute submenu action for an album with raw string parameters. */
    fun executeSubmenuActionForAlbum(
        action: SubmenuSwipeAction,
        albumTitle: String,
        artistName: String = "",
        albumId: String = "",
        coverUrl: String? = null,
        onAddToPlaylist: ((List<Song>) -> Unit)? = null
    ) = submenuActionCoordinator.executeForAlbum(action, albumTitle, artistName, albumId, coverUrl, onAddToPlaylist)

    fun executeSubmenuActionForArtist(
        action: SubmenuSwipeAction,
        artistName: String,
        onAddToPlaylist: ((List<Song>) -> Unit)? = null
    ) = submenuActionCoordinator.executeForArtist(action, artistName, onAddToPlaylist)

    fun setLibraryBlobsSettings(settings: LibraryBlobsSettings) {
        viewModelScope.launch {
            libraryPreferences.setLibraryBlobsSettings(settings)
            val currentFilter = _navigation.value.libraryBrowseFilter
            if (currentFilter !in settings.enabledFilters) {
                setLibraryBrowseFilter(settings.primaryFilter)
            }
        }
    }

    fun refreshDiscoverFeed(forceRefresh: Boolean = false) {
        discoverFeedCoordinator.refreshDiscoverFeed(forceRefresh)
    }

    fun refreshTopRelatedFeed(forceRefresh: Boolean = false) {
        discoverFeedCoordinator.refreshTopRelatedFeed(forceRefresh)
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

    /**
     * Level 1: Save album tracks to library using primitive values and optional candidates.
     */
    fun saveAlbumToLibrary(
        albumTitle: String,
        artistName: String,
        coverUrl: String? = null,
        year: Int = 0,
        genre: String = Song.UNKNOWN_GENRE,
        candidates: List<CatalogTrackCandidate> = emptyList(),
        albumId: String = ""
    ) {
        libraryEditCoordinator.saveAlbumToLibrary(
            albumTitle = albumTitle,
            artistName = artistName,
            coverUrl = coverUrl,
            year = year,
            genre = genre,
            candidates = candidates,
            albumId = albumId
        )
    }

    /**
     * Level 2: Save album tracks to library from a [CatalogAlbum].
     */
    fun saveAlbumToLibrary(album: CatalogAlbum, candidates: List<CatalogTrackCandidate> = emptyList()) {
        libraryEditCoordinator.saveAlbumToLibrary(album, candidates)
    }

    fun removeSavedAlbum(albumName: String, artistName: String) {
        libraryEditCoordinator.removeSavedAlbum(albumName, artistName)
    }

    /** Level 2: Remove saved album using a [CatalogAlbum]. */
    fun removeSavedAlbum(album: CatalogAlbum) = libraryEditCoordinator.removeSavedAlbum(album)

    /** Level 2: Remove saved album using an [Album]. */
    fun removeSavedAlbum(album: Album) = libraryEditCoordinator.removeSavedAlbum(album)

    /** Level 2: Debounced search for live typing in search bars without spamming HTTP or canceling early. */
    fun searchCatalogDebounced(
        query: String = catalogSearch.value.searchQueryDraft,
        filters: IdentifySearchFilters = catalogSearch.value.searchFilters,
        debounceMs: Long = 350L
    ) {
        catalogSearchCoordinator.searchDebounced(query, filters, debounceMs)
    }

    /** Level 2: Submits an explicit catalog search (e.g. on keyboard Enter or suggestion tap), saving to recent searches. */
    fun submitCatalogSearch(
        query: String = catalogSearch.value.searchQueryDraft,
        filters: IdentifySearchFilters = catalogSearch.value.searchFilters
    ) {
        catalogSearchCoordinator.submitSearch(query, filters)
    }

    fun searchCatalog(
        query: String = catalogSearch.value.searchQueryDraft,
        filters: IdentifySearchFilters = catalogSearch.value.searchFilters,
        saveToRecent: Boolean = false
    ) {
        catalogSearchCoordinator.search(query, filters, saveToRecent)
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
    ) = catalogInspectionCoordinator.selectAlbumForInspection(title, artist, coverUrl, albumId)

    /** Level 2: Inspect a [CatalogAlbum]. */
    fun selectAlbumForInspection(album: CatalogAlbum) =
        catalogInspectionCoordinator.selectAlbumForInspection(album)

    /** Level 2: Inspect a [RelatedAlbumItem] without converting to a dummy [CatalogAlbum]. */
    fun selectAlbumForInspection(album: RelatedAlbumItem) =
        catalogInspectionCoordinator.selectAlbumForInspection(album)

    fun selectPlaylistForInspection(playlist: CatalogPlaylist) =
        catalogInspectionCoordinator.selectPlaylistForInspection(playlist)

    fun selectGenreForInspection(genre: CatalogGenre) =
        catalogInspectionCoordinator.selectGenreForInspection(genre)

    fun selectArtistForInspection(artistName: String) =
        catalogInspectionCoordinator.selectArtistForInspection(artistName)

    fun toggleTrackSelection(index: Int) =
        catalogInspectionCoordinator.toggleTrackSelection(index)

    fun setAllTrackCandidatesSelection(selected: Boolean) =
        catalogInspectionCoordinator.setAllTrackCandidatesSelection(selected)

    fun toggleAllTrackCandidatesSelection() =
        catalogInspectionCoordinator.toggleAllTrackCandidatesSelection()

    fun clearSelectedCollection() =
        catalogInspectionCoordinator.clearSelectedCollection()

    fun searchMore() {
        catalogSearchCoordinator.searchMore()
    }

    fun resolveDownloadConflictOverwrite(applyToRemainingBatch: Boolean = false) {
        catalogDownloadCoordinator.resolveDownloadConflictOverwrite(applyToRemainingBatch)
    }

    fun resolveDownloadConflictSaveAs(newTitle: String, applyToRemainingBatch: Boolean = false) {
        catalogDownloadCoordinator.resolveDownloadConflictSaveAs(newTitle, applyToRemainingBatch)
    }

    fun cancelDownloadConflict() {
        catalogDownloadCoordinator.cancelDownloadConflict()
    }

    fun clearBatchConflictPolicy() {
        catalogDownloadCoordinator.clearBatchConflictPolicy()
    }

    private suspend fun rematchDiscoverAfterLibraryChange(extraSong: Song? = null) {
        val library = libraryWithExtra(extraSong)
        _lbPlaylistDetail.value.data?.let { current ->
            _lbPlaylistDetail.update {
                it.copy(data = current.copy(matches = current.matches.rematchLocals(library)))
            }
        }
        discoverFeedCoordinator.rematchCfRecommendations(library)
    }

    private suspend fun libraryWithExtra(extraSong: Song?): List<Song> =
        repository.allSongsFlow.first().let { list ->
            if (extraSong == null || list.any { it.id == extraSong.id }) list
            else list + extraSong
        }

    fun downloadRemoteItem(remote: PlayableItem.Remote) {
        catalogDownloadCoordinator.downloadRemoteItem(remote)
    }

    fun retryActiveDownload(id: String) {
        catalogDownloadCoordinator.retryActiveDownload(id)
    }

    fun resumeAllDownloads() {
        catalogDownloadCoordinator.resumeAllDownloads()
    }

    fun cycleActiveDownload(id: String) {
        catalogDownloadCoordinator.cycleActiveDownload(
            id = id,
            activeDownloads = activeDownloads.value,
            catalogPreviewKey = _catalogPreviewKey.value
        )
    }

    fun previewActiveDownload(id: String) {
        catalogDownloadCoordinator.previewActiveDownload(id, activeDownloads.value)
    }

    fun playActiveDownload(id: String) {
        catalogDownloadCoordinator.playActiveDownload(id, activeDownloads.value)
    }

    fun dismissActiveDownload(id: String) {
        catalogDownloadCoordinator.dismissActiveDownload(id)
    }

    fun dismissAllActiveDownloads() {
        catalogDownloadCoordinator.dismissAllActiveDownloads()
    }

    fun downloadSingleCandidate(index: Int) {
        catalogDownloadCoordinator.downloadSingleCandidate(index, catalogCollection.value)
    }

    fun downloadSelectedCandidatesBatch() {
        catalogDownloadCoordinator.downloadSelectedCandidatesBatch(catalogCollection.value)
    }

    fun downloadFromUrl(url: String) {
        catalogDownloadCoordinator.downloadFromUrl(url)
    }

    /**
     * Level 1: Low-level primitive download for an online track with custom candidates and targets.
     */
    fun downloadOnlineTrack(
        track: OnlineCatalogTrack,
        source: ActiveDownloadSource = ActiveDownloadSource.CATALOG,
        targetPlaylistId: Long? = null,
        existingCandidates: List<OnlineCatalogTrack>? = null,
        currentCandidateIndex: Int = 0,
        lookupIdentity: TrackIdentity? = null,
        explicitId: String? = null
    ) {
        catalogDownloadCoordinator.downloadOnlineTrack(
            track = track,
            source = source,
            targetPlaylistId = targetPlaylistId,
            existingCandidates = existingCandidates,
            currentCandidateIndex = currentCandidateIndex,
            lookupIdentity = lookupIdentity,
            explicitId = explicitId
        )
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
        private const val FIRST_LIBRARY_IDLE_MS = 1_500L
        const val VOLUME_BOOST_STEP = 0.10f
        const val VOLUME_BOOST_HUD_DURATION_MS = 2_000L
    }
}

