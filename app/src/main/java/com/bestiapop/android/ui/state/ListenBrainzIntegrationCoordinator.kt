package com.bestiapop.android.ui.state

import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.listenbrainz.rematchLocals
import com.bestiapop.android.data.listenbrainz.withArtwork
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.usecase.ImportListenBrainzPlaylistUseCase
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase
import com.bestiapop.android.service.PlaybackRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates ListenBrainz account actions, token validation, settings mutations,
 * remote playlist fetching, local library import, and library change rematching.
 */
class ListenBrainzIntegrationCoordinator(
    private val scope: CoroutineScope,
    private val repository: IMusicRepository,
    private val listenBrainzPreferences: ListenBrainzPreferencesRepository,
    private val playbackRuntime: PlaybackRuntime,
    private val discoverFeedCoordinator: DiscoverFeedCoordinator,
    private val enqueuePendingDownloads: suspend (Long, List<OnlineCatalogTrack>, Boolean) -> Unit,
    private val getCurrentPlaylistDetail: () -> PlaylistDetailNav,
    private val toast: (String) -> Unit,
    private val toastPlaylistSaved: (Int, Int) -> Unit
) {
    private val _tokenValidation = MutableStateFlow(LoadableUiState<String?>(null))
    val tokenValidation: StateFlow<LoadableUiState<String?>> = _tokenValidation.asStateFlow()

    private val matchListenBrainzTracksUseCase = MatchListenBrainzTracksUseCase()
    private val importListenBrainzPlaylistUseCase = ImportListenBrainzPlaylistUseCase(repository)

    private val _lbPlaylistDetail = MutableStateFlow(LoadableUiState<MatchedLbPlaylist?>(null))
    val lbPlaylistDetail: StateFlow<LoadableUiState<MatchedLbPlaylist?>> = _lbPlaylistDetail.asStateFlow()

    /** Held so opening another Discover playlist cancels the previous fetch. */
    private var lbDetailJob: Job? = null

    fun setListenBrainzEnabled(enabled: Boolean) {
        scope.launch {
            listenBrainzPreferences.setEnabled(enabled)
            if (enabled) playbackRuntime.requestListenSync()
            if (!enabled) discoverFeedCoordinator.clearDiscoverState()
        }
    }

    fun setListenBrainzDiscoverEnabled(enabled: Boolean) {
        scope.launch {
            listenBrainzPreferences.setDiscoverEnabled(enabled)
            if (enabled) {
                discoverFeedCoordinator.refreshListenBrainzDiscoverPlaylists()
            } else {
                discoverFeedCoordinator.clearDiscoverState()
            }
        }
    }

    fun setListenBrainzSaveWhileListening(enabled: Boolean) {
        scope.launch {
            listenBrainzPreferences.setSaveWhileListening(enabled)
        }
    }

    fun setListenBrainzSaveWhileListeningPercent(percent: Int) {
        scope.launch {
            listenBrainzPreferences.setSaveWhileListeningPercent(percent)
        }
    }

    fun saveListenBrainzToken(token: String) {
        scope.launch {
            listenBrainzPreferences.setToken(token)
            _tokenValidation.value = _tokenValidation.value.idle(data = null)
        }
    }

    fun validateListenBrainzToken(token: String) {
        scope.launch {
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
                val settings = listenBrainzPreferences.settingsFlow.first()
                if (settings.discoverEnabled) {
                    discoverFeedCoordinator.refreshListenBrainzDiscoverPlaylists()
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
        scope.launch {
            listenBrainzPreferences.clear()
            _tokenValidation.value = _tokenValidation.value.idle(data = null)
            discoverFeedCoordinator.clearDiscoverState()
        }
    }

    fun openListenBrainzPlaylist(mbid: String) {
        // Tracked so opening A then B cannot leave A's late response rendered under B's route.
        lbDetailJob?.cancel()
        lbDetailJob = scope.launch {
            loadListenBrainzPlaylist(mbid, forRestore = false)
        }
    }

    private fun isListenBrainzDetailCurrent(mbid: String): Boolean =
        getCurrentPlaylistDetail().lbMbidOrNull() == mbid

    suspend fun loadListenBrainzPlaylist(mbid: String, forRestore: Boolean): Boolean {
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
                enrichLbPlaylistDetailArtwork(mbid, matched)
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

    private fun enrichLbPlaylistDetailArtwork(mbid: String, matched: MatchedLbPlaylist) {
        val tracksMissingArt = matched.matches.filter { it.localSong == null && it.identity.artworkUri.isNullOrBlank() }
        val coverMissing = matched.detail.summary.coverUrl.isNullOrBlank()
        if (tracksMissingArt.isEmpty() && !coverMissing) return

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                tracksMissingArt.forEach { track ->
                    launch {
                        val art = MetadataFetcher.fetchTrackArtwork(track.identity)
                        if (!art.isNullOrBlank()) {
                            _lbPlaylistDetail.update { state ->
                                val cur = state.data ?: return@update state
                                if (cur.detail.summary.mbid != mbid) return@update state
                                val updatedMatches = cur.matches.withArtwork(track.identity.artist, track.identity.title, art)
                                val updatedCover = cur.detail.summary.coverUrl ?: art
                                cur.copy(
                                    detail = cur.detail.copy(summary = cur.detail.summary.copy(coverUrl = updatedCover)),
                                    matches = updatedMatches
                                ).let { state.copy(data = it) }
                            }
                        }
                    }
                }
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
        scope.launch {
            val playlistId = importListenBrainzPlaylistUseCase.createLocalFromMatched(matched)
                ?: return@launch
            toastPlaylistSaved(matched.matchedCount, matched.streamCount)
            onCreated?.invoke(playlistId)
        }
    }

    /**
     * Creates a local playlist with matched + pending metadata, then enqueues unmatched
     * downloads via catalog coordinator.
     */
    fun importListenBrainzPlaylistWithDownloads(onCreated: ((Long) -> Unit)? = null) {
        val matched = _lbPlaylistDetail.value.data ?: return
        val unmatched = importListenBrainzPlaylistUseCase.unmatchedCatalogTracks(matched)
        if (unmatched.isEmpty() && matched.matchedCount == 0) return

        scope.launch {
            val playlistId = importListenBrainzPlaylistUseCase.createLocalFromMatched(
                matched = matched,
                allowEmpty = unmatched.isNotEmpty()
            ) ?: return@launch

            onCreated?.invoke(playlistId)

            if (unmatched.isEmpty()) {
                toastPlaylistSaved(matched.matchedCount, 0)
                return@launch
            }

            enqueuePendingDownloads(
                playlistId,
                unmatched,
                true
            )
        }
    }

    /** Downloads pending metadata tracks for an already-saved local playlist. */
    fun downloadPlaylistPendingTracks(playlistId: Long) {
        if (playlistId <= 0L) return
        scope.launch {
            val pending = repository.getPlaylistPendingTracksFlow(playlistId).first()
            if (pending.isEmpty()) {
                toast(DownloadMessages.noPendingTracks)
                return@launch
            }
            enqueuePendingDownloads(
                playlistId,
                pending.map { it.toOnlineCatalogTrack() },
                true
            )
        }
    }

    suspend fun rematchDiscoverAfterLibraryChange(extraSong: Song? = null) {
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
}
