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
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.LibraryTagWritePreferencesRepository
import com.bestiapop.android.data.preferences.LibraryTagWriteSettings
import com.bestiapop.android.data.preferences.LibraryUiPreferencesCodec
import com.bestiapop.android.data.preferences.DEFAULT_STREAM_SKIP_GRACE_SECONDS
import com.bestiapop.android.data.preferences.NAV_DOWNLOADS
import com.bestiapop.android.data.preferences.NAV_LIBRARY
import com.bestiapop.android.data.preferences.NAV_PLAYLISTS
import com.bestiapop.android.data.preferences.NAV_SETTINGS
import com.bestiapop.android.data.preferences.UiNavSnapshot
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.ThemePreferencesRepository
import com.bestiapop.android.data.system.BackgroundExecutionProbe
import com.bestiapop.android.data.system.BackgroundExecutionStatus
import com.bestiapop.android.data.util.CrashReporter
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
import com.bestiapop.android.domain.util.clusterIdentifyAlbumGroups
import com.bestiapop.android.domain.util.findAlbumMergeTarget
import com.bestiapop.android.domain.util.isTrackNumberLabel
import com.bestiapop.android.domain.util.normalizeAlbumName
import com.bestiapop.android.service.PlaybackRuntime
import com.bestiapop.android.service.ProcessDownloadEvent
import com.bestiapop.android.service.ProcessDownloadRequest
import com.bestiapop.android.service.WebServerService
import com.bestiapop.android.ui.state.CatalogCollectionKind
import com.bestiapop.android.ui.state.CatalogCollectionUiState
import com.bestiapop.android.ui.state.CatalogSearchUiState
import com.bestiapop.android.ui.state.IdentifyReviewItem
import com.bestiapop.android.ui.state.IdentifyReviewPhase
import com.bestiapop.android.ui.state.IdentifyReviewState
import com.bestiapop.android.ui.state.IdentifySetupState
import com.bestiapop.android.ui.state.hasMediumSuggestion
import com.bestiapop.android.ui.state.identifyReviewFromPersisted
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import com.bestiapop.android.ui.state.LibraryListItem
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
        .stateIn(viewModelScope, SharingStarted.Lazily, ThemePresets.MidnightDark)

    // ListenBrainz state
    val listenBrainzSettings: StateFlow<ListenBrainzSettings> =
        listenBrainzPreferences.settingsFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, ListenBrainzSettings())

    private val playbackSettings: StateFlow<PlaybackSettings> = playbackRuntime.playbackSettings

    val downloadSettings: StateFlow<DownloadSettings> =
        downloadPreferences.settingsFlow
            .stateInUi(viewModelScope, DownloadSettings())

    private val _backgroundExecutionStatus =
        MutableStateFlow(BackgroundExecutionProbe.current(application))
    val backgroundExecutionStatus: StateFlow<BackgroundExecutionStatus> =
        _backgroundExecutionStatus.asStateFlow()

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
    val selectedNavIndex = navigation.mapToUiState(viewModelScope, NAV_LIBRARY) {
        it.selectedNavIndex
    }

    private var uiPrefsHydrated = false
    /** Tab to persist. Transient jumps move the live index without touching this. */
    private var persistedNavIndex = NAV_LIBRARY
    /** Tab to come back to after a transient jump into Settings. */
    private var navIndexBeforeTransient: Int? = null

    private val getLibrarySongsUseCase = com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase()
    private val _artistPhotos = MutableStateFlow<Map<String, String>>(emptyMap())
    val libraryProjection = LibraryProjectionState(
        scope = viewModelScope,
        rawSongs = rawSongs,
        albumOverrides = repository.albumOverridesFlow,
        searchQuery = searchQuery,
        sortOption = sortOption,
        sortDirection = sortDirection,
        artistPhotos = _artistPhotos,
        useCase = getLibrarySongsUseCase
    )

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
        libraryProjection.buildListItems(songs, viewMode)

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

    // Process-owned playback state. ViewModel only exposes/observes it.
    val currentItem = playbackRuntime.currentItem
    val currentSong = playbackRuntime.currentSong
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

    // Online Catalog & Link Downloader State
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
    private val _libraryJobProgress = MutableStateFlow<LibraryJobProgress?>(null)
    val libraryJobProgress: StateFlow<LibraryJobProgress?> = _libraryJobProgress.asStateFlow()
    private val _identifyReview = MutableStateFlow(IdentifyReviewState())
    val identifyReview: StateFlow<IdentifyReviewState> = _identifyReview.asStateFlow()
    private val _identifySetup = MutableStateFlow<IdentifySetupState?>(null)
    val identifySetup: StateFlow<IdentifySetupState?> = _identifySetup.asStateFlow()
    private val identifyMutex = Mutex()
    /** Serializes the first-launch disk import: two callers race the completed-flag check. */
    private val initialImportMutex = Mutex()
    private val identifiedWifiSongIds = mutableSetOf<Long>()
    val pendingOpenDownloads = _pendingOpenDownloads.asStateFlow()

    fun requestOpenDownloads() {
        _pendingOpenDownloads.value = true
    }

    fun onAppForeground() {
        _backgroundExecutionStatus.value = BackgroundExecutionProbe.current(getApplication())
        if (app.shouldAutoResumeDownloads) {
            processDownloadRuntime.resumeInterrupted()
        }
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

    fun setAutoWriteTagsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            libraryTagWritePreferences.setAutoWriteTagsEnabled(enabled)
        }
    }

    fun syncLibraryTagsToFiles() {
        if (_libraryJobProgress.value != null) {
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
        _navigation.update { it.copy(selectedNavIndex = NAV_SETTINGS) }
    }

    /** True when it consumed a pending transient jump and restored the previous tab. */
    fun returnFromTransientSettings(): Boolean {
        val previous = navIndexBeforeTransient ?: return false
        navIndexBeforeTransient = null
        _navigation.update { it.copy(selectedNavIndex = previous) }
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
        playbackRuntime.attachUi()
        viewModelScope.launch {
            restoreVolumeBoostIfNeeded()
        }
        viewModelScope.launch {
            playbackRuntime.events.collect { toast(it) }
        }
        viewModelScope.launch {
            hydrateUiPreferences()
        }

        viewModelScope.launch {
            ensureInitialLibraryImport(showRecoveryToast = true)
        }

        viewModelScope.launch {
            warnIfDatabaseWasDowngraded()
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.migrateCanonicalAudioUris()
            // One-shot: the migration leaves the album untouched below HIGH confidence, so without a
            // flag every cold start re-queried the same 'YouTube Music' rows over the network forever.
            if (!libraryPreferences.isLegacyYouTubeMusicMigrated()) {
                repository.migrateLegacyYouTubeMusicSongs()
                libraryPreferences.setLegacyYouTubeMusicMigrated()
            }
            if (!libraryPreferences.isDeviceDateAddedMigrated()) {
                repository.migrateDateAddedFromDevice()
                libraryPreferences.setDeviceDateAddedMigrated()
            }
        }

        viewModelScope.launch {
            identifyMutex.withLock {
                val snap = withContext(Dispatchers.IO) { identifyReviewStore.load() }
                if (snap.proposals.isNotEmpty()) {
                    val songs = withContext(Dispatchers.IO) { repository.getAllSongsSync() }
                    _identifyReview.value = identifyReviewFromPersisted(
                        snap.proposals,
                        snap.phase,
                        songs,
                        snap.applyFields
                    )
                }
            }
            identifyReview
                .map { state ->
                    PersistedIdentifyReviewQueue(
                        proposals = state.items.drop(state.currentIndex).map { it.proposal },
                        phase = state.phase.name,
                        applyFields = state.applyFields
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
            rawSongs.collect { songs ->
                val artists = songs.map { it.artist }.distinct().filter {
                    it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true)
                }
                for (artist in artists) {
                    // Keyed on "attempted", not on a stored photo: an artist Deezer has no picture
                    // for never entered the map and was re-queried on every emission, forever.
                    if (!artistPhotoAttempted.add(artist)) continue
                    val photoUrl = MetadataFetcher.fetchArtistPhotoUrl(artist)
                    if (!photoUrl.isNullOrEmpty()) {
                        _artistPhotos.update { it + (artist to photoUrl) }
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            rawSongs.collect { songs ->
                val unenhanced = songs.filter {
                    !SongPathNormalizer.hasUsableArtwork(it.artworkUri) &&
                        it.id !in metadataEnhanceAttempted
                }
                // enhanceSongMetadataAndLyrics only writes to Room when it found something, so a song
                // with no cover online stayed in this filter and was re-fetched on every emission.
                for (song in unenhanced.take(METADATA_ENHANCE_BATCH)) {
                    metadataEnhanceAttempted.add(song.id)
                    repository.enhanceSongMetadataAndLyrics(song)
                }
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

    fun playSong(
        song: Song,
        playlistOrQueue: List<Song> = emptyList(),
        applyManualModes: Boolean = true
    ) {
        _catalogPreviewKey.value = null
        val baseList = if (playlistOrQueue.isNotEmpty()) playlistOrQueue else libraryProjection.songs.value
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
            if (_navigation.compareAndSet(current, updated)) return true
        }
    }

    fun setSelectedNavIndex(index: Int, persist: Boolean = true) {
        val sanitized = LibraryUiPreferencesCodec.sanitizeNavIndex(index)
        navIndexBeforeTransient = null
        if (_navigation.value.selectedNavIndex == sanitized) {
            if (sanitized == NAV_PLAYLISTS) maybeRestoreDiscoverDetail()
            return
        }
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
        val display = libraryPreferences.displaySettingsFlow.first()
        _sortOption.value = parseSortOption(display.sortOptionName)
        _sortDirection.value = parseSortDirection(display.sortDirectionName)
        _libraryViewMode.value = parseLibraryViewMode(display.viewModeName)

        val nav = libraryPreferences.navSnapshotFlow.first()
        applyNavSnapshot(nav)
        uiPrefsHydrated = true

        pruneRestoredLibraryStack()
        pruneRestoredLocalPlaylist()
        if (_navigation.value.selectedNavIndex == NAV_PLAYLISTS) {
            restoreDiscoverDetailOrFallback()
        }
    }

    private fun applyNavSnapshot(nav: UiNavSnapshot) {
        _navigation.value = UiNavigationState.fromSnapshot(nav)
        persistedNavIndex = nav.navIndex
    }

    private fun persistNavSnapshot() {
        if (!uiPrefsHydrated) return
        // persistedNavIndex, not the live one: a transient tab (download notification deep link,
        // the Ajustes → Descargas shortcut) would otherwise become the next cold-start tab.
        val snapshot = _navigation.value.toSnapshot(persistedNavIndex)
        viewModelScope.launch { libraryPreferences.setNavSnapshot(snapshot) }
    }

    private suspend fun pruneRestoredLibraryStack() {
        val songs = rawSongs.first()
        val stack = _navigation.value.libraryStack
        val pruned = LibraryUiPreferencesCodec.pruneLibraryStack(
            albumName = stack.albumName,
            artistName = stack.artistName,
            genreName = stack.genreName,
            albumExists = { name -> songs.any { it.album.equals(name, ignoreCase = true) } },
            artistExists = { name -> songs.any { it.artist.equals(name, ignoreCase = true) } },
            genreExists = { name ->
                songs.any {
                    com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase.genreKey(it)
                        .equals(name, ignoreCase = true)
                }
            }
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
            // finally: a stuck banner blocks every later library job (see the guard in
            // syncLibraryTagsToFiles), so it must clear even if the scan throws.
            val count = try {
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
        val recovered = try {
            withContext(Dispatchers.IO) {
                val n = repository.resyncAppManagedMusic(importScanProgress())
                repository.scanMediaStore(importScanProgress())
                n
            }
        } finally {
            clearLibraryProgress()
        }
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
        showReview: Boolean = true,
        fields: IdentifyApplyFields = IdentifyApplyFields.ALL
    ) {
        if (songs.isEmpty()) return
        viewModelScope.launch {
            identifyMutex.withLock {
                runIdentifySongs(songs, force, showReview, fields)
            }
        }
    }

    /** Single-song identify: open existing pending item, or open setup configuration dialog. */
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
                isVisible = true
            ).withItemSearchChrome(item)
            return
        }
        openIdentifySetup(listOf(song), contextTitle = song.title)
    }

    fun openIdentifySetup(songs: List<Song>, contextTitle: String = "") {
        if (songs.isEmpty()) return
        _identifySetup.value = IdentifySetupState(
            songs = songs,
            applyFields = IdentifyApplyFields.ALL,
            contextTitle = contextTitle
        )
    }

    fun setIdentifySetupFields(fields: IdentifyApplyFields) {
        _identifySetup.update { it?.copy(applyFields = fields) }
    }

    fun dismissIdentifySetup() {
        _identifySetup.value = null
    }

    fun confirmIdentifySetup() {
        val current = _identifySetup.value ?: return
        _identifySetup.value = null
        identifySongs(
            songs = current.songs,
            force = true,
            showReview = true,
            fields = current.applyFields
        )
    }

    private suspend fun runIdentifySongs(
        songs: List<Song>,
        force: Boolean,
        showReview: Boolean,
        fields: IdentifyApplyFields = IdentifyApplyFields.ALL
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
                            repository.applySongIdentity(song.id, proposal.suggested, fields)
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
            enqueueIdentifyReview(reviewItems, showReview, fields)
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

    private fun enqueueIdentifyReview(
        items: List<IdentifyReviewItem>,
        showReview: Boolean,
        applyFields: IdentifyApplyFields = _identifyReview.value.applyFields
    ) {
        if (items.isEmpty()) return
        val current = _identifyReview.value
        val existingIds = current.pendingSongIds
        val incoming = items.filter { it.song.id !in existingIds }
        if (current.items.isEmpty()) {
            presentIdentifyQueue(incoming, showReview = showReview, applyFields = applyFields)
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
            phase = phase,
            applyFields = applyFields
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
        openedFromOverview: Boolean = false,
        applyFields: IdentifyApplyFields = _identifyReview.value.applyFields
    ) {
        if (items.isEmpty()) {
            _identifyReview.value = IdentifyReviewState(applyFields = applyFields)
            clearCatalogPreview()
            return
        }
        val phase = reviewPhaseFor(items)
        val first = items.first()
        val showSearch = phase == IdentifyReviewPhase.Item && first.proposal.candidates.isEmpty()
        _identifyReview.value = IdentifyReviewState(
            items = items,
            currentIndex = 0,
            sessionApplied = sessionApplied,
            sessionSkipped = sessionSkipped,
            isVisible = showReview,
            phase = phase,
            openedFromOverview = openedFromOverview && phase == IdentifyReviewPhase.Item,
            applyFields = applyFields
        ).let { base ->
            if (phase == IdentifyReviewPhase.Item) {
                base.withItemSearchChrome(first, forceShowSearch = showSearch)
            } else {
                base
            }
        }
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
            isVisible = true
        ).withItemSearchChrome(first)
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
                    repository.applySongIdentity(item.song.id, candidate, _identifyReview.value.applyFields)
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

    private fun defaultFilterArtist(item: IdentifyReviewItem): String =
        item.proposal.queryArtist.trim().takeUnless {
            it.isBlank() || IdentifyRanking.isPlaceholderArtist(it) || looksLikeStoragePath(it)
        } ?: item.song.artist.trim().takeUnless {
            IdentifyRanking.isPlaceholderArtist(it) || looksLikeStoragePath(it)
        }.orEmpty()

    private fun defaultFilterAlbum(item: IdentifyReviewItem): String =
        item.song.album.trim().takeUnless {
            it.isBlank() || IdentifyRanking.isGenericAlbum(it)
        }.orEmpty()

    private fun defaultFilterYear(item: IdentifyReviewItem): String =
        item.song.year.takeIf { it in 1000..9999 }?.toString().orEmpty()

    /** Search draft; filters stay collapsed until [toggleIdentifySearchFilters]. */
    private fun IdentifyReviewState.withItemSearchChrome(
        item: IdentifyReviewItem,
        forceShowSearch: Boolean? = null
    ): IdentifyReviewState {
        val showSearch = forceShowSearch ?: item.proposal.candidates.isEmpty()
        return copy(
            searchQueryDraft = defaultSearchDraft(item),
            showSearchField = showSearch,
            showSearchFilters = false,
            isSearching = false,
            isLoadingMore = false,
            visibleCandidateCount = IdentifyRanking.TOP_N,
            selectedCandidateIndex = 0,
            searchFilterArtist = "",
            searchFilterAlbum = "",
            searchFilterYear = ""
        )
    }

    fun selectIdentifyCandidate(index: Int) {
        val state = _identifyReview.value
        val candidates = state.visibleCandidates
        if (index !in candidates.indices) return
        _identifyReview.value = state.copy(selectedCandidateIndex = index)
    }

    fun setIdentifySearchDraft(query: String) {
        _identifyReview.value = _identifyReview.value.copy(searchQueryDraft = query)
    }

    fun setIdentifySearchFilterArtist(value: String) {
        _identifyReview.value = _identifyReview.value.copy(searchFilterArtist = value)
    }

    fun setIdentifySearchFilterAlbum(value: String) {
        _identifyReview.value = _identifyReview.value.copy(searchFilterAlbum = value)
    }

    fun setIdentifySearchFilterYear(value: String) {
        _identifyReview.value = _identifyReview.value.copy(
            searchFilterYear = value.filter { it.isDigit() }.take(4)
        )
    }

    fun toggleIdentifySearchField(show: Boolean? = null) {
        val state = _identifyReview.value
        val next = show ?: !state.showSearchField
        val draft = state.searchQueryDraft
        val shouldSeed = next && (draft.isBlank() || looksLikeStoragePath(draft))
        val item = state.current
        _identifyReview.value = state.copy(
            showSearchField = next,
            showSearchFilters = if (next) state.showSearchFilters else false,
            searchQueryDraft = if (shouldSeed) {
                item?.let { defaultSearchDraft(it) }.orEmpty()
            } else {
                draft
            }
        )
    }

    fun toggleIdentifySearchFilters(show: Boolean? = null) {
        val state = _identifyReview.value
        if (!state.showSearchField && show != false) {
            // Opening filters implies search chrome is visible.
            toggleIdentifySearchField(show = true)
        }
        val latest = _identifyReview.value
        val next = show ?: !latest.showSearchFilters
        val item = latest.current
        val seed = next && item != null &&
            latest.searchFilterArtist.isBlank() &&
            latest.searchFilterAlbum.isBlank() &&
            latest.searchFilterYear.isBlank()
        _identifyReview.value = latest.copy(
            showSearchFilters = next,
            searchFilterArtist = if (seed) defaultFilterArtist(item!!) else latest.searchFilterArtist,
            searchFilterAlbum = if (seed) defaultFilterAlbum(item!!) else latest.searchFilterAlbum,
            searchFilterYear = if (seed) defaultFilterYear(item!!) else latest.searchFilterYear
        )
    }

    fun searchIdentifyCandidates() {
        val state = _identifyReview.value
        val item = state.current ?: return
        val query = state.searchQueryDraft.trim()
        val filters = state.searchFilters.normalized()
        if (query.isEmpty() && !filters.hasAny) {
            toast("Escribí una búsqueda o un filtro")
            return
        }
        viewModelScope.launch {
            _identifyReview.value = _identifyReview.value.copy(isSearching = true, isLoadingMore = false)
            val proposal = withContext(Dispatchers.IO) {
                repository.proposeSongIdentity(
                    song = item.song,
                    customQuery = query.ifBlank { null },
                    force = true,
                    filters = filters
                )
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
                showSearchField = latest.showSearchField || proposal.candidates.isEmpty(),
                visibleCandidateCount = minOf(IdentifyRanking.TOP_N, proposal.candidates.size)
            )
            if (proposal.candidates.isEmpty()) {
                val label = query.ifBlank { "esos filtros" }
                toast("Sin resultados para \"$label\"")
            }
        }
    }

    fun loadMoreIdentifyCandidates() {
        val state = _identifyReview.value
        val item = state.current ?: return
        if (state.isSearching || state.isLoadingMore) return
        val all = item.proposal.candidates
        val visible = state.visibleCandidateCount
        if (visible < all.size) {
            _identifyReview.value = state.copy(
                visibleCandidateCount = (visible + IdentifyRanking.PAGE_SIZE)
                    .coerceAtMost(all.size)
            )
            return
        }
        if (!item.proposal.catalogMayHaveMore) {
            toast("No hay más candidatos")
            return
        }
        val query = state.searchQueryDraft.trim()
        val filters = state.searchFilters.normalized()
        viewModelScope.launch {
            _identifyReview.value = _identifyReview.value.copy(isLoadingMore = true)
            val proposal = withContext(Dispatchers.IO) {
                repository.proposeSongIdentity(
                    song = item.song,
                    customQuery = query.ifBlank { null },
                    force = true,
                    filters = filters,
                    catalogIndex = item.proposal.nextCatalogIndex,
                    existingCandidates = all
                )
            }
            val latest = _identifyReview.value
            if (latest.current?.song?.id != item.song.id) {
                _identifyReview.value = latest.copy(isLoadingMore = false)
                return@launch
            }
            val items = latest.items.toMutableList()
            items[latest.currentIndex] = item.copy(proposal = proposal)
            val grew = proposal.candidates.size > all.size
            _identifyReview.value = latest.copy(
                items = items,
                isLoadingMore = false,
                visibleCandidateCount = if (grew) {
                    (visible + IdentifyRanking.PAGE_SIZE).coerceAtMost(proposal.candidates.size)
                } else {
                    proposal.candidates.size
                }
            )
            if (!grew) {
                toast("No hay más candidatos")
            }
        }
    }

    fun applySelectedIdentifyCandidate() {
        val state = _identifyReview.value
        val item = state.current ?: return
        val candidate = state.visibleCandidates.getOrNull(state.selectedCandidateIndex)
            ?: item.proposal.suggested
        if (candidate == null) {
            toast("Elegí un candidato o buscá otro")
            return
        }
        viewModelScope.launch {
            when (
                withContext(Dispatchers.IO) {
                    repository.applySongIdentity(item.song.id, candidate, state.applyFields)
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
            sessionApplied = nextApplied,
            sessionSkipped = nextSkipped
        ).withItemSearchChrome(nextItem)
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
                val library = rawSongs.first()
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

    fun searchCatalog(
        query: String = _catalogSearch.value.searchQueryDraft,
        filters: IdentifySearchFilters = _catalogSearch.value.searchFilters
    ) {
        lastCatalogQuery = query
        lastCatalogFilters = filters
        val cleanQ = query.trim()
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
                    if (generation == catalogSearchGeneration) {
                        _catalogSearch.update { it.copy(tracks = results) }
                    }
                }
                CatalogCategory.ALBUMS -> {
                    val results = MetadataFetcher.searchAlbums(effectiveQuery.ifEmpty { cleanQ })
                    if (generation == catalogSearchGeneration) {
                        _catalogSearch.update { it.copy(albums = results) }
                    }
                }
                CatalogCategory.PLAYLISTS -> {
                    val results = MetadataFetcher.searchPlaylists(cleanQ.ifEmpty { effectiveQuery })
                    if (generation == catalogSearchGeneration) {
                        _catalogSearch.update { it.copy(playlists = results) }
                    }
                }
                CatalogCategory.GENRES -> {
                    val genres = MetadataFetcher.listGenres()
                    val results = if (cleanQ.isEmpty()) {
                        genres
                    } else {
                        genres.filter { TrackMatchKeys.containsNormalized(it.name, cleanQ) }
                    }
                    if (generation == catalogSearchGeneration) {
                        _catalogSearch.update { it.copy(genres = results) }
                    }
                }
                CatalogCategory.CHARTS -> {
                    val results = MetadataFetcher.fetchChartTracks()
                    if (generation == catalogSearchGeneration) {
                        _catalogSearch.update { it.copy(tracks = results) }
                    }
                }
            }
            if (generation != catalogSearchGeneration) return@launch
            _catalogSearch.update { it.copy(isSearching = false) }
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

    fun selectAlbumForInspection(album: CatalogAlbum) {
        selectCollectionForInspection(
            selectionKey = "album:${album.id}",
            title = album.title,
            kind = CatalogCollectionKind.ALBUM,
            coverUrl = album.coverUrl
        ) {
            MetadataFetcher.fetchAlbumTrackCandidates(album.id, album.title, album.artist, album.coverUrl)
        }
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
            val song = rawSongs.first().find { it.id == songId } ?: return@launch
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

    companion object {
        const val RADIO_LOADING_LABEL = "Armando radio…"
        private const val METADATA_ENHANCE_BATCH = 20
    }
}

