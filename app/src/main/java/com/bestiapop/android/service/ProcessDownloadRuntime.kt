package com.bestiapop.android.service

import android.content.Context
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadConflict
import com.bestiapop.android.data.model.DownloadConflictPolicy
import com.bestiapop.android.data.model.DownloadLane
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.DownloadPlaylistDestination
import com.bestiapop.android.data.model.DuplicateSongException
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.isInFlight
import com.bestiapop.android.data.model.isFailed
import com.bestiapop.android.data.model.lane
import com.bestiapop.android.data.model.overwriteTargetSongIdOrNull
import com.bestiapop.android.data.model.resolveDownloadPlaylistDestinations
import com.bestiapop.android.data.model.saveAsTitleOrNull
import com.bestiapop.android.data.model.withIdentity
import com.bestiapop.android.data.network.ConnectivityObserver
import com.bestiapop.android.data.preferences.DownloadPreferencesRepository
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.domain.usecase.DownloadAudioTrackUseCase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal data class ProcessDownloadRequest(
    val downloadId: String,
    val source: ActiveDownloadSource,
    val track: OnlineCatalogTrack,
    val candidates: List<OnlineCatalogTrack> = listOf(track),
    val currentCandidateIndex: Int = 0,
    val targetPlaylistId: Long? = null,
    val playlistTargets: List<DownloadPlaylistDestination> = emptyList(),
    val conflictPolicy: DownloadConflictPolicy? = null,
    val lookupIdentity: TrackIdentity? = null,
    val reconcileCommittedAsSuccess: Boolean = false,
    val downloadStarted: Boolean = false,
    val storageCommitted: Boolean = false,
    val batchId: String? = null,
    val titleOverride: String? = null
) {
    val treatDuplicateAsSuccess: Boolean
        get() = source.lane == DownloadLane.AUTOSAVE || reconcileCommittedAsSuccess

    companion object {
        fun from(download: ActiveDownload): ProcessDownloadRequest? {
            val track = download.currentTrack ?: return null
            val lookup = download.lookupIdentity ?: track.identity
            val targets = resolveDownloadPlaylistDestinations(
                download.playlistTargets,
                download.targetPlaylistId,
                lookup
            )
            return ProcessDownloadRequest(
                downloadId = download.id,
                source = download.source,
                track = track,
                candidates = download.candidates,
                currentCandidateIndex = download.currentCandidateIndex,
                targetPlaylistId = download.targetPlaylistId,
                playlistTargets = targets,
                conflictPolicy = download.restoredConflictPolicy(),
                lookupIdentity = lookup,
                reconcileCommittedAsSuccess = download.interrupted && download.storageCommitted,
                downloadStarted = download.downloadStarted,
                storageCommitted = download.storageCommitted,
                batchId = download.batchId,
                titleOverride = download.titleOverride
            )
        }
    }
}

