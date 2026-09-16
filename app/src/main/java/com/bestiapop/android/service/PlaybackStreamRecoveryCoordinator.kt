package com.bestiapop.android.service

import androidx.media3.common.Player
import com.bestiapop.android.data.listenbrainz.SaveWhileListeningEvent
import com.bestiapop.android.data.listenbrainz.SaveWhileListeningPolicy
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.indexOfQueueEntry
import com.bestiapop.android.data.model.indexOfRemoteSlot
import com.bestiapop.android.data.playback.PlaybackChangeHint
import com.bestiapop.android.data.playback.PlaybackFallbackPlanner
import com.bestiapop.android.data.util.PlaybackDiagnostics
import com.bestiapop.android.domain.util.TrackMatchKeys
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class PlaybackStreamRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val dependencies: PlaybackRuntimeDependencies,
    private val getQueue: () -> List<PlayableItem>,
    private val onUpdateQueue: (List<PlayableItem>) -> Unit,
    private val getCurrentItem: () -> PlayableItem?,
    private val onSetCurrentItem: (PlayableItem?, persistLastPlayed: Boolean, hint: PlaybackChangeHint) -> Unit,
    private val getPlaybackPositionMs: () -> Long,
    private val onSetPlaybackPositionMs: (Long) -> Unit,
    private val onSetIsPlaying: (Boolean) -> Unit,
    private val isPlayWhenReadyIntent: () -> Boolean,
    private val onSetPlayWhenReadyIntent: (Boolean) -> Unit,
    private val getController: () -> PlaybackControllerFacade?,
    private val setLastMediaItemIndex: (Int) -> Unit,
    private val onEmitEvent: (String) -> Unit,
    private val onCancelPendingPlayIntent: () -> Unit,
    private val ensurePreparedForPlayback: () -> Unit,
    private val getPlaybackGeneration: () -> Long
) {
    private val _resolvingRemote = MutableStateFlow(false)
    val resolvingRemote: StateFlow<Boolean> = _resolvingRemote.asStateFlow()
    private val resolvingCount = AtomicInteger(0)

    val rejectedQueueEntries = linkedSetOf<String>()

    private var prefetchJob: Job? = null
    private var remoteRecoveryJob: Job? = null
    var remoteRecoveryQueueEntryId: String? = null
        private set
    private var remoteRecoveryDeadlineMs = 0L
    private var resolvingTransitionJob: Job? = null
    private var resolvingTransitionQueueEntryId: String? = null

    private val saveWhileListeningAttempted = mutableSetOf<String>()
    private val saveWhileListeningFailures = mutableMapOf<String, Long>()
    private val pendingSaveSettingsJobs = mutableMapOf<String, Job>()

    fun beginResolving() {
        resolvingCount.incrementAndGet()
        _resolvingRemote.value = true
    }

    fun endResolving() {
        if (resolvingCount.decrementAndGet() <= 0) {
            resolvingCount.set(0)
            _resolvingRemote.value = false
        }
    }

    fun clearRejectedQueueEntries() {
        rejectedQueueEntries.clear()
    }

    fun cancelRemoteRecoveryJob() {
        remoteRecoveryJob?.cancel()
        remoteRecoveryJob = null
    }

    fun clearRemoteRecovery() {
        cancelRemoteRecoveryJob()
        remoteRecoveryQueueEntryId = null
        remoteRecoveryDeadlineMs = 0L
    }

    fun clearRemoteRecoveryAfterProgress() {
        val currentQueueEntryId = getCurrentItem()?.queueEntryId ?: return
        if (remoteRecoveryQueueEntryId == currentQueueEntryId) clearRemoteRecovery()
    }

    fun invalidatePlaybackWork(clearRejectedEntries: Boolean = true) {
        resolvingTransitionJob?.cancel()
        resolvingTransitionJob = null
        resolvingTransitionQueueEntryId = null
        cancelRemoteRecoveryJob()
        prefetchJob?.cancel()
        prefetchJob = null
        if (clearRejectedEntries) rejectedQueueEntries.clear()
    }

    fun unplayableRemoteMessage(remote: PlayableItem.Remote): String =
        remote.title.takeIf { it.isNotBlank() }
            ?.let { "No se pudo reproducir «$it»" }
            ?: "No se pudo reproducir el audio online"

    fun ensureRemoteReadyAt(index: Int, startPlaying: Boolean) {
        if (!startPlaying || !isPlayWhenReadyIntent()) return
        val item = getQueue().getOrNull(index) as? PlayableItem.Remote ?: return
        if (!dependencies.streamAccess.needsResolve(item)) return
        resolvePlayableWithFallback(
            startIndex = index,
            triggerQueueEntryId = item.queueEntryId,
            firstFailureMessage = "No se pudo resolver el audio online"
        )
    }

    fun applyResolvedRemote(
        original: PlayableItem.Remote,
        resolved: PlayableItem.Remote
    ): Int {
        val index = getQueue().indexOfRemoteSlot(original)
        if (index < 0) return -1
        val live = getQueue().toMutableList()
        live[index] = resolved
        onUpdateQueue(live)
        val player = getController()
        val isCurrentPlaying = player != null &&
            index == player.currentMediaItemIndex &&
            (player.isPlaying || player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY)
        val urlUnchanged = original.resolved?.audioUrl?.isNotBlank() == true &&
            original.resolved?.audioUrl == resolved.resolved?.audioUrl
        if (index < (player?.mediaItemCount ?: 0) && (!isCurrentPlaying || !urlUnchanged)) {
            player?.replaceMediaItem(index, resolved)
        }
        if (getCurrentItem()?.queueEntryId == resolved.queueEntryId ||
            index == player?.currentMediaItemIndex
        ) {
            onSetCurrentItem(
                resolved,
                false,
                PlaybackChangeHint.METADATA_UPDATE
            )
        }
        return index
    }

    fun handlePlayerError() {
        val player = getController() ?: return
        val index = player.currentMediaItemIndex
        val queued = getQueue().getOrNull(index)
        val trackKind = com.bestiapop.android.data.util.TrackKind.from(queued)
        PlaybackDiagnostics.warn(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.handlePlayerError: index=$index, trackType=$trackKind, isRemote=${trackKind == com.bestiapop.android.data.util.TrackKind.REMOTE}, playWhenReadyIntent=${isPlayWhenReadyIntent()}"
        )
        if (!isPlayWhenReadyIntent()) {
            onSetIsPlaying(false)
            return
        }
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
        val generation = getPlaybackGeneration()
        val queueEntryId = remote.queueEntryId
        val expectedController = player
        val deadlineMs = remoteRecoveryDeadlineMs
        remoteRecoveryJob = scope.launch {
            val ownJob = coroutineContext[Job]
            beginResolving()
            try {
                var attempt = 0
                while (dependencies.clockMs() < deadlineMs) {
                    if (!isPlayWhenReadyIntent() ||
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
                    if (!isPlayWhenReadyIntent() ||
                        !isFallbackContextCurrent(
                            generation,
                            queueEntryId,
                            expectedController
                        )
                    ) {
                        return@launch
                    }
                    if (refreshed != null && applyResolvedRemote(remote, refreshed) >= 0) {
                        val resumePos = getPlaybackPositionMs().coerceAtLeast(0L)
                        if (resumePos > 0L) {
                            expectedController.seekTo(index, resumePos)
                        }
                        expectedController.prepare()
                        if (isPlayWhenReadyIntent()) expectedController.play()
                        return@launch
                    }
                    attempt++
                    delay(minOf(REMOTE_RECOVERY_RETRY_MS * (1L shl (attempt - 1).coerceAtMost(3)), 3_000L))
                }
                if (isPlayWhenReadyIntent() &&
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

    fun recoverAfterUnplayable(message: String?) {
        val player = getController() ?: return
        if (!isPlayWhenReadyIntent()) return
        message?.let(onEmitEvent)
        val items = getQueue()
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

    fun resolvePlayableWithFallback(
        startIndex: Int,
        triggerQueueEntryId: String,
        firstFailureMessage: String?
    ) {
        val expectedController = getController() ?: return
        if (!isPlayWhenReadyIntent()) return
        if (resolvingTransitionJob?.isActive == true &&
            resolvingTransitionQueueEntryId == triggerQueueEntryId
        ) {
            return
        }
        resolvingTransitionJob?.cancel()
        val generation = getPlaybackGeneration()
        val snapshot = getQueue()
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
                    if (!isPlayWhenReadyIntent() && queueEntryId != triggerQueueEntryId) {
                        return@launch
                    }
                    if (queueEntryId in rejectedQueueEntries) continue
                    val liveIndex = getQueue().indexOfQueueEntry(step.item)
                    if (liveIndex < 0) continue
                    when (val live = getQueue().getOrNull(liveIndex) ?: continue) {
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
                                    firstFailureMessage?.let(onEmitEvent)
                                    failureAnnounced = true
                                }
                                continue
                            }
                            val slot = if (ready === live) {
                                getQueue().indexOfQueueEntry(live)
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
        val slot = getQueue().indexOfFirst { it.queueEntryId == queueEntryId }
        if (slot < 0) return
        if (slot != expectedController.currentMediaItemIndex) {
            if (!isPlayWhenReadyIntent()) return
            setLastMediaItemIndex(slot)
            onSetPlaybackPositionMs(0L)
            expectedController.seekTo(slot, 0L)
        }
        ensurePreparedForPlayback()
        if (isPlayWhenReadyIntent() && getController() === expectedController) {
            expectedController.play()
            prefetchAround(slot)
        }
    }

    private fun isFallbackContextCurrent(
        generation: Long,
        triggerQueueEntryId: String,
        expectedController: PlaybackControllerFacade
    ): Boolean =
        generation == getPlaybackGeneration() &&
                getController() === expectedController &&
                getCurrentItem()?.queueEntryId == triggerQueueEntryId

    private fun pauseAfterFallbackExhausted(
        expectedController: PlaybackControllerFacade,
        firstFailureMessage: String?
    ) {
        if (getController() !== expectedController || !isPlayWhenReadyIntent()) return
        firstFailureMessage?.let(onEmitEvent)
        onSetPlayWhenReadyIntent(false)
        onCancelPendingPlayIntent()
        expectedController.pause()
        onEmitEvent("No se encontró una canción reproducible en la cola")
    }

    fun prefetchAround(index: Int) {
        if (!isPlayWhenReadyIntent()) {
            prefetchJob?.cancel()
            prefetchJob = null
            return
        }
        prefetchJob?.cancel()
        val generation = getPlaybackGeneration()
        val expectedController = getController() ?: return
        prefetchJob = scope.launch {
            val snapshot = getQueue()
            val targets = listOfNotNull(
                snapshot.getOrNull(index + 1),
                snapshot.getOrNull(index + 2)
            ).filterIsInstance<PlayableItem.Remote>()
                .filter(dependencies.streamAccess::needsResolve)
                .filterNot { it.queueEntryId in rejectedQueueEntries }
            for (remote in targets) {
                if (generation != getPlaybackGeneration() ||
                    !isPlayWhenReadyIntent() ||
                    getController() !== expectedController
                ) {
                    return@launch
                }
                val resolved = dependencies.streamAccess.resolve(remote) ?: continue
                if (generation != getPlaybackGeneration() ||
                    !isPlayWhenReadyIntent() ||
                    getController() !== expectedController
                ) {
                    return@launch
                }
                applyResolvedRemote(remote, resolved)
            }
        }
    }

    fun maybeSaveWhileListening(
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
                    onEmitEvent("«${result.song.title}» guardada en la biblioteca")
                }

                is SaveWhileListeningDownloadResult.InFlight -> {
                    saveWhileListeningAttempted.remove(key)
                }

                is SaveWhileListeningDownloadResult.Failed -> {
                    saveWhileListeningFailures[key] = dependencies.clockMs()
                    saveWhileListeningAttempted.remove(key)
                    onEmitEvent(
                        "No se pudo guardar «${remote.title}»: " +
                                (result.error.localizedMessage ?: "error")
                    )
                }
            }
        }
    }

    companion object {
        private const val REMOTE_RECOVERY_RETRY_MS = 600L
        private const val SAVE_RETRY_COOLDOWN_MS = 10 * 60 * 1000L
    }
}
