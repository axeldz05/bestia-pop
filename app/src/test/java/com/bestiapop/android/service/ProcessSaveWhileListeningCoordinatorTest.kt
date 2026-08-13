package com.bestiapop.android.service

import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.domain.util.TrackMatchKeys
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessSaveWhileListeningCoordinatorTest {

    @Test
    fun manualOwnerMakesAutosaveInFlightNeutralAndPublishesOneResult() = runBlocking {
        val autosaveTransfers = AtomicInteger(0)
        val fixture = fixture(
            download = { _, _ ->
                autosaveTransfers.incrementAndGet()
                error("Autosave must not start a second transfer")
            }
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manualTransfers = AtomicInteger(0)
        val remote = remote()
        val plainId = TrackMatchKeys.downloadIdFor(remote.artist, remote.title)
        val manualId = TrackMatchKeys.batchDownloadIdFor(remote.artist, remote.title)
        val track = remote.toOnlineCatalogTrack()
        val saved = downloadedSong()
        try {
            fixture.processDownloads.awaitHydrated()
            val manual = async(Dispatchers.Default) {
                fixture.processDownloads.execute(
                    downloadId = manualId,
                    artist = remote.artist,
                    title = remote.title,
                    onRegistered = {
                        fixture.processDownloads.upsert(
                            ActiveDownload.queued(
                                id = manualId,
                                source = ActiveDownloadSource.BATCH,
                                candidates = listOf(track)
                            )
                        )
                    }
                ) {
                    manualTransfers.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    fixture.processDownloads.update(manualId) {
                        it.asSuccess(saved)
                    }
                    Result.success(saved)
                }
            }
            withTimeout(TEST_TIMEOUT_MS) { entered.await() }

            val autosave = fixture.saver.save(remote)

            assertEquals(SaveWhileListeningDownloadResult.InFlight(manualId), autosave)
            assertEquals(1, manualTransfers.get())
            assertEquals(0, autosaveTransfers.get())
            assertEquals(1, fixture.processDownloads.downloads.value.size)
            assertTrue(fixture.saver.downloads.value.isEmpty())
            assertFalse(
                fixture.processDownloads.downloads.value.any {
                    it.source == ActiveDownloadSource.SAVE_WHILE_LISTENING ||
                        it.state == CandidateDownloadState.ERROR
                }
            )
            assertTrue(
                fixture.processDownloads.isRunning(
                    downloadId = plainId,
                    artist = remote.artist,
                    title = remote.title
                )
            )

            release.complete(Unit)
            val completed = withTimeout(TEST_TIMEOUT_MS) { manual.await() }

            assertEquals(
                saved,
                (completed as CoordinatedDownloadResult.Completed).result.getOrThrow()
            )
            val onlyResult = fixture.processDownloads.downloads.value.single()
            assertEquals(CandidateDownloadState.SUCCESS, onlyResult.state)
            assertEquals(saved.id, onlyResult.resultSongId)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun successfulAutosavePublishesOneSuccessAndDoesNotDownloadExistingTrackAgain() = runBlocking {
        val transfers = AtomicInteger(0)
        val saved = downloadedSong()
        var librarySong: Song? = null
        val fixture = fixture(
            findSong = { _, _ -> librarySong },
            download = { _, onProgress ->
                transfers.incrementAndGet()
                onProgress(DownloadPhase.Saving)
                librarySong = saved
                Result.success(saved)
            }
        )
        val remote = remote()
        try {
            fixture.processDownloads.awaitHydrated()

            val first = fixture.saver.save(remote)
            val second = fixture.saver.save(remote)

            assertEquals(SaveWhileListeningDownloadResult.Saved(saved), first)
            assertEquals(SaveWhileListeningDownloadResult.Saved(saved), second)
            assertEquals(1, transfers.get())
            val onlyResult = fixture.saver.downloads.value.single()
            assertEquals(ActiveDownloadSource.SAVE_WHILE_LISTENING, onlyResult.source)
            assertEquals(CandidateDownloadState.SUCCESS, onlyResult.state)
            assertEquals(saved.id, onlyResult.resultSongId)
            assertEquals(1, fixture.processDownloads.downloads.value.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedAutosaveReturnsFailureAndPublishesOneError() = runBlocking {
        val failure = IllegalStateException("offline fixture")
        val fixture = fixture(download = { _, _ -> Result.failure(failure) })
        try {
            fixture.processDownloads.awaitHydrated()

            val result = fixture.saver.save(remote())

            assertTrue(result is SaveWhileListeningDownloadResult.Failed)
            assertEquals(failure, (result as SaveWhileListeningDownloadResult.Failed).error)
            val onlyResult = fixture.saver.downloads.value.single()
            assertEquals(CandidateDownloadState.ERROR, onlyResult.state)
            assertTrue(onlyResult.errorMessage.orEmpty().contains("offline fixture"))
            assertEquals(1, fixture.processDownloads.downloads.value.size)
        } finally {
            fixture.close()
        }
    }

    private fun fixture(
        findSong: suspend (String, String) -> Song? = { _, _ -> null },
        download: suspend (OnlineCatalogTrack, (DownloadPhase) -> Unit) -> Result<Song> =
            { _, _ -> Result.success(downloadedSong()) }
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val processDownloads = ProcessDownloadCoordinator(
            scope = scope,
            persistence = FakePersistence()
        )
        val runtime = ProcessDownloadRuntime(
            scope = scope,
            processDownloads = processDownloads,
            dependencies = ProcessDownloadRuntime.Dependencies(
                findSong = findSong,
                download = { track, _, onProgress -> download(track, onProgress) },
                isMetered = { false },
                downloadOnMeteredNetwork = { true }
            )
        )
        val saver = ProcessSaveWhileListeningCoordinator(
            scope = scope,
            runtime = runtime
        )
        return Fixture(scope, processDownloads, saver)
    }

    private data class Fixture(
        val scope: CoroutineScope,
        val processDownloads: ProcessDownloadCoordinator,
        val saver: ProcessSaveWhileListeningCoordinator
    ) {
        fun close() = scope.cancel()
    }

    private class FakePersistence : ActiveDownloadsPersistence {
        override suspend fun load(): List<ActiveDownload> = emptyList()
        override suspend fun save(downloads: List<ActiveDownload>) = Unit
    }

    companion object {
        private const val TEST_TIMEOUT_MS = 5_000L

        private fun remote(): PlayableItem.Remote = PlayableItem.remoteFrom(
            identity = TrackIdentity(
                title = "Same track",
                artist = "Same artist",
                album = "Remote album",
                durationMs = 180_000L
            ),
            youtubeQueryOrId = "same artist same track"
        )

        private fun downloadedSong(): Song = Song(
            id = 42L,
            uriString = "/music/same-track.m4a",
            title = "Same track",
            artist = "Same artist",
            album = "Remote album"
        )
    }
}
