package com.bestiapop.android.ui.state

import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.MatchedCfRecommendations
import com.bestiapop.android.data.listenbrainz.rematchLocals
import com.bestiapop.android.domain.usecase.DiscoverFeed
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.usecase.TopRelatedFeed
import com.bestiapop.android.data.model.toListenBrainzCatalogTrack
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.preferences.DiscoverSourcePreference
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.usecase.FetchAndMatchCfRecommendationsUseCase
import com.bestiapop.android.domain.usecase.GetDiscoverRecommendationsUseCase
import com.bestiapop.android.domain.usecase.GetTopRelatedItemsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinator managing the discover feeds (home feed, top related, ListenBrainz playlists,
 * and Collaborative Filtering recommendations). Keeps [com.bestiapop.android.ui.MusicPlayerViewModel] lean.
 */
class DiscoverFeedCoordinator(
    private val scope: CoroutineScope,
    private val repository: IMusicRepository,
    private val listenBrainzPreferences: ListenBrainzPreferencesRepository,
    private val libraryPreferences: LibraryPreferencesRepository,
    private val onClearExternalState: () -> Unit = {},
    private val getDiscoverRecommendationsUseCase: GetDiscoverRecommendationsUseCase = GetDiscoverRecommendationsUseCase(),
    private val getTopRelatedItemsUseCase: GetTopRelatedItemsUseCase = GetTopRelatedItemsUseCase(),
    private val fetchAndMatchCfRecommendationsUseCase: FetchAndMatchCfRecommendationsUseCase = FetchAndMatchCfRecommendationsUseCase()
) {
    private val _discoverFeed = MutableStateFlow(DiscoverFeed())
    val discoverFeed: StateFlow<DiscoverFeed> = _discoverFeed.asStateFlow()

    private val _isLoadingDiscoverFeed = MutableStateFlow(false)
    val isLoadingDiscoverFeed: StateFlow<Boolean> = _isLoadingDiscoverFeed.asStateFlow()

    private val _topRelatedFeed = MutableStateFlow(TopRelatedFeed())
    val topRelatedFeed: StateFlow<TopRelatedFeed> = _topRelatedFeed.asStateFlow()

    private val _isLoadingTopRelatedFeed = MutableStateFlow(false)
    val isLoadingTopRelatedFeed: StateFlow<Boolean> = _isLoadingTopRelatedFeed.asStateFlow()

    private val _lbDiscover = MutableStateFlow(LoadableUiState<List<LbPlaylistSummary>>(emptyList()))
    val lbDiscover: StateFlow<LoadableUiState<List<LbPlaylistSummary>>> = _lbDiscover.asStateFlow()

    private val _cfRecommendations = MutableStateFlow(LoadableUiState<MatchedCfRecommendations?>(null))
    val cfRecommendations: StateFlow<LoadableUiState<MatchedCfRecommendations?>> = _cfRecommendations.asStateFlow()

    fun refreshDiscoverFeed(forceRefresh: Boolean = false) {
        if (forceRefresh) {
            ListenBrainzClient.clearUserStatsCache()
        }
        scope.launch {
            val currentSource = libraryPreferences.discoverSourceFlow.first()
            if (currentSource != DiscoverSourcePreference.DEEZER) {
                refreshListenBrainzDiscoverPlaylists()
            }
            _isLoadingDiscoverFeed.value = true
            try {
                val songs = repository.allSongsFlow.first()
                val stats = repository.songPlayStatsFlow.first()
                val lbSettings = listenBrainzPreferences.settingsFlow.first()
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
        }
    }

    fun refreshTopRelatedFeed(forceRefresh: Boolean = false) {
        if (forceRefresh) {
            ListenBrainzClient.clearUserStatsCache()
        }
        scope.launch {
            _isLoadingTopRelatedFeed.value = true
            try {
                val songs = repository.allSongsFlow.first()
                val stats = repository.songPlayStatsFlow.first()
                val lbSettings = listenBrainzPreferences.settingsFlow.first()
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

    fun refreshListenBrainzDiscoverPlaylists() {
        scope.launch {
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
        scope.launch {
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

    suspend fun loadCfRecommendationsForRestore(): Boolean {
        val settings = listenBrainzPreferences.settingsFlow.first()
        if (!settings.showDiscoverPlaylists) return false
        refreshCfRecommendationsInternal(settings)
        return _cfRecommendations.value.data != null && _cfRecommendations.value.isLoaded
    }

    fun clearDiscoverState() {
        _lbDiscover.update { it.idle(emptyList()) }
        clearCfState()
        onClearExternalState()
    }

    fun clearCfState() {
        _cfRecommendations.update { it.idle(data = null) }
    }

    fun rematchCfRecommendations(library: List<Song>) {
        _cfRecommendations.value.data?.let { current ->
            _cfRecommendations.update {
                it.copy(data = current.copy(matches = current.matches.rematchLocals(library)))
            }
        }
    }
}