private data class DownloadExecutionContext(
    val request: ProcessDownloadRequest,
    val candidates: List<OnlineCatalogTrack>,
    val safeIndex: Int,
    val lookupIdentity: TrackIdentity,
    val destinations: List<DownloadPlaylistDestination>,
    val titleOverride: String?
) {
    fun queued(existing: ActiveDownload?): ActiveDownload = ActiveDownload.queued(
        id = request.downloadId,
        source = request.source,
        candidates = candidates,
        currentCandidateIndex = safeIndex,
        targetPlaylistId = request.targetPlaylistId,
        playlistTargets = destinations,
        resultSongId = existing?.resultSongId,
        lookupIdentity = lookupIdentity,
        downloadStarted = request.downloadStarted,
        storageCommitted = request.storageCommitted,
        overwriteTargetSongId = request.conflictPolicy.overwriteTargetSongIdOrNull,
        batchId = request.batchId,
        titleOverride = titleOverride
    )

    fun downloading(
        current: ActiveDownload,
        policy: DownloadConflictPolicy?,
        displayOverride: String?
    ): ActiveDownload = base(current).copy(
        overwriteTargetSongId = policy.overwriteTargetSongIdOrNull,
        titleOverride = displayOverride
    ).asDownloading()

    fun success(current: ActiveDownload?, song: Song): ActiveDownload =
        base(current).asSuccess(song)

    fun conflict(
        current: ActiveDownload?,
        activeTrack: OnlineCatalogTrack,
        existing: Song,
        displayOverride: String?
    ): Pair<DownloadConflict, ActiveDownload> {
        val row = base(current).copy(titleOverride = displayOverride).asConflict()
        return DownloadConflict(
            downloadId = row.id,
            source = row.source,
            track = activeTrack,
            existing = existing,
            candidates = row.candidates,
            currentCandidateIndex = row.currentCandidateIndex,
            targetPlaylistId = row.targetPlaylistId,
            playlistTargets = row.playlistTargets,
            batchId = row.batchId,
            applyToRemainingBatch = row.source == ActiveDownloadSource.BATCH ||
                row.source == ActiveDownloadSource.LB_IMPORT,
            lookupIdentity = row.lookupIdentity
        ) to row
    }

    private fun base(current: ActiveDownload?): ActiveDownload = (current ?: queued(null)).copy(
        targetPlaylistId = current?.targetPlaylistId ?: request.targetPlaylistId,
        playlistTargets = current?.playlistTargets?.ifEmpty { destinations } ?: destinations,
        lookupIdentity = lookupIdentity,
        batchId = request.batchId
    )
}

private class DownloadAlreadyRunningException(val ownerId: String) :
    IllegalStateException(DownloadMessages.alreadyQueued)

private class PlaylistTargetCompletionException(count: Int) :
    IllegalStateException(
        if (count == 1) {
            "La canción se guardó, pero falta agregarla a una playlist"
        } else {
            "La canción se guardó, pero faltan $count destinos de playlist"
        }
    )

internal sealed interface ProcessDownloadEvent {
    data class Completed(
        val song: Song,
        val source: ActiveDownloadSource
    ) : ProcessDownloadEvent
}

/**
 * Process-owned execution side of the online download queue.
 *
 * UI callers submit immutable requests and may await their result, but the actual child [Job] belongs
 * to [scope]. Activity/ViewModel destruction therefore cannot cancel a transfer.
 */
