package com.bestiapop.android.service

import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.DownloadPlaylistDestination
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessDownloadRuntimeTest {

    @Test
    fun submittedTransfer_survivesWaitingCallerCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(
            download = { track, _, progress ->
                progress(DownloadPhase.Downloading(track.title))
                entered.complete(Unit)
                release.await()
                Result.success(song(track, 41L))
            }
        )
        try {
            fixture.coordinator.awaitHydrated()
            val caller = launch {
                fixture.runtime.submit(request("one")).await()
            }
            withTimeout(TIMEOUT_MS) { entered.await() }

            caller.cancel()
            caller.join()
            release.complete(Unit)
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }

            val row = fixture.coordinator.downloads.value.single()
            assertEquals(CandidateDownloadState.SUCCESS, row.state)
            assertEquals(41L, row.resultSongId)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun transferWaitsForPlatformExecutionLease() = runBlocking {
        val leaseRequested = CompletableDeferred<Unit>()
        val releaseLease = CompletableDeferred<Unit>()
        val transfers = AtomicInteger(0)
        val leaseReleases = AtomicInteger(0)
        val fixture = fixture(
            acquireExecutionLease = {
                leaseRequested.complete(Unit)
                releaseLease.await()
                AutoCloseable { leaseReleases.incrementAndGet() }
            },
            download = { track, _, _ ->
                transfers.incrementAndGet()
                Result.success(song(track, 43L))
            }
        )
        try {
            fixture.coordinator.awaitHydrated()
            val result = fixture.runtime.submit(request("lease"))
            withTimeout(TIMEOUT_MS) { leaseRequested.await() }
            assertEquals(0, transfers.get())

            releaseLease.complete(Unit)
            assertTrue(withTimeout(TIMEOUT_MS) { result.await() }.isSuccess)
            assertEquals(1, transfers.get())
            assertEquals(1, leaseReleases.get())
        } finally {
            releaseLease.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun durableRegistrationFailure_preventsTransferStart() = runBlocking {
        val leases = AtomicInteger(0)
        val transfers = AtomicInteger(0)
        val fixture = fixture(
            failPersistence = true,
            acquireExecutionLease = {
                leases.incrementAndGet()
                AutoCloseable {}
            },
            download = { track, _, _ ->
                transfers.incrementAndGet()
                Result.success(song(track, 44L))
            }
        )
        try {
            fixture.coordinator.awaitHydrated()

            val result = fixture.runtime.submit(request("persist-failure")).await()

            assertTrue(result.isFailure)
            assertEquals(0, leases.get())
            assertEquals(0, transfers.get())
            assertEquals(
                CandidateDownloadState.ERROR,
                fixture.coordinator.downloads.value.single().state
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun resumeInterrupted_skipsHardErrorsAndConflicts() = runBlocking {
        val interrupted = row(
            id = "interrupted",
            state = CandidateDownloadState.ERROR,
            interrupted = true,
            error = DownloadMessages.interrupted
        )
        val hardError = row(
            id = "hard-error",
            state = CandidateDownloadState.ERROR,
            interrupted = false,
            error = "HTTP 500"
        )
        val conflict = row(id = "conflict", state = CandidateDownloadState.IDLE)
        val executions = AtomicInteger(0)
        val fixture = fixture(
            initial = listOf(interrupted, hardError, conflict),
            download = { track, _, _ ->
                executions.incrementAndGet()
                Result.success(song(track, 50L))
            }
        )
        try {
            fixture.coordinator.awaitHydrated()

            fixture.runtime.resumeInterrupted().join()

            val rows = fixture.coordinator.downloads.value.associateBy(ActiveDownload::id)
            assertEquals(1, executions.get())
            assertEquals(CandidateDownloadState.SUCCESS, rows.getValue("interrupted").state)
            assertEquals(CandidateDownloadState.ERROR, rows.getValue("hard-error").state)
            assertEquals(CandidateDownloadState.IDLE, rows.getValue("conflict").state)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun concurrentForegroundResumes_doNotDuplicateInterruptedTransfer() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executions = AtomicInteger(0)
        val fixture = fixture(
            initial = listOf(
                row(
                    id = "interrupted",
                    state = CandidateDownloadState.ERROR,
                    interrupted = true,
                    error = DownloadMessages.interrupted
                )
            ),
            download = { track, _, _ ->
                executions.incrementAndGet()
                entered.complete(Unit)
                release.await()
                Result.success(song(track, 51L))
            }
        )
        try {
            fixture.coordinator.awaitHydrated()

            val first = fixture.runtime.resumeInterrupted()
            withTimeout(TIMEOUT_MS) { entered.await() }
            val second = fixture.runtime.resumeInterrupted()
            release.complete(Unit)
            withTimeout(TIMEOUT_MS) {
                first.join()
                second.join()
            }

            assertEquals(1, executions.get())
            assertEquals(
                CandidateDownloadState.SUCCESS,
                fixture.coordinator.downloads.value.single().state
            )
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun resumeAllErrors_retriesEveryErrorThroughSharedPermit() = runBlocking {
        val executions = AtomicInteger(0)
        val fixture = fixture(
            initial = listOf(
                row("interrupted", CandidateDownloadState.ERROR, interrupted = true),
                row("hard-error", CandidateDownloadState.ERROR, interrupted = false),
                row("success", CandidateDownloadState.SUCCESS)
            ),
            download = { track, _, _ ->
                val id = executions.incrementAndGet().toLong()
                Result.success(song(track, id))
            }
        )
        try {
            fixture.coordinator.awaitHydrated()

            fixture.runtime.resumeAllErrors().join()

            assertEquals(2, executions.get())
            assertTrue(
                fixture.coordinator.downloads.value
                    .filter { it.id != "success" }
                    .all { it.state == CandidateDownloadState.SUCCESS }
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun interruptedAfterCommit_reconcilesExistingSongWithoutRedownload() = runBlocking {
        val existingTrack = track("committed")
        val existingSong = song(existingTrack, 88L)
        val executions = AtomicInteger(0)
        val fixture = fixture(
            initial = listOf(
                row(
                    id = "committed",
                    state = CandidateDownloadState.ERROR,
                    interrupted = true,
                    error = DownloadMessages.interrupted
                ).copy(downloadStarted = true, storageCommitted = true)
            ),
            findSong = { _, _ -> existingSong },
            download = { _, _, _ ->
                executions.incrementAndGet()
                error("A committed transfer must not download again")
            }
        )
        try {
            fixture.coordinator.awaitHydrated()

            fixture.runtime.resumeInterrupted().join()

            val restored = fixture.coordinator.downloads.value.single()
            assertEquals(0, executions.get())
            assertEquals(CandidateDownloadState.SUCCESS, restored.state)
            assertEquals(existingSong.id, restored.resultSongId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun meteredGate_blocksAfterRegistrationAndLeavesRetryableError() = runBlocking {
        val fixture = fixture(
            isMetered = true,
            allowMetered = false,
            download = { _, _, _ -> error("network transfer must not start") }
        )
        try {
            fixture.coordinator.awaitHydrated()

            val result = fixture.runtime.submit(request("metered")).await()

            assertTrue(result.isFailure)
            val row = fixture.coordinator.downloads.value.single()
            assertEquals(CandidateDownloadState.ERROR, row.state)
            assertEquals(DownloadMessages.blockedOnMetered, row.errorMessage)
            assertFalse(row.interrupted)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun successIsPublishedOnlyAfterPlaylistTargetsCommit() = runBlocking {
        val callbackEntered = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()
        val fixture = fixture(
            onPlaylistTargetCompleted = { _, _ ->
                callbackEntered.complete(Unit)
                releaseCallback.await()
            },
            download = { track, _, _ -> Result.success(song(track, 77L)) }
        )
        try {
            fixture.coordinator.awaitHydrated()
            val lookup = track("playlist").identity
            val result = fixture.runtime.submit(
                request("playlist").copy(
                    targetPlaylistId = 12L,
                    playlistTargets = listOf(DownloadPlaylistDestination(12L, lookup))
                )
            )
            withTimeout(TIMEOUT_MS) { callbackEntered.await() }

            assertEquals(
                CandidateDownloadState.DOWNLOADING,
                fixture.coordinator.downloads.value.single().state
            )
            assertTrue(fixture.persistence.saved().single().storageCommitted)

            releaseCallback.complete(Unit)
            assertTrue(withTimeout(TIMEOUT_MS) { result.await() }.isSuccess)
            assertEquals(
                CandidateDownloadState.SUCCESS,
                fixture.coordinator.downloads.value.single().state
            )
        } finally {
            releaseCallback.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun failedPlaylistCallback_keepsCommittedAudioResumable() = runBlocking {
        var failCallback = true
        var librarySong: Song? = null
        val fixture = fixture(
            findSong = { _, _ -> librarySong },
            onPlaylistTargetCompleted = { _, _ ->
                if (failCallback) error("playlist unavailable")
            },
            download = { track, _, progress ->
                progress(DownloadPhase.Completed)
                val saved = song(track, 91L)
                librarySong = saved
                Result.success(saved)
            }
        )
        try {
            fixture.coordinator.awaitHydrated()
            val lookup = track("callback-retry").identity
            val request = request("callback-retry").copy(
                targetPlaylistId = 15L,
                playlistTargets = listOf(DownloadPlaylistDestination(15L, lookup))
            )

            val first = fixture.runtime.submit(request).await()

            assertTrue(first.isFailure)
            assertTrue(fixture.coordinator.downloads.value.single().interrupted)

            failCallback = false
            fixture.runtime.resumeInterrupted().join()

            assertEquals(
                CandidateDownloadState.SUCCESS,
                fixture.coordinator.downloads.value.single().state
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun concurrentBatchConflicts_areQueuedWithoutStrandingRows() = runBlocking {
        val existing = song(track("existing"), 101L)
        val fixture = fixture(
            findSong = { _, _ -> existing },
            download = { _, _, _ -> error("conflicted rows must not download") }
        )
        try {
            fixture.coordinator.awaitHydrated()
            val requests = listOf("conflict-a", "conflict-b").map { id ->
                request(id).copy(
                    source = ActiveDownloadSource.BATCH,
                    batchId = "batch-conflicts"
                )
            }

            requests.map { fixture.runtime.submit(it) }.forEach {
                assertTrue(it.await().isFailure)
            }

            val first = fixture.runtime.downloadConflict.value
            assertTrue(first?.downloadId in requests.map { it.downloadId })
            fixture.runtime.cancelConflict()
            val second = fixture.runtime.downloadConflict.value
            assertTrue(second != null && second.downloadId != first?.downloadId)
            fixture.runtime.cancelConflict()

            assertEquals(null, fixture.runtime.downloadConflict.value)
            assertTrue(fixture.coordinator.downloads.value.isEmpty())
        } finally {
            fixture.close()
        }
    }

    private fun fixture(
        initial: List<ActiveDownload> = emptyList(),
        isMetered: Boolean = false,
        allowMetered: Boolean = true,
        failPersistence: Boolean = false,
        findSong: suspend (String, String) -> Song? = { _, _ -> null },
        onPlaylistTargetCompleted: suspend (DownloadPlaylistDestination, Song) -> Unit = { _, _ -> },
        acquireExecutionLease: suspend (ActiveDownloadSource) -> AutoCloseable = {
            AutoCloseable {}
        },
        download: suspend (
            OnlineCatalogTrack,
            com.bestiapop.android.data.model.DownloadConflictPolicy?,
            (DownloadPhase) -> Unit
        ) -> Result<Song>
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val persistence = FakePersistence(initial, failPersistence)
        val coordinator = ProcessDownloadCoordinator(
            scope = scope,
            persistence = persistence,
            onPlaylistTargetCompleted = onPlaylistTargetCompleted
        )
        val runtime = ProcessDownloadRuntime(
            scope = scope,
            processDownloads = coordinator,
            dependencies = ProcessDownloadRuntime.Dependencies(
                findSong = findSong,
                download = download,
                isMetered = { isMetered },
                downloadOnMeteredNetwork = { allowMetered },
                acquireExecutionLease = acquireExecutionLease
            )
        )
        return Fixture(scope, coordinator, runtime, persistence)
    }

    private fun request(id: String) = ProcessDownloadRequest(
        downloadId = id,
        source = ActiveDownloadSource.CATALOG,
        track = track(id),
        lookupIdentity = track(id).identity
    )

    private fun row(
        id: String,
        state: CandidateDownloadState,
        interrupted: Boolean = false,
        error: String? = if (state == CandidateDownloadState.ERROR) "error" else null
    ) = ActiveDownload(
        id = id,
        source = ActiveDownloadSource.CATALOG,
        candidates = listOf(track(id)),
        state = state,
        errorMessage = error,
        lookupIdentity = TrackIdentity(title = "Song $id", artist = "Artist"),
        interrupted = interrupted,
        resultSongId = if (state == CandidateDownloadState.SUCCESS) 99L else null
    )

    private fun track(id: String) = OnlineCatalogTrack(
        identity = TrackIdentity(title = "Song $id", artist = "Artist", album = "Album"),
        id = "video-$id",
        provider = "YouTube"
    )

    private fun song(track: OnlineCatalogTrack, id: Long) = Song(
        id = id,
        uriString = "/music/${track.id}.m4a",
        title = track.title,
        artist = track.artist,
        album = track.album
    )

    private data class Fixture(
        val scope: CoroutineScope,
        val coordinator: ProcessDownloadCoordinator,
        val runtime: ProcessDownloadRuntime,
        val persistence: FakePersistence
    ) {
        fun close() {
            scope.cancel()
        }
    }

    private class FakePersistence(
        initial: List<ActiveDownload>,
        private val failSaves: Boolean
    ) : ActiveDownloadsPersistence {
        @Volatile
        private var rows = initial

        override suspend fun load(): List<ActiveDownload> = rows

        override suspend fun save(downloads: List<ActiveDownload>) {
            if (failSaves) error("persistence unavailable")
            rows = downloads
        }

        fun saved(): List<ActiveDownload> = rows
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
