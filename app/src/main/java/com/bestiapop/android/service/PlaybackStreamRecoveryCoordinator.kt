package com.bestiapop.android.service

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.indexOfQueueEntry
import com.bestiapop.android.data.model.indexOfRemoteSlot
import com.bestiapop.android.data.model.toIdentity
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.data.playback.PlaybackChangeHint
import com.bestiapop.android.data.playback.PlaybackFallbackPlanner
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.PlaybackDiagnostics
import com.bestiapop.android.data.util.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

private data class PlaybackFailure(
    val error: PlaybackException?,
    val item: PlayableItem?,
    val isCovered: Boolean,
)

private sealed interface RecoveryAction {
    data object Ignore : RecoveryAction

    data object SilentSinkHandled : RecoveryAction

    data class MissingLocal(
        val local: PlayableItem.Local,
    ) : RecoveryAction

    data class ResolveRemotePending(
        val index: Int,
    ) : RecoveryAction

    data class RetryRemoteStream(
        val remote: PlayableItem.Remote,
        val index: Int,
        val deadlineMs: Long,
    ) : RecoveryAction

    data class FallbackUnplayable(
        val message: String,
    ) : RecoveryAction
}

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
    ensurePreparedForPlayback: () -> Unit,
    private val getPlaybackGeneration: () -> Long,
) {
    private val _resolvingRemote = MutableStateFlow(false)
    val resolvingRemote: StateFlow<Boolean> = _resolvingRemote.asStateFlow()
    private val resolvingCount = AtomicInteger(0)

    val rejectedQueueEntries = linkedSetOf<String>()
    private var activeFailure: PlaybackFailure? = null

    private var prefetchJob: Job? = null
    private var remoteRecoveryJob: Job? = null
    var remoteRecoveryQueueEntryId: String? = null
        private set
    private var remoteRecoveryDeadlineMs = 0L
    private var resolvingTransitionJob: Job? = null
    private var resolvingTransitionQueueEntryId: String? = null

    private val ensurePrepared: () -> Unit = ensurePreparedForPlayback

    internal fun isCoveredError(
        error: PlaybackException?,
        queued: PlayableItem?,
    ): Boolean {
        if (error == null) return false
        val code = error.errorCode
        if (code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        ) {
            return true
        }
        if (queued is PlayableItem.Local) {
            return !dependencies.hasPhysicalFile(queued.song)
        }
        if (queued is PlayableItem.Remote) {
            return queued.resolved == null ||
                queued.resolved.audioUrl.isBlank() ||
                code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        }
        return false
    }

    private fun classifyRecoveryAction(
        error: PlaybackException?,
        queued: PlayableItem?,
        index: Int,
        playWhenReady: Boolean,
    ): RecoveryAction {
        val errorCode = error?.errorCode
        val isSinkOrHardwareError =
            errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED

        if (isSinkOrHardwareError) return RecoveryAction.SilentSinkHandled
        if (!playWhenReady) return RecoveryAction.Ignore

        return when (queued) {
            is PlayableItem.Local -> {
                if (!dependencies.hasPhysicalFile(queued.song)) {
                    RecoveryAction.MissingLocal(queued)
                } else {
                    val title = queued.title.takeIf { it.isNotBlank() }
                    RecoveryAction.FallbackUnplayable(
                        if (title != null) "No se pudo reproducir «$title»" else "No se pudo reproducir",
                    )
                }
            }

            is PlayableItem.Remote -> {
                if (queued.resolved == null || queued.resolved.audioUrl.isBlank()) {
                    RecoveryAction.ResolveRemotePending(index)
                } else {
                    queued.resolved.clientName?.let { clientName ->
                        YouTubeExtractor.reportClientHttpFailure(clientName, 403)
                    }
                    val graceMs =
                        dependencies.playbackSettings.value.streamSkipGraceSeconds
                            .coerceAtLeast(0)
                            .times(1000L)
                    if (graceMs <= 0L) {
                        RecoveryAction.FallbackUnplayable(unplayableRemoteMessage(queued))
                    } else {
                        val deadline =
                            if (remoteRecoveryQueueEntryId == queued.queueEntryId) {
                                remoteRecoveryDeadlineMs
                            } else {
                                dependencies.clockMs() + graceMs
                            }
                        if (dependencies.clockMs() >= deadline) {
                            RecoveryAction.FallbackUnplayable(unplayableRemoteMessage(queued))
                        } else {
                            RecoveryAction.RetryRemoteStream(queued, index, deadline)
                        }
                    }
                }
            }

            null -> {
                RecoveryAction.Ignore
            }
        }
    }

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
        activeFailure = null
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
        activeFailure = null
    }

    fun invalidatePlaybackWork(clearRejectedEntries: Boolean = true) {
        resolvingTransitionJob?.cancel()
        resolvingTransitionJob = null
        resolvingTransitionQueueEntryId = null
        cancelRemoteRecoveryJob()
        prefetchJob?.cancel()
        prefetchJob = null
        activeFailure = null
        if (clearRejectedEntries) rejectedQueueEntries.clear()
    }

    fun unplayableRemoteMessage(remote: PlayableItem.Remote): String =
        remote.title
            .takeIf { it.isNotBlank() }
            ?.let { "No se pudo reproducir «$it»" }
            ?: "No se pudo reproducir el audio online"

    fun ensureRemoteReadyAt(
        index: Int,
        startPlaying: Boolean,
    ) {
        if (!startPlaying || !isPlayWhenReadyIntent()) return
        val item = getQueue().getOrNull(index) as? PlayableItem.Remote ?: return
        if (!dependencies.streamAccess.needsResolve(item)) return
        resolvePlayableWithFallback(
            startIndex = index,
            triggerQueueEntryId = item.queueEntryId,
            firstFailureMessage = "No se pudo resolver el audio online",
        )
    }

    fun applyResolvedRemote(
        original: PlayableItem.Remote,
        resolved: PlayableItem.Remote,
    ): Int {
        val index = getQueue().indexOfRemoteSlot(original)
        if (index < 0) return -1
        val live = getQueue().toMutableList()
        live[index] = resolved
        onUpdateQueue(live)
        val player = getController()
        val isCurrentPlaying =
            player != null &&
                index == player.currentMediaItemIndex &&
                (player.isPlaying || player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY)
        val urlUnchanged =
            original.resolved?.audioUrl?.isNotBlank() == true &&
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
                PlaybackChangeHint.METADATA_UPDATE,
            )
        }
        return index
    }

    fun handlePlayerError(error: PlaybackException? = null) {
        val player = getController() ?: return
        val index = player.currentMediaItemIndex
        val queued = getQueue().getOrNull(index)
        val trackKind = TrackKind.from(queued)
        val errorCode = error?.errorCode
        val isSinkOrHardwareError =
            errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED

        val isCovered = isCoveredError(error, queued)
        activeFailure = PlaybackFailure(error = error, item = queued, isCovered = isCovered)

        if (!isCovered && error != null) {
            CrashReporter.log(
                "PlaybackRuntime.handlePlayerError: uncovered error detected errorCode=${error.errorCodeName} ($errorCode) track=${queued?.title}",
            )
        }

        PlaybackDiagnostics.warn(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.handlePlayerError: index=$index, trackType=$trackKind, errorCode=${error?.errorCodeName} ($errorCode), isSinkOrHardwareError=$isSinkOrHardwareError, isCovered=$isCovered, playWhenReadyIntent=${isPlayWhenReadyIntent()}",
        )

        when (val action = classifyRecoveryAction(error, queued, index, isPlayWhenReadyIntent())) {
            RecoveryAction.SilentSinkHandled -> {
                activeFailure = null
                clearRejectedQueueEntries()
            }

            RecoveryAction.Ignore -> {
                onSetIsPlaying(false)
            }

            is RecoveryAction.MissingLocal -> {
                handleMissingLocal(action.local, player)
            }

            is RecoveryAction.ResolveRemotePending -> {
                ensureRemoteReadyAt(action.index, startPlaying = true)
            }

            is RecoveryAction.RetryRemoteStream -> {
                startRemoteStreamRecovery(action.remote, action.index, action.deadlineMs, player)
            }

            is RecoveryAction.FallbackUnplayable -> {
                recoverAfterUnplayable(action.message)
            }
        }
    }

    private fun handleMissingLocal(
        local: PlayableItem.Local,
        player: PlaybackControllerFacade,
    ) {
        val title = local.title.takeIf { it.isNotBlank() }
        scope.launch {
            dependencies.deleteMissingLocalSong(local.song)
        }
        if (dependencies.isOnline()) {
            val msg =
                if (title != null) {
                    "No se encontró «$title» en el dispositivo. Buscando en streaming…"
                } else {
                    "No se encontró la canción en el dispositivo. Buscando en streaming…"
                }
            onEmitEvent(msg)
            val streamingRemote =
                PlayableItem
                    .remoteFrom(
                        identity = local.song.toIdentity(),
                    ).copy(queueEntryId = local.queueEntryId)
            val liveQueue = getQueue().toMutableList()
            val targetIndex = liveQueue.indexOfFirst { it.queueEntryId == local.queueEntryId }
            if (targetIndex >= 0) {
                liveQueue[targetIndex] = streamingRemote
                onUpdateQueue(liveQueue)
                player.replaceMediaItem(targetIndex, streamingRemote)
                resolvePlayableWithFallback(
                    startIndex = targetIndex,
                    triggerQueueEntryId = streamingRemote.queueEntryId,
                    firstFailureMessage =
                        if (title != null) {
                            "No se pudo reproducir «$title» en streaming"
                        } else {
                            "No se pudo reproducir en streaming"
                        },
                )
                return
            }
        }
        recoverAfterUnplayable(
            if (title != null) {
                "No se encontró «$title» en el dispositivo"
            } else {
                "No se encontró la canción en el dispositivo"
            },
        )
    }

    private fun startRemoteStreamRecovery(
        remote: PlayableItem.Remote,
        index: Int,
        deadlineMs: Long,
        player: PlaybackControllerFacade,
    ) {
        remoteRecoveryQueueEntryId = remote.queueEntryId
        remoteRecoveryDeadlineMs = deadlineMs
        if (remoteRecoveryJob?.isActive == true) return
        val generation = getPlaybackGeneration()
        val queueEntryId = remote.queueEntryId
        val expectedController = player
        remoteRecoveryJob =
            scope.launch {
                val ownJob = coroutineContext[Job]
                beginResolving()
                try {
                    var attempt = 0
                    while (dependencies.clockMs() < deadlineMs) {
                        if (!isPlayWhenReadyIntent() ||
                            !isFallbackContextCurrent(
                                generation,
                                queueEntryId,
                                expectedController,
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
                                expectedController,
                            )
                        ) {
                            return@launch
                        }
                        if (refreshed != null && applyResolvedRemote(remote, refreshed) >= 0) {
                            val resumePos = getPlaybackPositionMs().coerceAtLeast(0L)
                            expectedController.seekTo(index, resumePos)
                            expectedController.prepare()
                            if (isPlayWhenReadyIntent()) expectedController.play()
                            activeFailure = null
                            return@launch
                        }
                        attempt++
                        delay(minOf(REMOTE_RECOVERY_RETRY_MS * (1L shl (attempt - 1).coerceAtMost(3)), 3_000L))
                    }
                    if (isPlayWhenReadyIntent() &&
                        isFallbackContextCurrent(
                            generation,
                            queueEntryId,
                            expectedController,
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
            firstFailureMessage = null,
        )
    }

    fun resolvePlayableWithFallback(
        startIndex: Int,
        triggerQueueEntryId: String,
        firstFailureMessage: String?,
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
        resolvingTransitionJob =
            scope.launch {
                val ownJob = coroutineContext[Job]
                beginResolving()
                var failureAnnounced = false
                try {
                    for (step in PlaybackFallbackPlanner.circularPlan(snapshot, startIndex)) {
                        if (!isFallbackContextCurrent(
                                generation,
                                triggerQueueEntryId,
                                expectedController,
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
                                    triggerQueueEntryId = triggerQueueEntryId,
                                )
                                return@launch
                            }

                            is PlayableItem.Remote -> {
                                val ready =
                                    if (dependencies.streamAccess.needsResolve(live)) {
                                        dependencies.streamAccess.resolve(live)
                                    } else {
                                        live
                                    }
                                if (!isFallbackContextCurrent(
                                        generation,
                                        triggerQueueEntryId,
                                        expectedController,
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
                                val slot =
                                    if (ready === live) {
                                        getQueue().indexOfQueueEntry(live)
                                    } else {
                                        applyResolvedRemote(live, ready)
                                    }
                                if (slot < 0) continue
                                activateFallbackCandidate(
                                    queueEntryId = ready.queueEntryId,
                                    expectedController = expectedController,
                                    generation = generation,
                                    triggerQueueEntryId = triggerQueueEntryId,
                                )
                                return@launch
                            }
                        }
                    }
                    if (isFallbackContextCurrent(
                            generation,
                            triggerQueueEntryId,
                            expectedController,
                        )
                    ) {
                        pauseAfterFallbackExhausted(
                            expectedController,
                            firstFailureMessage.takeUnless { failureAnnounced },
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
        triggerQueueEntryId: String,
    ) {
        if (!isFallbackContextCurrent(
                generation,
                triggerQueueEntryId,
                expectedController,
            )
        ) {
            return
        }
        val slot = getQueue().indexOfFirst { it.queueEntryId == queueEntryId }
        if (slot < 0) return
        val isCurrentItem = slot == expectedController.currentMediaItemIndex
        val needsSeek =
            !isCurrentItem ||
                expectedController.playbackState == Player.STATE_IDLE ||
                expectedController.playbackState == Player.STATE_ENDED ||
                expectedController.hasPlayerError
        if (needsSeek) {
            if (!isPlayWhenReadyIntent()) return
            setLastMediaItemIndex(slot)
            onSetPlaybackPositionMs(0L)
            expectedController.seekTo(slot, 0L)
        }
        ensurePrepared()
        if (isPlayWhenReadyIntent() && getController() === expectedController) {
            expectedController.play()
            activeFailure = null
            prefetchAround(slot)
        }
    }

    private fun isFallbackContextCurrent(
        generation: Long,
        triggerQueueEntryId: String,
        expectedController: PlaybackControllerFacade,
    ): Boolean =
        generation == getPlaybackGeneration() &&
            getController() === expectedController &&
            getCurrentItem()?.queueEntryId == triggerQueueEntryId

    private fun pauseAfterFallbackExhausted(
        expectedController: PlaybackControllerFacade,
        firstFailureMessage: String?,
    ) {
        if (getController() !== expectedController || !isPlayWhenReadyIntent()) return
        firstFailureMessage?.let(onEmitEvent)
        onSetPlayWhenReadyIntent(false)
        onCancelPendingPlayIntent()
        expectedController.pause()
        onEmitEvent("No se encontró una canción reproducible en la cola")
        reportFallbackExhausted(expectedController)
    }

    private fun reportFallbackExhausted(controller: PlaybackControllerFacade) {
        val failure = activeFailure
        val errorToReport: Throwable =
            failure?.error ?: IllegalStateException("Playback fallback exhausted: no playable tracks in queue")
        val isExplicitUncovered = failure != null && !failure.isCovered && failure.error != null
        val contextKeys =
            mutableMapOf(
                "playback_phase" to "fallback_exhausted",
                "queue_size" to getQueue().size.toString(),
                "queue_index" to controller.currentMediaItemIndex.toString(),
                "is_covered" to (!isExplicitUncovered).toString(),
            )
        failure?.error?.let { err ->
            contextKeys["uncovered_error_code"] = err.errorCode.toString()
            contextKeys["uncovered_error_name"] = err.errorCodeName
            err.message?.let { contextKeys["uncovered_error_message"] = it.take(256) }
        }
        val current = failure?.item ?: getCurrentItem()
        if (current != null) {
            contextKeys["track_kind"] = if (current is PlayableItem.Local) "LOCAL" else "REMOTE"
            contextKeys["track_title"] = current.title.take(128)
            contextKeys["track_artist"] = current.artist.take(128)
            contextKeys["media_id"] = current.mediaId.take(256)
        }
        CrashReporter.recordNonFatal(errorToReport, contextKeys)
        activeFailure = null
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
        prefetchJob =
            scope.launch {
                val snapshot = getQueue()
                val targets =
                    listOfNotNull(
                        snapshot.getOrNull(index + 1),
                        snapshot.getOrNull(index + 2),
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

    companion object {
        private const val REMOTE_RECOVERY_RETRY_MS = 600L
    }
}
