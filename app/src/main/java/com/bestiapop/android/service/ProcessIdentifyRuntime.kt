package com.bestiapop.android.service

import android.content.Context
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.LibraryJobKind
import com.bestiapop.android.data.model.LibraryJobProgress
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.ConnectivityObserver
import com.bestiapop.android.data.preferences.IdentifyReviewStore
import com.bestiapop.android.data.preferences.IdentifyWorkSnapshot
import com.bestiapop.android.data.preferences.IdentifyWorkStore
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.domain.usecase.IdentifyPipeline
import com.bestiapop.android.domain.util.AlbumTrackMatchResult
import com.bestiapop.android.domain.util.IdentifyAlbumBatchCandidate
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.KnownAlbumTracks
import com.bestiapop.android.domain.util.albumNamesMatch
import com.bestiapop.android.domain.util.applyKnownAlbumMatches
import com.bestiapop.android.domain.util.artistsCompatible
import com.bestiapop.android.domain.util.assignUniqueKnownAlbumMatches
import com.bestiapop.android.domain.util.findNextAlbumBatchCandidate
import com.bestiapop.android.domain.util.gapApplyFields
import com.bestiapop.android.domain.util.knownAlbumQueryOf
import com.bestiapop.android.domain.util.matchAndApplyKnownAlbumTracks
import com.bestiapop.android.domain.util.partitionAlbumBatchSongs
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.withContext

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
            append(" ($alreadyQueued ya en cola)")
        }
        if (skipped > 0) {
            append(" ($skipped omitidas)")
        }
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
    private val dependencies: Dependencies,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    internal data class Dependencies(
        val getSong: suspend (Long) -> Song?,
        val getSongs: (suspend (List<Long>) -> List<Song>)? = null,
        val propose: suspend (Song, force: Boolean, listenBrainzToken: String?) -> IdentifyProposal,
        val apply: suspend (songId: Long, proposal: IdentifyProposal, fields: IdentifyApplyFields) ->
            IdentifyResult,
        val listenBrainzToken: suspend () -> String?,
        val pendingSongIds: suspend () -> Set<Long>,
        val appendReview: suspend (List<IdentifyProposal>, IdentifyApplyFields) -> Unit,
        val loadWork: suspend () -> IdentifyWorkSnapshot?,
        val saveWork: suspend (IdentifyWorkSnapshot?) -> Unit,
        val isOnline: () -> Boolean = { true },
        val acquireExecutionLease: suspend () -> AutoCloseable = { AutoCloseable {} },
        val notifyCompleted: (IdentifyBatchSummary) -> Unit = {},
        val reportTelemetry: (IdentifyWorkSnapshot) -> Unit = {},
        val loadScopedAlbumTracks: suspend (artist: String, album: String) -> KnownAlbumTracks? =
            { _, _ -> null },
        val setAlbumArtwork: (suspend (albumKey: String, artworkUri: String?) -> Unit)? = null,
        val searchAlbums: (suspend (query: String) -> List<CatalogAlbum>)? = null
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
    private var unpersistedWorkEdits = 0
    private val reviewBuffer = ArrayList<IdentifyProposal>()
    private var reviewBufferFields: IdentifyApplyFields? = null
    private val attemptedAlbumGroupKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val songIdentityCache = java.util.concurrent.ConcurrentHashMap<Long, Song>()

    private val activeWorkerJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
    @Volatile
    private var userCancelled = false

    fun submit(
        songs: List<Song>,
        force: Boolean = false,
        showReview: Boolean = true,
        fields: IdentifyApplyFields = IdentifyApplyFields.ALL,
        fillGapsOnly: Boolean = false
    ): Job {
        userCancelled = false
        val job = scope.launch(ioDispatcher) {
            val started = enqueue(songs, force, showReview, fields, fillGapsOnly)
            if (started) {
                runMutex.withLock { processUntilEmpty() }
            }
        }
        activeWorkerJobs.add(job)
        job.invokeOnCompletion { activeWorkerJobs.remove(job) }
        return job
    }

    fun resumeInterrupted(): Job {
        userCancelled = false
        val job = scope.launch(ioDispatcher) {
            ensureHydrated()
            val remaining = workMutex.withLock { snapshot?.remainingSongIds.orEmpty() }
            if (remaining.isEmpty()) return@launch
            runMutex.withLock { processUntilEmpty() }
        }
        activeWorkerJobs.add(job)
        job.invokeOnCompletion { activeWorkerJobs.remove(job) }
        return job
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
        scope.launch(ioDispatcher) {
            flushReviewBuffer()
            workMutex.withLock {
                val current = snapshot ?: return@withLock
                if (!current.hasRemaining) return@withLock
                val interrupted = current.copy(interrupted = true)
                snapshot = interrupted
                persistLocked(interrupted, force = true)
            }
        }
    }

    fun cancelUser() {
        userCancelled = true
        val jobsToCancel = ArrayList(activeWorkerJobs)
        activeWorkerJobs.clear()
        jobsToCancel.forEach { it.cancel() }
        scope.launch(ioDispatcher) {
            flushReviewBuffer()
            workMutex.withLock {
                snapshot = null
                persistLocked(null, force = true)
                _progress.value = null
                _running.value = false
                attemptedAlbumGroupKeys.clear()
                songIdentityCache.clear()
            }
        }
    }

    private suspend fun enqueue(
        songs: List<Song>,
        force: Boolean,
        showReview: Boolean,
        fields: IdentifyApplyFields,
        fillGapsOnly: Boolean
    ): Boolean {
        if (songs.isEmpty()) return false
        ensureHydrated()
        songs.forEach { songIdentityCache[it.id] = it }
        var alreadyQueued = 0
        var started = false
        var queuedOnly = false
        workMutex.withLock {
            attemptedAlbumGroupKeys.clear()
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
                    persistLocked(snapshot, force = true)
                }
                return@withLock
            }
            val sortedToProcess = toProcess.sortedWith(
                compareBy<Song>(
                    { it.folderPath.orEmpty().lowercase() },
                    { it.album.takeUnless { a -> IdentifyRanking.isGenericAlbum(a) }.orEmpty().lowercase() },
                    { it.artist.takeUnless { a -> IdentifyRanking.isPlaceholderArtist(a) }.orEmpty().lowercase() },
                    { it.trackNumber.takeIf { t -> t > 0 } ?: Int.MAX_VALUE },
                    { it.title.lowercase() }
                )
            )
            val current = snapshot
            val remaining = (current?.remainingSongIds.orEmpty() + sortedToProcess.map { it.id }).distinct()
            val newIds = sortedToProcess.map { it.id }
            val fillGapsIds = current?.fillGapsOnlySongIds.orEmpty() +
                if (fillGapsOnly) newIds else emptyList()
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
                interrupted = false,
                fillGapsOnlySongIds = fillGapsIds
            )
            persistLocked(snapshot, force = true)
            started = true
        }
        if (queuedOnly) {
            _events.tryEmit(ProcessIdentifyEvent.AlreadyQueued(alreadyQueued, showReview))
        }
        return started
    }

    private sealed interface WorkTask {
        data class AlbumGroup(val candidate: IdentifyAlbumBatchCandidate) : WorkTask
        data class SingleSong(val songId: Long) : WorkTask
    }

    private suspend fun processUntilEmpty() = withContext(ioDispatcher) {
        ensureHydrated()
        var lease: AutoCloseable? = null
        _running.value = true
        try {
            if (workMutex.withLock { snapshot?.hasRemaining == true }) {
                lease = dependencies.acquireExecutionLease()
            }
            val missingIds = workMutex.withLock {
                snapshot?.remainingSongIds.orEmpty().filter { !songIdentityCache.containsKey(it) }
            }
            if (missingIds.isNotEmpty()) {
                val loaded = dependencies.getSongs?.invoke(missingIds)
                    ?: missingIds.mapNotNull { dependencies.getSong(it) }
                loaded.forEach { songIdentityCache[it.id] = it }
            }

            coroutineScope {
                val inFlight = mutableSetOf<Long>()
                val token = dependencies.listenBrainzToken()
                repeat(IDENTIFY_PARALLEL) {
                    launch(ioDispatcher) {
                        while (true) {
                            if (!dependencies.isOnline()) {
                                workMutex.withLock { markInterruptedLocked() }
                                break
                            }
                            val task: WorkTask = workMutex.withLock {
                                val currentSnap = snapshot ?: return@withLock null
                                val availableIds = currentSnap.remainingSongIds.filter { it !in inFlight }
                                if (availableIds.isEmpty()) return@withLock null

                                val availableSongs = availableIds.mapNotNull { songIdentityCache[it] }
                                val albumBatch = findNextAlbumBatchCandidate(
                                    availableSongs = availableSongs,
                                    batchFields = currentSnap.applyFields,
                                    fillGapsOnlyIds = currentSnap.fillGapsOnlySongIds.toSet(),
                                    attemptedGroupKeys = attemptedAlbumGroupKeys
                                )
                                if (albumBatch != null) {
                                    inFlight += albumBatch.songIds
                                    return@withLock WorkTask.AlbumGroup(albumBatch)
                                }

                                val songId = availableIds.first()
                                inFlight += songId
                                WorkTask.SingleSong(songId)
                            } ?: break

                            when (task) {
                                is WorkTask.AlbumGroup -> {
                                    try {
                                        val baseline = workMutex.withLock { snapshot } ?: break
                                        processAlbumGroup(task.candidate, baseline, inFlight, token)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        attemptedAlbumGroupKeys += task.candidate.groupKey
                                    } finally {
                                        workMutex.withLock { inFlight.removeAll(task.candidate.songIds.toSet()) }
                                    }
                                }
                                is WorkTask.SingleSong -> {
                                    try {
                                        val baseline = workMutex.withLock { snapshot } ?: break
                                        processOne(task.songId, baseline, inFlight, token)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        commitProcessed(task.songId) { it.copy(skipped = it.skipped + 1) }
                                    } finally {
                                        workMutex.withLock { inFlight.remove(task.songId) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            flushReviewBuffer()
            val current = workMutex.withLock { snapshot }
            if (current == null || !current.hasRemaining) {
                finishBatch(current)
            } else {
                workMutex.withLock { markInterruptedLocked() }
            }
        } catch (cancelled: CancellationException) {
            flushReviewBuffer()
            workMutex.withLock {
                if (!userCancelled) {
                    val current = snapshot ?: return@withLock
                    if (current.hasRemaining) {
                        val interrupted = current.copy(interrupted = true)
                        snapshot = interrupted
                        persistLocked(interrupted, force = true)
                    }
                }
            }
            throw cancelled
        } finally {
            lease?.close()
            _progress.value = null
            _running.value = false
        }
    }

    private suspend fun markInterruptedLocked() {
        val snap = snapshot ?: return
        if (!snap.hasRemaining) return
        val interrupted = snap.copy(interrupted = true)
        snapshot = interrupted
        persistLocked(interrupted, force = true)
    }

    private suspend fun processAlbumGroup(
        candidate: IdentifyAlbumBatchCandidate,
        baseline: IdentifyWorkSnapshot,
        inFlight: MutableSet<Long>,
        listenBrainzToken: String?
    ) {
        val total = baseline.totalCount.coerceAtLeast(1)
        _progress.value = LibraryJobProgress(
            kind = LibraryJobKind.IDENTIFY,
            done = baseline.processedCount,
            total = total,
            label = "${candidate.artist} - ${candidate.album}"
        )

        var knownAlbum: KnownAlbumTracks? = null
        var catalogAlbum: CatalogAlbum? = null

        try {
            knownAlbum = dependencies.loadScopedAlbumTracks(candidate.artist, candidate.album)
        } catch (_: Exception) {}

        if (knownAlbum?.artworkUri.isNullOrBlank()) {
            try {
                val hits = dependencies.searchAlbums?.invoke("${candidate.artist} ${candidate.album}".trim())
                catalogAlbum = hits?.firstOrNull { hit ->
                    albumNamesMatch(hit.title, candidate.album) && artistsCompatible(hit.artist, candidate.artist)
                } ?: hits?.firstOrNull { hit -> albumNamesMatch(hit.title, candidate.album) }
            } catch (_: Exception) {}
        }

        val artworkUri = knownAlbum?.artworkUri?.takeIf { it.isNotBlank() }
            ?: catalogAlbum?.coverUrl?.takeIf { it.isNotBlank() }

        if (artworkUri.isNullOrBlank()) {
            attemptedAlbumGroupKeys += candidate.groupKey
            val seed = candidate.songs.first()
            val otherIds = candidate.songIds.filter { it != seed.id }
            workMutex.withLock {
                inFlight.removeAll(otherIds.toSet())
            }
            processOne(seed.id, baseline, inFlight, listenBrainzToken)
            return
        }

        dependencies.setAlbumArtwork?.invoke(candidate.album, artworkUri)

        val fillGapsIds = workMutex.withLock { snapshot?.fillGapsOnlySongIds.orEmpty().toSet() }
        val batchFields = workMutex.withLock { snapshot?.applyFields ?: IdentifyApplyFields.ALL }

        val partition = partitionAlbumBatchSongs(
            songs = candidate.songs,
            batchFields = batchFields,
            fillGapsOnlyIds = fillGapsIds
        )

        val trackMatchResult = if (partition.otherGaps.isNotEmpty() && knownAlbum != null) {
            matchAndApplyKnownAlbumTracks(
                songs = partition.otherGaps,
                knownAlbum = knownAlbum,
                batchFields = batchFields,
                fillGapsOnlyIds = fillGapsIds,
                seedFolderPath = candidate.songs.firstOrNull()?.folderPath.orEmpty(),
                collectReviewProposals = true,
                apply = dependencies.apply
            )
        } else {
            AlbumTrackMatchResult()
        }

        if (trackMatchResult.reviewProposals.isNotEmpty()) {
            bufferReviewList(trackMatchResult.reviewProposals, batchFields)
        }

        val appliedIds = (partition.artworkOnlyIds + trackMatchResult.appliedSongIds).toSet()
        val completedIds = appliedIds + trackMatchResult.reviewProposals.map { it.songId }

        workMutex.withLock {
            commitCompletedLocked(
                completedIds = completedIds,
                forcePersist = true
            ) { current ->
                current.copy(
                    updated = current.updated + appliedIds.size,
                    reviewCount = current.reviewCount + trackMatchResult.reviewProposals.size
                )
            }
        }
    }

    private suspend fun processOne(
        songId: Long,
        baseline: IdentifyWorkSnapshot,
        inFlight: MutableSet<Long>,
        listenBrainzToken: String?
    ) {
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
        val proposal = dependencies.propose(song, baseline.force, listenBrainzToken)
        val fillGaps = songId in baseline.fillGapsOnlySongIds
        val gapFields = if (fillGaps) gapApplyFields(song) else null
        val applyFields = gapFields ?: baseline.applyFields.copy(title = false)
        val reviewProposal = if (fillGaps) proposal.copy(fillGapsOnly = true) else proposal
        var deltaUpdated = 0
        var deltaSkipped = 0
        var deltaMedium = 0
        var deltaLow = 0
        var deltaNone = 0
        var deltaLbHits = if (proposal.usedListenBrainz) 1 else 0
        var deltaReview = 0
        val gate = IdentifyPipeline.evaluateReviewGate(proposal)
        when {
            proposal.alreadyIdentified -> deltaSkipped = 1
            !gate.requiresReview && proposal.suggested != null -> {
                when (dependencies.apply(song.id, proposal, applyFields)) {
                    is IdentifyResult.Updated -> {
                        deltaUpdated = 1
                        val candArt = proposal.suggested.artworkUri
                        val candAlbum = proposal.suggested.album
                        if (applyFields.artwork && !candArt.isNullOrBlank() && !IdentifyRanking.isGenericAlbum(candAlbum)) {
                            dependencies.setAlbumArtwork?.invoke(candAlbum, candArt)
                            omitRemainingArtworkOnlySiblings(
                                albumName = candAlbum,
                                seedId = song.id,
                                inFlight = inFlight
                            )
                        }
                        fanOutKnownAlbum(
                            seed = song,
                            candidate = proposal.suggested,
                            fillGaps = fillGaps,
                            inFlight = inFlight
                        )
                    }
                    else -> {
                        bufferReview(reviewProposal, baseline.applyFields)
                        deltaReview = 1
                        deltaMedium = 1
                    }
                }
            }
            else -> {
                bufferReview(reviewProposal, baseline.applyFields)
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

    private suspend fun omitRemainingArtworkOnlySiblings(
        albumName: String,
        seedId: Long,
        inFlight: MutableSet<Long>
    ) {
        val remainingIds = workMutex.withLock {
            snapshot?.remainingSongIds.orEmpty().filter { id -> id != seedId && id !in inFlight }
        }
        if (remainingIds.isEmpty()) return
        val songs = dependencies.getSongs?.invoke(remainingIds)
            ?: remainingIds.mapNotNull { id -> dependencies.getSong(id) }
        if (songs.isEmpty()) return

        val fillGapsIds = workMutex.withLock { snapshot?.fillGapsOnlySongIds.orEmpty().toSet() }
        val batchFields = workMutex.withLock { snapshot?.applyFields ?: IdentifyApplyFields.ALL }

        val albumSongs = songs.filter { s -> s.album.equals(albumName, ignoreCase = true) }
        if (albumSongs.isEmpty()) return

        val partition = partitionAlbumBatchSongs(
            songs = albumSongs,
            batchFields = batchFields,
            fillGapsOnlyIds = fillGapsIds
        )
        if (partition.artworkOnlyIds.isEmpty()) return

        workMutex.withLock {
            val toRemove = partition.artworkOnlyIds.filter { it !in inFlight }
            commitCompletedLocked(
                completedIds = toRemove,
                forcePersist = true
            ) { current ->
                current.copy(updated = current.updated + toRemove.size)
            }
        }
    }

    private suspend fun fanOutKnownAlbum(
        seed: Song,
        candidate: IdentifyCandidate,
        fillGaps: Boolean,
        inFlight: MutableSet<Long>
    ) {
        val albumName = candidate.album
        val artist = candidate.artist
        if (IdentifyRanking.isGenericAlbum(albumName) ||
            IdentifyRanking.isPlaceholderArtist(artist)
        ) {
            return
        }
        val album = dependencies.loadScopedAlbumTracks(artist, albumName) ?: return
        if (album.tracks.size < 2) return
        val remainingIds = workMutex.withLock {
            snapshot?.remainingSongIds.orEmpty().filter { id ->
                id != seed.id && id !in inFlight
            }
        }
        if (remainingIds.isEmpty()) return
        val songs = dependencies.getSongs?.invoke(remainingIds)
            ?: remainingIds.mapNotNull { id -> dependencies.getSong(id) }
        if (songs.isEmpty()) return
        val candidateSongs = songs.filter { s ->
            (seed.folderPath.isNotBlank() && s.folderPath == seed.folderPath) ||
                s.album.equals(albumName, ignoreCase = true) ||
                (s.artist.equals(artist, ignoreCase = true) && !IdentifyRanking.isGenericAlbum(s.album)) ||
                IdentifyRanking.isGenericAlbum(s.album) ||
                IdentifyRanking.isPlaceholderArtist(s.artist)
        }
        if (candidateSongs.isEmpty()) return
        val queries = candidateSongs.map { knownAlbumQueryOf(it) }
        val matches = assignUniqueKnownAlbumMatches(
            queries = queries,
            albums = listOf(album),
            scoped = true,
            seedFolderPath = seed.folderPath
        )
        if (matches.isEmpty()) return
        val toApplySongs = workMutex.withLock {
            val ids = matches.keys.filter { id ->
                id in snapshot?.remainingSongIds.orEmpty() && id !in inFlight
            }
            inFlight += ids
            candidateSongs.filter { it.id in ids }
        }
        if (toApplySongs.isEmpty()) return
        val fillGapsIds = workMutex.withLock { snapshot?.fillGapsOnlySongIds.orEmpty().toSet() }
        val batchFields = workMutex.withLock {
            snapshot?.applyFields ?: IdentifyApplyFields.ALL
        }
        try {
            val result = applyKnownAlbumMatches(
                songs = toApplySongs,
                matches = matches,
                batchFields = batchFields,
                fillGapsOnlyIds = if (fillGaps) toApplySongs.map { it.id }.toSet() else fillGapsIds,
                collectReviewProposals = false,
                apply = dependencies.apply
            )
            if (result.appliedSongIds.isNotEmpty()) {
                workMutex.withLock {
                    commitCompletedLocked(
                        completedIds = result.appliedSongIds,
                        forcePersist = true
                    ) { current ->
                        current.copy(updated = current.updated + result.appliedSongIds.size)
                    }
                }
            }
        } finally {
            workMutex.withLock {
                inFlight.removeAll(toApplySongs.map { it.id }.toSet())
            }
        }
    }

    private suspend fun commitCompletedLocked(
        completedIds: Collection<Long>,
        forcePersist: Boolean = false,
        transform: (IdentifyWorkSnapshot) -> IdentifyWorkSnapshot = { it }
    ) {
        val current = snapshot ?: return
        val toRemove = completedIds.filter { it in current.remainingSongIds }
        if (toRemove.isEmpty()) return
        val remaining = current.remainingSongIds.filterNot { it in toRemove }
        val next = transform(current).copy(
            remainingSongIds = remaining,
            processedCount = current.processedCount + toRemove.size,
            interrupted = current.interrupted
        )
        snapshot = next
        persistLocked(next, force = forcePersist)
    }

    private suspend fun commitProcessed(
        songId: Long,
        transform: (IdentifyWorkSnapshot) -> IdentifyWorkSnapshot
    ) {
        workMutex.withLock {
            commitCompletedLocked(
                completedIds = listOf(songId),
                forcePersist = false,
                transform = transform
            )
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
        flushReviewBuffer()
        workMutex.withLock {
            snapshot = null
            persistLocked(null, force = true)
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

    private suspend fun persistLocked(snapshot: IdentifyWorkSnapshot?, force: Boolean = false) {
        unpersistedWorkEdits++
        val mustWrite = force ||
            snapshot == null ||
            snapshot.interrupted ||
            unpersistedWorkEdits >= PERSIST_EVERY
        if (!mustWrite) return
        dependencies.saveWork(snapshot)
        unpersistedWorkEdits = 0
    }

    private suspend fun bufferReview(proposal: IdentifyProposal, fields: IdentifyApplyFields) {
        val flush = workMutex.withLock {
            reviewBuffer += proposal
            if (reviewBufferFields == null) {
                reviewBufferFields = fields
            }
            if (reviewBuffer.size >= PERSIST_EVERY) {
                takeReviewBufferLocked()
            } else {
                null
            }
        }
        if (flush != null) {
            dependencies.appendReview(flush.first, flush.second)
        }
    }

    private suspend fun bufferReviewList(proposals: List<IdentifyProposal>, fields: IdentifyApplyFields) {
        if (proposals.isEmpty()) return
        val flush = workMutex.withLock {
            reviewBuffer += proposals
            if (reviewBufferFields == null) {
                reviewBufferFields = fields
            }
            if (reviewBuffer.size >= PERSIST_EVERY) {
                takeReviewBufferLocked()
            } else {
                null
            }
        }
        if (flush != null) {
            dependencies.appendReview(flush.first, flush.second)
        }
    }

    private suspend fun flushReviewBuffer() {
        val flush = workMutex.withLock { takeReviewBufferLocked() } ?: return
        dependencies.appendReview(flush.first, flush.second)
    }

    private fun takeReviewBufferLocked(): Pair<List<IdentifyProposal>, IdentifyApplyFields>? {
        if (reviewBuffer.isEmpty()) return null
        val items = reviewBuffer.toList()
        val fields = reviewBufferFields ?: IdentifyApplyFields.ALL
        reviewBuffer.clear()
        reviewBufferFields = null
        return items to fields
    }

    companion object {
        internal const val IDENTIFY_PARALLEL = 3
        internal const val PERSIST_EVERY = 8

        fun create(
            context: Context,
            scope: CoroutineScope,
            repository: MusicRepository,
            acquireExecutionLease: suspend () -> AutoCloseable = { AutoCloseable {} }
        ): ProcessIdentifyRuntime {
            val workStore = IdentifyWorkStore(context)
            val reviewStore = IdentifyReviewStore(context)
            val listenBrainzPreferences = ListenBrainzPreferencesRepository(context)
            val connectivity = ConnectivityObserver(context)
            return ProcessIdentifyRuntime(
                scope = scope,
                dependencies = Dependencies(
                    getSong = { id -> repository.getSongById(id) },
                    getSongs = { ids -> repository.getSongsByIds(ids) },
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
                    pendingSongIds = { reviewStore.pendingSongIds() },
                    appendReview = { proposals, fields ->
                        reviewStore.appendProposals(proposals, fields)
                    },
                    loadWork = { workStore.load() },
                    saveWork = { workStore.save(it) },
                    isOnline = connectivity::isCurrentlyOnline,
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
                    },
                    loadScopedAlbumTracks = { artist, album ->
                        repository.loadKnownAlbumTracks(artist, album, fetchCatalog = true)
                    },
                    setAlbumArtwork = { albumKey, artworkUri ->
                        repository.setAlbumArtwork(albumKey, artworkUri)
                    },
                    searchAlbums = { query ->
                        repository.searchAlbums(query)
                    }
                )
            )
        }
    }
}
