package com.bestiapop.android.service

import android.content.Context
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.LibraryJobKind
import com.bestiapop.android.data.model.LibraryJobProgress
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.IdentifyReviewStore
import com.bestiapop.android.data.preferences.IdentifyWorkSnapshot
import com.bestiapop.android.data.preferences.IdentifyWorkStore
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.data.util.CrashReporter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

data class IdentifyBatchSummary(
    val updated: Int,
    val reviewCount: Int,
    val alreadyQueued: Int,
    val skipped: Int,
    val showReview: Boolean
) {
    fun toastMessage(): String = buildString {
        append(if (updated == 1) "1 actualizada" else "$updated actualizadas")
        if (reviewCount > 0) {
            append(if (reviewCount == 1) ", 1 para revisar" else ", $reviewCount para revisar")
        }
        if (alreadyQueued > 0) {
            append(
                if (alreadyQueued == 1) ", 1 ya en revisión"
                else ", $alreadyQueued ya en revisión"
            )
        }
        if (skipped == 1) append(", 1 omitida")
        else if (skipped > 1) append(", $skipped omitidas")
    }
}

sealed interface ProcessIdentifyEvent {
    data class Completed(val summary: IdentifyBatchSummary) : ProcessIdentifyEvent
    data class AlreadyQueued(val count: Int, val showReview: Boolean) : ProcessIdentifyEvent
}

/**
 * Process-owned identify batch. Activity/ViewModel destruction cannot cancel lookup.
 */