internal class ProcessDownloadRuntime(
    private val scope: CoroutineScope,
    private val processDownloads: ProcessDownloadCoordinator,
    private val dependencies: Dependencies
) {
    internal data class Dependencies(
        val findSong: suspend (artist: String, title: String) -> Song?,
        val download: suspend (
            track: OnlineCatalogTrack,
            conflictPolicy: DownloadConflictPolicy?,
            onProgress: (DownloadPhase) -> Unit
        ) -> Result<Song>,
        val isMetered: () -> Boolean,
        val downloadOnMeteredNetwork: suspend () -> Boolean,
        val acquireExecutionLease: suspend (ActiveDownloadSource) -> AutoCloseable = {
            AutoCloseable {}
        }
    )

    val downloads: StateFlow<List<ActiveDownload>> = processDownloads.downloads

    private val _downloadConflict = MutableStateFlow<DownloadConflict?>(null)
    val downloadConflict: StateFlow<DownloadConflict?> = _downloadConflict.asStateFlow()
    private val conflictQueueLock = Any()
    private val pendingConflicts = ArrayDeque<DownloadConflict>()

    private val _events = MutableSharedFlow<ProcessDownloadEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ProcessDownloadEvent> = _events.asSharedFlow()

    private val resumeMutex = Mutex()
    private data class BatchPolicyState(
        val policy: DownloadConflictPolicy,
        var nextSaveAsSuffix: Int
    )
    private val batchPoliciesLock = Any()
    private val batchPolicies = mutableMapOf<String, BatchPolicyState>()

    fun submit(request: ProcessDownloadRequest): Deferred<Result<Song>> =
        scope.async { runTrackedDownload(request) }

    suspend fun saveWhileListening(
        remote: PlayableItem.Remote
    ): SaveWhileListeningDownloadResult {
        val id = com.bestiapop.android.domain.util.TrackMatchKeys.downloadIdFor(
            remote.artist,
            remote.title
        )
        if (id.isBlank()) {
            return SaveWhileListeningDownloadResult.Failed(
                IllegalArgumentException("Identidad de canción incompleta")
            )
        }
        dependencies.findSong(remote.artist, remote.title)?.let {
            return SaveWhileListeningDownloadResult.Saved(it)
        }
        val result = submit(
            ProcessDownloadRequest(
                downloadId = id,
                source = ActiveDownloadSource.SAVE_WHILE_LISTENING,
                track = remote.toOnlineCatalogTrack(),
                lookupIdentity = remote.identity
            )
        ).await()
        return result.fold(
            onSuccess = SaveWhileListeningDownloadResult::Saved,
            onFailure = { error ->
                if (error is DownloadAlreadyRunningException) {
                    SaveWhileListeningDownloadResult.InFlight(error.ownerId)
                } else {
                    SaveWhileListeningDownloadResult.Failed(error)
                }
            }
        )
    }

    fun retry(id: String): Deferred<Result<Song>>? {
        val request = downloads.value.firstOrNull { it.id == id }
            ?.let(ProcessDownloadRequest::from)
            ?: return null
        return scope.async {
            processDownloads.cancelAndJoin(id)
            runTrackedDownload(request)
        }
    }

    fun resumeAllErrors(): Job = resumeRows { it.state.isFailed }

    fun resumeInterrupted(lane: DownloadLane? = null): Job = resumeRows {
        it.state.isFailed &&
            it.interrupted &&
            (lane == null || it.source.lane == lane)
    }

    fun dismiss(id: String) {
        processDownloads.dismiss(id)
    }

    fun dismissAll() {
        processDownloads.dismissAll()
    }

    fun isRunning(downloadId: String, artist: String, title: String): Boolean =
        processDownloads.isRunning(downloadId, artist, title)

    fun findClaimedDownload(
        downloadId: String,
        artist: String,
        title: String
    ): ActiveDownload? = processDownloads.findByTrack(downloadId, artist, title)

    fun attachPlaylistDestination(
        downloadId: String,
        artist: String,
        title: String,
        destination: DownloadPlaylistDestination
    ): Boolean = processDownloads.attachTargetPlaylist(
        downloadId = downloadId,
        artist = artist,
        title = title,
        target = destination
    )

    fun upsertRow(download: ActiveDownload) {
        processDownloads.upsert(download)
    }

    suspend fun awaitIdle(lane: DownloadLane? = null) {
        downloads.first { rows ->
            rows.none {
                (lane == null || it.source.lane == lane) && it.state.isInFlight
            }
        }
    }

    suspend fun resumeInterruptedAndAwaitIdle(lane: DownloadLane) {
        resumeInterrupted(lane).join()
        awaitIdle(lane)
    }

    suspend fun settleLane(lane: DownloadLane, autoResume: Boolean) {
        processDownloads.awaitHydrated()
        if (autoResume) {
            resumeInterruptedAndAwaitIdle(lane)
        } else {
            awaitIdle(lane)
        }
    }

    fun interruptNow(lane: DownloadLane) {
        processDownloads.interruptNow(lane)
    }

    fun dismissRunning(lane: DownloadLane) {
        processDownloads.dismissRunning(lane)
    }

    fun resolveConflictOverwrite(applyToRemainingBatch: Boolean = false) {
        resumeConflict(
            policy = { DownloadConflictPolicy.Overwrite(it.existing.id) },
            applyToRemainingBatch = applyToRemainingBatch
        )
    }

    fun resolveConflictSaveAs(newTitle: String, applyToRemainingBatch: Boolean = false) {
        val conflict = _downloadConflict.value ?: return
        val title = newTitle.trim().ifBlank { "${conflict.existing.title} (2)" }
        resumeConflict(
            policy = { DownloadConflictPolicy.SaveAs(title) },
            applyToRemainingBatch = applyToRemainingBatch,
            titleOverride = title
        )
    }

    fun cancelConflict() {
        val conflict = synchronized(conflictQueueLock) {
            val current = pendingConflicts.removeFirstOrNull() ?: return
            _downloadConflict.value = pendingConflicts.firstOrNull()
            current
        }
        processDownloads.remove(conflict.downloadId)
    }

    fun clearBatchConflictPolicy() {
        synchronized(batchPoliciesLock) {
            batchPolicies.clear()
        }
    }

    private fun resumeRows(predicate: (ActiveDownload) -> Boolean): Job = scope.launch {
        processDownloads.awaitHydrated()
        resumeMutex.withLock {
            downloads.value
                .filter(predicate)
                .mapNotNull(ProcessDownloadRequest::from)
                .map { request ->
                    async {
                        val lookup = request.lookupIdentity ?: request.track.identity
                        if (processDownloads.isRunning(
                                request.downloadId,
                                lookup.artist,
                                lookup.title
                            )
                        ) {
                            Result.failure<Song>(
                                IllegalStateException(DownloadMessages.alreadyQueued)
                            )
                        } else {
                            runTrackedDownload(request)
                        }
                    }
                }
                .awaitAll()
        }
    }

    private fun resumeConflict(
        policy: (DownloadConflict) -> DownloadConflictPolicy,
        applyToRemainingBatch: Boolean,
        titleOverride: String? = null
    ) {
        val conflict = _downloadConflict.value ?: return
        val applyAll = applyToRemainingBatch || conflict.applyToRemainingBatch
        val resolved = policy(conflict)
        if (applyAll && conflict.batchId != null) {
            synchronized(batchPoliciesLock) {
                batchPolicies[conflict.batchId] = BatchPolicyState(
                    policy = resolved,
                    nextSaveAsSuffix = if (resolved is DownloadConflictPolicy.SaveAs) 2 else 1
                )
            }
        }
        val queued = synchronized(conflictQueueLock) {
            pendingConflicts.remove(conflict)
            val related = if (applyAll && conflict.batchId != null) {
                pendingConflicts.filter { it.batchId == conflict.batchId }.also {
                    pendingConflicts.removeAll(it.toSet())
                }
            } else {
                emptyList()
            }
            _downloadConflict.value = pendingConflicts.firstOrNull()
            related
        }
        submit(conflict.toRequest(resolved, titleOverride))
        queued.forEach { pending ->
            submit(pending.toRequest(conflictPolicy = null, titleOverride = null))
        }
    }

    private fun DownloadConflict.toRequest(
        conflictPolicy: DownloadConflictPolicy?,
        titleOverride: String?
    ) = ProcessDownloadRequest(
        downloadId = downloadId,
        source = source,
        track = track,
        candidates = candidates,
        currentCandidateIndex = currentCandidateIndex,
        targetPlaylistId = targetPlaylistId,
        playlistTargets = playlistTargets,
        conflictPolicy = conflictPolicy,
        lookupIdentity = lookupIdentity,
        batchId = batchId,
        titleOverride = titleOverride
    )

    private suspend fun runTrackedDownload(request: ProcessDownloadRequest): Result<Song> {
        val candidates = request.candidates.ifEmpty { listOf(request.track) }
        val safeIndex = request.currentCandidateIndex
            .coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
        val lookup = request.lookupIdentity ?: request.track.identity
        val destinations = resolveDownloadPlaylistDestinations(
            request.playlistTargets,
            request.targetPlaylistId,
            lookup
        )
        val existing = findClaimedDownload(
            downloadId = request.downloadId,
            artist = lookup.artist,
            title = lookup.title
        )
        val titleOverride = request.titleOverride
            ?: request.conflictPolicy.saveAsTitleOrNull
            ?: existing?.titleOverride
        val context = DownloadExecutionContext(
            request = request,
            candidates = candidates,
            safeIndex = safeIndex,
            lookupIdentity = lookup,
            destinations = destinations,
            titleOverride = titleOverride
        )
        var reusedExisting = false
        var executionLease: AutoCloseable? = null

        return try {
            val result = when (
                val coordinated = processDownloads.execute(
                    downloadId = request.downloadId,
                    artist = lookup.artist,
                    title = lookup.title,
                    playlistTarget = destinations.firstOrNull(),
                    onRegistered = {
                        processDownloads.upsert(context.queued(existing))
                        destinations.drop(1).forEach { target ->
                            processDownloads.attachTargetPlaylist(
                                downloadId = request.downloadId,
                                artist = lookup.artist,
                                title = lookup.title,
                                target = target
                            )
                        }
                    },
                    beforePermit = {
                        processDownloads.flushDurably()
                        executionLease = dependencies.acquireExecutionLease(request.source)
                        if (request.conflictPolicy == null) {
                            awaitConflictDialogFree(request.batchId)
                        }
                    }
                ) {
                    if (!request.reconcileCommittedAsSuccess) {
                        ensureDownloadNetworkAllowed()
                    } else {
                        null
                    }?.let { blockedMessage ->
                        processDownloads.update(request.downloadId) { row ->
                            row.asError(blockedMessage)
                        }
                        return@execute Result.failure(IllegalStateException(blockedMessage))
                    }
                    runTrackedDownloadLocked(
                        context = context,
                        onReusedExisting = { reusedExisting = true }
                    )
                }
            ) {
                is CoordinatedDownloadResult.Completed -> {
                    if (coordinated.incompletePlaylistTargets.isEmpty()) {
                        coordinated.result
                    } else {
                        Result.failure(
                            PlaylistTargetCompletionException(
                                coordinated.incompletePlaylistTargets.size
                            )
                        )
                    }
                }
                is CoordinatedDownloadResult.AlreadyRunning ->
                    Result.failure(DownloadAlreadyRunningException(coordinated.downloadId))
            }
            if (result.exceptionOrNull() is PlaylistTargetCompletionException) {
                processDownloads.update(request.downloadId) {
                    it.asError(result.exceptionOrNull()?.message, interrupted = true)
                        .copy(downloadStarted = true)
                }
            }
            result.getOrNull()?.let { song ->
                completeSuccessfulRequest(
                    context = context,
                    song = song,
                    recordDownloadedBytes = !reusedExisting
                )
            }
            result
        } catch (cancelled: CancellationException) {
            processDownloads.update(request.downloadId) {
                it.asError(DownloadMessages.interrupted, interrupted = true)
            }
            throw cancelled
        } catch (error: Throwable) {
            processDownloads.update(request.downloadId) {
                it.asError(mapDownloadError(error))
            }
            Result.failure(error)
        } finally {
            executionLease?.close()
            clearBatchPolicyIfIdle(request.batchId)
        }
    }

    private suspend fun runTrackedDownloadLocked(
        context: DownloadExecutionContext,
        onReusedExisting: () -> Unit
    ): Result<Song> {
        val request = context.request
        val activeTrack = context.candidates.getOrNull(context.safeIndex) ?: request.track
        val resolvedPolicy = request.conflictPolicy
            ?: request.batchId?.let {
                applyBatchPolicy(activeTrack, context.lookupIdentity, it)
            }
        val displayOverride = context.titleOverride
            ?: resolvedPolicy.saveAsTitleOrNull

        if (request.reconcileCommittedAsSuccess) {
            val committedLookup = when (resolvedPolicy) {
                is DownloadConflictPolicy.SaveAs ->
                    context.lookupIdentity.copy(title = resolvedPolicy.newTitle)
                else -> context.lookupIdentity
            }
            resolveExistingSong(activeTrack, committedLookup)?.let { existing ->
                onReusedExisting()
                return Result.success(existing)
            }
        }

        if (resolvedPolicy == null) {
            val existing = resolveExistingSong(activeTrack, context.lookupIdentity)
            if (existing != null) {
                if (request.treatDuplicateAsSuccess) {
                    onReusedExisting()
                    return Result.success(existing)
                }
                markDownloadConflict(
                    context = context,
                    activeTrack = activeTrack,
                    existing = existing,
                    titleOverride = displayOverride
                )
                return Result.failure(DuplicateSongException(existing, activeTrack))
            }
        }

        processDownloads.update(request.downloadId) { row ->
            context.downloading(row, resolvedPolicy, displayOverride)
        }
        val trackForDownload = when (val policy = resolvedPolicy) {
            is DownloadConflictPolicy.SaveAs ->
                activeTrack.withIdentity { copy(title = policy.newTitle) }
            else -> activeTrack
        }
        val result = dependencies.download(trackForDownload, resolvedPolicy) { phase ->
            processDownloads.updateProgress(request.downloadId) {
                it.copy(
                    state = CandidateDownloadState.DOWNLOADING,
                    progressMessage = phase.userMessage,
                    progressPercent = phase.percent,
                    interrupted = false,
                    storageCommitted = it.storageCommitted ||
                        phase is DownloadPhase.Completed ||
                        phase is DownloadPhase.Overwritten
                )
            }
        }
        if (result.isSuccess) {
            processDownloads.update(request.downloadId) {
                it.copy(
                    state = CandidateDownloadState.DOWNLOADING,
                    downloadStarted = true,
                    storageCommitted = true,
                    progressMessage = DownloadMessages.saving,
                    progressPercent = 100
                )
            }
            processDownloads.flushDurably()
        }

        val duplicate = result.exceptionOrNull() as? DuplicateSongException
        if (duplicate != null && resolvedPolicy == null) {
            if (request.treatDuplicateAsSuccess) {
                onReusedExisting()
                return Result.success(duplicate.existing)
            }
            markDownloadConflict(
                context = context,
                activeTrack = duplicate.track,
                existing = duplicate.existing,
                titleOverride = displayOverride
            )
            return result
        }

        result.exceptionOrNull()?.let { error ->
            if (error is DuplicateSongException) return@let
            CrashReporter.recordNonFatal(
                error,
                mapOf(
                    "download_phase" to "tracked_download",
                    "download_source" to request.source.name,
                    "download_id" to request.downloadId,
                    "track_title" to activeTrack.title,
                    "track_artist" to activeTrack.artist
                )
            )
            processDownloads.update(request.downloadId) {
                it.asError(mapDownloadError(error))
            }
        }
        return result
    }

    private suspend fun completeSuccessfulRequest(
        context: DownloadExecutionContext,
        song: Song,
        recordDownloadedBytes: Boolean
    ) {
        val request = context.request
        val current = downloads.value.firstOrNull { it.id == request.downloadId }
        processDownloads.update(request.downloadId) {
            context.success(current, song)
        }
        if (recordDownloadedBytes) {
            processDownloads.recordCompletedDownload(song)
        }
        _events.tryEmit(ProcessDownloadEvent.Completed(song, request.source))
    }

    private fun clearBatchPolicyIfIdle(batchId: String?) {
        if (batchId == null) return
        val hasPendingBatchDecisionOrWork = downloads.value.any { row ->
            row.batchId == batchId &&
                (row.state.isInFlight || row.state == CandidateDownloadState.IDLE)
        }
        if (!hasPendingBatchDecisionOrWork) {
            synchronized(batchPoliciesLock) {
                batchPolicies.remove(batchId)
            }
        }
    }

    private suspend fun resolveExistingSong(
        activeTrack: OnlineCatalogTrack,
        lookup: TrackIdentity
    ): Song? = dependencies.findSong(
        lookup.artist.ifBlank { activeTrack.artist },
        lookup.title.ifBlank { activeTrack.title }
    )

    private suspend fun applyBatchPolicy(
        activeTrack: OnlineCatalogTrack,
        lookup: TrackIdentity,
        batchId: String
    ): DownloadConflictPolicy? {
        val cached = synchronized(batchPoliciesLock) {
            batchPolicies[batchId]?.let { state ->
                val suffix = state.nextSaveAsSuffix
                if (state.policy is DownloadConflictPolicy.SaveAs) {
                    state.nextSaveAsSuffix++
                }
                state.policy to suffix
            }
        } ?: return null
        return when (cached.first) {
            is DownloadConflictPolicy.Overwrite ->
                resolveExistingSong(activeTrack, lookup)
                    ?.let { DownloadConflictPolicy.Overwrite(it.id) }
            is DownloadConflictPolicy.SaveAs -> {
                val base = lookup.title.ifBlank { activeTrack.title.ifBlank { "Track" } }
                DownloadConflictPolicy.SaveAs("$base (${cached.second})")
            }
        }
    }

    private fun markDownloadConflict(
        context: DownloadExecutionContext,
        activeTrack: OnlineCatalogTrack,
        existing: Song,
        titleOverride: String?
    ) {
        val request = context.request
        val current = downloads.value.firstOrNull { it.id == request.downloadId }
        val (conflict, row) = context.conflict(
            current = current,
            activeTrack = activeTrack,
            existing = existing,
            displayOverride = titleOverride
        )
        synchronized(conflictQueueLock) {
            if (pendingConflicts.none { it.downloadId == conflict.downloadId }) {
                pendingConflicts.addLast(conflict)
            }
            _downloadConflict.value = pendingConflicts.firstOrNull()
        }
        processDownloads.update(request.downloadId) {
            row
        }
    }

    private suspend fun awaitConflictDialogFree(batchId: String?) {
        val hasBatchPolicy: () -> Boolean = {
            batchId != null && synchronized(batchPoliciesLock) { batchPolicies[batchId] != null }
        }
        if (_downloadConflict.value == null || hasBatchPolicy()) return
        withTimeoutOrNull(CONFLICT_WAIT_TIMEOUT_MS) {
            _downloadConflict.first { conflict ->
                conflict == null || hasBatchPolicy()
            }
        }
    }

    private suspend fun ensureDownloadNetworkAllowed(): String? {
        if (!dependencies.isMetered()) return null
        return if (dependencies.downloadOnMeteredNetwork()) {
            null
        } else {
            DownloadMessages.blockedOnMetered
        }
    }

    private fun mapDownloadError(error: Throwable): String = when {
        error is DuplicateSongException ->
            "Ya existe en la biblioteca: ${error.existing.artist} — ${error.existing.title}"
        error.message?.contains("403") == true ->
            "Error HTTP 403 Forbidden: Enlace o firma expirada de YouTube."
        error.message?.contains("YouTube") == true ->
            error.message ?: "No se pudo obtener audio de YouTube."
        else -> DownloadMessages.downloadFailed(error.localizedMessage ?: "Error de red.")
    }

    companion object {
        private const val CONFLICT_WAIT_TIMEOUT_MS = 30_000L

        fun create(
            context: Context,
            scope: CoroutineScope,
            repository: MusicRepository,
            processDownloads: ProcessDownloadCoordinator,
            acquireExecutionLease: suspend (ActiveDownloadSource) -> AutoCloseable = {
                AutoCloseable {}
            }
        ): ProcessDownloadRuntime {
            val useCase = DownloadAudioTrackUseCase(repository)
            val connectivity = ConnectivityObserver(context)
            val preferences = DownloadPreferencesRepository(context)
            return ProcessDownloadRuntime(
                scope = scope,
                processDownloads = processDownloads,
                dependencies = Dependencies(
                    findSong = repository::findSongByArtistTitle,
                    download = { track, policy, onProgress ->
                        useCase.execute(track, onProgress, policy)
                    },
                    isMetered = connectivity::isMetered,
                    downloadOnMeteredNetwork = {
                        preferences.settingsFlow.first().downloadOnMeteredNetwork
                    },
                    acquireExecutionLease = acquireExecutionLease
                )
            )
        }
    }
}
