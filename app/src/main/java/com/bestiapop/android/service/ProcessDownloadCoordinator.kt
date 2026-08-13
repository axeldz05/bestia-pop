package com.bestiapop.android.service

import android.content.Context
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.DownloadLane
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.DownloadPlaylistDestination
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.isInFlight
import com.bestiapop.android.data.model.matchesLane
import com.bestiapop.android.data.network.ConnectivityObserver
import com.bestiapop.android.data.preferences.ActiveDownloadsStore
import com.bestiapop.android.data.preferences.DownloadPreferencesRepository
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.domain.util.TrackMatchKeys
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal interface ActiveDownloadsPersistence {
    suspend fun load(): List<ActiveDownload>
    suspend fun save(downloads: List<ActiveDownload>)
}

private class ActiveDownloadsStorePersistence(
    private val store: ActiveDownloadsStore
) : ActiveDownloadsPersistence {
    override suspend fun load(): List<ActiveDownload> = store.load()

    override suspend fun save(downloads: List<ActiveDownload>) {
        store.save(downloads)
    }
}

internal sealed interface CoordinatedDownloadResult {
    data class Completed(
        val result: Result<Song>,
        val incompletePlaylistTargets: List<DownloadPlaylistDestination> = emptyList()
    ) : CoordinatedDownloadResult
    data class AlreadyRunning(val downloadId: String) : CoordinatedDownloadResult
}

/**
 * Process-scoped source of truth for every online download owner.
 *
 * It atomically claims all id variants of a track, owns the global concurrency permit, exposes one
 * queue, persists that queue from one place, and retains the real caller [Job] for cancellation.
 */
