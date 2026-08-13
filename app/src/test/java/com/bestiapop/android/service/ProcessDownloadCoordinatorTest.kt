package com.bestiapop.android.service

import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadLane
import com.bestiapop.android.data.model.DownloadPlaylistDestination
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessDownloadCoordinatorTest {

    @Test
    fun concurrentManualAndAutosaveCallers_shareTrackIdVariants() = runBlocking {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executions = AtomicInteger(0)
        val expectedSong = downloadedSong()
        try {
            fixture.coordinator.awaitHydrated()
            val manual = async(Dispatchers.Default) {
                fixture.coordinator.execute(
                    downloadId = "batch:artist|song",
                    artist = "Artist",
                    title = "Song"
                ) {
                    executions.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    Result.success(expectedSong)
                }
            }
            withTimeout(TEST_TIMEOUT_MS) { entered.await() }

            val autosave = async(Dispatchers.Default) {
                fixture.coordinator.execute(
                    downloadId = "artist|song",
                    artist = "Artist",
                    title = "Song"
                ) {
                    executions.incrementAndGet()
                    Result.success(downloadedSong())
                }
            }

            val rejected = withTimeout(TEST_TIMEOUT_MS) { autosave.await() }
            assertTrue(rejected is CoordinatedDownloadResult.AlreadyRunning)
            assertEquals(
                "batch:artist|song",
                (rejected as CoordinatedDownloadResult.AlreadyRunning).downloadId
            )
            assertEquals(1, executions.get())

            release.complete(Unit)
            val completed = withTimeout(TEST_TIMEOUT_MS) { manual.await() }
            assertEquals(
                expectedSong,
                (completed as CoordinatedDownloadResult.Completed).result.getOrThrow()
            )
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun dismissByAlternateId_cancelsTheOwningJobAndRemovesItsRow() = runBlocking {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        try {
            fixture.coordinator.awaitHydrated()
            val runner = launch(Dispatchers.Default) {
                fixture.coordinator.execute(
                    downloadId = "batch:artist|song",
                    artist = "Artist",
                    title = "Song",
                    onRegistered = {
                        fixture.coordinator.upsert(
                            row(
                                id = "batch:artist|song",
                                source = ActiveDownloadSource.BATCH
                            )
                        )
                    }
                ) {
                    entered.complete(Unit)
                    try {
                        neverComplete.await()
                    } finally {
                        stopped.complete(Unit)
                    }
                    Result.success(downloadedSong())
                }
            }
            withTimeout(TEST_TIMEOUT_MS) { entered.await() }

            fixture.coordinator.dismiss("artist|song")

            withTimeout(TEST_TIMEOUT_MS) { stopped.await() }
            withTimeout(TEST_TIMEOUT_MS) { runner.join() }
            assertTrue(runner.isCancelled)
            assertFalse(
                fixture.coordinator.isRunning(
                    downloadId = "artist|song",
                    artist = "Artist",
                    title = "Song"
                )
            )
            assertTrue(fixture.coordinator.downloads.value.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun interruptAll_cancelsWritersButKeepsRowsForResume() = runBlocking {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        try {
            fixture.coordinator.awaitHydrated()
            val runner = launch(Dispatchers.Default) {
                fixture.coordinator.execute(
                    downloadId = "artist|song",
                    artist = "Artist",
                    title = "Song",
                    onRegistered = {
                        fixture.coordinator.upsert(
                            row("artist|song", ActiveDownloadSource.CATALOG)
                        )
                    }
                ) {
                    entered.complete(Unit)
                    neverComplete.await()
                    Result.success(downloadedSong())
                }
            }
            withTimeout(TEST_TIMEOUT_MS) { entered.await() }

            fixture.coordinator.interruptAll()
            withTimeout(TEST_TIMEOUT_MS) { runner.join() }

            assertTrue(runner.isCancelled)
            assertEquals(listOf("artist|song"), fixture.coordinator.downloads.value.map { it.id })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun dismissAll_cancelsEveryWriterAndClearsQueue() = runBlocking {
        val fixture = fixture()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val neverComplete = CompletableDeferred<Unit>()
        try {
            fixture.coordinator.awaitHydrated()
            val runners = (0 until 2).map { index ->
                launch(Dispatchers.Default) {
                    fixture.coordinator.execute(
                        downloadId = "artist|song-$index",
                        artist = "Artist",
                        title = "Song $index",
                        onRegistered = {
                            fixture.coordinator.upsert(
                                row("artist|song-$index", ActiveDownloadSource.BATCH)
                            )
                        }
                    ) {
                        entered.send(Unit)
                        neverComplete.await()
                        Result.success(downloadedSong(index.toLong() + 1))
                    }
                }
            }
            repeat(2) { withTimeout(TEST_TIMEOUT_MS) { entered.receive() } }

            fixture.coordinator.dismissAll()
            runners.forEach { withTimeout(TEST_TIMEOUT_MS) { it.join() } }

            assertTrue(runners.all { it.isCancelled })
            assertTrue(fixture.coordinator.downloads.value.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun interruptNow_onlyStopsTheRequestedLane() = runBlocking {
        val fixture = fixture()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val neverComplete = CompletableDeferred<Unit>()
        try {
            fixture.coordinator.awaitHydrated()
            val requests = listOf(
                Triple("manual|song", ActiveDownloadSource.CATALOG, "Manual"),
                Triple("autosave|song", ActiveDownloadSource.SAVE_WHILE_LISTENING, "Autosave")
            )
            val runners = requests.map { (id, source, title) ->
                launch(Dispatchers.Default) {
                    fixture.coordinator.execute(
                        downloadId = id,
                        artist = "Artist",
                        title = title,
                        onRegistered = {
                            fixture.coordinator.upsert(
                                row(id, source)
                            )
                        }
                    ) {
                        entered.send(Unit)
                        neverComplete.await()
                        Result.success(downloadedSong())
                    }
                }
            }
            repeat(2) { withTimeout(TEST_TIMEOUT_MS) { entered.receive() } }

            fixture.coordinator.interruptNow(DownloadLane.AUTOSAVE)
            runners[1].join()

            val rows = fixture.coordinator.downloads.value.associateBy(ActiveDownload::id)
            assertEquals(CandidateDownloadState.QUEUED, rows.getValue("manual|song").state)
            assertEquals(CandidateDownloadState.ERROR, rows.getValue("autosave|song").state)
            assertFalse(runners[0].isCompleted)
            assertTrue(runners[1].isCancelled)
        } finally {
            fixture.coordinator.dismissAll()
            fixture.close()
        }
    }

    @Test
    fun hydrationMergesRowsWrittenByBothOwnersWithoutDroppingPersistedRows() = runBlocking {
        val persisted = row("persisted-manual", ActiveDownloadSource.CATALOG)
        val persistence = FakePersistence(
            initial = listOf(persisted),
            holdLoad = true
        )
        val fixture = fixture(persistence = persistence)
        try {
            withTimeout(TEST_TIMEOUT_MS) { persistence.loadStarted.await() }
            fixture.coordinator.upsert(
                row("live-autosave", ActiveDownloadSource.SAVE_WHILE_LISTENING)
            )
            fixture.coordinator.upsert(
                row("live-manual", ActiveDownloadSource.DISCOVER)
            )
            persistence.releaseLoad.complete(Unit)

            withTimeout(TEST_TIMEOUT_MS) { fixture.coordinator.awaitHydrated() }
            fixture.coordinator.flush()

            assertEquals(
                setOf("persisted-manual", "live-autosave", "live-manual"),
                fixture.coordinator.downloads.value.map { it.id }.toSet()
            )
            assertEquals(
                setOf("persisted-manual", "live-autosave", "live-manual"),
                persistence.saved().map { it.id }.toSet()
            )
        } finally {
            persistence.releaseLoad.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun globalPermitCapsAllCallersAtThree() = runBlocking {
        val fixture = fixture()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val totalStarted = AtomicInteger(0)
        try {
            fixture.coordinator.awaitHydrated()
            val callers = (0 until 4).map { index ->
                async(Dispatchers.Default) {
                    fixture.coordinator.execute(
                        downloadId = "artist|song-$index",
                        artist = "Artist",
                        title = "Song $index"
                    ) {
                        val nowActive = active.incrementAndGet()
                        maxActive.updateAndGet { current -> maxOf(current, nowActive) }
                        totalStarted.incrementAndGet()
                        entered.send(Unit)
                        try {
                            release.await()
                        } finally {
                            active.decrementAndGet()
                        }
                        Result.success(downloadedSong(id = index.toLong() + 1))
                    }
                }
            }

            repeat(3) {
                withTimeout(TEST_TIMEOUT_MS) { entered.receive() }
            }
            delay(100)
            assertEquals(3, totalStarted.get())
            assertEquals(3, maxActive.get())

            release.complete(Unit)
            withTimeout(TEST_TIMEOUT_MS) { callers.awaitAll() }
            assertEquals(4, totalStarted.get())
            assertEquals(3, maxActive.get())
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun concurrentPlaylistTargets_areEachCompletedExactlyOnce() = runBlocking {
        val completedTargets = mutableListOf<DownloadPlaylistDestination>()
        val addedSongs = mutableListOf<Pair<Long, Long>>()
        val clearedPending = mutableListOf<DownloadPlaylistDestination>()
        val fixture = fixture(
            onPlaylistTargetCompleted = { target, song ->
                completedTargets += target
                addedSongs += target.playlistId to song.id
                clearedPending += target
            }
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val identity = TrackIdentity(
            title = "Song",
            artist = "Artist",
            album = "Album"
        )
        val firstTarget = DownloadPlaylistDestination(playlistId = 10L, identity = identity)
        val secondTarget = DownloadPlaylistDestination(
            playlistId = 20L,
            identity = identity.copy(album = "Imported album")
        )
        val downloaded = downloadedSong(id = 99L)
        try {
            fixture.coordinator.awaitHydrated()
            val owner = async(Dispatchers.Default) {
                fixture.coordinator.execute(
                    downloadId = "artist|song",
                    artist = identity.artist,
                    title = identity.title,
                    onRegistered = {
                        fixture.coordinator.upsert(
                            ActiveDownload.queued(
                                id = "artist|song",
                                source = ActiveDownloadSource.LB_IMPORT,
                                candidates = listOf(
                                    OnlineCatalogTrack(
                                        identity = identity,
                                        id = "video-targets",
                                        provider = "YouTube"
                                    )
                                ),
                                lookupIdentity = identity
                            )
                        )
                    }
                ) {
                    entered.complete(Unit)
                    release.await()
                    Result.success(downloaded)
                }
            }
            withTimeout(TEST_TIMEOUT_MS) { entered.await() }

            assertTrue(
                fixture.coordinator.attachTargetPlaylist(
                    downloadId = "batch:artist|song",
                    artist = identity.artist,
                    title = identity.title,
                    target = firstTarget
                )
            )
            assertTrue(
                fixture.coordinator.attachTargetPlaylist(
                    downloadId = "artist|song",
                    artist = identity.artist,
                    title = identity.title,
                    target = firstTarget
                )
            )
            val attached = listOf(secondTarget, secondTarget).map { target ->
                async(Dispatchers.Default) {
                    fixture.coordinator.execute(
                        downloadId = "batch:artist|song",
                        artist = identity.artist,
                        title = identity.title,
                        playlistTarget = target
                    ) {
                        error("A concurrent target must not start a second transfer")
                    }
                }
            }
            attached.forEach {
                assertTrue(
                    withTimeout(TEST_TIMEOUT_MS) { it.await() } is
                        CoordinatedDownloadResult.AlreadyRunning
                )
            }
            assertEquals(
                setOf(10L, 20L),
                fixture.coordinator.downloads.value.single()
                    .playlistTargets.map { it.playlistId }.toSet()
            )

            release.complete(Unit)
            val result = withTimeout(TEST_TIMEOUT_MS) { owner.await() }
            assertEquals(downloaded, (result as CoordinatedDownloadResult.Completed).result.getOrThrow())
            assertEquals(setOf(firstTarget, secondTarget), completedTargets.toSet())
            assertEquals(2, completedTargets.size)
            assertEquals(setOf(10L to 99L, 20L to 99L), addedSongs.toSet())
            assertEquals(setOf(firstTarget, secondTarget), clearedPending.toSet())
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    private fun fixture(
        persistence: FakePersistence = FakePersistence(),
        onPlaylistTargetCompleted: suspend (DownloadPlaylistDestination, Song) -> Unit = { _, _ -> }
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return Fixture(
            scope = scope,
            coordinator = ProcessDownloadCoordinator(
                scope = scope,
                persistence = persistence,
                onPlaylistTargetCompleted = onPlaylistTargetCompleted
            )
        )
    }

    private fun row(
        id: String,
        source: ActiveDownloadSource
    ): ActiveDownload = ActiveDownload.queued(
        id = id,
        source = source,
        candidates = listOf(
            OnlineCatalogTrack(
                id = "video-$id",
                title = "Song",
                artist = "Artist",
                album = "Album",
                artworkUri = null,
                durationMs = 120_000L,
                audioUrl = "video-$id",
                provider = "YouTube"
            )
        )
    )

    private fun downloadedSong(
        id: Long = 42L
    ): Song = Song(
        id = id,
        uriString = "/music/song-$id.m4a",
        title = "Song",
        artist = "Artist",
        album = "Album"
    )

    private data class Fixture(
        val scope: CoroutineScope,
        val coordinator: ProcessDownloadCoordinator
    ) {
        fun close() {
            scope.cancel()
        }
    }

    private class FakePersistence(
        initial: List<ActiveDownload> = emptyList(),
        holdLoad: Boolean = false
    ) : ActiveDownloadsPersistence {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>().apply {
            if (!holdLoad) complete(Unit)
        }
        private val lock = Any()
        private var stored = initial.toList()

        override suspend fun load(): List<ActiveDownload> {
            loadStarted.complete(Unit)
            releaseLoad.await()
            return saved()
        }

        override suspend fun save(downloads: List<ActiveDownload>) {
            synchronized(lock) {
                stored = downloads.toList()
            }
        }

        fun saved(): List<ActiveDownload> = synchronized(lock) { stored.toList() }
    }

    companion object {
        private const val TEST_TIMEOUT_MS = 5_000L
    }
}
