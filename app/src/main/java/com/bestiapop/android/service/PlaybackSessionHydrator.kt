package com.bestiapop.android.service

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.data.playback.PlaybackQueueOrder
import com.bestiapop.android.data.playback.PlaybackQueueSlots
import com.bestiapop.android.data.preferences.HydratedQueue
import com.bestiapop.android.data.preferences.LastPlayedSnapshot
import com.bestiapop.android.data.preferences.PersistedQueueItem
import com.bestiapop.android.data.preferences.PlaybackHydration
import com.bestiapop.android.data.preferences.PlaybackModeRestore
import com.bestiapop.android.data.preferences.QueueSnapshot
import com.bestiapop.android.data.preferences.QueueSnapshotCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class PlaybackSessionHydrator(
    private val scope: CoroutineScope,
    private val dependencies: PlaybackRuntimeDependencies,
    private val getCurrentItem: () -> PlayableItem?,
    private val getQueue: () -> List<PlayableItem>,
    private val getPlaybackPositionMs: () -> Long,
    private val getRepeatMode: () -> RepeatMode,
    private val isShuffle: () -> Boolean,
    private val getPreShuffleOrder: () -> List<String>?,
    private val getCurrentQueueIndex: () -> Int,
    private val isLibraryReady: () -> Boolean,
    private val getLibrary: () -> List<Song>,
    private val getUiAttachments: () -> Int,
    private val isPlayWhenReadyIntent: () -> Boolean,
    private val hasController: () -> Boolean,
    private val getControllerMediaItemCount: () -> Int,
    private val onClearDiscoverPlaybackOrigin: () -> Unit,
    private val onSetCurrentItem: (PlayableItem?, persistLastPlayed: Boolean) -> Unit,
    private val onSetPlaybackPositionMs: (Long) -> Unit,
    private val onSetIsPlaying: (Boolean) -> Unit,
    private val onApplyHydratedQueue: (hydrated: HydratedQueue, restoreShuffle: Boolean) -> Unit,
    private val onTogglePlayPause: () -> Unit
) {
    var persistedSessionRestored: Boolean = false
        internal set
    var idleSeedDone: Boolean = false
        internal set
    var liveSessionHydrated: Boolean = false
        internal set
    var autoplaySeedApplied: Boolean = false
        internal set

    private var pendingExternalPlaybackModes: Pair<Boolean, RepeatMode>? = null
    private val sessionRestoreMutex = Mutex()
    private var lastPersistedPositionAtMs = 0L
    private var seekPersistenceJob: Job? = null
    private val persistenceRequests = Channel<PlaybackPersistenceRequest>(Channel.UNLIMITED)

    init {
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
    }

    fun consumePendingExternalPlaybackModes(): Pair<Boolean, RepeatMode>? {
        val modes = pendingExternalPlaybackModes ?: return null
        pendingExternalPlaybackModes = null
        return modes
    }

    fun setPendingExternalPlaybackModes(modes: Pair<Boolean, RepeatMode>?) {
        pendingExternalPlaybackModes = modes
    }

    fun cancelSeekPersistence() {
        seekPersistenceJob?.cancel()
        seekPersistenceJob = null
    }

    fun scheduleSeekPersistence() {
        seekPersistenceJob?.cancel()
        seekPersistenceJob = scope.launch {
            delay(SEEK_PERSIST_DEBOUNCE_MS)
            captureAndQueuePlaybackSession(force = true)
            seekPersistenceJob = null
        }
    }

    fun persistPlaybackSession(force: Boolean = true) {
        scope.launch { captureAndQueuePlaybackSession(force) }
    }

    private fun captureAndQueuePlaybackSession(force: Boolean) {
        val now = dependencies.clockMs()
        if (!force && now - lastPersistedPositionAtMs < POSITION_SAVE_INTERVAL_MS) return
        lastPersistedPositionAtMs = now
        val position = getPlaybackPositionMs()
        val local = (getCurrentItem() as? PlayableItem.Local)?.song
        val items = getQueue()
        val index = getCurrentQueueIndex()
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

    private fun queueSnapshotForPersist(
        items: List<PlayableItem>,
        index: Int,
        positionMs: Long
    ): QueueSnapshot {
        val projection = PlaybackQueueSlots.projectSnapshot(
            queue = items,
            currentIndex = index,
            preShuffleOrder = getPreShuffleOrder().takeIf { isShuffle() }
        )
        return QueueSnapshotCodec.fromPlayable(
            items = projection.items,
            currentIndex = projection.currentIndex,
            positionMs = positionMs,
            shufflePlayOrder = projection.shufflePlayOrder
        )
    }

    fun currentRuntimeSnapshot(): PlaybackCollectionSnapshot? {
        val queue = getQueue()
        if (queue.isNotEmpty()) {
            val currentQueueEntryId = getCurrentItem()?.queueEntryId
            val index = queue.indexOfFirst { it.queueEntryId == currentQueueEntryId }
                .takeIf { it >= 0 }
                ?: getCurrentQueueIndex().coerceIn(queue.indices)
            return PlaybackCollectionSnapshot(
                items = queue,
                currentIndex = index,
                positionMs = getPlaybackPositionMs()
            )
        }
        val current = getCurrentItem() ?: return null
        return PlaybackCollectionSnapshot(
            items = listOf(current),
            currentIndex = 0,
            positionMs = getPlaybackPositionMs()
        )
    }

    suspend fun loadPersistedCollectionProjection(): PersistedCollectionProjection? {
        val last = dependencies.persistence.loadLastPlayed()
        val persistedQueue = dependencies.persistence.loadQueue()
        val hydrationSongs = songsForHydration(persistedQueue, last)
        val hydrated = PlaybackHydration.hydrateQueue(persistedQueue, hydrationSongs)
        if (hydrated != null && hydrated.items.isNotEmpty()) {
            val restoreShuffle = PlaybackModeRestore
                .resolve(
                    dependencies.playbackSettings.value,
                    hasLiveSession = false,
                    liveRepeat = getRepeatMode()
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
        val seed = PlaybackHydration.resolveIdleSeed(
            getLibrary().ifEmpty { hydrationSongs },
            last
        ) ?: return null
        return PersistedCollectionProjection(
            snapshot = PlaybackCollectionSnapshot(
                items = listOf(seed.toPlayable()),
                currentIndex = 0,
                positionMs = PlaybackHydration.resumePositionMs(seed, last)
            )
        )
    }

    private suspend fun songsForHydration(
        queue: QueueSnapshot?,
        last: LastPlayedSnapshot?
    ): List<Song> {
        val library = getLibrary()
        if (library.isNotEmpty()) return library
        val ids = LinkedHashSet<Long>()
        last?.songId?.takeIf { it > 0L }?.let { ids.add(it) }
        queue?.items?.forEach { item ->
            if (item is PersistedQueueItem.Local && item.songId > 0L) {
                ids.add(item.songId)
            }
        }
        if (ids.isEmpty()) return emptyList()
        return dependencies.loadSongsByIds(ids.toList())
    }

    suspend fun ensurePersistedSessionRestoredLocked(): PlaybackCollectionSnapshot? {
        currentRuntimeSnapshot()?.let { return it }
        if (idleSeedDone) return null
        val projection = loadPersistedCollectionProjection()
        if (projection == null) {
            if (!isLibraryReady()) return null
            idleSeedDone = true
            return null
        }
        idleSeedDone = true

        if (projection.hydratedQueue != null) {
            onApplyHydratedQueue(projection.hydratedQueue, projection.restoreShuffle)
            persistedSessionRestored = true
            return currentRuntimeSnapshot()
        }

        val item = projection.snapshot.currentItem
        onClearDiscoverPlaybackOrigin()
        onSetCurrentItem(item, false)
        onSetPlaybackPositionMs(projection.snapshot.positionMs)
        onSetIsPlaying(false)
        persistedSessionRestored = true
        return projection.snapshot
    }

    suspend fun ensurePersistedSessionRestored(): PlaybackCollectionSnapshot? =
        sessionRestoreMutex.withLock { ensurePersistedSessionRestoredLocked() }

    suspend fun systemResumptionMetadataSnapshot(): PlaybackCollectionSnapshot? {
        dependencies.playbackSettingsReady.first { it }
        return sessionRestoreMutex.withLock {
            currentRuntimeSnapshot() ?: loadPersistedCollectionProjection()?.snapshot
        }
    }

    suspend fun restoreSystemPlaybackSnapshot(): PlaybackCollectionSnapshot? {
        dependencies.playbackSettingsReady.first { it }
        return sessionRestoreMutex.withLock {
            ensurePersistedSessionRestoredLocked()?.also {
                autoplaySeedApplied = true
                pendingExternalPlaybackModes = isShuffle() to getRepeatMode()
            }
        }
    }

    fun maybeSeedIdlePlayer() {
        if (!dependencies.playbackSettingsReady.value || !hasController()) {
            return
        }
        if (liveSessionHydrated) return
        if (getControllerMediaItemCount() > 0) return
        scope.launch {
            val restored = ensurePersistedSessionRestored() ?: return@launch
            val settings = dependencies.playbackSettings.value
            if (persistedSessionRestored &&
                settings.autoplayOnLaunch &&
                getUiAttachments() > 0 &&
                !autoplaySeedApplied &&
                !isPlayWhenReadyIntent() &&
                restored.items.isNotEmpty()
            ) {
                autoplaySeedApplied = true
                onTogglePlayPause()
            }
        }
    }

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        private const val SEEK_PERSIST_DEBOUNCE_MS = 300L
    }
}
