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

    private val controllerLifecycleManager: PlaybackControllerLifecycleManager = PlaybackControllerLifecycleManager(
        scope = scope,
        dependencies = dependencies,
        getPlayWhenReadyIntent = { playWhenReadyIntent },
        getIsPlaying = { _isPlaying.value },
        getQueueSize = { _queue.value.size },
        emitEvent = { _events.tryEmit(it) },
        getPlayerListener = { playerListener },
        onControllerAttached = ::onControllerAttached,
        onControllerDisconnectedCleanup = ::onControllerDisconnectedCleanup,
        onTaskRemovedCleanup = ::onTaskRemovedCleanup,
        onIdleReleasedCleanup = ::onIdleReleasedCleanup,
        samplePositionAndOwnership = ::samplePositionAndOwnership
    )

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
        getUiAttachments = { controllerLifecycleManager.uiAttachmentCount },
        isPlayWhenReadyIntent = { playWhenReadyIntent },
        hasController = { controllerLifecycleManager.isControllerConnected },
        getControllerMediaItemCount = { controller?.mediaItemCount ?: 0 },
        onClearDiscoverPlaybackOrigin = ::clearDiscoverPlaybackOrigin,
        onSetCurrentItem = { item, persist -> setCurrentItem(item, persistLastPlayed = persist) },
        onSetPlaybackPositionMs = { _playbackPositionMs.value = it },
        onSetIsPlaying = { _isPlaying.value = it },
        onApplyHydratedQueue = ::applyHydratedQueue,
        onTogglePlayPause = ::togglePlayPause
    )

    private val timelineSynchronizer = PlaybackTimelineSynchronizer(
        scope = scope,
        dependencies = dependencies,
        getController = { controller },
        getQueue = { _queue.value },
        setQueue = { _queue.value = it },
        getCurrentItem = { _currentItem.value },
        setCurrentItem = { item, persist, hint -> setCurrentItem(item, persistLastPlayed = persist, hint = hint) },
        setPlaybackPositionMs = { _playbackPositionMs.value = it },
        getLastMediaItemIndex = { lastMediaItemIndex },
        setLastMediaItemIndex = { lastMediaItemIndex = it },
        getPlayWhenReadyIntent = { playWhenReadyIntent },
        setPlayWhenReadyIntent = { playWhenReadyIntent = it },
        getPendingPlayIntentEpoch = { pendingPlayIntentEpoch },
        cancelPendingPlayIntent = ::cancelPendingPlayIntent,
        setPendingNewPlaybackQueueEntryId = { pendingNewPlaybackQueueEntryId = it },
        getPlaybackGeneration = { playbackGeneration },
        isPlaybackGenerationCurrent = ::isPlaybackGenerationCurrent,
        ensureRemoteReadyAt = ::ensureRemoteReadyAt,
        prefetchAround = ::prefetchAround,
        syncShuffleToPlayer = ::syncShuffleToPlayer,
        updateTickerLifecycle = ::updateTickerLifecycle,
        invalidatePlaybackWork = ::invalidatePlaybackWork,
        clearDiscoverPlaybackOrigin = ::clearDiscoverPlaybackOrigin,
        persistPlaybackSession = { persistPlaybackSession(it) },
        applyPendingExternalPlaybackModes = ::applyPendingExternalPlaybackModes,
        restartAsyncPlaybackWork = ::restartAsyncPlaybackWork,
        setLiveSessionHydrated = { sessionHydrator.liveSessionHydrated = it }
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
        setTimelineMaterialized = { timelineSynchronizer.timelineMaterialized = it },
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

    private val analyticsCoordinator = PlaybackAnalyticsCoordinator(
        scope = scope,
        dependencies = dependencies,
        getCurrentItem = { _currentItem.value },
        setCurrentItem = { item, persist, hint -> setCurrentItem(item, persistLastPlayed = persist, hint = hint) },
        getCurrentSong = { _currentSong.value },
        setCurrentSong = { _currentSong.value = it },
        getQueue = { _queue.value },
        setQueue = { _queue.value = it },
        getController = { controller },
        getIsPlaying = { _isPlaying.value },
        setIsPlaying = { _isPlaying.value = it },
        getPlaybackPositionMs = { _playbackPositionMs.value },
        setPlaybackPositionMs = { _playbackPositionMs.value = it },
        getLastSeekTimestamp = { lastSeekTimestamp },
        clearRemoteRecoveryAfterProgress = ::clearRemoteRecoveryAfterProgress,
        clearRejectedQueueEntries = { streamRecoveryCoordinator.clearRejectedQueueEntries() },
        maybeSaveWhileListening = ::maybeSaveWhileListening
    )

    private val _queueFocusEpoch = MutableStateFlow(0)
    val queueFocusEpoch = _queueFocusEpoch.asStateFlow()
    val saveWhileListeningDownloads: StateFlow<List<ActiveDownload>> =
        dependencies.saveDownloads.downloads

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    private val started = AtomicBoolean(false)
    internal val controller: PlaybackControllerFacade?
        get() = controllerLifecycleManager.controller

    private var library: List<Song> = emptyList()
    private val libraryReady = MutableStateFlow(false)

    private var lastMediaItemIndex = -1
    private var lastKnownQueueEntryId: String? = null
    private var timelineMaterialized: Boolean
        get() = timelineSynchronizer.timelineMaterialized
        set(value) {
            timelineSynchronizer.timelineMaterialized = value
        }
    private var playWhenReadyIntent = false
    private var suppressPlaylistMutationCallbacks: Boolean
        get() = timelineSynchronizer.suppressPlaylistMutationCallbacks
        set(value) {
            timelineSynchronizer.suppressPlaylistMutationCallbacks = value
        }
    private var suppressShuffleWrapDetection = false
    private var pendingNewPlaybackQueueEntryId: String? = null
    private var playbackIntentEpoch = 0L
    private var pendingPlayIntentEpoch: Long? = null
    private var lastSeekTimestamp = 0L

    private var playbackGeneration = 0L
    private var queueSelectionJob: Job? = null
    private val selectionGate = PlaybackSelectionIntentGate()

    init {
        start()
    }

    fun warmup() {
        if (controller != null || controllerLifecycleManager.uiAttachmentCount > 0) return
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.warmup() (anticipating MediaController connection)"
        )
        controllerLifecycleManager.warmUpController()
    }

    fun attachUi() {
        controllerLifecycleManager.attachUi()
        maybeSeedIdlePlayer()
        val curSong = _currentSong.value
        if (curSong != null && curSong.lyrics.isNullOrEmpty()) {
            hydrateCurrentSongLyrics(curSong.id)
        }
        scope.launch { samplePositionAndOwnership() }
    }

    fun detachUi() {
        controllerLifecycleManager.detachUi()
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
        controllerLifecycleManager.attachControllerForTest(controller)
    }

    internal fun connectForTest(connector: PlaybackControllerConnector) {
        configureControllerConnector(connector)
    }

    internal val controllerConnectedForTest: Boolean
        get() = controllerLifecycleManager.isControllerConnected

    internal val tickerActiveForTest: Boolean
        get() = controllerLifecycleManager.isTickerActive

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
        controllerLifecycleManager.configureControllerConnector(connector)
    }

    private fun ensureControllerConnection() {
        controllerLifecycleManager.ensureControllerConnection()
    }

    private fun onControllerAttached(newController: PlaybackControllerFacade) {
        timelineSynchronizer.timelineMaterialized = false
        syncFromController()
        restorePlaybackModes()
        maybeSeedIdlePlayer()
        if (playWhenReadyIntent && _currentItem.value != null) {
            requestPlaybackForCurrent()
        }
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

    private fun onControllerDisconnectedCleanup(disconnected: PlaybackControllerFacade) {
        timelineSynchronizer.timelineMaterialized = false
        sessionHydrator.liveSessionHydrated = false
        _isPlaying.value = false
        invalidatePlaybackWork(clearRejectedEntries = false)
        if (playWhenReadyIntent && _currentItem.value != null) beginPendingPlayIntent()
    }

    private fun onTaskRemovedCleanup() {
        playWhenReadyIntent = false
        _isPlaying.value = false
        cancelPendingPlayIntent()
        timelineSynchronizer.timelineMaterialized = false
        sessionHydrator.liveSessionHydrated = false
    }

    private fun onIdleReleasedCleanup() {
        timelineSynchronizer.timelineMaterialized = false
        sessionHydrator.liveSessionHydrated = false
    }

    fun onTaskRemovedNotEngaged() {
        controllerLifecycleManager.onTaskRemovedNotEngaged()
    }

    private fun releaseControllerIfIdle() {
        controllerLifecycleManager.releaseControllerIfIdle()
    }

    private fun postOrRunReleaseControllerIfIdle() {
        controllerLifecycleManager.postOrRunReleaseControllerIfIdle()
    }

    private fun updateTickerLifecycle() {
        controllerLifecycleManager.updateTickerLifecycle()
    }

    private val playerListener: PlaybackControllerFacade.Listener = object : PlaybackControllerFacade.Listener {
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
            scope.launch { controllerLifecycleManager.handleControllerDisconnected(controller) }
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
        timelineSynchronizer.reconcileTimelineFromController()
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
        if (displayed != null && displayed.lyrics.isNullOrEmpty() && controllerLifecycleManager.uiAttachmentCount > 0) {
            hydrateCurrentSongLyrics(displayed.id)
        }
        if (persistLastPlayed) {
            persistPlaybackSession(force = true)
            if (occurrenceChanged) touchLastPlayed(item)
        }
    }

    fun hydrateCurrentSongLyrics(songId: Long) {
        analyticsCoordinator.hydrateCurrentSongLyrics(songId)
    }

    fun updateCurrentSongLyrics(songId: Long, lyrics: String?) {
        analyticsCoordinator.updateCurrentSongLyrics(songId, lyrics)
    }

    fun updateCurrentItemLyrics(lyrics: String?) {
        analyticsCoordinator.updateCurrentItemLyrics(lyrics)
    }

    private fun applyLyricsToCurrent(songId: Long, lyrics: String?) {
        analyticsCoordinator.applyLyricsToCurrent(songId, lyrics)
    }

    private fun touchLastPlayed(item: PlayableItem?, force: Boolean = false) {
        analyticsCoordinator.touchLastPlayed(item, force = force)
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
        if (startPlaying) {
            touchLastPlayed(snapshot.currentItem, force = true)
        }
        if (controller == null) {
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
        analyticsCoordinator.creditItemPlayback(item, completed)
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
        analyticsCoordinator.triggerFlushPostponedTagWrites()
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
        analyticsCoordinator.samplePositionAndOwnership()
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
        timelineSynchronizer.reloadPlayerTimeline(items, startIndex, startPositionMs, startPlaying, newPlayback)
    }

    private fun hasMaterializedTimeline(): Boolean =
        timelineSynchronizer.hasMaterializedTimeline()

    private fun mutateMaterializedTimeline(
        syncShuffle: Boolean = true,
        mutation: (PlaybackControllerFacade) -> Unit
    ) {
        timelineSynchronizer.mutateMaterializedTimeline(syncShuffle, mutation)
    }

    private fun syncChangedTimelineItems(oldQueue: List<PlayableItem>, newQueue: List<PlayableItem>) {
        timelineSynchronizer.syncChangedTimelineItems(oldQueue, newQueue)
    }

    private fun rebuildPlayerQueueAroundCurrent(newOrder: List<PlayableItem>): Boolean =
        timelineSynchronizer.rebuildPlayerQueueAroundCurrent(newOrder)

    private fun applyQueueReorder(
        newOrder: List<PlayableItem>,
        focusIndex: Int,
        positionMs: Long,
        startPlaying: Boolean
    ) {
        timelineSynchronizer.applyQueueReorder(newOrder, focusIndex, positionMs, startPlaying)
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
        timelineSynchronizer.cancelQueueAppend()
        invalidateQueueSelection()
        sessionHydrator.cancelSeekPersistence()
    }

    private fun isPlaybackGenerationCurrent(generation: Long): Boolean =
        generation == playbackGeneration

    private fun bumpQueueFocus() {
        _queueFocusEpoch.value += 1
    }

    companion object {
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
                    touchItemLastPlayed = repository::touchItemLastPlayed,
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
