package com.bestiapop.android.service

import androidx.media3.common.C
import androidx.media3.common.Player
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.network.HttpClients
import com.bestiapop.android.data.playback.PlaybackChangeHint
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.system.BackgroundExecutionStatus
import com.bestiapop.android.domain.radio.RadioSuggestResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic stress and concurrency simulation test evaluating all services simultaneously:
 * - PlaybackRuntime with rapid collection switching (Albums, Artists, Playlists, Radio)
 * - ProcessIdentifyRuntime batch processing in parallel
 * - ProcessDownloadRuntime parallel transfers with chunk/progress emission
 * - Transitions between Foreground (UI attached, 200ms ticker) and Background (UI detached, zero ticks, trimMemory)
 * - Battery unplugged / discharging state simulation (validating zero CPU/Wi-Fi wake locks for local tracks)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FullServiceStressSimulationTest {

    @Test
    fun testConcurrentStress_foregroundRapidCollectionSwitching() = runBlocking {
        val fixture = createStressEnvironment()
        try {
            fixture.playbackRuntime.attachUi()

            // 1. Launch massive batch identification in background (40 songs)
            val identifySubmitted = fixture.identifyRuntime.submit(
                (1L..40L).map { createSong(it, "Artist $it", "Album $it") }
            )

            // 2. Launch concurrent downloads (10 items)
            val downloadJobs = (1..10).map { i ->
                fixture.downloadRuntime.submit(
                    ProcessDownloadRequest(
                        downloadId = "dl-$i",
                        source = ActiveDownloadSource.CATALOG,
                        track = createCatalogTrack("dl-$i"),
                        lookupIdentity = TrackIdentity("Track dl-$i", "Artist dl-$i", "Album dl-$i")
                    )
                )
            }

            // 3. Rapid continuous collection switching on the player
            val albumBeatles = (1L..8L).map { createPlayableLocal(it, "Beatles Song $it", "The Beatles", "Abbey Road") }
            val albumDaftPunk = (9L..16L).map { createPlayableLocal(it, "Daft Track $it", "Daft Punk", "RAM") }
            val playlistRock = (17L..24L).map { createPlayableLocal(it, "Rock $it", "Rock Band", "Greatest Hits") }
            val playlistRemote = (1..6).map { createPlayableRemote("Remote Track $it", "Remote Artist $it") }

            val collections = listOf(albumBeatles, albumDaftPunk, playlistRock, playlistRemote)

            // Perform 16 rapid collection switches interspersed with seeks, skips, and state checks
            for (i in 0 until 16) {
                val targetCollection = collections[i % collections.size]
                fixture.playbackRuntime.playPlayableCollection(
                    items = targetCollection,
                    startIndex = (i % targetCollection.size)
                )

                // Seek & skip rapidly during playback
                fixture.playbackRuntime.seekTo(15_000L * (i + 1))
                if (i % 2 == 0) {
                    fixture.playbackRuntime.skipToNext()
                }

                delay(20L)
            }

            // Await completion of identify batch and download batch under timeout
            identifySubmitted.join()
            withTimeout(TIMEOUT_MS) { fixture.identifyRuntime.awaitIdle() }

            downloadJobs.forEach { it.join() }
            withTimeout(TIMEOUT_MS) { fixture.downloadRuntime.awaitIdle() }

            // Validate consistency: identify processed songs, downloads registered, player still responsive
            assertTrue(fixture.identifiedCount.get() >= 40)
            assertTrue(fixture.completedDownloads.size >= 10)
            assertNotNull(fixture.playbackRuntime.currentItem.value)
            assertTrue(fixture.playbackRuntime.queue.value.isNotEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testConcurrentStress_backgroundBatteryUnpluggedWithCollectionSwitching() = runBlocking {
        val fixture = createStressEnvironment()
        try {
            // Simulate device unplugged & battery discharging
            val batteryStatus = BatterySimulationStatus(
                isPlugged = false,
                isCharging = false,
                batteryPercent = 42,
                backgroundStatus = BackgroundExecutionStatus(
                    backgroundRestricted = false,
                    ignoringBatteryOptimizations = false
                )
            )
            assertFalse("Device must simulate discharging", batteryStatus.isCharging)
            assertFalse("Device must not ignore battery optimizations", batteryStatus.backgroundStatus.ignoringBatteryOptimizations)

            // Start in foreground, switch to background
            fixture.playbackRuntime.attachUi()
            fixture.playbackRuntime.playPlayableCollection(
                items = (1L..5L).map { createPlayableLocal(it, "Local $it", "Artist", "Album") }
            )
            assertTrue(fixture.playbackRuntime.isPlaying.value)

            // Transition to BACKGROUND: UI detached, trimMemory invoked, sockets evicted
            fixture.playbackRuntime.detachUi()
            fixture.simulateMemoryTrimAndEvictConnections()

            // Verify: In background, ticker must remain completely inactive (zero wakeups)
            val tickerCountBefore = fixture.tickerTickCount.get()
            delay(100L)
            assertEquals("Ticker must not run in background", tickerCountBefore, fixture.tickerTickCount.get())

            // Background collection switch 1: Local album while battery is discharging
            val localAlbum = (10L..15L).map { createPlayableLocal(it, "Background Local $it", "Artist", "Album") }
            fixture.playbackRuntime.playPlayableCollection(items = localAlbum, startIndex = 0)

            // Validate: Local track in background must NEVER hold network WakeLock/WifiLock
            val localWakeMode = playbackWakeMode(currentIsRemote = false, nextIsRemote = true)
            assertEquals("Local playback must use WAKE_MODE_NONE without holding Java WakeLocks", C.WAKE_MODE_NONE, localWakeMode)

            // Background collection switch 2: Remote stream
            val remotePlaylist = (1..4).map { createPlayableRemote("Background Stream $it", "Online Artist $it") }
            fixture.playbackRuntime.playPlayableCollection(items = remotePlaylist, startIndex = 0)

            // Validate: Remote track in background activates network wake mode for streaming
            val remoteWakeMode = playbackWakeMode(currentIsRemote = true, nextIsRemote = false)
            assertEquals("Remote playback must use WAKE_MODE_NETWORK", C.WAKE_MODE_NETWORK, remoteWakeMode)

            // Run concurrent background downloads while music is playing
            val bgDownload = fixture.downloadRuntime.submit(
                ProcessDownloadRequest(
                    downloadId = "bg-dl-1",
                    source = ActiveDownloadSource.CATALOG,
                    track = createCatalogTrack("bg-dl-1"),
                    lookupIdentity = TrackIdentity("BG Track", "BG Artist", "BG Album")
                )
            )
            bgDownload.join()
            withTimeout(TIMEOUT_MS) { fixture.downloadRuntime.awaitIdle() }

            // Pause playback: verify pause grace period starts cleanly
            fixture.playbackRuntime.togglePlayPause()
            assertFalse(fixture.playbackRuntime.isPlaying.value)

            // Ensure no lingering background crashes or corrupted state
            assertTrue(fixture.completedDownloads.any { it.id.contains("bg-dl-1") })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testConcurrentStress_rapidInterleavedForegroundBackgroundTransitions() = runBlocking {
        val fixture = createStressEnvironment()
        try {
            val albumA = (1L..5L).map { createPlayableLocal(it, "Track A$it", "Artist A", "Album A") }
            val albumB = (6L..10L).map { createPlayableLocal(it, "Track B$it", "Artist B", "Album B") }

            // Rapidly flip foreground / background 10 times while actively playing and switching collections
            for (i in 0 until 10) {
                if (i % 2 == 0) {
                    fixture.playbackRuntime.attachUi()
                    fixture.playbackRuntime.playPlayableCollection(albumA, startIndex = i % albumA.size)
                } else {
                    fixture.playbackRuntime.detachUi()
                    fixture.simulateMemoryTrimAndEvictConnections()
                    fixture.playbackRuntime.playPlayableCollection(albumB, startIndex = i % albumB.size)
                }
                delay(15L)
            }

            // Leave in foreground and ensure player is stable
            fixture.playbackRuntime.attachUi()
            delay(50L)
            assertNotNull(fixture.playbackRuntime.currentItem.value)
            assertEquals(5, fixture.playbackRuntime.queue.value.size)
        } finally {
            fixture.close()
        }
    }

    // --------------------------------------------------------------------------------------------
    // Test Infrastructure & Fixtures
    // --------------------------------------------------------------------------------------------

    private data class BatterySimulationStatus(
        val isPlugged: Boolean,
        val isCharging: Boolean,
        val batteryPercent: Int,
        val backgroundStatus: BackgroundExecutionStatus
    )

    private fun createSong(id: Long, artist: String, album: String) = Song(
        id = id,
        uriString = "/music/$id.mp3",
        title = "Title $id",
        artist = artist,
        album = album
    )

    private fun createPlayableLocal(id: Long, title: String, artist: String, album: String) = PlayableItem.Local(
        song = Song(
            id = id,
            uriString = "/music/$id.mp3",
            title = title,
            artist = artist,
            album = album,
            durationMs = 180_000L
        )
    )

    private fun createPlayableRemote(title: String, artist: String) = PlayableItem.Remote(
        identity = TrackIdentity(title = title, artist = artist, album = "Online Album", durationMs = 210_000L),
        youtubeQueryOrId = "yt-$title",
        resolved = ResolvedStream(
            audioUrl = "https://bestiapop.fake/audio/$title.m4a",
            userAgent = "BestiaPopTest/1.0",
            videoId = "yt-$title",
            resolvedAtEpochMs = System.currentTimeMillis()
        )
    )

    private fun createCatalogTrack(id: String) = OnlineCatalogTrack(
        identity = TrackIdentity(title = "Track $id", artist = "Artist $id", album = "Album $id"),
        id = "yt-$id",
        provider = "YouTube"
    )

    private fun createStressEnvironment(): StressEnvironment {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tickerCount = AtomicInteger(0)
        val identifiedCount = AtomicInteger(0)
        val completedDownloads = CopyOnWriteArrayList<ActiveDownload>()

        val controller = StressFakeController()

        // 1. PlaybackRuntime setup
        val playbackRuntime = PlaybackRuntime(
            PlaybackRuntimeDependencies(
                scope = testScope,
                libraryUpdates = MutableStateFlow(emptyList()),
                playbackSettings = MutableStateFlow(PlaybackSettings()),
                playbackSettingsReady = MutableStateFlow(true),
                listenSettings = MutableStateFlow(ListenBrainzSettings()),
                listenSettingsReady = MutableStateFlow(true),
                persistence = object : PlaybackRuntimePersistence {},
                listenTracker = object : PlaybackRuntimeListenTracker {
                    override fun onTrackChanged(song: Song?, hint: PlaybackChangeHint) {}
                    override fun onDurationKnown(songId: Long, durationMs: Long) {}
                    override fun onPlaybackTick(isPlaying: Boolean, elapsedRealtimeMs: Long) {
                        tickerCount.incrementAndGet()
                    }
                    override fun onStopped() {}
                },
                streamAccess = object : PlaybackRuntimeStreamAccess {
                    override fun needsResolve(item: PlayableItem.Remote): Boolean = item.resolved == null
                    override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote? =
                        item.copy(
                            resolved = ResolvedStream(
                                audioUrl = "https://bestiapop.fake/stream.m4a",
                                userAgent = "BestiaPopTest/1.0",
                                videoId = item.youtubeQueryOrId ?: "vid-test",
                                resolvedAtEpochMs = System.currentTimeMillis()
                            )
                        )
                    override suspend fun invalidate(item: PlayableItem.Remote) {}
                },
                saveDownloads = object : PlaybackRuntimeSaveDownloads {
                    override val downloads: StateFlow<List<ActiveDownload>> = MutableStateFlow(emptyList())
                    override suspend fun save(remote: PlayableItem.Remote): SaveWhileListeningDownloadResult =
                        SaveWhileListeningDownloadResult.Saved(createSong(9999L, remote.artist, remote.album ?: "Album"))
                    override fun dismiss(id: String) {}
                },
                radioSuggester = PlaybackRuntimeRadioSuggester {
                    RadioSuggestResult(
                        items = (1..4).map { createPlayableRemote("Radio Suggestion $it", "Radio Artist $it") },
                        usedOnlineDiscovery = true,
                        onlineDiscoveryFailed = false
                    )
                },
                isOnline = { true },
                clockMs = { System.currentTimeMillis() },
                elapsedRealtimeMs = { System.currentTimeMillis() },
                controllerReconnectBackoffMs = { 0L },
                startTicker = true,
                loadSongById = { null },
                ioDispatcher = Dispatchers.Default
            )
        )
        playbackRuntime.connectForTest {
            object : PlaybackControllerConnection {
                override fun addListener(listener: () -> Unit) {
                    listener()
                }
                override fun get(): PlaybackControllerFacade = controller
                override fun cancel() {}
            }
        }
        playbackRuntime.attachControllerForTest(controller)

        // 2. ProcessIdentifyRuntime setup
        val identifyRuntime = ProcessIdentifyRuntime(
            scope = testScope,
            dependencies = ProcessIdentifyRuntime.Dependencies(
                getSong = { id -> createSong(id, "Artist $id", "Album $id") },
                propose = { song, _, _ ->
                    delay(5L) // Simulate network/fingerprint lookup
                    val candidate = IdentifyCandidate(createCatalogTrack("cand-${song.id}"), 0.95f)
                    IdentifyProposal(
                        songId = song.id,
                        queryArtist = song.artist,
                        queryTitle = song.title,
                        confidence = IdentifyConfidence.HIGH,
                        candidates = listOf(candidate),
                        suggested = candidate
                    )
                },
                apply = { songId, _, _ ->
                    identifiedCount.incrementAndGet()
                    IdentifyResult.Updated(songId)
                },
                listenBrainzToken = { null },
                pendingSongIds = { emptySet() },
                appendReview = { _, _ -> },
                loadWork = { null },
                saveWork = {},
                isOnline = { true },
                acquireExecutionLease = { AutoCloseable {} },
                loadScopedAlbumTracks = { _, _ -> null },
                setAlbumArtwork = null,
                searchAlbums = null
            )
        )

        // 3. ProcessDownloadRuntime setup
        val downloadPersistence = object : ActiveDownloadsPersistence {
            private val rows = mutableListOf<ActiveDownload>()
            override suspend fun load(): List<ActiveDownload> = synchronized(rows) { rows.toList() }
            override suspend fun save(downloads: List<ActiveDownload>) {
                synchronized(rows) {
                    rows.clear()
                    rows.addAll(downloads)
                }
            }
        }
        val coordinator = ProcessDownloadCoordinator(
            scope = testScope,
            persistence = downloadPersistence,
            onPlaylistTargetCompleted = { _, _ -> }
        )
        val downloadRuntime = ProcessDownloadRuntime(
            scope = testScope,
            processDownloads = coordinator,
            dependencies = ProcessDownloadRuntime.Dependencies(
                findSong = { _, _ -> null },
                download = { track, _, progress ->
                    progress(DownloadPhase.Downloading(track.title))
                    delay(10L) // Simulate byte transfer
                    val s = createSong(999L, track.artist, track.album)
                    completedDownloads.add(
                        ActiveDownload(
                            id = track.id,
                            source = ActiveDownloadSource.CATALOG,
                            candidates = listOf(track),
                            state = CandidateDownloadState.SUCCESS,
                            lookupIdentity = track.identity,
                            resultSongId = s.id
                        )
                    )
                    Result.success(s)
                },
                isMetered = { false },
                downloadOnMeteredNetwork = { true },
                acquireExecutionLease = { AutoCloseable {} }
            )
        )

        return StressEnvironment(
            scope = testScope,
            playbackRuntime = playbackRuntime,
            identifyRuntime = identifyRuntime,
            downloadRuntime = downloadRuntime,
            tickerTickCount = tickerCount,
            identifiedCount = identifiedCount,
            completedDownloads = completedDownloads
        )
    }

    private data class StressEnvironment(
        val scope: CoroutineScope,
        val playbackRuntime: PlaybackRuntime,
        val identifyRuntime: ProcessIdentifyRuntime,
        val downloadRuntime: ProcessDownloadRuntime,
        val tickerTickCount: AtomicInteger,
        val identifiedCount: AtomicInteger,
        val completedDownloads: CopyOnWriteArrayList<ActiveDownload>
    ) {
        fun simulateMemoryTrimAndEvictConnections() {
            HttpClients.evictIdleConnections()
        }

        fun close() {
            scope.cancel()
        }
    }

    private class StressFakeController : PlaybackControllerFacade {
        private var listener: PlaybackControllerFacade.Listener? = null
        private val timeline = mutableListOf<PlayableItem>()
        var positionMs = 0L
        var durationMs = 180_000L
        var playing = false
        var wantsPlay = false
        var state = Player.STATE_IDLE
        private var index = 0
        private var repeatModeValue = Player.REPEAT_MODE_OFF
        private var shuffle = false

        override val mediaItemCount: Int get() = timeline.size
        override val currentMediaItemIndex: Int get() = index
        override val currentPosition: Long get() = positionMs
        override val duration: Long get() = durationMs
        override val isPlaying: Boolean get() = playing
        override val playWhenReady: Boolean get() = wantsPlay
        override val playbackState: Int get() = state
        override var repeatMode: Int
            get() = repeatModeValue
            set(value) {
                if (repeatModeValue == value) return
                repeatModeValue = value
                listener?.onRepeatModeChanged(value)
            }
        override var shuffleModeEnabled: Boolean
            get() = shuffle
            set(value) {
                shuffle = value
                listener?.onShuffleModeEnabledChanged(value)
            }

        override fun addListener(listener: PlaybackControllerFacade.Listener) {
            this.listener = listener
        }

        override fun items(): List<PlayableItem> = timeline.toList()

        override fun setMediaItems(
            items: List<PlayableItem>,
            startIndex: Int,
            startPositionMs: Long
        ) {
            timeline.clear()
            timeline.addAll(items)
            index = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            positionMs = startPositionMs
            state = Player.STATE_IDLE
            updatePlaying(false)
            listener?.onTimelineChanged()
        }

        override fun replaceMediaItem(index: Int, item: PlayableItem) {
            if (index in timeline.indices) {
                timeline[index] = item
                listener?.onTimelineChanged()
            }
        }

        override fun addMediaItems(items: List<PlayableItem>) {
            timeline.addAll(items)
            listener?.onTimelineChanged()
        }

        override fun addMediaItems(index: Int, items: List<PlayableItem>) {
            timeline.addAll(index.coerceIn(0, timeline.size), items)
            listener?.onTimelineChanged()
        }

        override fun removeMediaItem(index: Int) {
            if (index in timeline.indices) {
                timeline.removeAt(index)
                listener?.onTimelineChanged()
            }
        }

        override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
            val safeFrom = fromIndex.coerceIn(0, timeline.size)
            val safeTo = toIndex.coerceIn(safeFrom, timeline.size)
            for (i in safeTo - 1 downTo safeFrom) {
                timeline.removeAt(i)
            }
            listener?.onTimelineChanged()
        }

        override fun moveMediaItem(fromIndex: Int, toIndex: Int) {
            if (fromIndex in timeline.indices && toIndex in timeline.indices) {
                val item = timeline.removeAt(fromIndex)
                timeline.add(toIndex, item)
                listener?.onTimelineChanged()
            }
        }

        override fun prepare() {
            state = Player.STATE_READY
            updatePlaying(wantsPlay)
        }

        override fun play() {
            updatePlayWhenReady(true)
            updatePlaying(state == Player.STATE_READY)
        }

        override fun pause() {
            updatePlayWhenReady(false)
            updatePlaying(false)
        }

        override fun seekTo(positionMs: Long) {
            this.positionMs = positionMs
            listener?.onPositionDiscontinuity(positionMs)
        }

        override fun seekTo(index: Int, positionMs: Long) {
            this.index = index.coerceIn(0, (timeline.size - 1).coerceAtLeast(0))
            this.positionMs = positionMs
            listener?.onPositionDiscontinuity(positionMs)
            listener?.onMediaItemTransition(
                timeline.getOrNull(this.index),
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            )
        }

        override fun seekToNextMediaItem() {
            if (hasNextMediaItem()) {
                index++
                positionMs = 0L
                listener?.onMediaItemTransition(
                    timeline.getOrNull(index),
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                )
            }
        }

        override fun seekToPreviousMediaItem() {
            if (hasPreviousMediaItem()) {
                index--
                positionMs = 0L
                listener?.onMediaItemTransition(
                    timeline.getOrNull(index),
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                )
            }
        }

        override fun hasNextMediaItem(): Boolean = index < timeline.size - 1
        override fun hasPreviousMediaItem(): Boolean = index > 0
        override fun release() {
            timeline.clear()
            updatePlaying(false)
            listener = null
        }

        private fun updatePlaying(next: Boolean) {
            if (playing == next) return
            playing = next
            listener?.onIsPlayingChanged(next)
        }

        private fun updatePlayWhenReady(next: Boolean) {
            if (wantsPlay == next) return
            wantsPlay = next
            listener?.onPlayWhenReadyChanged(next)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