internal class ProcessDownloadCoordinator(
    private val scope: CoroutineScope,
    private val persistence: ActiveDownloadsPersistence,
    maxConcurrentDownloads: Int = MAX_CONCURRENT_DOWNLOADS,
    private val onDownloadCompleted: suspend (Song) -> Unit = {},
    private val onPlaylistTargetCompleted: suspend (DownloadPlaylistDestination, Song) -> Unit = { _, _ -> }
) {
    private class Claim(
        val downloadId: String,
        val job: Job,
        val aliases: Set<String>
    ) {
        val pendingPlaylistTargets = linkedSetOf<DownloadPlaylistDestination>()
        val reservedPlaylistTargets = mutableSetOf<DownloadPlaylistDestination>()
    }

    private val claimsLock = Any()
    private val claimsByAlias = mutableMapOf<String, Claim>()
    private val permits = Semaphore(maxConcurrentDownloads)
    private val persistMutex = Mutex()
    private val hydrated = CompletableDeferred<Unit>()
    private val _downloads = MutableStateFlow<List<ActiveDownload>>(emptyList())
    val downloads: StateFlow<List<ActiveDownload>> = _downloads.asStateFlow()

    init {
        require(maxConcurrentDownloads > 0) { "maxConcurrentDownloads must be positive" }
        scope.launch(Dispatchers.IO) {
            val restored = try {
                persistence.load()
            } catch (_: Exception) {
                emptyList()
            }
            var hadLiveRows = false
            _downloads.update { live ->
                hadLiveRows = live.isNotEmpty()
                mergeById(preferred = live, fallback = restored)
            }
            hydrated.complete(Unit)
            if (hadLiveRows) persistLatest()
        }
    }

    /**
     * Claims [downloadId] plus the plain/`batch:` ids for [artist]/[title], then runs [block] under
     * the process-wide permit. The first caller owns the transfer; concurrent variants are rejected.
     */
    suspend fun execute(
        downloadId: String,
        artist: String,
        title: String,
        playlistTarget: DownloadPlaylistDestination? = null,
        onRegistered: () -> Unit = {},
        beforePermit: suspend () -> Unit = {},
        block: suspend () -> Result<Song>
    ): CoordinatedDownloadResult {
        hydrated.await()
        val aliases = aliasesFor(downloadId, artist, title)
        require(aliases.isNotEmpty()) { "Download identity is blank" }
        val job = currentCoroutineContext()[Job]
            ?: error("A coordinated download requires a coroutine Job")
        val claim = Claim(downloadId = downloadId, job = job, aliases = aliases)
        var running: Claim? = null

        try {
            synchronized(claimsLock) {
                running = findRunningClaimLocked(aliases)
                if (running == null) {
                    aliases.forEach { claimsByAlias[it] = claim }
                    // Queue publication is part of registration, so another owner cannot observe a
                    // claimed job before its shared ActiveDownload row exists.
                    onRegistered()
                }
                playlistTarget?.let { target ->
                    registerPlaylistTargetLocked(running ?: claim, target)
                }
            }
        } catch (error: Throwable) {
            unregister(claim)
            throw error
        }

        val owner = running ?: claim
        playlistTarget?.let { preservePlaylistTarget(owner.downloadId, it) }
        running?.let { runningOwner ->
            return CoordinatedDownloadResult.AlreadyRunning(runningOwner.downloadId)
        }

        return try {
            beforePermit()
            val result = permits.withPermit { block() }
            val incompleteTargets = result.getOrNull()?.let { song ->
                completePlaylistTargets(claim, song)
            }.orEmpty()
            CoordinatedDownloadResult.Completed(result, incompleteTargets)
        } finally {
            unregister(claim)
        }
    }

    fun isRunning(downloadId: String, artist: String, title: String): Boolean =
        runningDownloadId(downloadId, artist, title) != null

    fun runningDownloadId(downloadId: String, artist: String, title: String): String? {
        val aliases = aliasesFor(downloadId, artist, title)
        if (aliases.isEmpty()) return null
        return synchronized(claimsLock) {
            findRunningClaimLocked(aliases)?.downloadId
        }
    }

    fun findByTrack(downloadId: String, artist: String, title: String): ActiveDownload? {
        val aliases = aliasesFor(downloadId, artist, title)
        val rows = downloads.value
        aliases.forEach { alias ->
            rows.firstOrNull { it.id == alias }?.let { return it }
        }
        val matchKey = TrackMatchKeys.matchKey(artist, title)
        return matchKey.takeIf { it.isNotEmpty() }?.let { key ->
            rows.firstOrNull { TrackMatchKeys.matchKey(it.artist, it.title) == key }
        }
    }

    fun upsert(download: ActiveDownload) {
        _downloads.update { rows ->
            val index = rows.indexOfFirst { it.id == download.id }
            if (index < 0) listOf(download) + rows
            else rows.toMutableList().apply { set(index, download) }
        }
        schedulePersist()
    }

    fun update(
        id: String,
        transform: (ActiveDownload) -> ActiveDownload
    ): Boolean {
        var changed = false
        _downloads.update { rows ->
            val index = rows.indexOfFirst { it.id == id }
            if (index < 0) {
                rows
            } else {
                changed = true
                rows.toMutableList().apply { set(index, transform(get(index))) }
            }
        }
        if (changed) schedulePersist()
        return changed
    }

    fun remove(id: String) {
        var changed = false
        _downloads.update { rows ->
            val filtered = rows.filterNot { it.id == id }
            changed = filtered.size != rows.size
            filtered
        }
        if (changed) schedulePersist()
    }

    /** Adds a playlist handoff to whichever id variant currently owns this track. */
    fun attachTargetPlaylist(
        downloadId: String,
        artist: String,
        title: String,
        target: DownloadPlaylistDestination
    ): Boolean {
        val aliases = aliasesFor(downloadId, artist, title)
        if (aliases.isEmpty()) return false
        val owner = synchronized(claimsLock) {
            findRunningClaimLocked(aliases)?.also { claim ->
                registerPlaylistTargetLocked(claim, target)
            }
        } ?: return false
        preservePlaylistTarget(owner.downloadId, target)
        return true
    }

    private fun preservePlaylistTarget(downloadId: String, target: DownloadPlaylistDestination) {
        update(downloadId) { row ->
            row.copy(
                targetPlaylistId = row.targetPlaylistId ?: target.playlistId,
                playlistTargets = (row.playlistTargets + target).distinct()
            )
        }
    }

    /** Cancels the exact claimed job even when [id] is an alternate plain/`batch:` alias. */
    fun dismiss(id: String) {
        val claim = synchronized(claimsLock) { claimsByAlias[id] }
        claim?.job?.cancel()
        remove(claim?.downloadId ?: id)
    }

    /** Used before retry so the old writer has fully stopped before a new claim can start. */
    suspend fun cancelAndJoin(id: String) {
        val claim = synchronized(claimsLock) { claimsByAlias[id] }
        claim?.job?.cancelAndJoin()
        remove(claim?.downloadId ?: id)
    }

    fun dismissAll() {
        val jobs = synchronized(claimsLock) {
            claimsByAlias.values.distinctBy { it.job }.map { it.job }
        }
        jobs.forEach { it.cancel() }
        if (_downloads.value.isNotEmpty()) {
            _downloads.value = emptyList()
            schedulePersist()
        }
    }

    /** Stops live writers but keeps their rows so cancellation can mark them resumable. */
    suspend fun interruptAll() {
        val jobs = runningJobs()
        jobs.forEach { it.cancel() }
        markRunningRowsInterrupted()
        jobs.joinAll()
    }

    fun interruptNow(lane: DownloadLane) {
        interruptMatchingRows { it.matchesLane(lane) }
    }

    fun dismissRunning(lane: DownloadLane) {
        val ids = _downloads.value
            .filter { it.matchesLane(lane) }
            .mapTo(mutableSetOf()) { it.id }
        val claims = synchronized(claimsLock) {
            claimsByAlias.values.distinctBy { it.job }.filter { it.downloadId in ids }
        }
        claims.forEach { it.job.cancel() }
        val claimedIds = claims.mapTo(mutableSetOf()) { it.downloadId }
        if (claimedIds.isNotEmpty()) {
            _downloads.update { rows -> rows.filterNot { it.id in claimedIds } }
            schedulePersist()
        }
    }

    suspend fun recordCompletedDownload(song: Song) {
        try {
            onDownloadCompleted(song)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The audio is already safely stored. Accounting failure must not turn it into ERROR.
        }
    }

    internal suspend fun awaitHydrated() {
        hydrated.await()
    }

    internal suspend fun flush() {
        hydrated.await()
        persistLatest()
    }

    /** Transfer boundary: unlike background snapshots, failure must stop bytes from starting. */
    internal suspend fun flushDurably() {
        hydrated.await()
        persistMutex.withLock {
            persistence.save(downloads.value)
        }
    }

    /**
     * Reserves each target before invoking its callback. The claim stays registered while callbacks
     * suspend, so a destination attached concurrently is either drained by this loop or observes
     * that the transfer has already unregistered; the same target pair is never invoked twice.
     */
    private suspend fun completePlaylistTargets(
        claim: Claim,
        song: Song
    ): List<DownloadPlaylistDestination> {
        val incomplete = mutableListOf<DownloadPlaylistDestination>()
        withContext(NonCancellable) {
            while (true) {
                val target = synchronized(claimsLock) {
                    val next = claim.pendingPlaylistTargets.firstOrNull()
                    if (next == null) {
                        unregisterLocked(claim)
                        null
                    } else {
                        claim.pendingPlaylistTargets.remove(next)
                        claim.reservedPlaylistTargets += next
                        next
                    }
                } ?: return@withContext
                try {
                    onPlaylistTargetCompleted(target, song)
                } catch (_: Exception) {
                    // Audio is safe. Keep trying other destinations and let the runtime retain a
                    // resumable row for any callback that did not commit.
                    incomplete += target
                }
            }
        }
        return incomplete
    }

    private fun registerPlaylistTargetLocked(claim: Claim, target: DownloadPlaylistDestination) {
        if (target !in claim.reservedPlaylistTargets) {
            claim.pendingPlaylistTargets += target
        }
    }

    private fun unregister(claim: Claim) {
        synchronized(claimsLock) {
            unregisterLocked(claim)
        }
    }

    private fun unregisterLocked(claim: Claim) {
        claim.aliases.forEach { alias ->
            if (claimsByAlias[alias] === claim) claimsByAlias.remove(alias)
        }
    }

    private fun findRunningClaimLocked(aliases: Set<String>): Claim? {
        for (alias in aliases) {
            val claim = claimsByAlias[alias] ?: continue
            if (!claim.job.isCompleted) return claim
            unregisterLocked(claim)
        }
        return null
    }

    private fun aliasesFor(downloadId: String, artist: String, title: String): Set<String> =
        buildSet {
            downloadId.takeIf { it.isNotBlank() }?.let(::add)
            addAll(TrackMatchKeys.downloadIdVariantsFor(artist, title))
        }

    private fun schedulePersist() {
        scope.launch(Dispatchers.IO) {
            hydrated.await()
            persistLatest()
        }
    }

    private fun runningJobs(): List<Job> = synchronized(claimsLock) {
        claimsByAlias.values.distinctBy { it.job }.map { it.job }
    }

    private fun markRunningRowsInterrupted() {
        markRowsInterrupted { true }
    }

    private fun interruptMatchingRows(predicate: (ActiveDownload) -> Boolean) {
        val ids = _downloads.value.filter(predicate).mapTo(mutableSetOf()) { it.id }
        val jobs = synchronized(claimsLock) {
            claimsByAlias.values.distinctBy { it.job }
                .filter { it.downloadId in ids }
                .map { it.job }
        }
        jobs.forEach { it.cancel() }
        markRowsInterrupted(predicate)
    }

    private fun markRowsInterrupted(predicate: (ActiveDownload) -> Boolean) {
        var changed = false
        _downloads.update { rows ->
            rows.map { row ->
                if (predicate(row) && row.state.isInFlight) {
                    changed = true
                    row.asError(DownloadMessages.interrupted, interrupted = true)
                } else {
                    row
                }
            }
        }
        if (changed) schedulePersist()
    }

    private suspend fun persistLatest() {
        persistMutex.withLock {
            try {
                persistence.save(downloads.value)
            } catch (_: Exception) {
                // Keep the process state authoritative; the next mutation retries persistence.
            }
        }
    }

    private fun mergeById(
        preferred: List<ActiveDownload>,
        fallback: List<ActiveDownload>
    ): List<ActiveDownload> = buildList(preferred.size + fallback.size) {
        val seen = mutableSetOf<String>()
        (preferred + fallback).forEach { row ->
            if (seen.add(row.id)) add(row)
        }
    }

    companion object {
        const val MAX_CONCURRENT_DOWNLOADS = 3

        fun create(
            context: Context,
            scope: CoroutineScope,
            onPlaylistTargetCompleted: suspend (DownloadPlaylistDestination, Song) -> Unit
        ): ProcessDownloadCoordinator {
            val preferences = DownloadPreferencesRepository(context)
            val connectivity = ConnectivityObserver(context)
            return ProcessDownloadCoordinator(
                scope = scope,
                persistence = ActiveDownloadsStorePersistence(ActiveDownloadsStore(context)),
                onDownloadCompleted = { song ->
                    val bytes = runCatching {
                        SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
                            ?.let { java.io.File(it) }
                            ?.takeIf { it.isFile }
                            ?.length()
                    }.getOrNull() ?: 0L
                    preferences.addDownloadedBytes(
                        byteCount = bytes,
                        metered = connectivity.isMetered()
                    )
                },
                onPlaylistTargetCompleted = onPlaylistTargetCompleted
            )
        }
    }
}
