package com.bestiapop.android.service

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.bestiapop.android.data.db.PendingListenDao
import com.bestiapop.android.data.listenbrainz.ListenSyncCoordinator
import com.bestiapop.android.data.listenbrainz.ListenTracker
import com.bestiapop.android.data.listenbrainz.SaveWhileListeningEvent
import com.bestiapop.android.data.listenbrainz.SaveWhileListeningPolicy
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.DiscoverPlaybackOrigin
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.indexOfQueueEntry
import com.bestiapop.android.data.model.indexOfRemoteSlot
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.data.model.withFreshQueueEntryIds
import com.bestiapop.android.data.network.ConnectivityObserver
import com.bestiapop.android.data.playback.PlaybackChangeHint
import com.bestiapop.android.data.playback.PlaybackFallbackPlanner
import com.bestiapop.android.data.playback.PlaybackFallbackStep
import com.bestiapop.android.data.playback.PlaybackQueueOrder
import com.bestiapop.android.data.playback.PlaybackQueueSlots
import com.bestiapop.android.data.playback.PlaybackSelectionIntentGate
import com.bestiapop.android.data.preferences.HydratedQueue
import com.bestiapop.android.data.preferences.LastPlayedSnapshot
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.PlaybackHydration
import com.bestiapop.android.data.preferences.PlaybackModeClear
import com.bestiapop.android.data.preferences.PlaybackModeRestore
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSessionStore
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.QueueSnapshot
import com.bestiapop.android.data.preferences.QueueSnapshotCodec
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.data.stream.StreamResolver
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.domain.radio.RadioEngine
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.radio.RadioSuggestResult
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val STREAM_READY_MAX_AGE_MS = 60_000L

private fun controllerReconnectBackoffMs(attempt: Int): Long {
    val exponent = (attempt - 1).coerceIn(0, 4)
    return 500L * (1L shl exponent)
}

internal interface PlaybackControllerFacade {
    interface Listener {
        fun onIsPlayingChanged(isPlaying: Boolean) = Unit
        fun onPlayWhenReadyChanged(playWhenReady: Boolean) = Unit
        fun onPlayerError() = Unit
        fun onPlaybackStateChanged(playbackState: Int) = Unit
        fun onMediaItemTransition(item: PlayableItem?, reason: Int) = Unit
        fun onTimelineChanged() = Unit
        fun onPositionDiscontinuity(positionMs: Long) = Unit
        fun onRepeatModeChanged(repeatMode: Int) = Unit
        fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = Unit
        fun onDisconnected(controller: PlaybackControllerFacade) = Unit
    }

    val mediaItemCount: Int
    val currentMediaItemIndex: Int
    val currentPosition: Long
    val duration: Long
    val isPlaying: Boolean
    val playWhenReady: Boolean
    val playbackState: Int
    var repeatMode: Int
    var shuffleModeEnabled: Boolean

    fun addListener(listener: Listener)
    fun items(): List<PlayableItem>
    fun setMediaItems(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long)
    fun replaceMediaItem(index: Int, item: PlayableItem)
    fun addMediaItems(items: List<PlayableItem>)
    fun addMediaItems(index: Int, items: List<PlayableItem>)
    fun removeMediaItem(index: Int)
    fun removeMediaItems(fromIndex: Int, toIndex: Int)
    fun moveMediaItem(fromIndex: Int, toIndex: Int)
    fun prepare()
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekTo(index: Int, positionMs: Long)
    fun seekToNextMediaItem()
    fun seekToPreviousMediaItem()
    fun hasNextMediaItem(): Boolean
    fun hasPreviousMediaItem(): Boolean
    fun release()
}

internal interface PlaybackControllerConnection {
    fun addListener(listener: () -> Unit)
    fun get(): PlaybackControllerFacade
    fun cancel()
}

internal fun interface PlaybackControllerConnector {
    fun connect(): PlaybackControllerConnection
}

internal interface PlaybackRuntimePersistence {
    suspend fun loadLastPlayed(): LastPlayedSnapshot? = null
    suspend fun loadQueue(): QueueSnapshot? = null
    suspend fun saveSession(
        lastPlayed: LastPlayedSnapshot?,
        queue: QueueSnapshot?,
        clearQueue: Boolean
    ) = Unit
}

internal interface PlaybackRuntimeListenTracker {
    fun onTrackChanged(song: Song?, hint: PlaybackChangeHint)
    fun onDurationKnown(songId: Long, durationMs: Long)
    fun onPlaybackTick(isPlaying: Boolean, elapsedRealtimeMs: Long)
    fun onStopped()
}

internal sealed interface SaveWhileListeningDownloadResult {
    data class Saved(val song: Song) : SaveWhileListeningDownloadResult
    data class InFlight(val downloadId: String) : SaveWhileListeningDownloadResult
    data class Failed(val error: Throwable) : SaveWhileListeningDownloadResult
}

internal interface PlaybackRuntimeSaveDownloads {
    val downloads: StateFlow<List<ActiveDownload>>
    suspend fun save(remote: PlayableItem.Remote): SaveWhileListeningDownloadResult
    fun dismiss(id: String)
}

internal interface PlaybackRuntimeStreamAccess {
    fun needsResolve(item: PlayableItem.Remote): Boolean
    suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote?
    suspend fun invalidate(item: PlayableItem.Remote)
}

internal data class PlaybackRuntimeRadioRequest(
    val seed: PlayableItem,
    val library: List<Song>,
    val mode: RadioMode,
    val excludeKeys: Set<String>,
    val settings: ListenBrainzSettings,
    val timeoutMs: Long,
    val coPlaylistSongIds: Set<Long>
)

internal fun interface PlaybackRuntimeRadioSuggester {
    suspend fun suggest(request: PlaybackRuntimeRadioRequest): RadioSuggestResult
}

internal data class PlaybackRuntimeDependencies(
    val scope: CoroutineScope,
    val libraryUpdates: Flow<List<Song>> = flowOf(emptyList()),
    val playbackSettings: StateFlow<PlaybackSettings> = MutableStateFlow(PlaybackSettings()),
    val playbackSettingsReady: StateFlow<Boolean> = MutableStateFlow(true),
    val listenSettings: StateFlow<ListenBrainzSettings> = MutableStateFlow(ListenBrainzSettings()),
    val listenSettingsReady: StateFlow<Boolean> = MutableStateFlow(true),
    val persistence: PlaybackRuntimePersistence = object : PlaybackRuntimePersistence {},
    val listenTracker: PlaybackRuntimeListenTracker = object : PlaybackRuntimeListenTracker {
        override fun onTrackChanged(song: Song?, hint: PlaybackChangeHint) = Unit
        override fun onDurationKnown(songId: Long, durationMs: Long) = Unit
        override fun onPlaybackTick(isPlaying: Boolean, elapsedRealtimeMs: Long) = Unit
        override fun onStopped() = Unit
    },
    val streamAccess: PlaybackRuntimeStreamAccess,
    val saveDownloads: PlaybackRuntimeSaveDownloads = object : PlaybackRuntimeSaveDownloads {
        override val downloads = MutableStateFlow<List<ActiveDownload>>(emptyList())
        override suspend fun save(remote: PlayableItem.Remote): SaveWhileListeningDownloadResult =
            SaveWhileListeningDownloadResult.Failed(
                IllegalStateException("Save while listening unavailable")
            )
        override fun dismiss(id: String) = Unit
    },
    val radioSuggester: PlaybackRuntimeRadioSuggester = PlaybackRuntimeRadioSuggester {
        RadioSuggestResult(emptyList(), usedOnlineDiscovery = false, onlineDiscoveryFailed = false)
    },
    val resolveCoPlaylistSongIds: suspend (PlayableItem) -> Set<Long> = { emptySet() },
    val isOnline: () -> Boolean = { false },
    val persistShuffle: suspend (Boolean) -> Unit = {},
    val persistRepeat: suspend (RepeatMode) -> Unit = {},
    val touchSongLastPlayed: suspend (Long) -> Unit = {},
    val updateSongDuration: suspend (Long, Long) -> Unit = { _, _ -> },
    val enhanceSong: suspend (Song) -> Unit = {},
    val requestListenSync: () -> Unit = {},
    val clockMs: () -> Long = System::currentTimeMillis,
    val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    val controllerReconnectBackoffMs: (attempt: Int) -> Long = ::controllerReconnectBackoffMs,
    val startTicker: Boolean = true
)

private data class PlaybackPersistenceRequest(
    val lastPlayed: LastPlayedSnapshot?,
    val queue: QueueSnapshot?,
    val clearQueue: Boolean
)

private data class PersistedCollectionProjection(
    val snapshot: PlaybackCollectionSnapshot,
    val hydratedQueue: HydratedQueue? = null,
    val restoreShuffle: Boolean = false
)

/**
 * Process-scoped playback owner retained by BestiaPopApplication.
 *
 * UI attachment does not own playback work. Detaching keeps an active or queued session alive, but
 * an entirely idle runtime releases its MediaController and restarts the lease on demand.
 */