internal class ProcessIdentifyRuntime(
    private val scope: CoroutineScope,
    private val dependencies: Dependencies
) {
    internal data class Dependencies(
        val getSong: suspend (Long) -> Song?,
        val propose: suspend (Song, force: Boolean, listenBrainzToken: String?) -> IdentifyProposal,
        val apply: suspend (songId: Long, proposal: IdentifyProposal, fields: IdentifyApplyFields) ->
            IdentifyResult,
        val listenBrainzToken: suspend () -> String?,
        val pendingSongIds: suspend () -> Set<Long>,
        val appendReview: suspend (IdentifyProposal, IdentifyApplyFields) -> Unit,
        val loadWork: suspend () -> IdentifyWorkSnapshot?,
        val saveWork: suspend (IdentifyWorkSnapshot?) -> Unit,
        val acquireExecutionLease: suspend () -> AutoCloseable = { AutoCloseable {} },
        val notifyCompleted: (IdentifyBatchSummary) -> Unit = {},
        val reportTelemetry: (IdentifyWorkSnapshot) -> Unit = {}
    )

    private val workMutex = Mutex()
    private val runMutex = Mutex()
    private val _progress = MutableStateFlow<LibraryJobProgress?>(null)
    val progress: StateFlow<LibraryJobProgress?> = _progress.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _events = MutableSharedFlow<ProcessIdentifyEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ProcessIdentifyEvent> = _events.asSharedFlow()

    private var snapshot: IdentifyWorkSnapshot? = null
    private var hydrated = false

    fun submit(
        songs: List<Song>,
        force: Boolean = false,
        showReview: Boolean = true,
        fields: IdentifyApplyFields = IdentifyApplyFields.ALL
    ): Job = scope.launch {
        val started = enqueue(songs, force, showReview, fields)
        if (started) {
            runMutex.withLock { processUntilEmpty() }
        }
    }

    fun resumeInterrupted(): Job = scope.launch {
        ensureHydrated()
        val remaining = workMutex.withLock { snapshot?.remainingSongIds.orEmpty() }
        if (remaining.isEmpty()) return@launch
        runMutex.withLock { processUntilEmpty() }
    }

    suspend fun awaitIdle() {
        running.first { !it }
    }

    suspend fun settle(autoResume: Boolean) {
        if (autoResume) {
            resumeInterrupted().join()
        }
        awaitIdle()
    }

    fun interruptNow() {
        scope.launch {
            workMutex.withLock {
                val current = snapshot ?: return@withLock
                if (!current.hasRemaining) return@withLock
                val interrupted = current.copy(interrupted = true)
                snapshot = interrupted
                persistLocked(interrupted)
            }
        }
    }

    fun cancelUser() {
        scope.launch {
            workMutex.withLock {
                snapshot = null
                persistLocked(null)
                _progress.value = null
            }
        }
    }

    private suspend fun enqueue(
        songs: List<Song>,
        force: Boolean,
        showReview: Boolean,
        fields: IdentifyApplyFields
    ): Boolean {
        if (songs.isEmpty()) return false
        ensureHydrated()
        var alreadyQueued = 0
        var started = false
        var queuedOnly = false
        workMutex.withLock {
            val pending = dependencies.pendingSongIds()
            val inFlight = snapshot?.remainingSongIds.orEmpty().toSet()
            val toProcess = songs.filter { it.id !in pending && it.id !in inFlight }
            alreadyQueued = songs.count { it.id in pending }
            if (toProcess.isEmpty()) {
                queuedOnly = alreadyQueued > 0
                if (queuedOnly && snapshot != null) {
                    snapshot = snapshot?.copy(
                        alreadyQueued = snapshot!!.alreadyQueued + alreadyQueued,
                        showReview = snapshot!!.showReview || showReview
                    )
                    persistLocked(snapshot)
                }
                return@withLock
            }
            val current = snapshot
            val remaining = (current?.remainingSongIds.orEmpty() + toProcess.map { it.id }).distinct()
            snapshot = IdentifyWorkSnapshot(
                remainingSongIds = remaining,
                force = (current?.force == true) || force,
                showReview = (current?.showReview == true) || showReview,
                applyFields = fields,
                processedCount = current?.processedCount ?: 0,
                totalCount = (current?.totalCount ?: 0) + toProcess.size,
                updated = current?.updated ?: 0,
                skipped = current?.skipped ?: 0,
                medium = current?.medium ?: 0,
                low = current?.low ?: 0,
                none = current?.none ?: 0,
                lbHits = current?.lbHits ?: 0,
                alreadyQueued = (current?.alreadyQueued ?: 0) + alreadyQueued,
                reviewCount = current?.reviewCount ?: 0,
                interrupted = false
            )
            persistLocked(snapshot)
            started = true
        }
        if (queuedOnly) {
            _events.tryEmit(ProcessIdentifyEvent.AlreadyQueued(alreadyQueued, showReview))
        }
        return started
    }

    private suspend fun processUntilEmpty() {
        ensureHydrated()
        var lease: AutoCloseable? = null
        _running.value = true
        try {
            while (true) {
                val next = workMutex.withLock {
                    val current = snapshot
                    val id = current?.remainingSongIds?.firstOrNull()
                    if (current == null || id == null) {
                        Triple(null, current, true)
                    } else {
                        Triple(id, current, false)
                    }
                }
                val songId = next.first
                val current = next.second
                if (next.third) {
                    finishBatch(current)
                    break
                }
                if (songId == null || current == null) break
                if (lease == null) {
                    lease = dependencies.acquireExecutionLease()
                }
                try {
                    processOne(songId, current)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    commitProcessed(songId) { it.copy(skipped = it.skipped + 1) }
                }
            }
        } catch (cancelled: CancellationException) {
            workMutex.withLock {
                val current = snapshot ?: return@withLock
                if (current.hasRemaining) {
                    val interrupted = current.copy(interrupted = true)
                    snapshot = interrupted
                    persistLocked(interrupted)
                }
            }
            throw cancelled
        } finally {
            lease?.close()
            _progress.value = null
            _running.value = false
        }
    }

    private suspend fun processOne(songId: Long, baseline: IdentifyWorkSnapshot) {
        val song = dependencies.getSong(songId)
        val total = baseline.totalCount.coerceAtLeast(1)
        _progress.value = LibraryJobProgress(
            kind = LibraryJobKind.IDENTIFY,
            done = baseline.processedCount,
            total = total,
            label = song?.title.orEmpty()
        )
        if (song == null) {
            commitProcessed(songId) { it.copy(skipped = it.skipped + 1) }
            return
        }
        val token = dependencies.listenBrainzToken()
        val proposal = dependencies.propose(song, baseline.force, token)
        var deltaUpdated = 0
        var deltaSkipped = 0
        var deltaMedium = 0
        var deltaLow = 0
        var deltaNone = 0
        var deltaLbHits = if (proposal.usedListenBrainz) 1 else 0
        var deltaReview = 0
        when {
            proposal.alreadyIdentified -> deltaSkipped = 1
            proposal.confidence == IdentifyConfidence.HIGH && proposal.suggested != null -> {
                when (dependencies.apply(song.id, proposal, baseline.applyFields)) {
                    is IdentifyResult.Updated -> deltaUpdated = 1
                    else -> {
                        dependencies.appendReview(proposal, baseline.applyFields)
                        deltaReview = 1
                        deltaMedium = 1
                    }
                }
            }
            else -> {
                dependencies.appendReview(proposal, baseline.applyFields)
                deltaReview = 1
                when (proposal.confidence) {
                    IdentifyConfidence.MEDIUM -> deltaMedium = 1
                    IdentifyConfidence.LOW -> deltaLow = 1
                    else -> deltaNone = 1
                }
            }
        }
        commitProcessed(songId) { snap ->
            snap.copy(
                updated = snap.updated + deltaUpdated,
                skipped = snap.skipped + deltaSkipped,
                medium = snap.medium + deltaMedium,
                low = snap.low + deltaLow,
                none = snap.none + deltaNone,
                lbHits = snap.lbHits + deltaLbHits,
                reviewCount = snap.reviewCount + deltaReview
            )
        }
    }

    private suspend fun commitProcessed(
        songId: Long,
        transform: (IdentifyWorkSnapshot) -> IdentifyWorkSnapshot
    ) {
        workMutex.withLock {
            val current = snapshot ?: return@withLock
            val remaining = current.remainingSongIds.filterNot { it == songId }
            val next = transform(current).copy(
                remainingSongIds = remaining,
                processedCount = current.processedCount + 1,
                interrupted = false
            )
            snapshot = next
            persistLocked(next)
        }
    }

    private suspend fun finishBatch(current: IdentifyWorkSnapshot?) {
        val summary = current?.let {
            IdentifyBatchSummary(
                updated = it.updated,
                reviewCount = it.reviewCount,
                alreadyQueued = it.alreadyQueued,
                skipped = it.skipped,
                showReview = it.showReview
            )
        }
        current?.let(dependencies.reportTelemetry)
        workMutex.withLock {
            snapshot = null
            persistLocked(null)
        }
        if (summary != null &&
            (summary.updated > 0 || summary.reviewCount > 0 ||
                summary.alreadyQueued > 0 || summary.skipped > 0 ||
                (current?.totalCount ?: 0) > 0)
        ) {
            _events.tryEmit(ProcessIdentifyEvent.Completed(summary))
            dependencies.notifyCompleted(summary)
        }
    }

    private suspend fun ensureHydrated() {
        if (hydrated) return
        workMutex.withLock {
            if (hydrated) return@withLock
            snapshot = dependencies.loadWork()
            hydrated = true
        }
    }

    private suspend fun persistLocked(snapshot: IdentifyWorkSnapshot?) {
        dependencies.saveWork(snapshot)
    }

    companion object {
        fun create(
            context: Context,
            scope: CoroutineScope,
            repository: MusicRepository,
            acquireExecutionLease: suspend () -> AutoCloseable = { AutoCloseable {} }
        ): ProcessIdentifyRuntime {
            val workStore = IdentifyWorkStore(context)
            val reviewStore = IdentifyReviewStore(context)
            val listenBrainzPreferences = ListenBrainzPreferencesRepository(context)
            return ProcessIdentifyRuntime(
                scope = scope,
                dependencies = Dependencies(
                    getSong = { id -> repository.getAllSongsSync().find { it.id == id } },
                    propose = { song, force, token ->
                        repository.proposeSongIdentity(
                            song = song,
                            force = force,
                            listenBrainzToken = token
                        )
                    },
                    apply = { songId, proposal, fields ->
                        val suggested = proposal.suggested
                            ?: return@Dependencies IdentifyResult.NoMatch
                        repository.applySongIdentity(songId, suggested, fields)
                    },
                    listenBrainzToken = {
                        val settings = listenBrainzPreferences.settingsFlow.first()
                        settings.userToken.takeIf { settings.enabled && it.isNotBlank() }
                    },
                    pendingSongIds = {
                        reviewStore.load().proposals.map { it.songId }.toSet()
                    },
                    appendReview = { proposal, fields ->
                        reviewStore.appendProposals(listOf(proposal), fields)
                    },
                    loadWork = { workStore.load() },
                    saveWork = { workStore.save(it) },
                    acquireExecutionLease = acquireExecutionLease,
                    notifyCompleted = { summary ->
                        IdentifyNotificationHelper(context).notifyCompleted(summary)
                    },
                    reportTelemetry = { snap ->
                        CrashReporter.setKey("identify_high", "${snap.updated}")
                        CrashReporter.setKey("identify_medium", "${snap.medium}")
                        CrashReporter.setKey("identify_low", "${snap.low}")
                        CrashReporter.setKey("identify_none", "${snap.none}")
                        CrashReporter.setKey("identify_skipped", "${snap.skipped}")
                        CrashReporter.setKey("identify_lb_hits", "${snap.lbHits}")
                        CrashReporter.log(
                            "identify_batch high=${snap.updated} medium=${snap.medium} " +
                                "low=${snap.low} none=${snap.none} skipped=${snap.skipped} " +
                                "lb_hits=${snap.lbHits}"
                        )
                    }
                )
            )
        }
    }
}
