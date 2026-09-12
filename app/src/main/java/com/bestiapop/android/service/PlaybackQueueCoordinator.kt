package com.bestiapop.android.service

import androidx.media3.common.Player
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.withFreshQueueEntryIds
import com.bestiapop.android.data.playback.PlaybackChangeHint
import com.bestiapop.android.data.preferences.PlaybackModeClear
import com.bestiapop.android.data.playback.PlaybackQueueOrder
import com.bestiapop.android.data.playback.PlaybackQueueSlots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PlaybackQueueCoordinator(
    private val scope: CoroutineScope,
    private val dependencies: PlaybackRuntimeDependencies,
    private val getQueue: () -> List<PlayableItem>,
    private val onSetQueue: (List<PlayableItem>) -> Unit,
    private val getCurrentItem: () -> PlayableItem?,
    private val onSetCurrentItem: (PlayableItem?, persistLastPlayed: Boolean, hint: PlaybackChangeHint) -> Unit,
    private val getRepeatMode: () -> RepeatMode,
    private val onSetRepeatModeState: (RepeatMode) -> Unit,
    private val isShuffle: () -> Boolean,
    private val onSetShuffleState: (Boolean) -> Unit,
    private val getPlaybackPositionMs: () -> Long,
    private val onSetPlaybackPositionMs: (Long) -> Unit,
    private val isPlayWhenReadyIntent: () -> Boolean,
    private val onSetPlayWhenReadyIntent: (Boolean) -> Unit,
    private val getController: () -> PlaybackControllerFacade?,
    private val hasMaterializedTimeline: () -> Boolean,
    private val setTimelineMaterialized: (Boolean) -> Unit,
    private val getLastMediaItemIndex: () -> Int,
    private val setLastMediaItemIndex: (Int) -> Unit,
    private val currentQueueIndex: () -> Int,
    private val onInvalidatePlaybackWork: (clearRejectedEntries: Boolean) -> Unit,
    private val onRestartAsyncPlaybackWork: () -> Unit,
    private val onPersistPlaybackSession: (force: Boolean) -> Unit,
    private val onBumpQueueFocus: () -> Unit,
    private val onStopRadio: () -> Unit,
    private val onCancelPendingPlayIntent: () -> Unit,
    private val onClearDiscoverPlaybackOrigin: () -> Unit,
    private val onReleaseControllerIfIdle: () -> Unit,
    private val onEnsureControllerConnection: () -> Unit,
    private val applyQueueReorder: (newOrder: List<PlayableItem>, focusIndex: Int, positionMs: Long, startPlaying: Boolean) -> Unit,
    private val mutateMaterializedTimeline: (syncShuffle: Boolean, mutation: (PlaybackControllerFacade) -> Unit) -> Unit,
    private val syncChangedTimelineItems: (oldQueue: List<PlayableItem>, newQueue: List<PlayableItem>) -> Unit,
    private val onSetPendingExternalPlaybackModes: (Pair<Boolean, RepeatMode>?) -> Unit
) {
    var preShuffleOrder: List<String>? = null
        internal set

    fun toggleRepeatMode() {
        setRepeatMode(
            when (getRepeatMode()) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
        )
    }

    fun toggleShuffle() {
        onInvalidatePlaybackWork(false)
        val enabling = !isShuffle()
        val items = getQueue()
        val position = if (hasMaterializedTimeline()) {
            getController()?.currentPosition?.coerceAtLeast(0L) ?: getPlaybackPositionMs()
        } else {
            getPlaybackPositionMs()
        }
        val wasPlaying = isPlayWhenReadyIntent()
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
            val currentSlot = getCurrentItem()?.queueEntryId
            setShuffleEnabled(false)
            if (!restored.isNullOrEmpty()) {
                val index = restored.indexOfFirst { it.queueEntryId == currentSlot }
                    .takeIf { it >= 0 } ?: 0
                applyQueueReorder(restored, index, position, wasPlaying)
            }
        }
        onBumpQueueFocus()
        onPersistPlaybackSession(true)
        onRestartAsyncPlaybackWork()
    }

    fun addPlayableBatch(items: List<PlayableItem>) {
        if (items.isEmpty()) return
        onInvalidatePlaybackWork(false)
        val additions = items.withFreshQueueEntryIds()
        onSetQueue(getQueue() + additions)
        mutateMaterializedTimeline(true) { it.addMediaItems(additions) }
        onEnsureControllerConnection()
        onPersistPlaybackSession(true)
        onRestartAsyncPlaybackWork()
    }

    fun playNextBatch(items: List<PlayableItem>) {
        if (items.isEmpty()) return
        onInvalidatePlaybackWork(false)
        val additions = items.withFreshQueueEntryIds()
        val live = getQueue().toMutableList()
        val currentIndex = currentQueueIndex().coerceAtLeast(0)
        val insertAt = (currentIndex + 1).coerceAtMost(live.size)
        live.addAll(insertAt, additions)
        onSetQueue(live)
        mutateMaterializedTimeline(true) { it.addMediaItems(insertAt, additions) }
        onEnsureControllerConnection()
        onPersistPlaybackSession(true)
        onRestartAsyncPlaybackWork()
    }

    fun updateAlbumArtworkInQueue(albumKey: String, artworkUri: String?) {
        val current = getCurrentItem()
        if (current is PlayableItem.Local &&
            (current.song.album.equals(albumKey, ignoreCase = true) ||
                com.bestiapop.android.domain.util.albumIdentityKey(current.song.album) == albumKey)
        ) {
            onSetCurrentItem(
                current.copy(resolvedArtworkUri = artworkUri),
                false,
                PlaybackChangeHint.METADATA_UPDATE
            )
        }
        val q = getQueue()
        var changed = false
        val newQ = q.map { item ->
            if (item is PlayableItem.Local &&
                (item.song.album.equals(albumKey, ignoreCase = true) ||
                    com.bestiapop.android.domain.util.albumIdentityKey(item.song.album) == albumKey)
            ) {
                changed = true
                item.copy(resolvedArtworkUri = artworkUri)
            } else {
                item
            }
        }
        if (changed) {
            onSetQueue(newQ)
            syncChangedTimelineItems(q, newQ)
        }
    }

    fun removeFromQueue(queueEntryId: String): Boolean {
        val index = getQueue().indexOfFirst { it.queueEntryId == queueEntryId }
        if (index !in getQueue().indices) return false
        removeFromQueue(index)
        return true
    }

    fun removeFromQueue(index: Int) {
        val old = getQueue()
        if (index !in old.indices) return
        onInvalidatePlaybackWork(false)
        val currentSlot = getCurrentItem()?.queueEntryId
        val currentIndex = currentQueueIndex().coerceIn(old.indices)
        val live = old.toMutableList().apply { removeAt(index) }
        onSetQueue(live)
        if (live.isEmpty()) {
            onStopRadio()
            onSetPlayWhenReadyIntent(false)
            onCancelPendingPlayIntent()
            getController()?.pause()
            mutateMaterializedTimeline(true) { it.removeMediaItem(index) }
            setLastMediaItemIndex(-1)
            onSetPlaybackPositionMs(0L)
            onClearDiscoverPlaybackOrigin()
            onSetCurrentItem(null, false, PlaybackChangeHint.METADATA_UPDATE)
            setTimelineMaterialized(false)
        } else {
            mutateMaterializedTimeline(true) { it.removeMediaItem(index) }
            val nextIndex = when {
                index < currentIndex -> currentIndex - 1
                index == currentIndex -> index.coerceAtMost(live.lastIndex)
                else -> currentIndex
            }.coerceIn(live.indices)
            setLastMediaItemIndex(nextIndex)
            if (currentSlot == old[index].queueEntryId) {
                onSetPlaybackPositionMs(0L)
                onSetCurrentItem(live[nextIndex], false, PlaybackChangeHint.METADATA_UPDATE)
            }
        }
        onPersistPlaybackSession(true)
        if (live.isNotEmpty()) {
            onRestartAsyncPlaybackWork()
        }
        onReleaseControllerIfIdle()
    }

    fun clearQueue() {
        onInvalidatePlaybackWork(false)
        onStopRadio()
        onSetPlayWhenReadyIntent(false)
        onCancelPendingPlayIntent()
        getController()?.pause()
        onSetQueue(emptyList())
        mutateMaterializedTimeline(true) { it.clearMediaItems() }
        setLastMediaItemIndex(-1)
        onSetPlaybackPositionMs(0L)
        onClearDiscoverPlaybackOrigin()
        onSetCurrentItem(null, false, PlaybackChangeHint.METADATA_UPDATE)
        setTimelineMaterialized(false)
        onPersistPlaybackSession(true)
        onReleaseControllerIfIdle()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val live = getQueue().toMutableList()
        if (fromIndex !in live.indices || toIndex !in live.indices || fromIndex == toIndex) return
        onInvalidatePlaybackWork(false)
        val currentSlot = getCurrentItem()?.queueEntryId
        live.add(toIndex, live.removeAt(fromIndex))
        onSetQueue(live)
        mutateMaterializedTimeline(true) { it.moveMediaItem(fromIndex, toIndex) }
        setLastMediaItemIndex(
            live.indexOfFirst { it.queueEntryId == currentSlot }
                .takeIf { it >= 0 }
                ?: getLastMediaItemIndex().coerceIn(live.indices)
        )
        onPersistPlaybackSession(true)
        onRestartAsyncPlaybackWork()
    }

    fun permuteQueueToPlayOrder(
        items: List<PlayableItem>,
        currentIndex: Int,
        backupSource: Boolean
    ): Pair<List<PlayableItem>, Int> {
        if (items.isEmpty()) return items to 0
        if (backupSource) preShuffleOrder = PlaybackQueueSlots.capturePreShuffleOrder(items)
        val order = PlaybackQueueOrder.shufflePlayOrder(items.size, currentIndex)
        return PlaybackQueueOrder.applyPlayOrder(items, order) to 0
    }

    fun preShuffleQueueOrNull(): List<PlayableItem>? {
        val order = preShuffleOrder ?: return null
        return PlaybackQueueSlots.restorePreShuffleOrder(getQueue(), order)
    }

    fun setShuffleEnabled(enabled: Boolean, syncPlayer: Boolean = true) {
        val wasEnabled = isShuffle()
        onSetShuffleState(enabled)
        scope.launch { dependencies.persistShuffle(enabled) }
        if (!enabled && wasEnabled) {
            preShuffleOrder = null
        }
        if (syncPlayer) {
            syncShuffleToPlayer()
        }
    }

    fun disableShuffleRestoringOrder() {
        if (!isShuffle()) return
        val restored = preShuffleQueueOrNull()
        val currentSlot = getCurrentItem()?.queueEntryId
        val position = if (hasMaterializedTimeline()) {
            getController()?.currentPosition ?: getPlaybackPositionMs()
        } else {
            getPlaybackPositionMs()
        }
        val wasPlaying = isPlayWhenReadyIntent()
        setShuffleEnabled(false)
        if (!restored.isNullOrEmpty()) {
            val index = restored.indexOfFirst { it.queueEntryId == currentSlot }
                .takeIf { it >= 0 } ?: 0
            applyQueueReorder(restored, index, position, wasPlaying)
        }
    }

    fun syncShuffleToPlayer() {
        if (hasMaterializedTimeline()) {
            getController()?.shuffleModeEnabled = isShuffle()
        }
    }

    fun setRepeatMode(mode: RepeatMode, syncPlayer: Boolean = true) {
        onSetRepeatModeState(mode)
        if (syncPlayer) {
            applyRepeatModeToController(mode)
        }
        scope.launch { dependencies.persistRepeat(mode) }
    }

    fun applyRepeatModeToController(mode: RepeatMode) {
        getController()?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    fun repeatModeFromPlayer(value: Int): RepeatMode = when (value) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    fun applyManualPlayModes(deferPlayerSync: Boolean = false) {
        val (shuffle, repeat) = PlaybackModeClear.afterManualPlay(
            isShuffle(),
            getRepeatMode(),
            dependencies.playbackSettings.value
        )
        if (deferPlayerSync) {
            onSetShuffleState(shuffle)
            onSetRepeatModeState(repeat)
            scope.launch { dependencies.persistShuffle(shuffle) }
            scope.launch { dependencies.persistRepeat(repeat) }
            onSetPendingExternalPlaybackModes(shuffle to repeat)
        } else {
            applyResolvedModes(shuffle, repeat)
        }
    }

    fun applySkipModes() {
        val (shuffle, repeat) = PlaybackModeClear.afterSkip(
            isShuffle(),
            getRepeatMode(),
            dependencies.playbackSettings.value
        )
        applyResolvedModes(shuffle, repeat)
    }

    fun applyRadioStartModes() {
        val (shuffle, repeat) = PlaybackModeClear.afterRadioStart(
            isShuffle(),
            getRepeatMode()
        )
        applyResolvedModes(shuffle, repeat)
    }

    fun applyResolvedModes(shuffle: Boolean, repeat: RepeatMode) {
        if (shuffle != isShuffle()) {
            if (shuffle) setShuffleEnabled(true) else disableShuffleRestoringOrder()
        }
        if (repeat != getRepeatMode()) setRepeatMode(repeat)
    }
}
