package com.bestiapop.android.service

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.radio.RadioSuggestResult
import com.bestiapop.android.domain.util.TrackMatchKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PlaybackRadioCoordinator(
    private val scope: CoroutineScope,
    private val dependencies: PlaybackRuntimeDependencies,
    private val getCurrentItem: () -> PlayableItem?,
    private val getQueue: () -> List<PlayableItem>,
    private val getCurrentIndex: () -> Int,
    private val getRepeatMode: () -> RepeatMode,
    private val isPlayWhenReadyIntent: () -> Boolean,
    private val canKeepCurrent: () -> Boolean,
    private val getLibrary: () -> List<Song>,
    private val onEmitEvent: (String) -> Unit,
    private val onClearDiscoverPlaybackOrigin: () -> Unit,
    private val onApplyRadioStartModes: () -> Unit,
    private val onReplaceUpcomingWithRadio: (List<PlayableItem>) -> Unit,
    private val onPlayPlayableCollection: (List<PlayableItem>, fromRadio: Boolean, rotate: Boolean) -> Unit,
    private val onAddPlayableBatch: (List<PlayableItem>) -> Unit,
    private val onPrefetchAround: (Int) -> Unit
) {
    private val _radioActive = MutableStateFlow(false)
    val radioActive = _radioActive.asStateFlow()

    private val _radioLoading = MutableStateFlow(false)
    val radioLoading = _radioLoading.asStateFlow()

    private val _radioMode = MutableStateFlow(RadioMode.KNOWN)
    val radioMode = _radioMode.asStateFlow()

    private val _radioStatusLabel = MutableStateFlow<String?>(null)
    val radioStatusLabel = _radioStatusLabel.asStateFlow()

    private var radioStartJob: Job? = null
    private var radioRefillJob: Job? = null
    private val playedInRadioSession = linkedSetOf<String>()
    private var radioPreferredMode: RadioMode? = null
    private var lastEmptyRadioRefillAtMs = 0L

    fun setRadioPreferredMode(mode: RadioMode) {
        radioPreferredMode = mode
    }

    fun preferredRadioModeOrNull(): RadioMode? = radioPreferredMode

    fun stopRadio() {
        radioStartJob?.cancel()
        radioStartJob = null
        radioRefillJob?.cancel()
        radioRefillJob = null
        lastEmptyRadioRefillAtMs = 0L
        _radioLoading.value = false
        _radioActive.value = false
        playedInRadioSession.clear()
        _radioMode.value = RadioMode.KNOWN
        updateRadioStatusLabel()
    }

    fun clearRadioSession() {
        stopRadio()
        radioPreferredMode = null
    }

    fun clearRadioSessionKeepPreference() {
        radioRefillJob?.cancel()
        radioRefillJob = null
        _radioActive.value = false
        playedInRadioSession.clear()
        updateRadioStatusLabel()
    }

    fun startRadio(
        seedSong: Song? = null,
        mode: RadioMode? = null,
        auto: Boolean = false,
        announceMode: Boolean = false
    ) {
        val seed = seedSong?.toPlayable() ?: getCurrentItem() ?: run {
            if (!auto) onEmitEvent("Elegí una canción para iniciar la radio")
            return
        }
        if (seed.artist.isBlank() || seed.title.isBlank()) return
        if (_radioLoading.value) return
        if (mode != null) radioPreferredMode = mode
        val resolvedMode = mode ?: radioPreferredMode
            ?: if (dependencies.isOnline()) RadioMode.BOTH else RadioMode.KNOWN
        val keepCurrent = !auto && canKeepCurrent()
        radioStartJob?.cancel()
        radioStartJob = scope.launch {
            dependencies.listenSettingsReady.first { it }
            if (!isActive) return@launch
            _radioLoading.value = true
            try {
                val exclude = buildRadioExcludeKeys(seed, includeQueue = true, getCurrentItem())
                val batch = suggestRadioWithRetry(
                    PlaybackRuntimeRadioRequest(
                        seed = seed,
                        library = getLibrary(),
                        mode = resolvedMode,
                        excludeKeys = exclude,
                        settings = dependencies.listenSettings.value,
                        timeoutMs = RADIO_START_TIMEOUT_MS,
                        coPlaylistSongIds = dependencies.resolveCoPlaylistSongIds(seed)
                    )
                )
                if (!isActive) return@launch
                if (batch.items.isEmpty()) {
                    if (!auto) {
                        onEmitEvent(
                            if (resolvedMode == RadioMode.NEW) {
                                "Radio online no disponible"
                            } else {
                                "No encontré canciones parecidas"
                            }
                        )
                    }
                    return@launch
                }
                lastEmptyRadioRefillAtMs = 0L
                onClearDiscoverPlaybackOrigin()
                onApplyRadioStartModes()
                val previousPlayed =
                    if (_radioActive.value) playedInRadioSession.toSet() else emptySet()
                clearRadioSessionKeepPreference()
                _radioMode.value = resolvedMode
                playedInRadioSession += previousPlayed
                playedInRadioSession += exclude
                rememberRadioPlayed(seed)
                _radioActive.value = true
                updateRadioStatusLabel()
                if (announceMode && !auto) onEmitEvent(radioModeLabel(resolvedMode))
                if (keepCurrent) {
                    onReplaceUpcomingWithRadio(batch.items)
                    onEmitEvent("Se agregaron canciones de la radio a la cola")
                    onPrefetchAround(getCurrentIndex())
                } else {
                    onPlayPlayableCollection(
                        batch.items,
                        true,
                        false
                    )
                }
            } finally {
                _radioLoading.value = false
            }
        }
    }

    fun maybeAutoStartRadioOnQueueEnd() {
        if (getRepeatMode() != RepeatMode.OFF || _radioLoading.value) return
        if (getQueue().isEmpty() || !isPlayWhenReadyIntent()) return
        val seed = getCurrentItem() ?: return
        if (seed.artist.isBlank() || seed.title.isBlank()) return
        startRadio(auto = true)
    }

    fun maybeRefillRadio(currentIndex: Int) {
        if (!_radioActive.value || radioRefillJob?.isActive == true) return
        val remaining = getQueue().size - currentIndex - 1
        if (remaining >= RADIO_REFILL_THRESHOLD) return
        val seed = getCurrentItem() ?: return
        val sinceEmpty = dependencies.clockMs() - lastEmptyRadioRefillAtMs
        if (lastEmptyRadioRefillAtMs > 0L && sinceEmpty < RADIO_EMPTY_COOLDOWN_MS) return
        radioRefillJob = scope.launch {
            val batch = suggestRadioWithRetry(
                PlaybackRuntimeRadioRequest(
                    seed = seed,
                    library = getLibrary(),
                    mode = _radioMode.value,
                    excludeKeys = buildRadioExcludeKeys(seed),
                    settings = dependencies.listenSettings.value,
                    timeoutMs = RADIO_REFILL_TIMEOUT_MS,
                    coPlaylistSongIds = dependencies.resolveCoPlaylistSongIds(seed)
                )
            )
            if (!isActive || !_radioActive.value) return@launch
            if (batch.items.isNotEmpty()) {
                lastEmptyRadioRefillAtMs = 0L
                onAddPlayableBatch(batch.items)
            } else if (batch.items.isEmpty()) {
                lastEmptyRadioRefillAtMs = dependencies.clockMs()
            }
        }
    }

    suspend fun suggestRadioWithRetry(
        request: PlaybackRuntimeRadioRequest
    ): RadioSuggestResult {
        suspend fun once(): RadioSuggestResult = dependencies.radioSuggester.suggest(request)
        if (request.mode != RadioMode.NEW) return once()

        val deadline = dependencies.clockMs() + request.timeoutMs
        var attempt = 0
        var result = once()
        while (result.items.isEmpty() && dependencies.clockMs() < deadline) {
            attempt++
            delay(minOf(attempt * 1_000L, 5_000L))
            result = once()
        }
        return result
    }

    fun rememberRadioPlayed(item: PlayableItem) {
        TrackMatchKeys.matchKey(item.artist, item.title)
            .takeIf { it.isNotEmpty() }
            ?.let(playedInRadioSession::add)
        playedInRadioSession += item.mediaId
    }

    private fun buildRadioExcludeKeys(
        seed: PlayableItem,
        includeQueue: Boolean = true,
        extra: PlayableItem? = null
    ): MutableSet<String> {
        val exclude = playedInRadioSession.toMutableSet()
        fun add(item: PlayableItem) {
            TrackMatchKeys.matchKey(item.artist, item.title)
                .takeIf { it.isNotEmpty() }
                ?.let(exclude::add)
            exclude += item.mediaId
        }
        add(seed)
        extra?.let(::add)
        if (includeQueue) getQueue().forEach(::add)
        return exclude
    }

    private fun updateRadioStatusLabel() {
        _radioStatusLabel.value =
            if (_radioActive.value) radioModeLabel(_radioMode.value) else null
    }

    private fun radioModeLabel(mode: RadioMode): String = when (mode) {
        RadioMode.KNOWN -> "Radio · Solo conocidos"
        RadioMode.NEW -> "Radio · Solo nuevos"
        RadioMode.BOTH -> "Radio · Ambos"
    }

    companion object {
        private const val RADIO_REFILL_THRESHOLD = 5
        private const val RADIO_START_TIMEOUT_MS = 45_000L
        private const val RADIO_REFILL_TIMEOUT_MS = 20_000L
        private const val RADIO_EMPTY_COOLDOWN_MS = 60_000L
    }
}
