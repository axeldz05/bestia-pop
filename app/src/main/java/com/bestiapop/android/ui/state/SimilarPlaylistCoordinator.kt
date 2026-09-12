package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.PlaylistMessages
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.usecase.BuildSimilarPlaylistPreviewUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinator for the "Similar Playlist" preview dialog lifecycle, recommendation fetching,
 * item selection, and playlist creation.
 * Keeps [com.bestiapop.android.ui.MusicPlayerViewModel] lean by encapsulating preview state.
 */
class SimilarPlaylistCoordinator(
    private val scope: CoroutineScope,
    private val useCase: BuildSimilarPlaylistPreviewUseCase,
    private val isNetworkOnline: () -> Boolean,
    private val resolvePreferredRadioMode: (RadioMode?, Boolean) -> RadioMode,
    private val getListenBrainzSettings: () -> ListenBrainzSettings,
    private val getAllSongs: suspend () -> List<Song>,
    private val onPlaylistCreated: (playlistId: Long, localCount: Int, pendingCount: Int, downloadMissing: Boolean) -> Unit,
    private val playPlayableCollection: (List<PlayableItem>) -> Unit,
    private val addPlayableBatch: (List<PlayableItem>) -> Unit,
    private val toast: (String) -> Unit
) {
    private val _state = MutableStateFlow<SimilarPlaylistPreviewState?>(null)
    val state: StateFlow<SimilarPlaylistPreviewState?> = _state.asStateFlow()

    private var previewJob: Job? = null
    private var previewSeeds: List<PlayableItem> = emptyList()

    fun open(
        seeds: List<PlayableItem>,
        mode: RadioMode? = null
    ) {
        if (seeds.isEmpty()) return
        val networkOnline = isNetworkOnline()
        val resolvedMode = resolvePreferredRadioMode(mode, networkOnline)
        previewSeeds = seeds
        val name = BuildSimilarPlaylistPreviewUseCase.defaultPlaylistName(seeds)
        _state.value = SimilarPlaylistPreviewState(
            items = emptyList(),
            selectedKeys = emptySet(),
            mode = resolvedMode,
            loading = true,
            seedCount = seeds.size,
            playlistName = name
        )
        runPreview(resolvedMode)
    }

    fun dismiss() {
        previewJob?.cancel()
        previewJob = null
        previewSeeds = emptyList()
        _state.value = null
    }

    fun toggleItem(key: String) {
        val current = _state.value ?: return
        if (current.loading || key.isBlank()) return
        val next = current.selectedKeys.toMutableSet()
        if (!next.add(key)) next.remove(key)
        _state.value = current.copy(selectedKeys = next)
    }

    fun setMode(mode: RadioMode) {
        val current = _state.value ?: return
        if (current.mode == mode && !current.loading) return
        _state.value = current.copy(mode = mode, loading = true)
        runPreview(mode)
    }

    fun setPlaylistName(name: String) {
        val current = _state.value ?: return
        _state.value = current.copy(playlistName = name)
    }

    fun confirmAsPlaylist(
        name: String? = null,
        downloadMissing: Boolean = false
    ) {
        val current = _state.value ?: return
        if (current.loading) return
        val selected = current.selectedItems
        if (selected.isEmpty()) {
            toast(DownloadMessages.selectAtLeastOneSong)
            return
        }
        val playlistName = (name ?: current.playlistName).ifBlank {
            BuildSimilarPlaylistPreviewUseCase.defaultPlaylistName(previewSeeds)
        }
        scope.launch {
            val playlistId = useCase.createPlaylistFromPlayables(
                name = playlistName,
                items = selected
            )
            if (playlistId == null) {
                toast(PlaylistMessages.createFailed)
                return@launch
            }
            val localCount = selected.count { it is PlayableItem.Local }
            val pendingCount = selected.count { it is PlayableItem.Remote }
            dismiss()
            onPlaylistCreated(playlistId, localCount, pendingCount, downloadMissing)
        }
    }

    private inline fun withSelectedItems(action: (List<PlayableItem>) -> Unit) {
        val current = _state.value ?: return
        if (current.loading) return
        val selected = current.selectedItems
        if (selected.isEmpty()) {
            toast(DownloadMessages.selectAtLeastOneSong)
            return
        }
        action(selected)
        dismiss()
    }

    fun play() {
        withSelectedItems { selected ->
            playPlayableCollection(selected)
        }
    }

    fun enqueue() {
        withSelectedItems { selected ->
            addPlayableBatch(selected)
            toast("Agregadas a la cola (${selected.size})")
        }
    }

    private fun runPreview(mode: RadioMode) {
        val seeds = previewSeeds
        if (seeds.isEmpty()) return
        previewJob?.cancel()
        previewJob = scope.launch {
            val settings = getListenBrainzSettings()
            val networkOnline = isNetworkOnline()
            val canUseLb = settings.enabled &&
                settings.userToken.isNotBlank() &&
                networkOnline
            val library = getAllSongs()
            val preview = useCase.execute(
                seeds = seeds,
                library = library,
                mode = mode,
                lbToken = settings.userToken.takeIf { it.isNotBlank() },
                lbAvailable = canUseLb,
                lbUsername = settings.username,
                networkAvailable = networkOnline
            )
            val current = _state.value
            if (current == null) return@launch
            if (preview.items.isEmpty()) {
                toast(
                    if (preview.failedOnline) "Radio online no disponible"
                    else "No encontré canciones parecidas"
                )
            }
            _state.value = current.copy(
                items = preview.items,
                selectedKeys = SimilarPlaylistPreviewState.keysOf(preview.items),
                mode = mode,
                loading = false,
                usedOnline = preview.usedOnline,
                failedOnline = preview.failedOnline
            )
        }
    }
}