@OptIn(UnstableApi::class)
class PlaybackRuntime internal constructor(
    private val dependencies: PlaybackRuntimeDependencies
) {
    private val scope = dependencies.scope

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
    val displayQueue: StateFlow<List<PlayableItem>> = _queue
    private val _discoverPlaybackOrigin =
        MutableStateFlow<DiscoverPlaybackOrigin>(DiscoverPlaybackOrigin.None)
    val discoverPlaybackOrigin = _discoverPlaybackOrigin.asStateFlow()
    private val _resolvingRemote = MutableStateFlow(false)
    val resolvingRemote = _resolvingRemote.asStateFlow()
    private val _radioActive = MutableStateFlow(false)
    val radioActive = _radioActive.asStateFlow()
    private val _radioLoading = MutableStateFlow(false)
    val radioLoading = _radioLoading.asStateFlow()
    private val _radioMode = MutableStateFlow(RadioMode.KNOWN)
    val radioMode = _radioMode.asStateFlow()
    private val _radioStatusLabel = MutableStateFlow<String?>(null)
    val radioStatusLabel = _radioStatusLabel.asStateFlow()
    private val _queueFocusEpoch = MutableStateFlow(0)
    val queueFocusEpoch = _queueFocusEpoch.asStateFlow()
    val saveWhileListeningDownloads: StateFlow<List<ActiveDownload>> =
        dependencies.saveDownloads.downloads

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    private val started = AtomicBoolean(false)
    private val uiAttachments = AtomicInteger(0)
    private val resolvingCount = AtomicInteger(0)
    private var controller: PlaybackControllerFacade? = null
    private var controllerConnector: PlaybackControllerConnector? = null
    private var controllerFuture: PlaybackControllerConnection? = null
    private var controllerReconnectJob: Job? = null
    private var consecutiveControllerFailures = 0
    private var tickerJob: Job? = null
    private var library: List<Song> = emptyList()
    private val libraryReady = MutableStateFlow(false)

    private var preShuffleOrder: List<String>? = null
    private var lastMediaItemIndex = -1
    private var lastKnownQueueEntryId: String? = null
    private var timelineMaterialized = false
    private var playWhenReadyIntent = false
    private var suppressPlaylistMutationCallbacks = false
    private var suppressShuffleWrapDetection = false
    private var pendingNewPlaybackQueueEntryId: String? = null
    private var playbackIntentEpoch = 0L
    private var pendingPlayIntentEpoch: Long? = null
    private var lastTouchedSongId = -1L
    private var liveSessionHydrated = false
    private var idleSeedDone = false
    private var persistedSessionRestored = false
    private var autoplaySeedApplied = false
    private var pendingExternalPlaybackModes: Pair<Boolean, RepeatMode>? = null
    private val sessionRestoreMutex = Mutex()
    private var lastPersistedPositionAtMs = 0L
    private var lastSeekTimestamp = 0L
    private var seekPersistenceJob: Job? = null
    private val persistenceRequests = Channel<PlaybackPersistenceRequest>(Channel.UNLIMITED)

    private var playbackGeneration = 0L
    private var prefetchJob: Job? = null
    private var remoteRecoveryJob: Job? = null
    private var remoteRecoveryQueueEntryId: String? = null
    private var remoteRecoveryDeadlineMs = 0L
    private var resolvingTransitionJob: Job? = null
    private var resolvingTransitionQueueEntryId: String? = null
    private var queueSelectionJob: Job? = null
    private val selectionGate = PlaybackSelectionIntentGate()
    private val rejectedQueueEntries = linkedSetOf<String>()

    private val saveWhileListeningAttempted = mutableSetOf<String>()
    private val saveWhileListeningFailures = mutableMapOf<String, Long>()
    private val pendingSaveSettingsJobs = mutableMapOf<String, Job>()

    private var radioStartJob: Job? = null
    private var radioRefillJob: Job? = null
    private val playedInRadioSession = linkedSetOf<String>()
    private var radioPreferredMode: RadioMode? = null
    private var lastEmptyRadioRefillAtMs = 0L

    init {
        start()
    }

    fun attachUi() {
        uiAttachments.incrementAndGet()
        ensureControllerConnection()
        maybeSeedIdlePlayer()
    }

    fun detachUi() {
        uiAttachments.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        releaseControllerIfIdle()
    }

    fun requestListenSync() {
        dependencies.requestListenSync()
    }

    fun dismissSaveWhileListeningDownload(id: String) {
        dependencies.saveDownloads.dismiss(id)
    }

    internal val playbackSettings: StateFlow<PlaybackSettings>
        get() = dependencies.playbackSettings

    internal suspend fun awaitPlaybackSettings(): PlaybackSettings {
        dependencies.playbackSettingsReady.first { it }
        return dependencies.playbackSettings.value
    }

    internal suspend fun systemResumptionMetadataSnapshot(): PlaybackCollectionSnapshot? {
        dependencies.playbackSettingsReady.first { it }
        libraryReady.first { it }
        return sessionRestoreMutex.withLock {
            currentRuntimeSnapshot() ?: loadPersistedCollectionProjection()?.snapshot
        }
    }

    internal suspend fun restoreSystemPlaybackSnapshot(): PlaybackCollectionSnapshot? {
        dependencies.playbackSettingsReady.first { it }
        libraryReady.first { it }
        return sessionRestoreMutex.withLock {
            ensurePersistedSessionRestoredLocked()?.also {
                autoplaySeedApplied = true
                pendingExternalPlaybackModes = _isShuffle.value to _repeatMode.value
            }
        }
    }

    internal fun attachControllerForTest(controller: PlaybackControllerFacade) {
        attachController(controller)
    }

    internal fun connectForTest(connector: PlaybackControllerConnector) {
        configureControllerConnector(connector)
    }

    internal val controllerConnectedForTest: Boolean
        get() = controller != null

    internal val tickerActiveForTest: Boolean
        get() = tickerJob?.isActive == true

    internal suspend fun tickForTest() {
        samplePositionAndOwnership()
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            for (request in persistenceRequests) {
                withContext(Dispatchers.IO) {
                    dependencies.persistence.saveSession(
                        lastPlayed = request.lastPlayed,
                        queue = request.queue,
                        clearQueue = request.clearQueue
                    )
                }
            }
        }
        scope.launch {
            dependencies.libraryUpdates.collectLatest { songs ->
                library = songs
                libraryReady.value = true
                refreshLocalMetadata(songs)
                maybeSeedIdlePlayer()
            }
        }
        scope.launch {
            dependencies.playbackSettingsReady.collectLatest { ready ->
                if (ready) {
                    restorePlaybackModes()
                    maybeSeedIdlePlayer()
                }
            }
        }
    }

    internal fun connect(context: Context) {
        configureControllerConnector(
            PlaybackControllerConnector {
                MediaControllerConnection(
                    context = context,
                    library = { library }
                )
            }
        )
    }

    private fun configureControllerConnector(connector: PlaybackControllerConnector) {
        controllerConnector = connector
        ensureControllerConnection()
    }

    private fun ensureControllerConnection() {
        if (!shouldRetainController()) return
        if (controller != null || controllerFuture != null ||
            controllerReconnectJob?.isActive == true
        ) {
            return
        }
        val connector = controllerConnector ?: return
        val future = runCatching(connector::connect).getOrElse {
            onControllerConnectionFailed()
            return
        }
        controllerFuture = future
        future.addListener {
            scope.launch {
                if (controllerFuture !== future) return@launch
                controllerFuture = null
                runCatching(future::get).fold(
                    onSuccess = { connected ->
                        consecutiveControllerFailures = 0
                        if (shouldRetainController()) {
                            attachController(connected)
                        } else {
                            connected.release()
                        }
                    },
                    onFailure = { onControllerConnectionFailed() }
                )
            }
        }
    }

    private fun onControllerConnectionFailed() {
        consecutiveControllerFailures++
        if (consecutiveControllerFailures == 1) {
            _events.tryEmit("No se pudo conectar la reproducción")
        }
        if (!shouldRetainController() || controllerReconnectJob?.isActive == true) return
        val delayMs = dependencies.controllerReconnectBackoffMs(consecutiveControllerFailures)
            .coerceAtLeast(0L)
        controllerReconnectJob = scope.launch {
            delay(delayMs)
            controllerReconnectJob = null
            ensureControllerConnection()
        }
    }

    private fun attachController(newController: PlaybackControllerFacade) {
        if (controller === newController) return
        check(controller == null) { "PlaybackRuntime already owns a controller" }
        controller = newController
        timelineMaterialized = false
        newController.addListener(playerListener)
        syncFromController()
        restorePlaybackModes()
        maybeSeedIdlePlayer()
        if (playWhenReadyIntent && _currentItem.value != null) {
            requestPlaybackForCurrent()
        }
        updateTickerLifecycle()
    }

    private fun handleControllerDisconnected(disconnected: PlaybackControllerFacade) {
        if (controller !== disconnected) return
        controller = null
        timelineMaterialized = false
        liveSessionHydrated = false
        _isPlaying.value = false
        tickerJob?.cancel()
        tickerJob = null
        invalidatePlaybackWork(clearRejectedEntries = false)
        disconnected.release()
        if (playWhenReadyIntent && _currentItem.value != null) beginPendingPlayIntent()
        ensureControllerConnection()
    }

    private fun shouldRetainController(): Boolean =
        uiAttachments.get() > 0 ||
            _queue.value.isNotEmpty() ||
            playWhenReadyIntent ||
            _isPlaying.value

    private fun releaseControllerIfIdle() {
        if (shouldRetainController()) {
            ensureControllerConnection()
            return
        }
        controllerReconnectJob?.cancel()
        controllerReconnectJob = null
        controllerFuture?.let { future ->
            controllerFuture = null
            future.cancel()
        }
        tickerJob?.cancel()
        tickerJob = null
        val owned = controller
        controller = null
        timelineMaterialized = false
        liveSessionHydrated = false
        owned?.release()
    }

    private fun updateTickerLifecycle() {
        val shouldTick = dependencies.startTicker && controller?.isPlaying == true
        if (!shouldTick) {
            tickerJob?.cancel()
            tickerJob = null
            return
        }
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive && controller?.isPlaying == true) {
                samplePositionAndOwnership()
                delay(POSITION_TICK_MS)
            }
            tickerJob = null
        }
    }

    private val playerListener = object : PlaybackControllerFacade.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                clearRemoteRecoveryAfterProgress()
            } else {
                controller?.let { player ->
                    _playbackPositionMs.value = player.currentPosition.coerceAtLeast(0L)
                }
                dependencies.listenTracker.onStopped()
                persistPlaybackSession(force = true)
            }
            updateTickerLifecycle()
            if (!isPlaying) releaseControllerIfIdle()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
            playWhenReadyIntent = playWhenReady
            if (playWhenReady) {
                pendingPlayIntentEpoch = null
                val index = currentQueueIndex()
                ensureRemoteReadyAt(index, startPlaying = true)
                prefetchAround(index)
            } else {
                cancelPendingPlayIntent()
                prefetchJob?.cancel()
                prefetchJob = null
                cancelRemoteRecoveryJob()
            }
        }

        override fun onPlayerError() {
            handlePlayerError()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            (_currentItem.value as? PlayableItem.Remote)?.let {
                maybeSaveWhileListening(
                    remote = it,
                    event = SaveWhileListeningEvent.PLAYBACK_COMPLETED,
                    positionMs = _playbackPositionMs.value
                )
            }
            maybeAutoStartRadioOnQueueEnd()
        }

        override fun onMediaItemTransition(item: PlayableItem?, reason: Int) {
            handleMediaItemTransition(item, reason)
        }

        override fun onTimelineChanged() {
            reconcileTimelineFromController()
        }

        override fun onPositionDiscontinuity(positionMs: Long) {
            _playbackPositionMs.value = positionMs.coerceAtLeast(0L)
            lastSeekTimestamp = dependencies.clockMs()
            if (!_isPlaying.value) scheduleSeekPersistence()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val resolved = repeatModeFromPlayer(repeatMode)
            if (resolved == _repeatMode.value) return
            _repeatMode.value = resolved
            scope.launch { dependencies.persistRepeat(resolved) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            reconcileExternalShuffleMode(shuffleModeEnabled)
        }

        override fun onDisconnected(controller: PlaybackControllerFacade) {
            scope.launch { handleControllerDisconnected(controller) }
        }
    }

    private fun syncFromController() {
        val player = controller ?: return
        if (player.mediaItemCount <= 0) {
            timelineMaterialized = false
            return
        }
        val rebuilt = player.items()
        if (rebuilt.isEmpty()) return
        timelineMaterialized = true
        liveSessionHydrated = true
        idleSeedDone = true
        val index = player.currentMediaItemIndex.coerceIn(0, rebuilt.lastIndex)
        _queue.value = rebuilt
        lastMediaItemIndex = index
        setCurrentItem(rebuilt[index], persistLastPlayed = false)
        _isPlaying.value = player.isPlaying
        playWhenReadyIntent = player.playWhenReady
        _playbackPositionMs.value = player.currentPosition.coerceAtLeast(0L)
        if (_isPlaying.value) clearRemoteRecoveryAfterProgress()
        _repeatMode.value = repeatModeFromPlayer(player.repeatMode)
        _isShuffle.value = player.shuffleModeEnabled
        applyPendingExternalPlaybackModes()
        if (playWhenReadyIntent) {
            ensureRemoteReadyAt(index, startPlaying = true)
            prefetchAround(index)
        }
    }

    private fun reconcileTimelineFromController() {
        if (suppressPlaylistMutationCallbacks) return
        val player = controller ?: return
        val itemCount = player.mediaItemCount
        if (itemCount <= 0) {
            val changed = _queue.value.isNotEmpty()
            if (changed) invalidatePlaybackWork(clearRejectedEntries = false)
            _queue.value = emptyList()
            lastMediaItemIndex = -1
            timelineMaterialized = false
            playWhenReadyIntent = false
            cancelPendingPlayIntent()
            _playbackPositionMs.value = 0L
            setCurrentItem(null, persistLastPlayed = false)
            if (changed) {
                clearDiscoverPlaybackOrigin()
                persistPlaybackSession(force = true)
            }
            return
        }
        val rebuilt = player.items()
        if (rebuilt.size != itemCount || rebuilt.isEmpty()) return
        val oldQueueEntryIds = _queue.value.map { it.queueEntryId }
        val newQueueEntryIds = rebuilt.map { it.queueEntryId }
        val structureChanged = oldQueueEntryIds != newQueueEntryIds
        if (structureChanged) invalidatePlaybackWork(clearRejectedEntries = false)
        val index = player.currentMediaItemIndex.coerceIn(rebuilt.indices)
        val occurrenceChanged = _currentItem.value?.queueEntryId != rebuilt[index].queueEntryId
        _queue.value = rebuilt
        timelineMaterialized = true
        liveSessionHydrated = true
        lastMediaItemIndex = index
        _playbackPositionMs.value = player.currentPosition.coerceAtLeast(0L)
        setCurrentItem(
            rebuilt[index],
            persistLastPlayed = occurrenceChanged,
            hint = if (occurrenceChanged) {
                PlaybackChangeHint.NEW_PLAYBACK
            } else {
                PlaybackChangeHint.METADATA_UPDATE
            }
        )
        applyPendingExternalPlaybackModes()
        if (structureChanged) {
            persistPlaybackSession(force = true)
            restartAsyncPlaybackWork()
        }
    }

    private fun reconcileExternalShuffleMode(enabled: Boolean) {
        if (enabled == _isShuffle.value) return
        invalidatePlaybackWork(clearRejectedEntries = false)
        val items = _queue.value
        val position = _playbackPositionMs.value
        if (enabled && items.isNotEmpty()) {
            val (shuffled, index) = permuteQueueToPlayOrder(
                items = items,
                currentIndex = currentQueueIndex(),
                backupSource = true
            )
            setShuffleEnabled(true)
            applyQueueReorder(shuffled, index, position, playWhenReadyIntent)
        } else if (!enabled) {
            disableShuffleRestoringOrder()
        } else {
            setShuffleEnabled(true)
        }
        bumpQueueFocus()
        persistPlaybackSession(force = true)
        restartAsyncPlaybackWork()
    }

    private fun refreshLocalMetadata(songs: List<Song>) {
        val oldQueue = _queue.value
        if (oldQueue.none { it is PlayableItem.Local }) return
        val updated = oldQueue.map { item ->
            if (item !is PlayableItem.Local) {
                item
            } else {
                songs.firstOrNull {
                    (item.song.id > 0L && it.id == item.song.id) ||
                        it.uriString == item.song.uriString
                }?.let { item.copy(song = it) } ?: item
            }
        }
        _queue.value = updated
        val currentSlot = _currentItem.value?.queueEntryId
        updated.firstOrNull { it.queueEntryId == currentSlot }?.let {
            setCurrentItem(
                it,
                persistLastPlayed = false,
                hint = PlaybackChangeHint.METADATA_UPDATE
            )
        }
    }

    private fun setCurrentItem(
        item: PlayableItem?,
        persistLastPlayed: Boolean = true,
        hint: PlaybackChangeHint = PlaybackChangeHint.METADATA_UPDATE
    ) {
        val previousSlot = _currentItem.value?.queueEntryId
        val occurrenceChanged = item?.queueEntryId != previousSlot
        if (occurrenceChanged) clearRemoteRecovery()
        _currentItem.value = item
        lastKnownQueueEntryId = item?.queueEntryId
        val local = (item as? PlayableItem.Local)?.song
        _currentSong.value = local
        dependencies.listenTracker.onTrackChanged(local, hint)
        if (local != null && occurrenceChanged) {
            scope.launch(Dispatchers.IO) { dependencies.enhanceSong(local) }
        }
        if (persistLastPlayed) {
            persistPlaybackSession(force = true)
            if (occurrenceChanged) touchLastPlayed(local)
        }
    }

    private fun touchLastPlayed(song: Song?) {
        if (song == null || song.id <= 0L || song.id == lastTouchedSongId) return
        lastTouchedSongId = song.id
        scope.launch(Dispatchers.IO) { dependencies.touchSongLastPlayed(song.id) }
    }

    private fun currentQueueIndex(): Int {
        val items = _queue.value
        if (items.isEmpty()) return 0
        val player = controller
        if (player != null &&
            player.mediaItemCount == items.size &&
            player.currentMediaItemIndex in items.indices
        ) {
            return player.currentMediaItemIndex
        }
        if (lastMediaItemIndex in items.indices) return lastMediaItemIndex
        val queueEntryId = lastKnownQueueEntryId ?: return 0
        return items.indexOfFirst { it.queueEntryId == queueEntryId }.coerceAtLeast(0)
    }

    private fun persistPlaybackSession(force: Boolean) {
        // The production scope is Main.immediate: all mutable playback state is frozen here before
        // the serialized writer crosses to IO.
        scope.launch { captureAndQueuePlaybackSession(force) }
    }

    private fun captureAndQueuePlaybackSession(force: Boolean) {
        val now = dependencies.clockMs()
        if (!force && now - lastPersistedPositionAtMs < POSITION_SAVE_INTERVAL_MS) return
        lastPersistedPositionAtMs = now
        val position = _playbackPositionMs.value
        val local = (_currentItem.value as? PlayableItem.Local)?.song
        val items = _queue.value
        val index = currentQueueIndex()
        val last = local?.let { PlaybackHydration.snapshotFromSong(it, position) }
        val request = if (items.isEmpty()) {
            PlaybackPersistenceRequest(last, queue = null, clearQueue = true)
        } else {
            PlaybackPersistenceRequest(
                lastPlayed = last,
                queue = queueSnapshotForPersist(items, index, position),
                clearQueue = false
            )
        }
        persistenceRequests.trySend(request)
    }

    private fun scheduleSeekPersistence() {
        seekPersistenceJob?.cancel()
        seekPersistenceJob = scope.launch {
            delay(SEEK_PERSIST_DEBOUNCE_MS)
            captureAndQueuePlaybackSession(force = true)
            seekPersistenceJob = null
        }
    }

    private fun queueSnapshotForPersist(
        items: List<PlayableItem>,
        index: Int,
        positionMs: Long
    ): QueueSnapshot {
        val projection = PlaybackQueueSlots.projectSnapshot(
            queue = items,
            currentIndex = index,
            preShuffleOrder = preShuffleOrder.takeIf { _isShuffle.value }
        )
        return QueueSnapshotCodec.fromPlayable(
            items = projection.items,
            currentIndex = projection.currentIndex,
            positionMs = positionMs,
            shufflePlayOrder = projection.shufflePlayOrder
        )
    }

    private fun restorePlaybackModes() {
        if (!dependencies.playbackSettingsReady.value) return
        val settings = dependencies.playbackSettings.value
        val player = controller
        val hasLiveSession = (player?.mediaItemCount ?: 0) > 0
        val liveRepeat = repeatModeFromPlayer(player?.repeatMode ?: Player.REPEAT_MODE_OFF)
        val resolved = PlaybackModeRestore.resolve(settings, hasLiveSession, liveRepeat)
        // Live sessions keep prefs as shuffle source of truth (physical queue shuffle + identity
        // Media3 ShuffleOrder). Reading player.shuffleModeEnabled can falsely clear the flag and
        // drop shufflePlayOrder on the next persist.
        _isShuffle.value = resolved.shuffle
        _repeatMode.value = resolved.repeat
        if (resolved.applyRepeatToPlayer) applyRepeatModeToController(resolved.repeat)
        syncShuffleToPlayer()
    }

    private fun applyPendingExternalPlaybackModes() {
        val (shuffle, repeat) = pendingExternalPlaybackModes ?: return
        pendingExternalPlaybackModes = null
        _isShuffle.value = shuffle
        _repeatMode.value = repeat
        applyRepeatModeToController(repeat)
        syncShuffleToPlayer()
    }

    private fun maybeSeedIdlePlayer() {
        if (!libraryReady.value || !dependencies.playbackSettingsReady.value || controller == null) {
            return
        }
        if (liveSessionHydrated) return
        if ((controller?.mediaItemCount ?: 0) > 0) return
        scope.launch {
            val restored = sessionRestoreMutex.withLock {
                ensurePersistedSessionRestoredLocked()
            } ?: return@launch
            val settings = dependencies.playbackSettings.value
            if (persistedSessionRestored &&
                settings.autoplayOnLaunch &&
                uiAttachments.get() > 0 &&
                !autoplaySeedApplied &&
                !playWhenReadyIntent &&
                restored.items.isNotEmpty()
            ) {
                autoplaySeedApplied = true
                togglePlayPause()
            }
        }
    }

    private suspend fun loadPersistedCollectionProjection(): PersistedCollectionProjection? {
        val last = dependencies.persistence.loadLastPlayed()
        val persistedQueue = dependencies.persistence.loadQueue()
        val hydrated = PlaybackHydration.hydrateQueue(persistedQueue, library)
        if (hydrated != null && hydrated.items.isNotEmpty()) {
            val restoreShuffle = PlaybackModeRestore
                .resolve(
                    dependencies.playbackSettings.value,
                    hasLiveSession = false,
                    liveRepeat = _repeatMode.value
                )
                .shuffle
            val order = PlaybackQueueOrder.validPlayOrderOrNull(
                hydrated.shufflePlayOrder,
                hydrated.items.size
            )
            if (order != null && restoreShuffle) {
                val shuffled = PlaybackQueueOrder.applyPlayOrder(hydrated.items, order)
                val index = PlaybackQueueOrder.toDisplayIndex(
                    order,
                    hydrated.currentIndex,
                    hydrated.items.size
                ).coerceIn(shuffled.indices)
                return PersistedCollectionProjection(
                    snapshot = PlaybackCollectionSnapshot(shuffled, index, hydrated.positionMs),
                    hydratedQueue = hydrated,
                    restoreShuffle = true
                )
            }
            return PersistedCollectionProjection(
                snapshot = PlaybackCollectionSnapshot(
                    hydrated.items,
                    hydrated.currentIndex,
                    hydrated.positionMs
                ),
                hydratedQueue = hydrated,
                restoreShuffle = false
            )
        }
        val seed = PlaybackHydration.resolveIdleSeed(library, last) ?: return null
        return PersistedCollectionProjection(
            snapshot = PlaybackCollectionSnapshot(
                items = listOf(seed.toPlayable()),
                currentIndex = 0,
                positionMs = PlaybackHydration.resumePositionMs(seed, last)
            )
        )
    }

    private suspend fun ensurePersistedSessionRestoredLocked(): PlaybackCollectionSnapshot? {
        currentRuntimeSnapshot()?.let { return it }
        if (idleSeedDone) return null
        idleSeedDone = true

        val projection = loadPersistedCollectionProjection() ?: return null
        if (projection.hydratedQueue != null) {
            applyHydratedQueue(projection.hydratedQueue, projection.restoreShuffle)
            persistedSessionRestored = true
            return currentRuntimeSnapshot()
        }

        val item = projection.snapshot.currentItem
        clearDiscoverPlaybackOrigin()
        setCurrentItem(item, persistLastPlayed = false)
        _playbackPositionMs.value = projection.snapshot.positionMs
        _isPlaying.value = false
        persistedSessionRestored = true
        return projection.snapshot
    }

    private fun currentRuntimeSnapshot(): PlaybackCollectionSnapshot? {
        val queue = _queue.value
        if (queue.isNotEmpty()) {
            val currentQueueEntryId = _currentItem.value?.queueEntryId
            val index = queue.indexOfFirst { it.queueEntryId == currentQueueEntryId }
                .takeIf { it >= 0 }
                ?: lastMediaItemIndex.coerceIn(queue.indices)
            return PlaybackCollectionSnapshot(
                items = queue,
                currentIndex = index,
                positionMs = _playbackPositionMs.value
            )
        }
        val current = _currentItem.value ?: return null
        return PlaybackCollectionSnapshot(
            items = listOf(current),
            currentIndex = 0,
            positionMs = _playbackPositionMs.value
        )
    }

    private fun applyHydratedQueue(hydrated: HydratedQueue, restoreShuffle: Boolean) {
        invalidatePlaybackWork()
        clearDiscoverPlaybackOrigin()
        val order = PlaybackQueueOrder.validPlayOrderOrNull(
            hydrated.shufflePlayOrder,
            hydrated.items.size
        )
        if (order != null && restoreShuffle) {
            preShuffleOrder = PlaybackQueueSlots.capturePreShuffleOrder(hydrated.items)
            val shuffled = PlaybackQueueOrder.applyPlayOrder(hydrated.items, order)
            val index = PlaybackQueueOrder.toDisplayIndex(
                order,
                hydrated.currentIndex,
                hydrated.items.size
            ).coerceIn(0, shuffled.lastIndex)
            _queue.value = shuffled
            lastMediaItemIndex = index
            _isShuffle.value = true
            setCurrentItem(shuffled[index], persistLastPlayed = false)
        } else {
            preShuffleOrder = null
            _queue.value = hydrated.items
            lastMediaItemIndex = hydrated.currentIndex
            setCurrentItem(hydrated.items[hydrated.currentIndex], persistLastPlayed = false)
        }
        _playbackPositionMs.value = hydrated.positionMs
        _isPlaying.value = false
        timelineMaterialized = false
        ensureControllerConnection()
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
        invalidatePlaybackWork()
        _discoverPlaybackOrigin.value =
            if (fromRadio) DiscoverPlaybackOrigin.None else origin
        launchPlayableCollection(
            items = items.withFreshQueueEntryIds(),
            startIndex = startIndex,
            fromRadio = fromRadio,
            rotate = rotate,
            applyManualModes = applyManualModes,
            startShuffled = startShuffled,
            resumeAtMs = resumeAtMs
        )
    }

    internal fun stageExternalPlayableCollection(
        items: List<PlayableItem>,
        startIndex: Int,
        startPositionMs: Long
    ): PlaybackCollectionSnapshot? {
        if (items.isEmpty()) return null
        invalidatePlaybackWork()
        clearRadioSession()
        clearDiscoverPlaybackOrigin()
        val staged = items.withFreshQueueEntryIds()
        val index = startIndex.coerceIn(staged.indices)
        stageQueueCore(staged, index)
        preShuffleOrder = null
        applyManualPlayModes(deferPlayerSync = true)
        val snapshot = publishStagedCollectionCore(staged, index, startPositionMs)
        timelineMaterialized = false
        persistPlaybackSession(force = true)
        return snapshot
    }

    private fun launchPlayableCollection(
        items: List<PlayableItem>,
        startIndex: Int,
        fromRadio: Boolean,
        rotate: Boolean,
        applyManualModes: Boolean,
        startShuffled: Boolean,
        resumeAtMs: Long?
    ) {
        if (!fromRadio) clearRadioSession()
        val validIndex = startIndex.coerceIn(0, items.lastIndex)
        val shouldRotate = rotate && !fromRadio && validIndex > 0
        val ordered = if (shouldRotate) PlaybackQueueOrder.rotateToStart(items, validIndex) else items
        val startAt = if (shouldRotate) 0 else validIndex
        val shouldApplyManualModes = applyManualModes && !fromRadio
        val resumePosition = if (startShuffled) null else resumeAtMs?.takeIf { it > 0L }
        if (controller == null) {
            playWhenReadyIntent = true
            beginPendingPlayIntent()
        }
        val playingIndex = finishPlayPlayableCollection(
            items = ordered,
            index = startAt,
            applyManualModes = shouldApplyManualModes,
            fromRadio = fromRadio,
            startShuffled = startShuffled,
            resumeAtMs = resumePosition
        )
        ensureControllerConnection()
        if (fromRadio && _radioActive.value && playWhenReadyIntent) {
            maybeRefillRadio(playingIndex)
        }
    }

    private fun finishPlayPlayableCollection(
        items: List<PlayableItem>,
        index: Int,
        applyManualModes: Boolean,
        fromRadio: Boolean,
        startShuffled: Boolean = false,
        resumeAtMs: Long? = null,
        startPlaying: Boolean = true
    ): Int {
        val (playItems, playIndex) = if (startShuffled) {
            permuteQueueToPlayOrder(items, index, backupSource = true)
        } else {
            if (!fromRadio && (applyManualModes || !_isShuffle.value)) preShuffleOrder = null
            items to index
        }
        stageQueueCore(playItems, playIndex)
        val startPosition = if (startShuffled) 0L else resumeAtMs?.coerceAtLeast(0L) ?: 0L
        when {
            startShuffled -> {
                setShuffleEnabled(true)
                val (_, nextRepeat) = PlaybackModeClear.afterManualPlay(
                    shuffle = true,
                    repeat = _repeatMode.value,
                    settings = dependencies.playbackSettings.value
                )
                if (nextRepeat != _repeatMode.value) setRepeatMode(nextRepeat)
            }
            applyManualModes -> applyManualPlayModes()
            fromRadio -> setShuffleEnabled(false)
        }
        val snapshot = publishStagedCollectionCore(playItems, playIndex, startPosition)
        playWhenReadyIntent = startPlaying
        reloadPlayerTimeline(
            snapshot.items,
            snapshot.currentIndex,
            snapshot.positionMs,
            startPlaying = startPlaying,
            newPlayback = true
        )
        if (controller == null) {
            touchLastPlayed((snapshot.currentItem as? PlayableItem.Local)?.song)
            persistPlaybackSession(force = true)
        }
        return snapshot.currentIndex
    }

    private fun stageQueueCore(
        items: List<PlayableItem>,
        index: Int
    ) {
        val previous = _currentItem.value
        if (previous?.queueEntryId != items[index].queueEntryId) {
            (previous as? PlayableItem.Remote)?.let {
                maybeSaveWhileListening(
                    it,
                    SaveWhileListeningEvent.MANUAL_SKIP,
                    _playbackPositionMs.value
                )
            }
        }
        _queue.value = items
        lastMediaItemIndex = index
    }

    private fun publishStagedCollectionCore(
        items: List<PlayableItem>,
        index: Int,
        startPositionMs: Long
    ): PlaybackCollectionSnapshot {
        setCurrentItem(items[index], persistLastPlayed = false)
        liveSessionHydrated = true
        idleSeedDone = true
        bumpQueueFocus()
        val position = startPositionMs.coerceAtLeast(0L)
        _playbackPositionMs.value = position
        pendingNewPlaybackQueueEntryId = items[index].queueEntryId
        return PlaybackCollectionSnapshot(items, index, position)
    }

    fun togglePlayPause() {
        val player = controller
        val current = _currentItem.value
        if (playWhenReadyIntent || pendingPlayIntentEpoch != null) {
            playWhenReadyIntent = false
            cancelPendingPlayIntent()
            prefetchJob?.cancel()
            prefetchJob = null
            cancelRemoteRecoveryJob()
            resolvingTransitionJob?.cancel()
            resolvingTransitionJob = null
            resolvingTransitionQueueEntryId = null
            player?.pause()
            return
        }
        current ?: return
        playWhenReadyIntent = true
        if (player == null) {
            beginPendingPlayIntent()
            ensureControllerConnection()
            return
        }
        requestPlaybackForCurrent()
    }

    private fun requestPlaybackForCurrent() {
        val player = controller ?: run {
            beginPendingPlayIntent()
            ensureControllerConnection()
            return
        }
        val current = _currentItem.value ?: return
        val items = _queue.value.ifEmpty { listOf(current) }
        val index = items.indexOfFirst { it.queueEntryId == current.queueEntryId }
            .takeIf { it >= 0 }
            ?: lastMediaItemIndex.coerceIn(0, items.lastIndex)
        val position = _playbackPositionMs.value.coerceAtLeast(0L)
        playWhenReadyIntent = true
        pendingPlayIntentEpoch = null
        if (!hasMaterializedTimeline() || player.mediaItemCount != items.size) {
            reloadPlayerTimeline(
                items = items,
                startIndex = index,
                startPositionMs = position,
                startPlaying = true
            )
            return
        }
        if (player.currentMediaItemIndex != index) {
            player.pause()
            player.seekTo(index, position)
        }
        player.play()
        val remote = items[index] as? PlayableItem.Remote
        if (remote != null && dependencies.streamAccess.needsResolve(remote)) {
            ensureRemoteReadyAt(index, startPlaying = true)
        } else {
            ensurePreparedForPlayback()
            prefetchAround(index)
        }
    }

    private fun beginPendingPlayIntent(): Long {
        playbackIntentEpoch++
        pendingPlayIntentEpoch = playbackIntentEpoch
        return playbackIntentEpoch
    }

    private fun cancelPendingPlayIntent() {
        playbackIntentEpoch++
        pendingPlayIntentEpoch = null
    }

    fun skipToNext() {
        invalidatePlaybackWork()
        bumpQueueFocus()
        applySkipModes()
        controller?.seekToNextMediaItem()
        ensurePreparedForPlayback()
    }

    fun skipToPrevious() {
        invalidatePlaybackWork()
        bumpQueueFocus()
        applySkipModes()
        controller?.let { player ->
            when {
                player.hasPreviousMediaItem() -> player.seekToPreviousMediaItem()
                player.mediaItemCount > 1 -> player.seekTo(player.mediaItemCount - 1, 0L)
            }
        }
        ensurePreparedForPlayback()
    }

    fun seekTo(positionMs: Long) {
        lastSeekTimestamp = dependencies.clockMs()
        _playbackPositionMs.value = positionMs.coerceAtLeast(0L)
        controller?.seekTo(positionMs)
        scheduleSeekPersistence()
    }

    fun toggleRepeatMode() {
        setRepeatMode(
            when (_repeatMode.value) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
        )
    }

    fun toggleShuffle() {
        invalidatePlaybackWork(clearRejectedEntries = false)
        val enabling = !_isShuffle.value
        val items = _queue.value
        val position = if (hasMaterializedTimeline()) {
            controller?.currentPosition?.coerceAtLeast(0L) ?: _playbackPositionMs.value
        } else {
            _playbackPositionMs.value
        }
        val wasPlaying = playWhenReadyIntent
        if (enabling) {
            setShuffleEnabled(true)
            if (items.isNotEmpty()) {
                val (shuffled, index) = permuteQueueToPlayOrder(
                    items,
                    currentQueueIndex(),
                    backupSource = true
                )
                applyQueueReorder(shuffled, index, position, wasPlaying)
            }
        } else {
            val restored = preShuffleQueueOrNull()
            val currentSlot = _currentItem.value?.queueEntryId
            setShuffleEnabled(false)
            if (!restored.isNullOrEmpty()) {
                val index = restored.indexOfFirst { it.queueEntryId == currentSlot }
                    .takeIf { it >= 0 } ?: 0
                applyQueueReorder(restored, index, position, wasPlaying)
            }
        }
        bumpQueueFocus()
        persistPlaybackSession(force = true)
        restartAsyncPlaybackWork()
    }

    fun addPlayableBatch(items: List<PlayableItem>) {
        if (items.isEmpty()) return
        invalidatePlaybackWork(clearRejectedEntries = false)
        val additions = items.withFreshQueueEntryIds()
        _queue.value = _queue.value + additions
        mutateMaterializedTimeline { it.addMediaItems(additions) }
        ensureControllerConnection()
        persistPlaybackSession(force = true)
        restartAsyncPlaybackWork()
    }

    fun playNextBatch(items: List<PlayableItem>) {
        if (items.isEmpty()) return
        invalidatePlaybackWork(clearRejectedEntries = false)
        val additions = items.withFreshQueueEntryIds()
        val live = _queue.value.toMutableList()
        val currentIndex = currentQueueIndex().coerceAtLeast(0)
        val insertAt = (currentIndex + 1).coerceAtMost(live.size)
        live.addAll(insertAt, additions)
        _queue.value = live
        mutateMaterializedTimeline { it.addMediaItems(insertAt, additions) }
        ensureControllerConnection()
        persistPlaybackSession(force = true)
        restartAsyncPlaybackWork()
    }

    fun removeFromQueue(index: Int) {
        val old = _queue.value
        if (index !in old.indices) return
        invalidatePlaybackWork(clearRejectedEntries = false)
        val currentSlot = _currentItem.value?.queueEntryId
        val currentIndex = currentQueueIndex().coerceIn(old.indices)
        val live = old.toMutableList().apply { removeAt(index) }
        _queue.value = live
        mutateMaterializedTimeline { it.removeMediaItem(index) }
        if (live.isEmpty()) {
            playWhenReadyIntent = false
            cancelPendingPlayIntent()
            controller?.pause()
            lastMediaItemIndex = -1
            _playbackPositionMs.value = 0L
            clearDiscoverPlaybackOrigin()
            setCurrentItem(null, persistLastPlayed = false)
            timelineMaterialized = false
        } else {
            val nextIndex = when {
                index < currentIndex -> currentIndex - 1
                index == currentIndex -> index.coerceAtMost(live.lastIndex)
                else -> currentIndex
            }.coerceIn(live.indices)
            lastMediaItemIndex = nextIndex
            if (currentSlot == old[index].queueEntryId) {
                _playbackPositionMs.value = 0L
                setCurrentItem(live[nextIndex], persistLastPlayed = false)
            }
        }
        persistPlaybackSession(force = true)
        restartAsyncPlaybackWork()
        releaseControllerIfIdle()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val live = _queue.value.toMutableList()
        if (fromIndex !in live.indices || toIndex !in live.indices || fromIndex == toIndex) return
        invalidatePlaybackWork(clearRejectedEntries = false)
        val currentSlot = _currentItem.value?.queueEntryId
        live.add(toIndex, live.removeAt(fromIndex))
        _queue.value = live
        mutateMaterializedTimeline { it.moveMediaItem(fromIndex, toIndex) }
        lastMediaItemIndex = live.indexOfFirst { it.queueEntryId == currentSlot }
            .takeIf { it >= 0 }
            ?: lastMediaItemIndex.coerceIn(live.indices)
        persistPlaybackSession(force = true)
        restartAsyncPlaybackWork()
    }

    fun skipToQueueIndex(index: Int) {
        val selected = _queue.value.getOrNull(index) ?: return
        invalidatePlaybackWork()
        playWhenReadyIntent = true
        if (selected is PlayableItem.Local) {
            selectionGate.onLocalSelected()
            if (controller == null) beginPendingPlayIntent()
            applyQueueSelection(selected)
            return
        }
        val token = selectionGate.beginRemoteSelection()
        val playIntent = beginPendingPlayIntent()
        val generation = playbackGeneration
        val snapshot = _queue.value
        val start = snapshot.indexOfQueueEntry(selected)
        if (start < 0) return
        queueSelectionJob = scope.launch {
            beginResolving()
            try {
                for (step in PlaybackFallbackPlanner.circularPlan(snapshot, start)) {
                    if (!selectionGate.isCurrent(token) ||
                        !isPlaybackGenerationCurrent(generation)
                    ) {
                        return@launch
                    }
                    val liveIndex = _queue.value.indexOfQueueEntry(step.item)
                    if (liveIndex < 0) continue
                    when (val live = _queue.value[liveIndex]) {
                        is PlayableItem.Local -> {
                            if (selectionGate.isCurrent(token)) {
                                val shouldPlay = playWhenReadyIntent &&
                                    (pendingPlayIntentEpoch == playIntent || controller != null)
                                if (shouldPlay) pendingPlayIntentEpoch = null
                                applyQueueSelection(live, startPlaying = shouldPlay)
                            }
                            return@launch
                        }
                        is PlayableItem.Remote -> {
                            val ready = if (dependencies.streamAccess.needsResolve(live)) {
                                dependencies.streamAccess.resolve(live)
                            } else {
                                live
                            } ?: continue
                            if (!selectionGate.isCurrent(token) ||
                                !isPlaybackGenerationCurrent(generation)
                            ) {
                                return@launch
                            }
                            val slot = if (ready === live) {
                                _queue.value.indexOfQueueEntry(live)
                            } else {
                                applyResolvedRemote(live, ready)
                            }
                            val applied = _queue.value.getOrNull(slot) ?: continue
                            if (selectionGate.isCurrent(token)) {
                                val shouldPlay = playWhenReadyIntent &&
                                    (pendingPlayIntentEpoch == playIntent || controller != null)
                                if (shouldPlay) pendingPlayIntentEpoch = null
                                applyQueueSelection(applied, startPlaying = shouldPlay)
                            }
                            return@launch
                        }
                    }
                }
                if (selectionGate.isCurrent(token) &&
                    isPlaybackGenerationCurrent(generation)
                ) {
                    _events.tryEmit("No se pudo resolver el audio online")
                }
            } finally {
                if (pendingPlayIntentEpoch == playIntent) pendingPlayIntentEpoch = null
                endResolving()
            }
        }
    }

    private fun applyQueueSelection(
        item: PlayableItem,
        startPlaying: Boolean = true
    ): Boolean {
        val items = _queue.value
        val slot = items.indexOfQueueEntry(item)
        if (slot < 0) return false
        bumpQueueFocus()
        _playbackPositionMs.value = 0L
        val player = controller
        if (player == null || player.mediaItemCount != items.size) {
            if (startPlaying) {
                playWhenReadyIntent = true
                if (player == null) beginPendingPlayIntent()
            }
            finishPlayPlayableCollection(
                items,
                slot,
                applyManualModes = false,
                fromRadio = _radioActive.value,
                startPlaying = startPlaying
            )
        } else {
            lastMediaItemIndex = slot
            playWhenReadyIntent = startPlaying
            if (!startPlaying) player.pause()
            player.seekTo(slot, 0L)
            player.prepare()
            if (startPlaying) {
                player.play()
                prefetchAround(slot)
            }
            persistPlaybackSession(force = true)
        }
        return true
    }

    private fun handleMediaItemTransition(incoming: PlayableItem?, reason: Int) {
        val player = controller
        val newIndex = player?.currentMediaItemIndex ?: -1
        if (suppressPlaylistMutationCallbacks) {
            lastMediaItemIndex = newIndex
            return
        }
        val queueSize = _queue.value.size
        val wrappedShuffleCycle = !suppressShuffleWrapDetection &&
            _isShuffle.value &&
            _repeatMode.value == RepeatMode.ALL &&
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            lastMediaItemIndex == queueSize - 1 &&
            newIndex == 0 &&
            queueSize > 1

        if (incoming != null) {
            val previous = _currentItem.value
            val playable = _queue.value.firstOrNull {
                it.queueEntryId == incoming.queueEntryId
            } ?: _queue.value.getOrNull(newIndex) ?: incoming
            val sameOccurrence = previous?.queueEntryId == playable.queueEntryId
            val explicitStart = pendingNewPlaybackQueueEntryId == playable.queueEntryId
            if (explicitStart) pendingNewPlaybackQueueEntryId = null
            val metadataOnly =
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                    sameOccurrence &&
                    !explicitStart
            if (!metadataOnly) {
                (previous as? PlayableItem.Remote)?.let { outgoing ->
                    val event = when (reason) {
                        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ->
                            SaveWhileListeningEvent.AUTOMATIC_TRANSITION
                        else -> SaveWhileListeningEvent.MANUAL_SKIP
                    }
                    maybeSaveWhileListening(
                        outgoing,
                        event,
                        _playbackPositionMs.value
                    )
                }
                _playbackPositionMs.value = 0L
                invalidatePlaybackWork(clearRejectedEntries = false)
            }
            setCurrentItem(
                playable,
                hint = if (metadataOnly) {
                    PlaybackChangeHint.METADATA_UPDATE
                } else {
                    PlaybackChangeHint.NEW_PLAYBACK
                }
            )
            ensurePreparedForPlayback()
            if (playWhenReadyIntent) {
                ensureRemoteReadyAt(newIndex, startPlaying = true)
                prefetchAround(newIndex)
            }
            if (_radioActive.value) {
                rememberRadioPlayed(playable)
                if (playWhenReadyIntent) maybeRefillRadio(newIndex)
            }
        } else {
            dependencies.listenTracker.onTrackChanged(null, PlaybackChangeHint.METADATA_UPDATE)
        }

        if (wrappedShuffleCycle) {
            val avoid = _queue.value.getOrNull(lastMediaItemIndex)?.queueEntryId
            val reshuffled = _queue.value.shuffled().toMutableList()
            if (avoid != null && reshuffled.size > 1 && reshuffled.first().queueEntryId == avoid) {
                val swap = reshuffled.indexOfFirst { it.queueEntryId != avoid }.takeIf { it > 0 } ?: 1
                val first = reshuffled[0]
                reshuffled[0] = reshuffled[swap]
                reshuffled[swap] = first
            }
            _queue.value = reshuffled
            suppressShuffleWrapDetection = true
            try {
                reloadPlayerTimeline(
                    reshuffled,
                    0,
                    0L,
                    startPlaying = playWhenReadyIntent
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

    private fun ensureRemoteReadyAt(index: Int, startPlaying: Boolean) {
        if (!startPlaying || !playWhenReadyIntent) return
        val item = _queue.value.getOrNull(index) as? PlayableItem.Remote ?: return
        if (!dependencies.streamAccess.needsResolve(item)) return
        resolvePlayableWithFallback(
            startIndex = index,
            triggerQueueEntryId = item.queueEntryId,
            firstFailureMessage = "No se pudo resolver el audio online"
        )
    }

    private fun resolvePlayableWithFallback(
        startIndex: Int,
        triggerQueueEntryId: String,
        firstFailureMessage: String?
    ) {
        val expectedController = controller ?: return
        if (!playWhenReadyIntent) return
        if (resolvingTransitionJob?.isActive == true &&
            resolvingTransitionQueueEntryId == triggerQueueEntryId
        ) {
            return
        }
        resolvingTransitionJob?.cancel()
        val generation = playbackGeneration
        val snapshot = _queue.value
        if (snapshot.isEmpty()) return
        resolvingTransitionQueueEntryId = triggerQueueEntryId
        resolvingTransitionJob = scope.launch {
            val ownJob = coroutineContext[Job]
            beginResolving()
            var failureAnnounced = false
            try {
                for (step in PlaybackFallbackPlanner.circularPlan(snapshot, startIndex)) {
                    if (!isFallbackContextCurrent(
                            generation,
                            triggerQueueEntryId,
                            expectedController
                        )
                    ) {
                        return@launch
                    }
                    val queueEntryId = step.item.queueEntryId
                    if (!playWhenReadyIntent && queueEntryId != triggerQueueEntryId) {
                        return@launch
                    }
                    if (queueEntryId in rejectedQueueEntries) continue
                    val liveIndex = _queue.value.indexOfQueueEntry(step.item)
                    if (liveIndex < 0) continue
                    when (val live = _queue.value[liveIndex]) {
                        is PlayableItem.Local -> {
                            activateFallbackCandidate(
                                queueEntryId = live.queueEntryId,
                                expectedController = expectedController,
                                generation = generation,
                                triggerQueueEntryId = triggerQueueEntryId
                            )
                            return@launch
                        }
                        is PlayableItem.Remote -> {
                            val ready = if (dependencies.streamAccess.needsResolve(live)) {
                                dependencies.streamAccess.resolve(live)
                            } else {
                                live
                            }
                            if (!isFallbackContextCurrent(
                                    generation,
                                    triggerQueueEntryId,
                                    expectedController
                                )
                            ) {
                                return@launch
                            }
                            if (ready == null) {
                                rejectedQueueEntries += live.queueEntryId
                                if (!failureAnnounced) {
                                    firstFailureMessage?.let(_events::tryEmit)
                                    failureAnnounced = true
                                }
                                continue
                            }
                            val slot = if (ready === live) {
                                _queue.value.indexOfQueueEntry(live)
                            } else {
                                applyResolvedRemote(live, ready)
                            }
                            if (slot < 0) continue
                            activateFallbackCandidate(
                                queueEntryId = ready.queueEntryId,
                                expectedController = expectedController,
                                generation = generation,
                                triggerQueueEntryId = triggerQueueEntryId
                            )
                            return@launch
                        }
                    }
                }
                if (isFallbackContextCurrent(
                        generation,
                        triggerQueueEntryId,
                        expectedController
                    )
                ) {
                    pauseAfterFallbackExhausted(
                        expectedController,
                        firstFailureMessage.takeUnless { failureAnnounced }
                    )
                }
            } finally {
                endResolving()
                if (resolvingTransitionJob === ownJob) {
                    resolvingTransitionQueueEntryId = null
                    resolvingTransitionJob = null
                }
            }
        }
    }

    private fun activateFallbackCandidate(
        queueEntryId: String,
        expectedController: PlaybackControllerFacade,
        generation: Long,
        triggerQueueEntryId: String
    ) {
        if (!isFallbackContextCurrent(
                generation,
                triggerQueueEntryId,
                expectedController
            )
        ) {
            return
        }
        val slot = _queue.value.indexOfFirst { it.queueEntryId == queueEntryId }
        if (slot < 0) return
        if (slot != expectedController.currentMediaItemIndex) {
            if (!playWhenReadyIntent) return
            lastMediaItemIndex = slot
            _playbackPositionMs.value = 0L
            expectedController.seekTo(slot, 0L)
        }
        ensurePreparedForPlayback()
        if (playWhenReadyIntent && controller === expectedController) {
            expectedController.play()
            prefetchAround(slot)
        }
    }

    private fun isFallbackContextCurrent(
        generation: Long,
        triggerQueueEntryId: String,
        expectedController: PlaybackControllerFacade
    ): Boolean =
        generation == playbackGeneration &&
            controller === expectedController &&
            _currentItem.value?.queueEntryId == triggerQueueEntryId

    private fun pauseAfterFallbackExhausted(
        expectedController: PlaybackControllerFacade,
        firstFailureMessage: String?
    ) {
        if (controller !== expectedController || !playWhenReadyIntent) return
        firstFailureMessage?.let(_events::tryEmit)
        playWhenReadyIntent = false
        cancelPendingPlayIntent()
        expectedController.pause()
        _events.tryEmit("No se encontró una canción reproducible en la cola")
    }

    private fun prefetchAround(index: Int) {
        if (!playWhenReadyIntent) {
            prefetchJob?.cancel()
            prefetchJob = null
            return
        }
        prefetchJob?.cancel()
        val generation = playbackGeneration
        val expectedController = controller ?: return
        prefetchJob = scope.launch {
            val snapshot = _queue.value
            val targets = listOfNotNull(
                snapshot.getOrNull(index + 1),
                snapshot.getOrNull(index + 2)
            ).filterIsInstance<PlayableItem.Remote>()
                .filter(dependencies.streamAccess::needsResolve)
                .filterNot { it.queueEntryId in rejectedQueueEntries }
            for (remote in targets) {
                if (!isPlaybackGenerationCurrent(generation) ||
                    !playWhenReadyIntent ||
                    controller !== expectedController
                ) {
                    return@launch
                }
                val resolved = dependencies.streamAccess.resolve(remote) ?: continue
                if (!isPlaybackGenerationCurrent(generation) ||
                    !playWhenReadyIntent ||
                    controller !== expectedController
                ) {
                    return@launch
                }
                applyResolvedRemote(remote, resolved)
            }
        }
    }

    private fun applyResolvedRemote(
        original: PlayableItem.Remote,
        resolved: PlayableItem.Remote
    ): Int {
        val index = _queue.value.indexOfRemoteSlot(original)
        if (index < 0) return -1
        val live = _queue.value.toMutableList()
        live[index] = resolved
        _queue.value = live
        if (index < (controller?.mediaItemCount ?: 0)) {
            controller?.replaceMediaItem(index, resolved)
        }
        if (_currentItem.value?.queueEntryId == resolved.queueEntryId ||
            index == controller?.currentMediaItemIndex
        ) {
            setCurrentItem(
                resolved,
                persistLastPlayed = false,
                hint = PlaybackChangeHint.METADATA_UPDATE
            )
        }
        return index
    }

    private fun handlePlayerError() {
        val player = controller ?: return
        if (!playWhenReadyIntent) return
        val index = player.currentMediaItemIndex
        val queued = _queue.value.getOrNull(index)
        val remote = queued as? PlayableItem.Remote
        if (remote == null) {
            recoverAfterUnplayable(
                (queued as? PlayableItem.Local)?.title
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "No se pudo reproducir «$it»" }
                    ?: "No se pudo reproducir"
            )
            return
        }
        if (remote.resolved == null || remote.resolved.audioUrl.isBlank()) {
            ensureRemoteReadyAt(index, startPlaying = true)
            return
        }
        val graceMs = dependencies.playbackSettings.value.streamSkipGraceSeconds
            .coerceAtLeast(0)
            .times(1000L)
        if (graceMs <= 0L) {
            recoverAfterUnplayable(unplayableRemoteMessage(remote))
            return
        }
        if (remoteRecoveryQueueEntryId != remote.queueEntryId) {
            remoteRecoveryQueueEntryId = remote.queueEntryId
            remoteRecoveryDeadlineMs = dependencies.clockMs() + graceMs
        }
        if (dependencies.clockMs() >= remoteRecoveryDeadlineMs) {
            cancelRemoteRecoveryJob()
            recoverAfterUnplayable(unplayableRemoteMessage(remote))
            return
        }
        if (remoteRecoveryJob?.isActive == true) return
        val generation = playbackGeneration
        val queueEntryId = remote.queueEntryId
        val expectedController = player
        val deadlineMs = remoteRecoveryDeadlineMs
        remoteRecoveryJob = scope.launch {
            val ownJob = coroutineContext[Job]
            beginResolving()
            try {
                while (dependencies.clockMs() < deadlineMs) {
                    if (!playWhenReadyIntent ||
                        !isFallbackContextCurrent(
                            generation,
                            queueEntryId,
                            expectedController
                        )
                    ) {
                        return@launch
                    }
                    dependencies.streamAccess.invalidate(remote)
                    val refreshed = dependencies.streamAccess.resolve(remote.copy(resolved = null))
                    if (!playWhenReadyIntent ||
                        !isFallbackContextCurrent(
                            generation,
                            queueEntryId,
                            expectedController
                        )
                    ) {
                        return@launch
                    }
                    if (refreshed != null && applyResolvedRemote(remote, refreshed) >= 0) {
                        expectedController.prepare()
                        if (playWhenReadyIntent) expectedController.play()
                        return@launch
                    }
                    delay(REMOTE_RECOVERY_RETRY_MS)
                }
                if (playWhenReadyIntent &&
                    isFallbackContextCurrent(
                        generation,
                        queueEntryId,
                        expectedController
                    )
                ) {
                    recoverAfterUnplayable(unplayableRemoteMessage(remote))
                }
            } finally {
                endResolving()
                if (remoteRecoveryJob === ownJob) {
                    remoteRecoveryJob = null
                }
            }
        }
    }

    private fun recoverAfterUnplayable(message: String?) {
        val player = controller ?: return
        if (!playWhenReadyIntent) return
        message?.let(_events::tryEmit)
        val items = _queue.value
        if (items.isEmpty()) return
        val index = player.currentMediaItemIndex.coerceIn(items.indices)
        val failed = items[index]
        rejectedQueueEntries += failed.queueEntryId
        val nextIndex = (index + 1) % items.size
        resolvePlayableWithFallback(
            startIndex = nextIndex,
            triggerQueueEntryId = failed.queueEntryId,
            firstFailureMessage = null
        )
    }

    private fun cancelRemoteRecoveryJob() {
        remoteRecoveryJob?.cancel()
        remoteRecoveryJob = null
    }

    private fun clearRemoteRecovery() {
        cancelRemoteRecoveryJob()
        remoteRecoveryQueueEntryId = null
        remoteRecoveryDeadlineMs = 0L
    }

    private fun clearRemoteRecoveryAfterProgress() {
        val currentQueueEntryId = _currentItem.value?.queueEntryId ?: return
        if (remoteRecoveryQueueEntryId == currentQueueEntryId) clearRemoteRecovery()
    }

    private fun ensurePreparedForPlayback() {
        val player = controller ?: return
        if (player.mediaItemCount == 0) return
        val remote = _queue.value.getOrNull(player.currentMediaItemIndex) as? PlayableItem.Remote
        if (remote != null && (remote.resolved == null || remote.resolved.audioUrl.isBlank())) return
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    private fun restartAsyncPlaybackWork() {
        if (!playWhenReadyIntent || _queue.value.isEmpty()) return
        val index = currentQueueIndex().coerceIn(_queue.value.indices)
        val current = _queue.value[index]
        if (current is PlayableItem.Remote &&
            remoteRecoveryQueueEntryId == current.queueEntryId
        ) {
            handlePlayerError()
        } else {
            ensureRemoteReadyAt(index, startPlaying = true)
        }
        prefetchAround(index)
    }

    private suspend fun samplePositionAndOwnership() {
        val player = controller ?: return
        if (_isPlaying.value != player.isPlaying) _isPlaying.value = player.isPlaying
        if (player.isPlaying && dependencies.clockMs() - lastSeekTimestamp > 600L) {
            _playbackPositionMs.value = player.currentPosition.coerceAtLeast(0L)
            if (_playbackPositionMs.value >= FALLBACK_SUCCESS_POSITION_MS) {
                clearRemoteRecoveryAfterProgress()
                rejectedQueueEntries.clear()
            }
            persistPlaybackSession(force = false)
            val duration = player.duration
            val current = _currentItem.value
            if (duration > 0L && current != null && current.durationMs <= 0L) {
                when (current) {
                    is PlayableItem.Local -> {
                        scope.launch(Dispatchers.IO) {
                            dependencies.updateSongDuration(current.song.id, duration)
                        }
                        dependencies.listenTracker.onDurationKnown(current.song.id, duration)
                    }
                    is PlayableItem.Remote -> {
                        val index = player.currentMediaItemIndex
                        val updated = current.withIdentity { copy(durationMs = duration) }
                        val live = _queue.value.toMutableList()
                        if (index in live.indices) {
                            live[index] = updated
                            _queue.value = live
                            setCurrentItem(
                                updated,
                                persistLastPlayed = false,
                                hint = PlaybackChangeHint.METADATA_UPDATE
                            )
                        }
                    }
                }
            }
            (current as? PlayableItem.Remote)?.let { remote ->
                maybeSaveWhileListening(
                    remote,
                    SaveWhileListeningEvent.PROGRESS,
                    _playbackPositionMs.value,
                    durationMs = remote.durationMs.takeIf { it > 0L } ?: duration
                )
            }
        } else if (!player.isPlaying && player.mediaItemCount > 0) {
            _playbackPositionMs.value = player.currentPosition.coerceAtLeast(0L)
        }
        dependencies.listenTracker.onPlaybackTick(
            player.isPlaying,
            dependencies.elapsedRealtimeMs()
        )
    }

    private fun maybeSaveWhileListening(
        remote: PlayableItem.Remote,
        event: SaveWhileListeningEvent,
        positionMs: Long,
        durationMs: Long = remote.durationMs
    ) {
        if (!dependencies.listenSettingsReady.value) {
            val queueEntryId = remote.queueEntryId
            pendingSaveSettingsJobs.remove(queueEntryId)?.cancel()
            pendingSaveSettingsJobs[queueEntryId] = scope.launch {
                val ownJob = coroutineContext[Job]
                try {
                    dependencies.listenSettingsReady.first { it }
                    maybeSaveWhileListening(remote, event, positionMs, durationMs)
                } finally {
                    if (pendingSaveSettingsJobs[queueEntryId] === ownJob) {
                        pendingSaveSettingsJobs.remove(queueEntryId)
                    }
                }
            }
            return
        }
        val settings = dependencies.listenSettings.value
        if (!settings.saveWhileListening) return
        if (!SaveWhileListeningPolicy.shouldSave(
                positionMs = positionMs,
                durationMs = durationMs,
                thresholdPercent = settings.saveWhileListeningPercent,
                event = event
            )
        ) {
            return
        }
        val key = TrackMatchKeys.downloadIdFor(remote.artist, remote.title)
        if (key.isEmpty() || key in saveWhileListeningAttempted) return
        val failedAt = saveWhileListeningFailures[key]
        if (failedAt != null &&
            dependencies.clockMs() - failedAt < SAVE_RETRY_COOLDOWN_MS
        ) {
            return
        }
        saveWhileListeningAttempted += key
        scope.launch {
            when (val result = dependencies.saveDownloads.save(remote)) {
                is SaveWhileListeningDownloadResult.Saved -> {
                    saveWhileListeningFailures.remove(key)
                    _events.tryEmit("«${result.song.title}» guardada en la biblioteca")
                }
                is SaveWhileListeningDownloadResult.InFlight -> {
                    // Neutral: another owner holds the claim. Clear attempted so autosave can retry
                    // if that owner fails, is dismissed, or is cancelled.
                    saveWhileListeningAttempted.remove(key)
                }
                is SaveWhileListeningDownloadResult.Failed -> {
                    saveWhileListeningFailures[key] = dependencies.clockMs()
                    saveWhileListeningAttempted.remove(key)
                    _events.tryEmit(
                        "No se pudo guardar «${remote.title}»: " +
                            (result.error.localizedMessage ?: "error")
                    )
                }
            }
        }
    }

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

    fun startRadio(
        seedSong: Song? = null,
        mode: RadioMode? = null,
        auto: Boolean = false,
        announceMode: Boolean = false
    ) {
        val seed = seedSong?.toPlayable() ?: _currentItem.value ?: run {
            if (!auto) _events.tryEmit("Elegí una canción para iniciar la radio")
            return
        }
        if (seed.artist.isBlank() || seed.title.isBlank()) return
        if (_radioLoading.value) return
        if (mode != null) radioPreferredMode = mode
        val resolvedMode = mode ?: radioPreferredMode
            ?: if (dependencies.isOnline()) RadioMode.BOTH else RadioMode.KNOWN
        val keepCurrent = !auto && shouldKeepCurrentWhenStartingRadio()
        radioStartJob?.cancel()
        radioStartJob = scope.launch {
            dependencies.listenSettingsReady.first { it }
            if (!isActive) return@launch
            _radioLoading.value = true
            try {
                val exclude = buildRadioExcludeKeys(seed, includeQueue = true, _currentItem.value)
                val batch = suggestRadioWithRetry(
                    PlaybackRuntimeRadioRequest(
                        seed = seed,
                        library = library,
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
                        _events.tryEmit(
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
                clearDiscoverPlaybackOrigin()
                applyRadioStartModes()
                val previousPlayed =
                    if (_radioActive.value) playedInRadioSession.toSet() else emptySet()
                clearRadioSessionKeepPreference()
                _radioMode.value = resolvedMode
                playedInRadioSession += previousPlayed
                playedInRadioSession += exclude
                rememberRadioPlayed(seed)
                _radioActive.value = true
                updateRadioStatusLabel()
                if (announceMode && !auto) _events.tryEmit(radioModeLabel(resolvedMode))
                if (keepCurrent) {
                    replaceUpcomingWithRadio(batch.items)
                    _events.tryEmit("Se agregaron canciones de la radio a la cola")
                    prefetchAround(controller?.currentMediaItemIndex ?: lastMediaItemIndex)
                } else {
                    playPlayableCollection(
                        batch.items,
                        fromRadio = true,
                        rotate = false
                    )
                }
            } finally {
                _radioLoading.value = false
            }
        }
    }

    private fun maybeAutoStartRadioOnQueueEnd() {
        if (_repeatMode.value != RepeatMode.OFF || _radioLoading.value) return
        val seed = _currentItem.value ?: return
        if (seed.artist.isBlank() || seed.title.isBlank()) return
        startRadio(auto = true)
    }

    private fun maybeRefillRadio(currentIndex: Int) {
        if (!_radioActive.value || radioRefillJob?.isActive == true) return
        val remaining = _queue.value.size - currentIndex - 1
        if (remaining >= RADIO_REFILL_THRESHOLD) return
        val seed = _currentItem.value ?: return
        val sinceEmpty = dependencies.clockMs() - lastEmptyRadioRefillAtMs
        if (lastEmptyRadioRefillAtMs > 0L && sinceEmpty < RADIO_EMPTY_COOLDOWN_MS) return
        radioRefillJob = scope.launch {
            val batch = suggestRadioWithRetry(
                PlaybackRuntimeRadioRequest(
                    seed = seed,
                    library = library,
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
                addPlayableBatch(batch.items)
            } else if (batch.items.isEmpty()) {
                lastEmptyRadioRefillAtMs = dependencies.clockMs()
            }
        }
    }

    internal suspend fun suggestRadioWithRetry(
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

    private fun replaceUpcomingWithRadio(suggestions: List<PlayableItem>) {
        val currentIndex = (controller?.currentMediaItemIndex ?: lastMediaItemIndex).coerceAtLeast(0)
        val live = _queue.value
        if (currentIndex !in live.indices) {
            playPlayableCollection(suggestions, fromRadio = true, rotate = false)
            return
        }
        invalidatePlaybackWork(clearRejectedEntries = false)
        val additions = suggestions.withFreshQueueEntryIds()
        _queue.value = live.subList(0, currentIndex + 1) + additions
        mutateMaterializedTimeline { player ->
            val next = currentIndex + 1
            if (next < player.mediaItemCount) player.removeMediaItems(next, player.mediaItemCount)
            player.addMediaItems(additions)
        }
        persistPlaybackSession(force = true)
        restartAsyncPlaybackWork()
    }

    private fun shouldKeepCurrentWhenStartingRadio(): Boolean {
        val player = controller ?: return false
        val index = player.currentMediaItemIndex
        return _queue.value.isNotEmpty() &&
            index in _queue.value.indices &&
            player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE
    }

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

    private fun clearDiscoverPlaybackOrigin() {
        if (_discoverPlaybackOrigin.value != DiscoverPlaybackOrigin.None) {
            _discoverPlaybackOrigin.value = DiscoverPlaybackOrigin.None
        }
    }

    private fun rememberRadioPlayed(item: PlayableItem) {
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
        if (includeQueue) _queue.value.forEach(::add)
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

    private fun reloadPlayerTimeline(
        items: List<PlayableItem>,
        startIndex: Int,
        startPositionMs: Long,
        startPlaying: Boolean,
        newPlayback: Boolean = false
    ) {
        val player = controller ?: return
        if (items.isEmpty()) return
        val validIndex = startIndex.coerceIn(items.indices)
        if (newPlayback) pendingNewPlaybackQueueEntryId = items[validIndex].queueEntryId
        playWhenReadyIntent = startPlaying
        if (!startPlaying) player.pause()
        player.setMediaItems(
            items,
            validIndex,
            startPositionMs.coerceAtLeast(0L)
        )
        timelineMaterialized = true
        lastMediaItemIndex = validIndex
        if (startPlaying) {
            pendingPlayIntentEpoch = null
            player.play()
        }
        val remote = items[validIndex] as? PlayableItem.Remote
        if (remote != null && dependencies.streamAccess.needsResolve(remote)) {
            if (startPlaying) ensureRemoteReadyAt(validIndex, startPlaying = true)
        } else {
            player.prepare()
            if (startPlaying) prefetchAround(validIndex)
        }
        syncShuffleToPlayer()
        updateTickerLifecycle()
    }

    private fun hasMaterializedTimeline(): Boolean =
        timelineMaterialized && (controller?.mediaItemCount ?: 0) > 0

    private fun mutateMaterializedTimeline(
        mutation: (PlaybackControllerFacade) -> Unit
    ) {
        val player = controller ?: return
        if (!hasMaterializedTimeline()) return
        suppressPlaylistMutationCallbacks = true
        try {
            mutation(player)
        } finally {
            suppressPlaylistMutationCallbacks = false
        }
        // Media3 regenerates ShuffleOrder after playlist mutations. Logical shuffle is already
        // represented by the physical queue, so restore identity traversal after every mutation.
        syncShuffleToPlayer()
    }

    private fun rebuildPlayerQueueAroundCurrent(newOrder: List<PlayableItem>): Boolean {
        val player = controller ?: return false
        if (!hasMaterializedTimeline() || newOrder.isEmpty()) return false
        val current = _currentItem.value ?: return false
        val playIndex = newOrder.indexOfFirst { it.queueEntryId == current.queueEntryId }
            .takeIf { it >= 0 } ?: return false
        val playerIndex = player.currentMediaItemIndex
        if (playerIndex !in 0 until player.mediaItemCount) return false
        suppressPlaylistMutationCallbacks = true
        try {
            if (playerIndex + 1 < player.mediaItemCount) {
                player.removeMediaItems(playerIndex + 1, player.mediaItemCount)
            }
            if (playerIndex > 0) player.removeMediaItems(0, playerIndex)
            if (playIndex > 0) player.addMediaItems(0, newOrder.subList(0, playIndex))
            if (playIndex < newOrder.lastIndex) {
                player.addMediaItems(newOrder.subList(playIndex + 1, newOrder.size))
            }
            lastMediaItemIndex = player.currentMediaItemIndex.coerceAtLeast(0)
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
        _playbackPositionMs.value = positionMs
        if (!hasMaterializedTimeline()) return
        if (!rebuildPlayerQueueAroundCurrent(newOrder)) {
            reloadPlayerTimeline(newOrder, focusIndex, positionMs, startPlaying)
        }
    }

    private fun permuteQueueToPlayOrder(
        items: List<PlayableItem>,
        currentIndex: Int,
        backupSource: Boolean
    ): Pair<List<PlayableItem>, Int> {
        if (items.isEmpty()) return items to 0
        if (backupSource) preShuffleOrder = PlaybackQueueSlots.capturePreShuffleOrder(items)
        val order = PlaybackQueueOrder.shufflePlayOrder(items.size, currentIndex)
        return PlaybackQueueOrder.applyPlayOrder(items, order) to 0
    }

    private fun preShuffleQueueOrNull(): List<PlayableItem>? {
        val order = preShuffleOrder ?: return null
        return PlaybackQueueSlots.restorePreShuffleOrder(_queue.value, order)
    }

    private fun setShuffleEnabled(enabled: Boolean) {
        val wasEnabled = _isShuffle.value
        _isShuffle.value = enabled
        scope.launch { dependencies.persistShuffle(enabled) }
        if (!enabled && wasEnabled) {
            preShuffleOrder = null
        }
        syncShuffleToPlayer()
    }

    private fun disableShuffleRestoringOrder() {
        if (!_isShuffle.value) return
        val restored = preShuffleQueueOrNull()
        val currentSlot = _currentItem.value?.queueEntryId
        val position = if (hasMaterializedTimeline()) {
            controller?.currentPosition ?: _playbackPositionMs.value
        } else {
            _playbackPositionMs.value
        }
        val wasPlaying = playWhenReadyIntent
        setShuffleEnabled(false)
        if (!restored.isNullOrEmpty()) {
            val index = restored.indexOfFirst { it.queueEntryId == currentSlot }
                .takeIf { it >= 0 } ?: 0
            applyQueueReorder(restored, index, position, wasPlaying)
        }
    }

    private fun syncShuffleToPlayer() {
        if (hasMaterializedTimeline()) {
            controller?.shuffleModeEnabled = _isShuffle.value
        }
    }

    private fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
        applyRepeatModeToController(mode)
        scope.launch { dependencies.persistRepeat(mode) }
    }

    private fun applyRepeatModeToController(mode: RepeatMode) {
        controller?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    private fun applyManualPlayModes(deferPlayerSync: Boolean = false) {
        val (shuffle, repeat) = PlaybackModeClear.afterManualPlay(
            _isShuffle.value,
            _repeatMode.value,
            dependencies.playbackSettings.value
        )
        if (deferPlayerSync) {
            _isShuffle.value = shuffle
            _repeatMode.value = repeat
            scope.launch { dependencies.persistShuffle(shuffle) }
            scope.launch { dependencies.persistRepeat(repeat) }
            pendingExternalPlaybackModes = shuffle to repeat
        } else {
            applyResolvedModes(shuffle, repeat)
        }
    }

    private fun applySkipModes() {
        val (shuffle, repeat) = PlaybackModeClear.afterSkip(
            _isShuffle.value,
            _repeatMode.value,
            dependencies.playbackSettings.value
        )
        applyResolvedModes(shuffle, repeat)
    }

    private fun applyRadioStartModes() {
        val (shuffle, repeat) = PlaybackModeClear.afterRadioStart(
            _isShuffle.value,
            _repeatMode.value
        )
        applyResolvedModes(shuffle, repeat)
    }

    private fun applyResolvedModes(shuffle: Boolean, repeat: RepeatMode) {
        if (shuffle != _isShuffle.value) {
            if (shuffle) setShuffleEnabled(true) else disableShuffleRestoringOrder()
        }
        if (repeat != _repeatMode.value) setRepeatMode(repeat)
    }

    private fun repeatModeFromPlayer(value: Int): RepeatMode = when (value) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    private fun invalidateQueueSelection() {
        selectionGate.invalidate()
        queueSelectionJob?.cancel()
        queueSelectionJob = null
    }

    private fun invalidatePlaybackWork(clearRejectedEntries: Boolean = true) {
        playbackGeneration++
        resolvingTransitionJob?.cancel()
        resolvingTransitionJob = null
        resolvingTransitionQueueEntryId = null
        cancelRemoteRecoveryJob()
        prefetchJob?.cancel()
        prefetchJob = null
        invalidateQueueSelection()
        seekPersistenceJob?.cancel()
        seekPersistenceJob = null
        if (clearRejectedEntries) rejectedQueueEntries.clear()
    }

    private fun isPlaybackGenerationCurrent(generation: Long): Boolean =
        generation == playbackGeneration

    private fun beginResolving() {
        resolvingCount.incrementAndGet()
        _resolvingRemote.value = true
    }

    private fun endResolving() {
        if (resolvingCount.decrementAndGet() <= 0) {
            resolvingCount.set(0)
            _resolvingRemote.value = false
        }
    }

    private fun bumpQueueFocus() {
        _queueFocusEpoch.value += 1
    }

    private fun unplayableRemoteMessage(remote: PlayableItem.Remote): String =
        remote.title.takeIf { it.isNotBlank() }
            ?.let { "No se pudo reproducir «$it»" }
            ?: "No se pudo reproducir el audio online"

    companion object {
        private const val POSITION_TICK_MS = 200L
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        private const val SEEK_PERSIST_DEBOUNCE_MS = 300L
        private const val FALLBACK_SUCCESS_POSITION_MS = 1_000L
        private const val REMOTE_RECOVERY_RETRY_MS = 600L
        private const val SAVE_RETRY_COOLDOWN_MS = 10 * 60 * 1000L
        private const val RADIO_REFILL_THRESHOLD = 5
        private const val RADIO_START_TIMEOUT_MS = 45_000L
        private const val RADIO_REFILL_TIMEOUT_MS = 20_000L
        private const val RADIO_EMPTY_COOLDOWN_MS = 60_000L
        private const val RADIO_BATCH_SIZE = 30

        internal fun create(
            context: Context,
            repository: MusicRepository,
            radioEngine: RadioEngine,
            pendingListenDao: PendingListenDao,
            saveDownloads: PlaybackRuntimeSaveDownloads
        ): PlaybackRuntime {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val playbackPreferences = PlaybackPreferencesRepository(context)
            val listenPreferences = ListenBrainzPreferencesRepository(context)
            val connectivity = ConnectivityObserver(context)
            val playbackSettings = MutableStateFlow(PlaybackSettings())
            val playbackSettingsReady = MutableStateFlow(false)
            scope.launch {
                playbackPreferences.settingsFlow.collectLatest { loaded ->
                    playbackSettings.value = loaded
                    playbackSettingsReady.value = true
                }
            }
            val listenSettings = MutableStateFlow(ListenBrainzSettings())
            val listenSettingsReady = MutableStateFlow(false)
            scope.launch {
                listenPreferences.settingsFlow.collectLatest {
                    listenSettings.value = it
                    listenSettingsReady.value = true
                }
            }
            val sync = ListenSyncCoordinator(
                scope,
                pendingListenDao,
                listenPreferences,
                connectivity::isCurrentlyOnline
            )
            val tracker = ListenTracker(
                scope,
                pendingListenDao,
                listenPreferences,
                sync::requestSync
            )
            val resolver = repository.streamResolver
            val runtime = PlaybackRuntime(
                PlaybackRuntimeDependencies(
                    scope = scope,
                    libraryUpdates = repository.allSongsFlow,
                    playbackSettings = playbackSettings,
                    playbackSettingsReady = playbackSettingsReady,
                    listenSettings = listenSettings,
                    listenSettingsReady = listenSettingsReady,
                    persistence = PlaybackSessionStoreRuntimePersistence(
                        PlaybackSessionStore(context)
                    ),
                    listenTracker = ListenTrackerRuntimeAdapter(tracker),
                    streamAccess = StreamResolverRuntimeAccess(
                        resolver,
                        System::currentTimeMillis
                    ),
                    saveDownloads = saveDownloads,
                    radioSuggester = PlaybackRuntimeRadioSuggester { request ->
                        val online = connectivity.isCurrentlyOnline()
                        val settings = request.settings
                        radioEngine.suggest(
                            seed = request.seed,
                            library = request.library,
                            mode = request.mode,
                            excludeKeys = request.excludeKeys,
                            limit = RADIO_BATCH_SIZE,
                            lbToken = settings.userToken.takeIf { it.isNotBlank() },
                            lbAvailable = settings.enabled &&
                                settings.userToken.isNotBlank() &&
                                online,
                            lbUsername = settings.username,
                            networkAvailable = online,
                            coPlaylistSongIds = request.coPlaylistSongIds
                        )
                    },
                    resolveCoPlaylistSongIds = { seed ->
                        val local = seed as? PlayableItem.Local
                        if (local == null) emptySet()
                        else runCatching {
                            repository.getCoPlaylistSongIds(local.song.id)
                        }.getOrDefault(emptySet())
                    },
                    isOnline = connectivity::isCurrentlyOnline,
                    persistShuffle = playbackPreferences::setLastShuffleEnabled,
                    persistRepeat = playbackPreferences::setLastRepeatMode,
                    touchSongLastPlayed = repository::touchSongLastPlayed,
                    updateSongDuration = repository::updateSongDuration,
                    enhanceSong = repository::enhanceSongMetadataAndLyrics,
                    requestListenSync = sync::requestSync
                )
            )
            runtime.connect(context)
            scope.launch {
                connectivity.isOnline.collectLatest { if (it) sync.requestSync() }
            }
            scope.launch {
                if (pendingListenDao.count() > 0 && connectivity.isCurrentlyOnline()) {
                    sync.requestSync()
                }
            }
            return runtime
        }
    }
}

private class PlaybackSessionStoreRuntimePersistence(
    private val store: PlaybackSessionStore
) : PlaybackRuntimePersistence {
    override suspend fun loadLastPlayed() = store.load()
    override suspend fun loadQueue() = store.loadQueue()
    override suspend fun saveSession(
        lastPlayed: LastPlayedSnapshot?,
        queue: QueueSnapshot?,
        clearQueue: Boolean
    ) {
        store.saveSession(lastPlayed, queue, clearQueue)
    }
}

private class ListenTrackerRuntimeAdapter(
    private val tracker: ListenTracker
) : PlaybackRuntimeListenTracker {
    override fun onTrackChanged(song: Song?, hint: PlaybackChangeHint) =
        tracker.onTrackChanged(song, hint)

    override fun onDurationKnown(songId: Long, durationMs: Long) =
        tracker.onDurationKnown(songId, durationMs)

    override fun onPlaybackTick(isPlaying: Boolean, elapsedRealtimeMs: Long) =
        tracker.onPlaybackTick(isPlaying, elapsedRealtimeMs)

    override fun onStopped() = tracker.onStopped()
}

private class StreamResolverRuntimeAccess(
    private val resolver: StreamResolver,
    private val clockMs: () -> Long
) : PlaybackRuntimeStreamAccess {
    override fun needsResolve(item: PlayableItem.Remote): Boolean {
        val resolved = item.resolved ?: return true
        if (resolved.audioUrl.isBlank() || !resolver.isFresh(resolved)) return true
        return clockMs() - resolved.resolvedAtEpochMs > STREAM_READY_MAX_AGE_MS
    }

    override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote? =
        resolver.resolveForPlayback(item, maxCachedAgeMs = STREAM_READY_MAX_AGE_MS)
            .getOrNull()
            ?.let { item.copy(resolved = it) }

    override suspend fun invalidate(item: PlayableItem.Remote) {
        resolver.invalidate(item)
    }
}

@OptIn(UnstableApi::class)
private class MediaControllerConnection(
    context: Context,
    private val library: () -> List<Song>
) : PlaybackControllerConnection {
    private val disconnectionRelay = MediaControllerDisconnectionRelay()
    private val audioStore = MusicFileStore(context)
    private val future: ListenableFuture<MediaController>
    private var facade: PlaybackControllerFacade? = null

    init {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        future = MediaController.Builder(context, token)
            .setListener(disconnectionRelay)
            .buildAsync()
    }

    override fun addListener(listener: () -> Unit) {
        future.addListener(listener, MoreExecutors.directExecutor())
    }

    override fun get(): PlaybackControllerFacade =
        facade ?: MediaControllerFacade(
            controller = future.get(),
            audioStore = audioStore,
            library = library,
            disconnectionRelay = disconnectionRelay
        ).also { facade = it }

    override fun cancel() {
        future.cancel(true)
    }
}

private class MediaControllerDisconnectionRelay : MediaController.Listener {
    @Volatile
    private var callback: (() -> Unit)? = null

    fun attach(callback: () -> Unit) {
        this.callback = callback
    }

    fun clear() {
        callback = null
    }

    override fun onDisconnected(controller: MediaController) {
        callback?.invoke()
    }
}

@OptIn(UnstableApi::class)
private class MediaControllerFacade(
    private val controller: MediaController,
    private val audioStore: MusicFileStore,
    private val library: () -> List<Song>,
    private val disconnectionRelay: MediaControllerDisconnectionRelay
) : PlaybackControllerFacade {
    private val released = AtomicBoolean(false)
    private var runtimeListener: PlaybackControllerFacade.Listener? = null
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            runtimeListener?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            runtimeListener?.onPlayWhenReadyChanged(playWhenReady)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            runtimeListener?.onPlayerError()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            runtimeListener?.onPlaybackStateChanged(playbackState)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            runtimeListener?.onMediaItemTransition(
                mediaItem?.let { PlaybackMediaItemCodec.decode(it, library()) },
                reason
            )
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            runtimeListener?.onTimelineChanged()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            runtimeListener?.onPositionDiscontinuity(newPosition.positionMs)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            runtimeListener?.onRepeatModeChanged(repeatMode)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            runtimeListener?.onShuffleModeEnabledChanged(shuffleModeEnabled)
        }
    }

    override val mediaItemCount: Int get() = controller.mediaItemCount
    override val currentMediaItemIndex: Int get() = controller.currentMediaItemIndex
    override val currentPosition: Long get() = controller.currentPosition
    override val duration: Long get() = controller.duration
    override val isPlaying: Boolean get() = controller.isPlaying
    override val playWhenReady: Boolean get() = controller.playWhenReady
    override val playbackState: Int get() = controller.playbackState
    override var repeatMode: Int
        get() = controller.repeatMode
        set(value) {
            controller.repeatMode = value
        }
    override var shuffleModeEnabled: Boolean
        get() = controller.shuffleModeEnabled
        set(value) {
            val args = Bundle().apply {
                putIntArray(
                    MusicService.EXTRA_SHUFFLE_ORDER,
                    if (value) IntArray(controller.mediaItemCount) { it } else IntArray(0)
                )
            }
            controller.sendCustomCommand(
                SessionCommand(MusicService.ACTION_SET_SHUFFLE_ORDER, Bundle.EMPTY),
                args
            )
        }

    override fun addListener(listener: PlaybackControllerFacade.Listener) {
        runtimeListener = listener
        disconnectionRelay.attach { listener.onDisconnected(this) }
        controller.addListener(playerListener)
    }

    override fun items(): List<PlayableItem> = buildList {
        for (index in 0 until controller.mediaItemCount) {
            PlaybackMediaItemCodec.decode(controller.getMediaItemAt(index), library())?.let(::add)
        }
    }

    override fun setMediaItems(
        items: List<PlayableItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        controller.setMediaItems(items.map(::encode), startIndex, startPositionMs)
    }

    override fun replaceMediaItem(index: Int, item: PlayableItem) {
        controller.replaceMediaItem(index, encode(item))
    }

    override fun addMediaItems(items: List<PlayableItem>) {
        controller.addMediaItems(items.map(::encode))
    }

    override fun addMediaItems(index: Int, items: List<PlayableItem>) {
        controller.addMediaItems(index, items.map(::encode))
    }

    override fun removeMediaItem(index: Int) = controller.removeMediaItem(index)
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) =
        controller.removeMediaItems(fromIndex, toIndex)

    override fun moveMediaItem(fromIndex: Int, toIndex: Int) =
        controller.moveMediaItem(fromIndex, toIndex)

    override fun prepare() = controller.prepare()
    override fun play() = controller.play()
    override fun pause() = controller.pause()
    override fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    override fun seekTo(index: Int, positionMs: Long) = controller.seekTo(index, positionMs)
    override fun seekToNextMediaItem() = controller.seekToNextMediaItem()
    override fun seekToPreviousMediaItem() = controller.seekToPreviousMediaItem()
    override fun hasNextMediaItem(): Boolean = controller.hasNextMediaItem()
    override fun hasPreviousMediaItem(): Boolean = controller.hasPreviousMediaItem()
    override fun release() {
        if (!released.compareAndSet(false, true)) return
        disconnectionRelay.clear()
        runtimeListener = null
        controller.removeListener(playerListener)
        controller.release()
    }

    private fun encode(item: PlayableItem): MediaItem =
        PlaybackMediaItemCodec.encode(item) { song ->
            audioStore.playableUri(song.uriString)
        }
}
