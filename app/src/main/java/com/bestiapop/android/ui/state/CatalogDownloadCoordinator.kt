package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadConflictPolicy
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.DownloadPlaylistDestination
import com.bestiapop.android.data.model.OfflineMessages
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.model.catalogPreviewKeyFor
import com.bestiapop.android.data.model.isRemote
import com.bestiapop.android.data.model.youtubeSearchQuery
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.service.ProcessDownloadRequest
import com.bestiapop.android.service.ProcessDownloadRuntime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TrackedBatchItem(
    val track: OnlineCatalogTrack,
    val candidates: List<OnlineCatalogTrack> = listOf(track),
    val currentCandidateIndex: Int = 0,
    val idHint: String? = null,
    val lookupIdentity: TrackIdentity? = null
)

internal data class CatalogBatchPlaylistTarget(
    val selectionKey: String,
    val playlistId: Long
)

/**
 * Coordinator for online catalog downloads, link downloads, active download actions
 * (retry, resume, preview, dismiss), conflict resolution, and batch playlist creation.
 * Keeps [com.bestiapop.android.ui.MusicPlayerViewModel] lean.
 */
internal class CatalogDownloadCoordinator(
    private val scope: CoroutineScope,
    private val processDownloadRuntime: ProcessDownloadRuntime,
    private val repository: IMusicRepository,
    private val toast: (String) -> Unit,
    private val toastDownloadsQueued: (alreadyQueued: Boolean, count: Int) -> Unit,
    private val toastSongAlreadyInLibrary: (title: String) -> Unit,
    private val playOnlineCatalogTrackAsStream: (OnlineCatalogTrack, Boolean) -> Unit,
    private val playSong: (Song) -> Unit,
    private val rematchDiscover: suspend (Song?) -> Unit,
    private val launchCycleYouTubeMatch: (
        query: String,
        current: List<OnlineCatalogTrack>,
        wasPreviewing: Boolean,
        apply: suspend (List<OnlineCatalogTrack>) -> OnlineCatalogTrack?
    ) -> Unit,
    private val isOnline: () -> Boolean = { true }
) {
    private val catalogBatchPlaylistMutex = Mutex()
    private var catalogBatchPlaylistTarget: CatalogBatchPlaylistTarget? = null

    val currentBatchPlaylistId: Long?
        get() = catalogBatchPlaylistTarget?.playlistId

    fun resetBatchPlaylistTarget() {
        catalogBatchPlaylistTarget = null
    }

    fun resolveDownloadConflictOverwrite(applyToRemainingBatch: Boolean = false) {
        processDownloadRuntime.resolveConflictOverwrite(applyToRemainingBatch)
    }

    fun resolveDownloadConflictSaveAs(newTitle: String, applyToRemainingBatch: Boolean = false) {
        processDownloadRuntime.resolveConflictSaveAs(newTitle, applyToRemainingBatch)
    }

    fun cancelDownloadConflict() {
        processDownloadRuntime.cancelConflict()
    }

    fun clearBatchConflictPolicy() {
        processDownloadRuntime.clearBatchConflictPolicy()
    }

    fun activeDownloadIdFor(
        track: OnlineCatalogTrack,
        source: ActiveDownloadSource,
        explicitId: String? = null
    ): String {
        explicitId?.takeIf { it.isNotBlank() }?.let { return it }
        val match = TrackMatchKeys.downloadIdFor(track.artist, track.title)
        if (match.isNotEmpty()) return match
        return catalogPreviewKeyFor(track).ifBlank { track.audioUrl.ifBlank { track.id } }
    }

    /** Submit to the process runtime; cancelling this caller only stops waiting for the result. */
    suspend fun runTrackedDownload(
        downloadId: String,
        source: ActiveDownloadSource,
        track: OnlineCatalogTrack,
        existingCandidates: List<OnlineCatalogTrack>? = null,
        currentCandidateIndex: Int = 0,
        targetPlaylistId: Long? = null,
        conflictPolicy: DownloadConflictPolicy? = null,
        lookupIdentity: TrackIdentity? = null,
        batchId: String? = null,
        titleOverride: String? = null
    ): Result<Song> = processDownloadRuntime.submit(
        ProcessDownloadRequest(
            downloadId = downloadId,
            source = source,
            track = track,
            candidates = existingCandidates?.takeIf { it.isNotEmpty() } ?: listOf(track),
            currentCandidateIndex = currentCandidateIndex,
            targetPlaylistId = targetPlaylistId,
            conflictPolicy = conflictPolicy,
            lookupIdentity = lookupIdentity,
            batchId = batchId,
            titleOverride = titleOverride
        )
    ).await()

    private fun checkOnline(): Boolean {
        if (!isOnline()) {
            toast(OfflineMessages.connectionDisabled)
            return false
        }
        return true
    }

    /**
     * Level 2: Shared pre-flight validation, status checks, and feedback toast for downloading any online track.
     */
    fun preflightOnlineTrackDownload(
        meta: TrackMeta,
        source: ActiveDownloadSource,
        enqueue: suspend () -> Unit
    ): Boolean {
        if (!checkOnline()) return false
        val key = TrackMatchKeys.downloadIdFor(meta.artist, meta.title)
        if (key.isEmpty()) {
            toast(DownloadMessages.missingArtistOrTitle)
            return false
        }
        val existing = processDownloadRuntime.findClaimedDownload(
            key,
            meta.artist,
            meta.title
        )
        if (processDownloadRuntime.isRunning(key, meta.artist, meta.title)) {
            toastDownloadsQueued(true, 1)
            return false
        }
        when (existing?.state) {
            CandidateDownloadState.SUCCESS -> {
                scope.launch {
                    val song = existing.resultSongId?.let { repository.getSongById(it) }
                    if (song != null && !song.isRemote) {
                        toastSongAlreadyInLibrary(meta.title)
                        rematchDiscover(null)
                    } else {
                        toastDownloadsQueued(false, 1)
                        enqueue()
                    }
                }
                return true
            }
            else -> Unit
        }

        scope.launch {
            toastDownloadsQueued(false, 1)
            enqueue()
        }
        return true
    }

    /**
     * Manual download of a streamed remote (Para Ti / Recomendados / Now Playing) into the library.
     */
    fun downloadRemoteItem(remote: PlayableItem.Remote) {
        preflightOnlineTrackDownload(remote, ActiveDownloadSource.DISCOVER) {
            enqueueRemoteDownload(remote, ActiveDownloadSource.DISCOVER)
        }
    }

    suspend fun enqueueRemoteDownload(
        remote: PlayableItem.Remote,
        source: ActiveDownloadSource
    ): Result<Song> {
        val key = TrackMatchKeys.downloadIdFor(remote.artist, remote.title)
        val track = remote.toOnlineCatalogTrack(provider = "YouTube")
        return runTrackedDownload(downloadId = key, source = source, track = track)
    }

    fun retryActiveDownload(id: String) {
        if (!checkOnline()) return
        processDownloadRuntime.retry(id)
    }

    fun resumeAllDownloads() {
        if (!checkOnline()) return
        processDownloadRuntime.resumeAllErrors()
    }

    fun cycleActiveDownload(
        id: String,
        activeDownloads: List<ActiveDownload>,
        catalogPreviewKey: String?
    ) {
        val download = activeDownloads.find { it.id == id } ?: return
        val current = download.currentTrack ?: return
        val wasPreviewing = catalogPreviewKey == catalogPreviewKeyFor(current) ||
                download.candidates.any { catalogPreviewKeyFor(it) == catalogPreviewKey }
        val query = download.youtubeSearchQuery()
            .ifBlank { current.title.trim() }
            .ifBlank { current.id.ifBlank { current.audioUrl } }
        if (query.isBlank()) return

        launchCycleYouTubeMatch(
            query,
            download.candidates,
            wasPreviewing
        ) { candidatesList ->
            val cycled = ActiveDownload.withCycledCandidate(download, candidatesList)
            processDownloadRuntime.upsertRow(cycled)
            cycled.currentTrack
        }
    }

    fun previewActiveDownload(id: String, activeDownloads: List<ActiveDownload>) {
        val track = activeDownloads.find { it.id == id }?.currentTrack ?: return
        playOnlineCatalogTrackAsStream(track, false)
    }

    fun playActiveDownload(id: String, activeDownloads: List<ActiveDownload>) {
        val download = activeDownloads.find { it.id == id } ?: return
        val songId = download.resultSongId ?: return
        scope.launch {
            val song = repository.getSongById(songId) ?: return@launch
            playSong(song)
        }
    }

    fun dismissActiveDownload(id: String) {
        processDownloadRuntime.dismiss(id)
    }

    fun dismissAllActiveDownloads() {
        processDownloadRuntime.dismissAll()
    }

    fun downloadSingleCandidate(
        index: Int,
        collection: CatalogCollectionUiState
    ) {
        val list = collection.candidates
        if (index !in list.indices) return
        val candidate = list[index]
        scope.launch {
            val targetPlaylistId = ensureCatalogPlaylistForBatch(collection)
            val track = candidate.currentTrack ?: return@launch
            downloadOnlineTrack(
                track = track,
                source = ActiveDownloadSource.BATCH,
                targetPlaylistId = targetPlaylistId,
                existingCandidates = candidate.candidates,
                currentCandidateIndex = candidate.currentCandidateIndex,
                lookupIdentity = candidate.identity,
                explicitId = TrackMatchKeys.batchDownloadIdFor(candidate.artist, candidate.title)
            )
        }
    }

    fun downloadSelectedCandidatesBatch(collection: CatalogCollectionUiState) {
        if (!checkOnline()) return
        val selected = collection.candidates.filter {
            it.isSelected && it.currentTrack != null
        }
        if (selected.isEmpty()) return

        scope.launch {
            clearBatchConflictPolicy()
            val targetPlaylistId = ensureCatalogPlaylistForBatch(collection)
            val items = selected.mapNotNull { candidate ->
                val track = candidate.currentTrack ?: return@mapNotNull null
                TrackedBatchItem(
                    track = track,
                    candidates = candidate.candidates,
                    currentCandidateIndex = candidate.currentCandidateIndex,
                    idHint = TrackMatchKeys.batchDownloadIdFor(candidate.artist, candidate.title),
                    lookupIdentity = candidate.identity
                )
            }
            enqueueTrackedBatch(
                items = items,
                source = ActiveDownloadSource.BATCH,
                idStrategy = {
                    activeDownloadIdFor(
                        it.track,
                        ActiveDownloadSource.BATCH,
                        explicitId = it.idHint
                    )
                },
                playlistId = targetPlaylistId
            )
        }
    }

    suspend fun ensureCatalogPlaylistForBatch(
        collection: CatalogCollectionUiState
    ): Long? = catalogBatchPlaylistMutex.withLock {
        val selectionKey = collection.selectionKey ?: return@withLock null
        if (collection.kind != CatalogCollectionKind.PLAYLIST) return@withLock null
        catalogBatchPlaylistTarget
            ?.takeIf { it.selectionKey == selectionKey }
            ?.let { return@withLock it.playlistId }
        val name = collection.title?.takeIf { it.isNotBlank() } ?: "Playlist"
        val id = repository.createPlaylist(name, coverUri = collection.coverUrl)
        if (collection.selectionKey == selectionKey) {
            catalogBatchPlaylistTarget = CatalogBatchPlaylistTarget(selectionKey, id)
        }
        id
    }

    fun downloadFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        downloadOnlineTrack(
            OnlineCatalogTrack.fromUrl(trimmed),
            source = ActiveDownloadSource.LINK
        )
    }

    /**
     * Level 1: Low-level primitive download for an online track with custom candidates and targets.
     */
    fun downloadOnlineTrack(
        track: OnlineCatalogTrack,
        source: ActiveDownloadSource = ActiveDownloadSource.CATALOG,
        targetPlaylistId: Long? = null,
        existingCandidates: List<OnlineCatalogTrack>? = null,
        currentCandidateIndex: Int = 0,
        lookupIdentity: TrackIdentity? = null,
        explicitId: String? = null
    ) {
        preflightOnlineTrackDownload(lookupIdentity ?: track.identity, source) {
            val downloadId = activeDownloadIdFor(track, source, explicitId)
            runTrackedDownload(
                downloadId = downloadId,
                source = source,
                track = track,
                existingCandidates = existingCandidates,
                currentCandidateIndex = currentCandidateIndex,
                targetPlaylistId = targetPlaylistId,
                lookupIdentity = lookupIdentity
            )
        }
    }

    suspend fun enqueueTrackedBatch(
        items: List<TrackedBatchItem>,
        source: ActiveDownloadSource,
        idStrategy: (TrackedBatchItem) -> String,
        playlistId: Long?,
        toastQueued: Boolean = false
    ) {
        if (items.isEmpty()) return
        if (toastQueued) {
            toastDownloadsQueued(false, items.size)
        }
        val batchId = "${source.name}:${System.nanoTime()}"

        val queued = items.mapNotNull { item ->
            val downloadId = idStrategy(item)
            if (downloadId.isBlank()) return@mapNotNull null
            val lookup = item.lookupIdentity ?: item.track.identity
            if (processDownloadRuntime.isRunning(downloadId, lookup.artist, lookup.title)) {
                val attached = playlistId != null &&
                        processDownloadRuntime.attachPlaylistDestination(
                            downloadId = downloadId,
                            artist = lookup.artist,
                            title = lookup.title,
                            destination = DownloadPlaylistDestination(
                                playlistId = playlistId,
                                identity = lookup
                            )
                        )
                if (playlistId == null || attached) return@mapNotNull null
            }
            val candidates = item.candidates.ifEmpty { listOf(item.track) }
            val safeIndex = item.currentCandidateIndex.coerceIn(0, candidates.lastIndex)
            Triple(item, downloadId, safeIndex)
        }

        val successCount = AtomicInteger(0)
        coroutineScope {
            queued.map { (item, downloadId, safeIndex) ->
                async {
                    val result = runTrackedDownload(
                        downloadId = downloadId,
                        source = source,
                        track = item.track,
                        existingCandidates = item.candidates,
                        currentCandidateIndex = safeIndex,
                        targetPlaylistId = playlistId,
                        lookupIdentity = item.lookupIdentity,
                        batchId = batchId
                    )
                    if (result.isSuccess) successCount.incrementAndGet()
                }
            }.awaitAll()
        }

        toast(DownloadMessages.batchProcessed(successCount.get(), queued.size))
    }

    suspend fun enqueuePendingDownloads(
        playlistId: Long,
        tracks: List<OnlineCatalogTrack>,
        toastQueued: Boolean
    ) = enqueueTrackedBatch(
        items = tracks.map { TrackedBatchItem(track = it) },
        source = ActiveDownloadSource.LB_IMPORT,
        idStrategy = {
            TrackMatchKeys.downloadIdFor(it.track.artist, it.track.title)
        },
        playlistId = playlistId,
        toastQueued = toastQueued
    )
}
