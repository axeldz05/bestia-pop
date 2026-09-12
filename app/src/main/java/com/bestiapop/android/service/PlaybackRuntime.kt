package com.bestiapop.android.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.bestiapop.android.data.db.PendingListenDao
import com.bestiapop.android.data.listenbrainz.ListenSyncCoordinator
import com.bestiapop.android.data.listenbrainz.ListenTracker
import com.bestiapop.android.data.listenbrainz.SaveWhileListeningEvent
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.DiscoverPlaybackOrigin
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.ensureFreshQueueEntryIds
import com.bestiapop.android.data.model.indexOfQueueEntry
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.data.model.withFreshQueueEntryIds
import com.bestiapop.android.data.network.ConnectivityObserver
import com.bestiapop.android.data.playback.PlaybackChangeHint
import com.bestiapop.android.data.playback.PlaybackFallbackPlanner
import com.bestiapop.android.data.playback.PlaybackQueueOrder
import com.bestiapop.android.data.playback.PlaybackQueueSlots
import com.bestiapop.android.data.playback.PlaybackSelectionIntentGate
import com.bestiapop.android.data.preferences.HydratedQueue
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.PlaybackModeClear
import com.bestiapop.android.data.preferences.PlaybackModeRestore
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSessionStore
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.data.util.PlaybackDiagnostics
import com.bestiapop.android.domain.radio.RadioEngine
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.radio.RadioSuggestResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INITIAL_PLAYBACK_WINDOW_SIZE = 30
private const val QUEUE_APPEND_CHUNK_SIZE = 100

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
    val isPlaybackActive: Boolean get() = _isPlaying.value || playWhenReadyIntent
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

    private val sessionHydrator: PlaybackSessionHydrator = PlaybackSessionHydrator(
        scope = scope,
        dependencies = dependencies,
        getCurrentItem = { _currentItem.value },
        getQueue = { _queue.value },
        getPlaybackPositionMs = { _playbackPositionMs.value },
        getRepeatMode = { _repeatMode.value },
        isShuffle = { _isShuffle.value },
        getPreShuffleOrder = { queueCoordinator.preShuffleOrder },
        getCurrentQueueIndex = ::currentQueueIndex,
        isLibraryReady = { libraryReady.value },
        getLibrary = { library },
        getUiAttachments = { uiAttachments.get() },
        isPlayWhenReadyIntent = { playWhenReadyIntent },
        hasController = { controller != null },
        getControllerMediaItemCount = { controller?.mediaItemCount ?: 0 },
        onClearDiscoverPlaybackOrigin = ::clearDiscoverPlaybackOrigin,
        onSetCurrentItem = { item, persist -> setCurrentItem(item, persistLastPlayed = persist) },
        onSetPlaybackPositionMs = { _playbackPositionMs.value = it },
        onSetIsPlaying = { _isPlaying.value = it },
        onApplyHydratedQueue = ::applyHydratedQueue,
        onTogglePlayPause = ::togglePlayPause
    )

    private val queueCoordinator: PlaybackQueueCoordinator = PlaybackQueueCoordinator(
        scope = scope,
        dependencies = dependencies,
        getQueue = { _queue.value },
        onSetQueue = { _queue.value = it },
        getCurrentItem = { _currentItem.value },
        onSetCurrentItem = { item, persist, hint -> setCurrentItem(item, persistLastPlayed = persist, hint = hint) },
        getRepeatMode = { _repeatMode.value },
        onSetRepeatModeState = { _repeatMode.value = it },
        isShuffle = { _isShuffle.value },
        onSetShuffleState = { _isShuffle.value = it },
        getPlaybackPositionMs = { _playbackPositionMs.value },
        onSetPlaybackPositionMs = { _playbackPositionMs.value = it },
        isPlayWhenReadyIntent = { playWhenReadyIntent },
        onSetPlayWhenReadyIntent = { playWhenReadyIntent = it },
        getController = { controller },
        hasMaterializedTimeline = ::hasMaterializedTimeline,
        setTimelineMaterialized = { timelineMaterialized = it },
        getLastMediaItemIndex = { lastMediaItemIndex },
        setLastMediaItemIndex = { lastMediaItemIndex = it },
        currentQueueIndex = ::currentQueueIndex,
        onInvalidatePlaybackWork = ::invalidatePlaybackWork,
        onRestartAsyncPlaybackWork = ::restartAsyncPlaybackWork,
        onPersistPlaybackSession = { sessionHydrator.persistPlaybackSession(it) },
        onBumpQueueFocus = ::bumpQueueFocus,
        onStopRadio = ::stopRadio,
        onCancelPendingPlayIntent = ::cancelPendingPlayIntent,
        onClearDiscoverPlaybackOrigin = ::clearDiscoverPlaybackOrigin,
        onReleaseControllerIfIdle = ::releaseControllerIfIdle,
        onEnsureControllerConnection = ::ensureControllerConnection,
        applyQueueReorder = ::applyQueueReorder,
        mutateMaterializedTimeline = ::mutateMaterializedTimeline,
        syncChangedTimelineItems = ::syncChangedTimelineItems,
        onSetPendingExternalPlaybackModes = { sessionHydrator.setPendingExternalPlaybackModes(it) }
    )

    private val streamRecoveryCoordinator: PlaybackStreamRecoveryCoordinator = PlaybackStreamRecoveryCoordinator(
        scope = scope,
        dependencies = dependencies,
        getQueue = { _queue.value },
        onUpdateQueue = { _queue.value = it },
        getCurrentItem = { _currentItem.value },
        onSetCurrentItem = { item, persist, hint -> setCurrentItem(item, persistLastPlayed = persist, hint = hint) },
        getPlaybackPositionMs = { _playbackPositionMs.value },
        onSetPlaybackPositionMs = { _playbackPositionMs.value = it },
        onSetIsPlaying = { _isPlaying.value = it },
        isPlayWhenReadyIntent = { playWhenReadyIntent },
        onSetPlayWhenReadyIntent = { playWhenReadyIntent = it },
        getController = { controller },
        setLastMediaItemIndex = { lastMediaItemIndex = it },
        onEmitEvent = { _events.tryEmit(it) },
        onCancelPendingPlayIntent = ::cancelPendingPlayIntent,
        ensurePreparedForPlayback = ::ensurePreparedForPlayback,
        getPlaybackGeneration = { playbackGeneration }
    )

    val resolvingRemote: StateFlow<Boolean> = streamRecoveryCoordinator.resolvingRemote

    private val radioCoordinator = PlaybackRadioCoordinator(
        scope = scope,
        dependencies = dependencies,
        getCurrentItem = { _currentItem.value },
        getQueue = { _queue.value },
        getCurrentIndex = { controller?.currentMediaItemIndex ?: lastMediaItemIndex },
        getRepeatMode = { _repeatMode.value },
        isPlayWhenReadyIntent = { playWhenReadyIntent },
        canKeepCurrent = ::shouldKeepCurrentWhenStartingRadio,
        getLibrary = { library },
        onEmitEvent = { _events.tryEmit(it) },
        onClearDiscoverPlaybackOrigin = ::clearDiscoverPlaybackOrigin,
        onApplyRadioStartModes = ::applyRadioStartModes,
        onReplaceUpcomingWithRadio = ::replaceUpcomingWithRadio,
        onPlayPlayableCollection = { items, fromRadio, rotate ->
            playPlayableCollection(items, fromRadio = fromRadio, rotate = rotate)
        },
        onAddPlayableBatch = ::addPlayableBatch,
        onPrefetchAround = ::prefetchAround
    )
    val radioActive = radioCoordinator.radioActive
    val radioLoading = radioCoordinator.radioLoading
    val radioMode = radioCoordinator.radioMode
    val radioStatusLabel = radioCoordinator.radioStatusLabel
    private val _queueFocusEpoch = MutableStateFlow(0)
    val queueFocusEpoch = _queueFocusEpoch.asStateFlow()
    val saveWhileListeningDownloads: StateFlow<List<ActiveDownload>> =
        dependencies.saveDownloads.downloads

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    private val started = AtomicBoolean(false)
    private val uiAttachments = AtomicInteger(0)
    private var controller: PlaybackControllerFacade? = null
    private var controllerConnector: PlaybackControllerConnector? = null
    private var controllerFuture: PlaybackControllerConnection? = null
    private var controllerReconnectJob: Job? = null
    private var consecutiveControllerFailures = 0
    private var tickerJob: Job? = null
    private var library: List<Song> = emptyList()
    private val libraryReady = MutableStateFlow(false)

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
    private var lyricsHydrateJob: Job? = null
    private var lastSeekTimestamp = 0L

    private var playbackGeneration = 0L
    private var queueSelectionJob: Job? = null
    private var queueAppendJob: Job? = null
    private val selectionGate = PlaybackSelectionIntentGate()

    private val warmingUp = AtomicBoolean(false)

    init {
        start()
    }

    fun warmup() {
        if (controller != null || uiAttachments.get() > 0) return
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.warmup() (anticipating MediaController connection)"
        )
        warmingUp.set(true)
        ensureControllerConnection()
    }

    fun attachUi() {
        warmingUp.set(false)
        val count = uiAttachments.incrementAndGet()
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.attachUi() (uiAttachments=$count)"
        )
        ensureControllerConnection()
        maybeSeedIdlePlayer()
        val curSong = _currentSong.value
        if (curSong != null && curSong.lyrics.isNullOrEmpty()) {
            hydrateCurrentSongLyrics(curSong.id)
        }
        scope.launch { samplePositionAndOwnership() }
        updateTickerLifecycle()
    }

    fun detachUi() {
        val count = uiAttachments.updateAndGet { c -> (c - 1).coerceAtLeast(0) }
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.detachUi() (uiAttachments=$count)"
        )
        updateTickerLifecycle()
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

    internal suspend fun systemResumptionMetadataSnapshot(): PlaybackCollectionSnapshot? =
        sessionHydrator.systemResumptionMetadataSnapshot()

    internal suspend fun restoreSystemPlaybackSnapshot(): PlaybackCollectionSnapshot? =
        sessionHydrator.restoreSystemPlaybackSnapshot()

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

    private fun libraryUpdateContext() = try {
        val interceptor = scope.coroutineContext[ContinuationInterceptor]
        if (interceptor === Dispatchers.Main || interceptor === Dispatchers.Main.immediate) {
            Dispatchers.Default
        } else {
            EmptyCoroutineContext
        }
    } catch (_: IllegalStateException) {
        EmptyCoroutineContext
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(libraryUpdateContext()) {
            dependencies.libraryUpdates.collectLatest { songs ->
                library = songs
                val oldQueue = _queue.value
                val updated = refreshLocalQueueMetadata(oldQueue, songs)
                withContext(scope.coroutineContext) {
                    libraryReady.value = true
                    if (updated !== oldQueue) {
                        val isPlaying = _isPlaying.value || playWhenReadyIntent
                        val sortedByTrack = if (!isPlaying) {
                            reorderAlbumQueueByTrackNumber(updated, _isShuffle.value)
                        } else {
                            null
                        }
                        if (sortedByTrack != null) {
                            val currentSlot = _currentItem.value?.queueEntryId
                            val newIndex = sortedByTrack.indexOfFirst { it.queueEntryId == currentSlot }
                                .takeIf { it >= 0 } ?: 0
                            val position = _playbackPositionMs.value
                            applyQueueReorder(sortedByTrack, newIndex, position, isPlaying)
                            persistPlaybackSession(force = true)
                        } else {
                            _queue.value = updated
                            val currentSlot = _currentItem.value?.queueEntryId
                            updated.firstOrNull { it.queueEntryId == currentSlot }?.let {
                                setCurrentItem(
                                    it,
                                    persistLastPlayed = false,
                                    hint = PlaybackChangeHint.METADATA_UPDATE
                                )
                            }
                            syncChangedTimelineItems(oldQueue, updated)
                        }
                    }
                    maybeSeedIdlePlayer()
                }
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

    fun requestResumeAfterServiceRestart() {
        playWhenReadyIntent = true
        ensureControllerConnection()
        scope.launch {
            sessionHydrator.ensurePersistedSessionRestored()
            if (_currentItem.value != null) {
                requestPlaybackForCurrent()
            }
        }
    }

    internal fun onPlaybackStartedFromService() {
        if (controller == null) {
            playWhenReadyIntent = true
            ensureControllerConnection()
        }
    }

    private fun handleControllerDisconnected(disconnected: PlaybackControllerFacade) {
        if (controller !== disconnected) return
        controller = null
        timelineMaterialized = false
        sessionHydrator.liveSessionHydrated = false
        _isPlaying.value = false
        tickerJob?.cancel()
        tickerJob = null
        invalidatePlaybackWork(clearRejectedEntries = false)
        disconnected.release()
        if (playWhenReadyIntent && _currentItem.value != null) beginPendingPlayIntent()
        ensureControllerConnection()
    }

    fun onTaskRemovedNotEngaged() {
        PlaybackDiagnostics.warn(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.onTaskRemovedNotEngaged: task removed while not engaged, releasing controller"
        )
        warmingUp.set(false)
        uiAttachments.set(0)
        playWhenReadyIntent = false
        _isPlaying.value = false
        cancelPendingPlayIntent()
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
        sessionHydrator.liveSessionHydrated = false
        owned?.release()
    }

    private fun shouldRetainController(): Boolean =
        warmingUp.get() ||
                uiAttachments.get() > 0 ||
                playWhenReadyIntent ||
                _isPlaying.value

    private fun releaseControllerIfIdle() {
        val shouldRetain = shouldRetainController()
        if (shouldRetain) {
            PlaybackDiagnostics.log(
                PlaybackDiagnostics.TAG_RUNTIME,
                "PlaybackRuntime.releaseControllerIfIdle: RETAINING controller (uiAttachments=${uiAttachments.get()}, queueSize=${_queue.value.size}, playWhenReadyIntent=$playWhenReadyIntent, isPlaying=${_isPlaying.value})"
            )
            ensureControllerConnection()
            return
        }
        PlaybackDiagnostics.warn(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.releaseControllerIfIdle: RELEASING controller (idle, no UI, no playback)"
        )
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
        sessionHydrator.liveSessionHydrated = false
        owned?.release()
    }

    private fun postOrRunReleaseControllerIfIdle() {
        val posted = try {
            val looper = android.os.Looper.myLooper()
            if (looper != null) {
                android.os.Handler(looper).post {
                    releaseControllerIfIdle()
                }
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
        if (!posted) {
            releaseControllerIfIdle()
        }
    }

    private fun updateTickerLifecycle() {
        val shouldTick = dependencies.startTicker &&
                controller?.isPlaying == true &&
                uiAttachments.get() > 0
        if (!shouldTick) {
            tickerJob?.cancel()
            tickerJob = null
            return
        }
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive && controller?.isPlaying == true && uiAttachments.get() > 0) {
                samplePositionAndOwnership()
                delay(POSITION_TICK_MS)
            }
            tickerJob = null
        }
    }

    private val playerListener = object : PlaybackControllerFacade.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlaybackDiagnostics.log(
                PlaybackDiagnostics.TAG_RUNTIME,
                "PlaybackRuntime.playerListener.onIsPlayingChanged: isPlaying=$isPlaying"
            )
            _isPlaying.value = isPlaying
            if (isPlaying) {
                clearRemoteRecoveryAfterProgress()
                scope.launch { samplePositionAndOwnership() }
            } else {
                controller?.let { player ->
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    _playbackPositionMs.value = pos
                    if (pos > 0L) {
                        dependencies.listenTracker.creditPlaybackTime(pos)
                    }
                }
                dependencies.listenTracker.onStopped()
                persistPlaybackSession(force = true)
                triggerFlushPostponedTagWrites()
            }
            updateTickerLifecycle()
            if (!isPlaying) postOrRunReleaseControllerIfIdle()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
            PlaybackDiagnostics.log(
                PlaybackDiagnostics.TAG_RUNTIME,
                "PlaybackRuntime.playerListener.onPlayWhenReadyChanged: playWhenReady=$playWhenReady"
            )
            playWhenReadyIntent = playWhenReady
            if (playWhenReady) {
                pendingPlayIntentEpoch = null
                val index = currentQueueIndex()
                ensureRemoteReadyAt(index, startPlaying = true)
                prefetchAround(index)
            } else {
                cancelPendingPlayIntent()
                streamRecoveryCoordinator.invalidatePlaybackWork(clearRejectedEntries = false)
            }
        }

        override fun onPlayerError() {
            PlaybackDiagnostics.error(
                PlaybackDiagnostics.TAG_RUNTIME,
                "PlaybackRuntime.playerListener.onPlayerError() triggered"
            )
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
            radioCoordinator.maybeAutoStartRadioOnQueueEnd()
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
        sessionHydrator.liveSessionHydrated = true
        sessionHydrator.idleSeedDone = true
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
        if (suppressPlaylistMutationCallbacks || queueAppendJob?.isActive == true) return
        val player = controller ?: return
        val itemCount = player.mediaItemCount
        if (itemCount <= 0) {
            if (playWhenReadyIntent || pendingPlayIntentEpoch != null || timelineMaterialized) return
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
        val oldQueue = _queue.value
        val oldQueueEntryIds = oldQueue.map { it.queueEntryId }
        val newQueueEntryIds = rebuilt.map { it.queueEntryId }

        val isWindowOfOldQueue = oldQueue.size > INITIAL_PLAYBACK_WINDOW_SIZE &&
                newQueueEntryIds.size <= INITIAL_PLAYBACK_WINDOW_SIZE &&
                newQueueEntryIds.isNotEmpty() &&
                run {
                    val firstIndex = oldQueueEntryIds.indexOf(newQueueEntryIds.first())
                    firstIndex >= 0 &&
                            firstIndex + newQueueEntryIds.size <= oldQueueEntryIds.size &&
                            oldQueueEntryIds.subList(firstIndex, firstIndex + newQueueEntryIds.size) == newQueueEntryIds
                }
        val structureChanged = !isWindowOfOldQueue && oldQueueEntryIds != newQueueEntryIds
        if (structureChanged) invalidatePlaybackWork(clearRejectedEntries = false)

        val index = player.currentMediaItemIndex.coerceIn(rebuilt.indices)
        val targetQueueEntryId = rebuilt[index].queueEntryId

        val liveQueue = if (isWindowOfOldQueue) {
            oldQueue
        } else if (structureChanged) {
            rebuilt.map { rebuiltItem ->
                oldQueue.firstOrNull { it.queueEntryId == rebuiltItem.queueEntryId } ?: rebuiltItem
            }
        } else {
            oldQueue
        }

        val liveCurrentItem = liveQueue.firstOrNull { it.queueEntryId == targetQueueEntryId } ?: rebuilt[index]
        val occurrenceChanged = _currentItem.value?.queueEntryId != liveCurrentItem.queueEntryId

        _queue.value = liveQueue
        timelineMaterialized = true
        sessionHydrator.liveSessionHydrated = true
        lastMediaItemIndex = liveQueue.indexOfFirst { it.queueEntryId == targetQueueEntryId }
            .takeIf { it >= 0 } ?: index
        _playbackPositionMs.value = player.currentPosition.coerceAtLeast(0L)
        setCurrentItem(
            liveCurrentItem,
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
        val previous = _currentSong.value
        val displayed = when {
            local == null -> null
            previous?.id == local.id -> previous.keepLyricsIfIncomingSlim(local)
            else -> local
        }
        _currentSong.value = displayed
        dependencies.listenTracker.onTrackChanged(local, hint)
        if (displayed != null && displayed.lyrics.isNullOrEmpty() && uiAttachments.get() > 0) {
            hydrateCurrentSongLyrics(displayed.id)
        }
        if (persistLastPlayed) {
            persistPlaybackSession(force = true)
            if (occurrenceChanged) touchLastPlayed(local)
        }
    }

    fun hydrateCurrentSongLyrics(songId: Long) {
        lyricsHydrateJob?.cancel()
        lyricsHydrateJob = scope.launch(dependencies.ioDispatcher) {
            val full = dependencies.loadSongById(songId) ?: return@launch
            if (full.lyrics.isNullOrEmpty()) return@launch
            withContext(scope.coroutineContext) {
                applyLyricsToCurrent(songId, full.lyrics)
            }
        }
    }

    fun updateCurrentSongLyrics(songId: Long, lyrics: String?) {
        applyLyricsToCurrent(songId, lyrics)
    }

    fun updateCurrentItemLyrics(lyrics: String?) {
        val cur = _currentItem.value ?: return
        when (cur) {
            is PlayableItem.Local -> applyLyricsToCurrent(cur.song.id, lyrics)
            is PlayableItem.Remote -> _currentItem.value = cur.copy(lyrics = lyrics)
        }
    }

    private fun applyLyricsToCurrent(songId: Long, lyrics: String?) {
        val current = _currentSong.value
        if (current?.id == songId) {
            _currentSong.value = current.copy(lyrics = lyrics)
        }
        val curItem = _currentItem.value
        if (curItem is PlayableItem.Local && curItem.song.id == songId) {
            _currentItem.value = curItem.copy(song = curItem.song.copy(lyrics = lyrics))
        } else if (curItem is PlayableItem.Remote && songId < 0) {
            _currentItem.value = curItem.copy(lyrics = lyrics)
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

    private fun persistPlaybackSession(force: Boolean = true) {
        sessionHydrator.persistPlaybackSession(force)
    }

    private fun scheduleSeekPersistence() {
        sessionHydrator.scheduleSeekPersistence()
    }

    private fun restorePlaybackModes() {
        if (!dependencies.playbackSettingsReady.value) return
        val settings = dependencies.playbackSettings.value
        val player = controller
        val hasLiveSession = (player?.mediaItemCount ?: 0) > 0
        val liveRepeat = repeatModeFromPlayer(player?.repeatMode ?: Player.REPEAT_MODE_OFF)
        val resolved = PlaybackModeRestore.resolve(settings, hasLiveSession, liveRepeat)
        _isShuffle.value = resolved.shuffle
        _repeatMode.value = resolved.repeat
        if (resolved.applyRepeatToPlayer) applyRepeatModeToController(resolved.repeat)
        syncShuffleToPlayer()
    }

    private fun applyPendingExternalPlaybackModes() {
        val (shuffle, repeat) = sessionHydrator.consumePendingExternalPlaybackModes() ?: return
        _isShuffle.value = shuffle
        _repeatMode.value = repeat
        applyRepeatModeToController(repeat)
        syncShuffleToPlayer()
    }

    fun maybeSeedIdlePlayer() {
        sessionHydrator.maybeSeedIdlePlayer()
    }

    private fun applyHydratedQueue(hydrated: HydratedQueue, restoreShuffle: Boolean) {
        invalidatePlaybackWork()
        clearDiscoverPlaybackOrigin()
        val order = PlaybackQueueOrder.validPlayOrderOrNull(
            hydrated.shufflePlayOrder,
            hydrated.items.size
        )
        if (order != null && restoreShuffle) {
            queueCoordinator.preShuffleOrder = PlaybackQueueSlots.capturePreShuffleOrder(hydrated.items)
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
            queueCoordinator.preShuffleOrder = null
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
            items = items.ensureFreshQueueEntryIds(),
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
        radioCoordinator.clearRadioSession()
        clearDiscoverPlaybackOrigin()
        val staged = items.ensureFreshQueueEntryIds()
        val index = startIndex.coerceIn(staged.indices)
        stageQueueCore(staged, index)
        queueCoordinator.preShuffleOrder = null
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
        if (!fromRadio) radioCoordinator.clearRadioSession()
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
        if (fromRadio && radioActive.value && playWhenReadyIntent) {
            radioCoordinator.maybeRefillRadio(playingIndex)
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
            if (!fromRadio && (applyManualModes || !_isShuffle.value)) queueCoordinator.preShuffleOrder = null
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
        sessionHydrator.liveSessionHydrated = true
        sessionHydrator.idleSeedDone = true
        bumpQueueFocus()
        val position = startPositionMs.coerceAtLeast(0L)
        _playbackPositionMs.value = position
        pendingNewPlaybackQueueEntryId = items[index].queueEntryId
        return PlaybackCollectionSnapshot(items, index, position)
    }

    fun togglePlayPause() {
        val player = controller
        if (playWhenReadyIntent || pendingPlayIntentEpoch != null) {
            playWhenReadyIntent = false
            cancelPendingPlayIntent()
            streamRecoveryCoordinator.invalidatePlaybackWork(clearRejectedEntries = false)
            player?.pause()
            return
        }
        ensurePlaying()
    }

    fun seekToAndPlay(positionMs: Long) {
        seekTo(positionMs)
        ensurePlaying()
    }

    private fun ensurePlaying() {
        _currentItem.value ?: return
        if (playWhenReadyIntent || pendingPlayIntentEpoch != null) return
        playWhenReadyIntent = true
        if (controller == null) {
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
        if (!hasMaterializedTimeline() || player.mediaItemCount != items.size || player.hasPlayerError) {
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
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            player.prepare()
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
        queueCoordinator.toggleRepeatMode()
    }

    fun toggleShuffle() {
        queueCoordinator.toggleShuffle()
    }

    fun addPlayableBatch(items: List<PlayableItem>) {
        queueCoordinator.addPlayableBatch(items)
    }

    fun playNextBatch(items: List<PlayableItem>) {
        queueCoordinator.playNextBatch(items)
    }

    fun updateAlbumArtworkInQueue(albumKey: String, artworkUri: String?) {
        queueCoordinator.updateAlbumArtworkInQueue(albumKey, artworkUri)
    }

    fun removeFromQueue(queueEntryId: String): Boolean =
        queueCoordinator.removeFromQueue(queueEntryId)

    fun removeFromQueue(index: Int) {
        queueCoordinator.removeFromQueue(index)
    }

    fun clearQueue() {
        queueCoordinator.clearQueue()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        queueCoordinator.moveQueueItem(fromIndex, toIndex)
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
            streamRecoveryCoordinator.beginResolving()
            try {
                val online = dependencies.isOnline()
                for (step in PlaybackFallbackPlanner.circularPlan(snapshot, start)) {
                    if (!selectionGate.isCurrent(token) ||
                        !isPlaybackGenerationCurrent(generation)
                    ) {
                        return@launch
                    }
                    val liveIndex = _queue.value.indexOfQueueEntry(step.item)
                    if (liveIndex < 0) continue
                    when (val live = _queue.value.getOrNull(liveIndex) ?: continue) {
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
                            val needsResolve = dependencies.streamAccess.needsResolve(live)
                            if (needsResolve && !online) {
                                continue
                            }
                            val ready = if (needsResolve) {
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
                    val message = if (!online) {
                        "Sin conexión a internet"
                    } else {
                        "No se pudo resolver el audio online"
                    }
                    _events.tryEmit(message)
                }
            } finally {
                if (pendingPlayIntentEpoch == playIntent) pendingPlayIntentEpoch = null
                streamRecoveryCoordinator.endResolving()
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
                fromRadio = radioActive.value,
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

        if (wrappedShuffleCycle) {
            val previous = _currentItem.value
            creditItemPlayback(previous, completed = true)
            (previous as? PlayableItem.Remote)?.let { outgoing ->
                maybeSaveWhileListening(
                    outgoing,
                    SaveWhileListeningEvent.AUTOMATIC_TRANSITION,
                    _playbackPositionMs.value
                )
            }
            _playbackPositionMs.value = 0L
            invalidatePlaybackWork(clearRejectedEntries = false)

            val avoid = _queue.value.getOrNull(lastMediaItemIndex)?.queueEntryId
            val reshuffled = PlaybackQueueOrder.reshuffleItemsAvoidingKey(
                items = _queue.value,
                avoidKey = avoid,
                keySelector = { it.queueEntryId }
            )
            _queue.value = reshuffled
            suppressShuffleWrapDetection = true
            try {
                reloadPlayerTimeline(
                    reshuffled,
                    0,
                    0L,
                    startPlaying = playWhenReadyIntent
                )
                setCurrentItem(
                    reshuffled[0],
                    persistLastPlayed = false,
                    hint = PlaybackChangeHint.NEW_PLAYBACK
                )
                lastMediaItemIndex = 0
            } finally {
                suppressShuffleWrapDetection = false
            }
            ensurePreparedForPlayback()
            if (playWhenReadyIntent) {
                val remoteItem = reshuffled[0] as? PlayableItem.Remote
                if (remoteItem != null && (remoteItem.resolved == null || remoteItem.resolved.audioUrl.isBlank())) {
                    ensureRemoteReadyAt(0, startPlaying = true)
                }
                prefetchAround(0)
            }
            if (radioActive.value) {
                radioCoordinator.rememberRadioPlayed(reshuffled[0])
                if (playWhenReadyIntent) radioCoordinator.maybeRefillRadio(0)
            }
            persistPlaybackSession(force = true)
            return
        }

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
                creditItemPlayback(
                    previous,
                    completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                )
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
                triggerFlushPostponedTagWrites()
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
                val remoteItem = playable as? PlayableItem.Remote
                if (remoteItem != null && (remoteItem.resolved == null || remoteItem.resolved.audioUrl.isBlank())) {
                    ensureRemoteReadyAt(newIndex, startPlaying = true)
                }
                prefetchAround(newIndex)
            }
            if (radioActive.value) {
                radioCoordinator.rememberRadioPlayed(playable)
                if (playWhenReadyIntent) radioCoordinator.maybeRefillRadio(newIndex)
            }
        } else {
            dependencies.listenTracker.onTrackChanged(null, PlaybackChangeHint.METADATA_UPDATE)
        }
        lastMediaItemIndex = newIndex
    }

    private fun creditItemPlayback(item: PlayableItem?, completed: Boolean = false) {
        val playedMs = if (completed && item != null && item.durationMs > 0L) {
            item.durationMs
        } else {
            _playbackPositionMs.value
        }
        if (playedMs > 0L) {
            dependencies.listenTracker.creditPlaybackTime(playedMs)
        }
    }

    private fun ensureRemoteReadyAt(index: Int, startPlaying: Boolean) {
        streamRecoveryCoordinator.ensureRemoteReadyAt(index, startPlaying)
    }

    private fun prefetchAround(index: Int) {
        streamRecoveryCoordinator.prefetchAround(index)
    }

    private fun applyResolvedRemote(
        original: PlayableItem.Remote,
        resolved: PlayableItem.Remote
    ): Int = streamRecoveryCoordinator.applyResolvedRemote(original, resolved)

    private fun handlePlayerError() {
        streamRecoveryCoordinator.handlePlayerError()
    }

    private fun cancelRemoteRecoveryJob() {
        streamRecoveryCoordinator.cancelRemoteRecoveryJob()
    }

    private fun clearRemoteRecovery() {
        streamRecoveryCoordinator.clearRemoteRecovery()
    }

    private fun clearRemoteRecoveryAfterProgress() {
        streamRecoveryCoordinator.clearRemoteRecoveryAfterProgress()
    }

    fun isSongActiveInPlayback(songId: Long): Boolean {
        val current = _currentItem.value
        if (current is PlayableItem.Local && current.song.id == songId) {
            return true
        }
        val q = _queue.value
        if (q.isNotEmpty()) {
            val currentIndex = currentQueueIndex()
            for (offset in -1..1) {
                val idx = currentIndex + offset
                if (idx in q.indices) {
                    val item = q[idx]
                    if (item is PlayableItem.Local && item.song.id == songId) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun triggerFlushPostponedTagWrites() {
        val activeId = (_currentItem.value as? PlayableItem.Local)?.song?.id
        scope.launch(dependencies.ioDispatcher) {
            dependencies.flushPostponedTagWrites(activeId)
        }
    }

    private fun ensurePreparedForPlayback() {
        val player = controller ?: return
        if (player.mediaItemCount == 0) return
        val remote = _queue.value.getOrNull(player.currentMediaItemIndex) as? PlayableItem.Remote
        if (remote != null && (remote.resolved == null || remote.resolved.audioUrl.isBlank())) return
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED || player.hasPlayerError) {
            player.prepare()
        }
    }

    private fun restartAsyncPlaybackWork() {
        val queue = _queue.value
        if (!playWhenReadyIntent || queue.isEmpty()) return
        val index = currentQueueIndex().coerceIn(queue.indices)
        val current = queue.getOrNull(index) ?: return
        if (current is PlayableItem.Remote &&
            streamRecoveryCoordinator.remoteRecoveryQueueEntryId == current.queueEntryId
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
                streamRecoveryCoordinator.clearRejectedQueueEntries()
            }
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
        streamRecoveryCoordinator.maybeSaveWhileListening(remote, event, positionMs, durationMs)
    }

    fun setRadioPreferredMode(mode: RadioMode) {
        radioCoordinator.setRadioPreferredMode(mode)
    }

    fun preferredRadioModeOrNull(): RadioMode? = radioCoordinator.preferredRadioModeOrNull()

    fun stopRadio() {
        radioCoordinator.stopRadio()
    }

    fun startRadio(
        seedSong: Song? = null,
        mode: RadioMode? = null,
        auto: Boolean = false,
        announceMode: Boolean = false
    ) {
        radioCoordinator.startRadio(seedSong, mode, auto, announceMode)
    }

    internal suspend fun suggestRadioWithRetry(
        request: PlaybackRuntimeRadioRequest
    ): RadioSuggestResult = radioCoordinator.suggestRadioWithRetry(request)

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

    private fun clearDiscoverPlaybackOrigin() {
        if (_discoverPlaybackOrigin.value != DiscoverPlaybackOrigin.None) {
            _discoverPlaybackOrigin.value = DiscoverPlaybackOrigin.None
        }
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
        if (!startPlaying || (newPlayback && player.isPlaying)) player.pause()

        queueAppendJob?.cancel()
        queueAppendJob = null

        val useWindow = items.size > INITIAL_PLAYBACK_WINDOW_SIZE
        val windowStart = if (useWindow) (validIndex - 10).coerceAtLeast(0) else 0
        val windowEnd =
            if (useWindow) (windowStart + INITIAL_PLAYBACK_WINDOW_SIZE).coerceAtMost(items.size) else items.size
        val initialItems = if (useWindow) items.subList(windowStart, windowEnd) else items
        val initialIndex = validIndex - windowStart

        suppressPlaylistMutationCallbacks = true
        try {
            player.setMediaItems(
                initialItems,
                initialIndex,
                startPositionMs.coerceAtLeast(0L)
            )
        } finally {
            suppressPlaylistMutationCallbacks = false
        }
        timelineMaterialized = true
        lastMediaItemIndex = validIndex
        val remote = items[validIndex] as? PlayableItem.Remote
        if (remote != null && dependencies.streamAccess.needsResolve(remote)) {
            if (startPlaying) ensureRemoteReadyAt(validIndex, startPlaying = true)
        } else {
            player.prepare()
            if (startPlaying) prefetchAround(validIndex)
        }
        if (startPlaying) {
            pendingPlayIntentEpoch = null
            player.play()
        }
        syncShuffleToPlayer()
        updateTickerLifecycle()

        if (useWindow) {
            val generation = playbackGeneration
            queueAppendJob = scope.launch(Dispatchers.Default) {
                var addedAny = false
                if (windowEnd < items.size) {
                    val tail = items.subList(windowEnd, items.size)
                    for (chunk in tail.chunked(QUEUE_APPEND_CHUNK_SIZE)) {
                        if (!isActive || !isPlaybackGenerationCurrent(generation)) break
                        delay(40L)
                        withContext(Dispatchers.Main.immediate) {
                            if (hasMaterializedTimeline() && isPlaybackGenerationCurrent(generation)) {
                                mutateMaterializedTimeline(syncShuffle = false) { it.addMediaItems(chunk) }
                                addedAny = true
                            }
                        }
                    }
                }
                if (windowStart > 0) {
                    val head = items.subList(0, windowStart)
                    var insertIndex = 0
                    for (chunk in head.chunked(QUEUE_APPEND_CHUNK_SIZE)) {
                        if (!isActive || !isPlaybackGenerationCurrent(generation)) break
                        delay(40L)
                        withContext(Dispatchers.Main.immediate) {
                            if (hasMaterializedTimeline() && isPlaybackGenerationCurrent(generation)) {
                                mutateMaterializedTimeline(syncShuffle = false) { it.addMediaItems(insertIndex, chunk) }
                                insertIndex += chunk.size
                                addedAny = true
                            }
                        }
                    }
                }
                if (addedAny && isActive && isPlaybackGenerationCurrent(generation)) {
                    withContext(Dispatchers.Main.immediate) {
                        if (hasMaterializedTimeline() && isPlaybackGenerationCurrent(generation)) {
                            syncShuffleToPlayer()
                        }
                    }
                }
            }
        }
    }

    private fun hasMaterializedTimeline(): Boolean =
        timelineMaterialized && (controller?.mediaItemCount ?: 0) > 0

    private fun mutateMaterializedTimeline(
        syncShuffle: Boolean = true,
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
        if (syncShuffle) {
            syncShuffleToPlayer()
        }
    }

    private fun syncChangedTimelineItems(oldQueue: List<PlayableItem>, newQueue: List<PlayableItem>) {
        mutateMaterializedTimeline(syncShuffle = false) { player ->
            newQueue.forEachIndexed { index, item ->
                if (item !== oldQueue.getOrNull(index)) {
                    player.replaceMediaItem(index, item)
                }
            }
        }
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
    ): Pair<List<PlayableItem>, Int> =
        queueCoordinator.permuteQueueToPlayOrder(items, currentIndex, backupSource)

    private fun preShuffleQueueOrNull(): List<PlayableItem>? =
        queueCoordinator.preShuffleQueueOrNull()

    private fun setShuffleEnabled(enabled: Boolean) {
        queueCoordinator.setShuffleEnabled(enabled)
    }

    private fun disableShuffleRestoringOrder() {
        queueCoordinator.disableShuffleRestoringOrder()
    }

    private fun syncShuffleToPlayer() {
        queueCoordinator.syncShuffleToPlayer()
    }

    fun setRepeatMode(mode: RepeatMode) {
        queueCoordinator.setRepeatMode(mode)
    }

    private fun applyRepeatModeToController(mode: RepeatMode) {
        queueCoordinator.applyRepeatModeToController(mode)
    }

    private fun applyManualPlayModes(deferPlayerSync: Boolean = false) {
        queueCoordinator.applyManualPlayModes(deferPlayerSync)
    }

    private fun applySkipModes() {
        queueCoordinator.applySkipModes()
    }

    private fun applyRadioStartModes() {
        queueCoordinator.applyRadioStartModes()
    }

    private fun repeatModeFromPlayer(value: Int): RepeatMode =
        queueCoordinator.repeatModeFromPlayer(value)

    private fun invalidateQueueSelection() {
        selectionGate.invalidate()
        queueSelectionJob?.cancel()
        queueSelectionJob = null
    }

    private fun invalidatePlaybackWork(clearRejectedEntries: Boolean = true) {
        playbackGeneration++
        streamRecoveryCoordinator.invalidatePlaybackWork(clearRejectedEntries)
        queueAppendJob?.cancel()
        queueAppendJob = null
        invalidateQueueSelection()
        sessionHydrator.cancelSeekPersistence()
    }

    private fun isPlaybackGenerationCurrent(generation: Long): Boolean =
        generation == playbackGeneration

    private fun bumpQueueFocus() {
        _queueFocusEpoch.value += 1
    }

    companion object {
        private const val POSITION_TICK_MS = 200L
        private const val FALLBACK_SUCCESS_POSITION_MS = 1_000L
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
                    loadSongById = repository::getSongById,
                    loadSongsByIds = repository::getSongsByIds,
                    requestListenSync = sync::requestSync,
                    flushPostponedTagWrites = { activeId -> repository.flushPostponedTagWrites(activeId) }
                )
            )
            repository.isSongActiveInPlayback = { songId -> runtime.isSongActiveInPlayback(songId) }
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
