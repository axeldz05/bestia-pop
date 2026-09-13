package com.bestiapop.android.service

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.playback.PlaybackChangeHint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INITIAL_PLAYBACK_WINDOW_SIZE = 30
private const val QUEUE_APPEND_CHUNK_SIZE = 100

internal class PlaybackTimelineSynchronizer(
    private val scope: CoroutineScope,
    private val dependencies: PlaybackRuntimeDependencies,
    private val getController: () -> PlaybackControllerFacade?,
    private val getQueue: () -> List<PlayableItem>,
    private val setQueue: (List<PlayableItem>) -> Unit,
    private val getCurrentItem: () -> PlayableItem?,
    private val setCurrentItem: (item: PlayableItem?, persistLastPlayed: Boolean, hint: PlaybackChangeHint) -> Unit,
    private val setPlaybackPositionMs: (Long) -> Unit,
    private val getLastMediaItemIndex: () -> Int,
    private val setLastMediaItemIndex: (Int) -> Unit,
    private val getPlayWhenReadyIntent: () -> Boolean,
    private val setPlayWhenReadyIntent: (Boolean) -> Unit,
    private val getPendingPlayIntentEpoch: () -> Long?,
    private val cancelPendingPlayIntent: () -> Unit,
    private val setPendingNewPlaybackQueueEntryId: (String?) -> Unit,
    private val getPlaybackGeneration: () -> Long,
    private val isPlaybackGenerationCurrent: (Long) -> Boolean,
    private val ensureRemoteReadyAt: (index: Int, startPlaying: Boolean) -> Unit,
    private val prefetchAround: (index: Int) -> Unit,
    private val syncShuffleToPlayer: () -> Unit,
    private val updateTickerLifecycle: () -> Unit,
    private val invalidatePlaybackWork: (clearRejectedEntries: Boolean) -> Unit,
    private val clearDiscoverPlaybackOrigin: () -> Unit,
    private val persistPlaybackSession: (force: Boolean) -> Unit,
    private val applyPendingExternalPlaybackModes: () -> Unit,
    private val restartAsyncPlaybackWork: () -> Unit,
    private val setLiveSessionHydrated: (Boolean) -> Unit
) {
    var timelineMaterialized = false
    var suppressPlaylistMutationCallbacks = false

    private var queueAppendJob: Job? = null

    val isQueueAppendActive: Boolean
        get() = queueAppendJob?.isActive == true

    fun cancelQueueAppend() {
        queueAppendJob?.cancel()
        queueAppendJob = null
    }

    fun hasMaterializedTimeline(): Boolean =
        timelineMaterialized && (getController()?.mediaItemCount ?: 0) > 0

    fun mutateMaterializedTimeline(
        syncShuffle: Boolean = true,
        mutation: (PlaybackControllerFacade) -> Unit
    ) {
        val player = getController() ?: return
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

    fun syncChangedTimelineItems(oldQueue: List<PlayableItem>, newQueue: List<PlayableItem>) {
        mutateMaterializedTimeline(syncShuffle = false) { player ->
            newQueue.forEachIndexed { index, item ->
                if (item !== oldQueue.getOrNull(index)) {
                    player.replaceMediaItem(index, item)
                }
            }
        }
    }

    fun rebuildPlayerQueueAroundCurrent(newOrder: List<PlayableItem>): Boolean {
        val player = getController() ?: return false
        if (!hasMaterializedTimeline() || newOrder.isEmpty()) return false
        val current = getCurrentItem() ?: return false
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
            setLastMediaItemIndex(player.currentMediaItemIndex.coerceAtLeast(0))
        } finally {
            suppressPlaylistMutationCallbacks = false
        }
        syncShuffleToPlayer()
        return true
    }

    fun applyQueueReorder(
        newOrder: List<PlayableItem>,
        focusIndex: Int,
        positionMs: Long,
        startPlaying: Boolean
    ) {
        setQueue(newOrder)
        setLastMediaItemIndex(focusIndex)
        setCurrentItem(newOrder[focusIndex], false, PlaybackChangeHint.METADATA_UPDATE)
        setPlaybackPositionMs(positionMs)
        if (!hasMaterializedTimeline()) return
        if (!rebuildPlayerQueueAroundCurrent(newOrder)) {
            reloadPlayerTimeline(newOrder, focusIndex, positionMs, startPlaying)
        }
    }

    fun reloadPlayerTimeline(
        items: List<PlayableItem>,
        startIndex: Int,
        startPositionMs: Long,
        startPlaying: Boolean,
        newPlayback: Boolean = false
    ) {
        val player = getController() ?: return
        if (items.isEmpty()) return
        val validIndex = startIndex.coerceIn(items.indices)
        if (newPlayback) setPendingNewPlaybackQueueEntryId(items[validIndex].queueEntryId)
        setPlayWhenReadyIntent(startPlaying)
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
        setLastMediaItemIndex(validIndex)
        val remote = items[validIndex] as? PlayableItem.Remote
        if (remote != null && dependencies.streamAccess.needsResolve(remote)) {
            if (startPlaying) ensureRemoteReadyAt(validIndex, true)
        } else {
            player.prepare()
            if (startPlaying) prefetchAround(validIndex)
        }
        if (startPlaying) {
            player.play()
        }
        syncShuffleToPlayer()
        updateTickerLifecycle()

        if (useWindow) {
            val generation = getPlaybackGeneration()
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

    fun reconcileTimelineFromController() {
        if (suppressPlaylistMutationCallbacks || queueAppendJob?.isActive == true) return
        val player = getController() ?: return
        val itemCount = player.mediaItemCount
        if (itemCount <= 0) {
            if (getPlayWhenReadyIntent() || getPendingPlayIntentEpoch() != null || timelineMaterialized) return
            val changed = getQueue().isNotEmpty()
            if (changed) invalidatePlaybackWork(false)
            setQueue(emptyList())
            setLastMediaItemIndex(-1)
            timelineMaterialized = false
            setPlayWhenReadyIntent(false)
            cancelPendingPlayIntent()
            setPlaybackPositionMs(0L)
            setCurrentItem(null, false, PlaybackChangeHint.METADATA_UPDATE)
            if (changed) {
                clearDiscoverPlaybackOrigin()
                persistPlaybackSession(true)
            }
            return
        }
        val rebuilt = player.items()
        if (rebuilt.size != itemCount || rebuilt.isEmpty()) return
        val oldQueue = getQueue()
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
        if (structureChanged) invalidatePlaybackWork(false)

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
        val occurrenceChanged = getCurrentItem()?.queueEntryId != liveCurrentItem.queueEntryId

        setQueue(liveQueue)
        timelineMaterialized = true
        setLiveSessionHydrated(true)
        setLastMediaItemIndex(
            liveQueue.indexOfFirst { it.queueEntryId == targetQueueEntryId }
                .takeIf { it >= 0 } ?: index
        )
        setPlaybackPositionMs(player.currentPosition.coerceAtLeast(0L))
        setCurrentItem(
            liveCurrentItem,
            occurrenceChanged,
            if (occurrenceChanged) {
                PlaybackChangeHint.NEW_PLAYBACK
            } else {
                PlaybackChangeHint.METADATA_UPDATE
            }
        )
        applyPendingExternalPlaybackModes()
        if (structureChanged) {
            persistPlaybackSession(true)
            restartAsyncPlaybackWork()
        }
    }
}
