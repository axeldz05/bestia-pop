package com.bestiapop.android.service

import androidx.media3.common.Player
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.DiscoverPlaybackOrigin
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.playback.PlaybackChangeHint
import com.bestiapop.android.data.preferences.LastPlayedSnapshot
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.PersistedQueueItem
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.QueueSnapshot
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.radio.RadioSuggestResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRuntimeContinuityTest {

    @Test
    fun discoverOrigin_survivesUiDetachAndNewUiAttachment() {
        val fixture = fixture()
        try {
            val origin = DiscoverPlaybackOrigin.ListenBrainz(
                mbid = "playlist-mbid",
                title = "Daily Jams"
            )
            fixture.runtime.attachUi()
            fixture.runtime.playPlayableCollection(
                items = listOf(PlayableItem.Local(song(1, "Discover local"))),
                rotate = false,
                origin = origin
            )

            fixture.runtime.detachUi()
            fixture.runtime.attachUi() // Models a replacement ViewModel in the same process.

            assertEquals(origin, fixture.runtime.discoverPlaybackOrigin.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun discoverOrigin_setsForShuffleAndClearsForManualLocalPlayback() {
        val fixture = fixture()
        try {
            fixture.runtime.playPlayableCollection(
                items = listOf(
                    PlayableItem.Local(song(1, "Discover A")),
                    PlayableItem.Local(song(2, "Discover B"))
                ),
                rotate = false,
                startShuffled = true,
                origin = DiscoverPlaybackOrigin.CfRecommendations
            )
            assertEquals(
                DiscoverPlaybackOrigin.CfRecommendations,
                fixture.runtime.discoverPlaybackOrigin.value
            )

            fixture.runtime.playPlayableCollection(
                items = listOf(PlayableItem.Local(song(3, "Manual local"))),
                rotate = false
            )

            assertEquals(
                DiscoverPlaybackOrigin.None,
                fixture.runtime.discoverPlaybackOrigin.value
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun detachUi_keepsSlidingPrefetchAndResolvesRemoteNPlus3() {
        val stream = FakeStreamAccess()
        val fixture = fixture(streamAccess = stream)
        try {
            val remotes = (0..4).map { remote("q$it", "Remote $it") }
            fixture.runtime.attachUi()
            fixture.runtime.playPlayableCollection(remotes, rotate = false)

            assertTrue("initial selection should resolve N", "q0" in stream.resolvedQueries)
            assertTrue("initial prefetch should resolve N+1", "q1" in stream.resolvedQueries)
            assertTrue("initial prefetch should resolve N+2", "q2" in stream.resolvedQueries)
            assertTrue("N+3 must not be resolved before the window slides", "q3" !in stream.resolvedQueries)

            fixture.runtime.detachUi() // Models MusicPlayerViewModel.onCleared().
            fixture.controller.transitionTo(1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

            assertTrue("process runtime must resolve N+3 after UI detach", "q3" in stream.resolvedQueries)
            assertNotNull(fixture.runtime.queue.value[3].let { it as PlayableItem.Remote }.resolved)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun detachUi_keepsRemoteErrorRecoveryOwnedByRuntime() {
        val stream = FakeStreamAccess()
        val fixture = fixture(streamAccess = stream)
        try {
            fixture.runtime.playPlayableCollection(
                listOf(remote("recover-query", "Recover me")),
                rotate = false
            )
            fixture.runtime.attachUi()
            fixture.runtime.detachUi()

            fixture.controller.failCurrentItem()

            assertEquals(1, stream.invalidateCount.get())
            assertTrue(
                stream.resolvedQueries.count { it == "recover-query" } >= 2
            )
            assertNotNull(
                (fixture.runtime.currentItem.value as PlayableItem.Remote).resolved
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun detachUi_pausesTickerAndPersistsOnPauseStateTransition() = runBlocking {
        val persistence = FakePersistence()
        val tracker = FakeListenTracker()
        val fixture = fixture(persistence = persistence, tracker = tracker, startTicker = true)
        try {
            val song = song(1, "Local")
            fixture.runtime.attachUi()
            fixture.runtime.playPlayableCollection(listOf(PlayableItem.Local(song)), rotate = false)
            assertTrue("ticker must be active when UI is attached and playing", fixture.runtime.tickerActiveForTest)

            fixture.runtime.detachUi()
            assertFalse("ticker must pause when UI is detached", fixture.runtime.tickerActiveForTest)

            fixture.clock.set(20_000L)
            fixture.controller.positionMs = 42_000L
            fixture.controller.pause()

            withTimeout(2_000L) {
                while (persistence.lastQueue?.positionMs != 42_000L) delay(10L)
            }
            assertEquals(42_000L, fixture.runtime.playbackPositionMs.value)
            assertEquals(song.id, tracker.lastChangedSongId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun detachUi_keepsSaveWhileListeningAtThreshold() = runBlocking {
        val saver = FakeSaveDownloads()
        val listenSettings = MutableStateFlow(
            ListenBrainzSettings(
                saveWhileListening = true,
                saveWhileListeningPercent = 25
            )
        )
        val fixture = fixture(
            listenSettings = listenSettings,
            saveDownloads = saver
        )
        try {
            val remote = remote("save-query", "Save me", durationMs = 100_000L)
            fixture.runtime.attachUi()
            fixture.runtime.playPlayableCollection(listOf(remote), rotate = false)
            fixture.runtime.detachUi()

            fixture.clock.set(30_000L)
            fixture.controller.positionMs = 30_000L
            fixture.controller.durationMs = 100_000L
            fixture.controller.playing = true
            fixture.controller.wantsPlay = true
            fixture.runtime.tickForTest()

            withTimeout(2_000L) {
                while (saver.saveCount.get() == 0) delay(10L)
            }
            assertEquals(1, saver.saveCount.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun autosaveInFlight_isNeutralAndAllowsRetryAfterOwnerReleasesClaim() = runBlocking {
        val results = ArrayDeque(
            listOf(
                SaveWhileListeningDownloadResult.InFlight("manual-owner"),
                SaveWhileListeningDownloadResult.Saved(
                    Song(
                        id = 99L,
                        uriString = "/saved/claimed.m4a",
                        title = "Already downloading",
                        artist = "Artist"
                    )
                )
            )
        )
        val saver = FakeSaveDownloads { results.removeFirst() }
        val listenSettings = MutableStateFlow(
            ListenBrainzSettings(
                saveWhileListening = true,
                saveWhileListeningPercent = 25
            )
        )
        val fixture = fixture(
            listenSettings = listenSettings,
            saveDownloads = saver
        )
        val events = CopyOnWriteArrayList<String>()
        val eventsJob = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.runtime.events.collect(events::add)
        }
        try {
            fixture.runtime.playPlayableCollection(
                listOf(remote("claimed", "Already downloading", durationMs = 100_000L)),
                rotate = false
            )
            fixture.controller.positionMs = 30_000L
            fixture.controller.durationMs = 100_000L
            fixture.controller.playing = true
            fixture.controller.wantsPlay = true
            fixture.clock.set(30_000L)

            fixture.runtime.tickForTest()
            yield()
            assertEquals(1, saver.saveCount.get())
            assertTrue(events.none { it.startsWith("No se pudo guardar") })

            withTimeout(2_000L) {
                while (
                    saver.saveCount.get() < 2 ||
                    events.none { it.contains("guardada en la biblioteca") }
                ) {
                    delay(10L)
                    fixture.runtime.tickForTest()
                    yield()
                }
            }
            assertEquals(2, saver.saveCount.get())
            assertTrue(events.none { it.startsWith("No se pudo guardar") })
        } finally {
            eventsJob.cancel()
            fixture.close()
        }
    }

    @Test
    fun detachUi_keepsRadioRefillPolicyRunning() {
        val radioCalls = AtomicInteger(0)
        val suggester = PlaybackRuntimeRadioSuggester {
            when (radioCalls.incrementAndGet()) {
                1 -> RadioSuggestResult(
                    items = listOf(
                        PlayableItem.Local(song(2, "Initial A")),
                        PlayableItem.Local(song(3, "Initial B"))
                    ),
                    usedOnlineDiscovery = false,
                    onlineDiscoveryFailed = false
                )
                else -> RadioSuggestResult(
                    items = listOf(
                        PlayableItem.Local(song(4, "Refill A")),
                        PlayableItem.Local(song(5, "Refill B"))
                    ),
                    usedOnlineDiscovery = false,
                    onlineDiscoveryFailed = false
                )
            }
        }
        val fixture = fixture(radioSuggester = suggester)
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Seed"))),
                rotate = false,
                origin = DiscoverPlaybackOrigin.ListenBrainz("radio-origin", "Discover")
            )
            fixture.controller.state = Player.STATE_READY
            fixture.runtime.startRadio(mode = RadioMode.KNOWN)
            assertEquals(1, radioCalls.get())
            assertEquals(
                DiscoverPlaybackOrigin.None,
                fixture.runtime.discoverPlaybackOrigin.value
            )

            fixture.runtime.attachUi()
            fixture.runtime.detachUi()
            fixture.controller.transitionTo(1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

            assertEquals("radio refill must survive ViewModel detach", 2, radioCalls.get())
            assertTrue(fixture.runtime.queue.value.size >= 5)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun stopRadio_lateStartCompletionDoesNotMutateQueue() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(
            radioSuggester = PlaybackRuntimeRadioSuggester {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                RadioSuggestResult(
                    items = listOf(PlayableItem.Local(song(9, "Late start"))),
                    usedOnlineDiscovery = false,
                    onlineDiscoveryFailed = false
                )
            }
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Seed"))),
                rotate = false
            )
            fixture.controller.state = Player.STATE_READY
            val originalQueue = fixture.runtime.queue.value

            fixture.runtime.startRadio(mode = RadioMode.KNOWN)
            started.await()
            fixture.runtime.stopRadio()
            release.complete(Unit)
            yield()

            assertEquals(originalQueue, fixture.runtime.queue.value)
            assertFalse(fixture.runtime.radioActive.value)
            assertFalse(fixture.runtime.radioLoading.value)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun stopRadio_lateRefillCompletionDoesNotMutateQueue() = runBlocking {
        val refillStarted = CompletableDeferred<Unit>()
        val releaseRefill = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val fixture = fixture(
            radioSuggester = PlaybackRuntimeRadioSuggester {
                if (calls.incrementAndGet() == 1) {
                    RadioSuggestResult(
                        items = listOf(
                            PlayableItem.Local(song(2, "Initial A")),
                            PlayableItem.Local(song(3, "Initial B"))
                        ),
                        usedOnlineDiscovery = false,
                        onlineDiscoveryFailed = false
                    )
                } else {
                    refillStarted.complete(Unit)
                    withContext(NonCancellable) { releaseRefill.await() }
                    RadioSuggestResult(
                        items = listOf(PlayableItem.Local(song(4, "Late refill"))),
                        usedOnlineDiscovery = false,
                        onlineDiscoveryFailed = false
                    )
                }
            }
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Seed"))),
                rotate = false
            )
            fixture.controller.state = Player.STATE_READY
            fixture.runtime.startRadio(mode = RadioMode.KNOWN)
            fixture.controller.transitionTo(1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            refillStarted.await()
            val queueBeforeStop = fixture.runtime.queue.value

            fixture.runtime.stopRadio()
            releaseRefill.complete(Unit)
            yield()

            assertEquals(queueBeforeStop, fixture.runtime.queue.value)
            assertFalse(fixture.runtime.radioActive.value)
        } finally {
            releaseRefill.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun startRadioDuringPlayback_keepsCurrentAndReplacesUpcoming() {
        val fixture = fixture(
            radioSuggester = PlaybackRuntimeRadioSuggester {
                RadioSuggestResult(
                    items = listOf(
                        PlayableItem.Local(song(10, "Radio A")),
                        PlayableItem.Local(song(11, "Radio B"))
                    ),
                    usedOnlineDiscovery = false,
                    onlineDiscoveryFailed = false
                )
            }
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(
                    PlayableItem.Local(song(1, "Current")),
                    PlayableItem.Local(song(2, "Old upcoming A")),
                    PlayableItem.Local(song(3, "Old upcoming B"))
                ),
                rotate = false
            )
            fixture.controller.state = Player.STATE_READY
            val currentQueueEntryId = fixture.runtime.currentItem.value?.queueEntryId

            fixture.runtime.startRadio(mode = RadioMode.KNOWN)

            assertEquals(currentQueueEntryId, fixture.runtime.currentItem.value?.queueEntryId)
            assertEquals(
                listOf("Current", "Radio A", "Radio B"),
                fixture.runtime.queue.value.map { it.title }
            )
            assertEquals(
                fixture.runtime.queue.value.map { it.queueEntryId },
                fixture.controller.items().map { it.queueEntryId }
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun radioModes_publishObservableStatusLabels() {
        val fixture = fixture(
            radioSuggester = PlaybackRuntimeRadioSuggester {
                RadioSuggestResult(
                    items = listOf(PlayableItem.Local(song(20, "Suggested ${it.mode}"))),
                    usedOnlineDiscovery = it.mode != RadioMode.KNOWN,
                    onlineDiscoveryFailed = false
                )
            }
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Seed"))),
                rotate = false
            )
            fixture.controller.state = Player.STATE_READY

            listOf(
                RadioMode.KNOWN to "Radio · Solo conocidos",
                RadioMode.NEW to "Radio · Solo nuevos",
                RadioMode.BOTH to "Radio · Ambos"
            ).forEach { (mode, label) ->
                fixture.runtime.startRadio(mode = mode)
                assertEquals(mode, fixture.runtime.radioMode.value)
                assertEquals(label, fixture.runtime.radioStatusLabel.value)
                fixture.runtime.stopRadio()
                assertEquals(null, fixture.runtime.radioStatusLabel.value)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun emptyNewRadio_retriesWithInjectedClockAndEmitsVisibleEvent() = runTest {
        val calls = AtomicInteger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = fixture(
            radioSuggester = PlaybackRuntimeRadioSuggester {
                calls.incrementAndGet()
                RadioSuggestResult(emptyList(), false, true)
            },
            dispatcher = dispatcher,
            clockMs = { testScheduler.currentTime }
        )
        val events = CopyOnWriteArrayList<String>()
        val eventsJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.runtime.events.collect(events::add)
        }
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Seed"))),
                rotate = false
            )
            fixture.controller.state = Player.STATE_READY
            fixture.runtime.startRadio(mode = RadioMode.NEW)

            advanceUntilIdle()

            assertTrue(calls.get() > 1)
            assertTrue(testScheduler.currentTime >= 45_000L)
            assertTrue(events.contains("Radio online no disponible"))
            assertFalse(fixture.runtime.radioActive.value)
        } finally {
            eventsJob.cancel()
            fixture.close()
        }
    }

    @Test
    fun radioRefill_assignsUniqueQueueEntryIdsToEveryAddedSlot() {
        val calls = AtomicInteger()
        val duplicate = PlayableItem.Local(song(4, "Repeated refill"))
        val fixture = fixture(
            radioSuggester = PlaybackRuntimeRadioSuggester {
                if (calls.incrementAndGet() == 1) {
                    RadioSuggestResult(
                        items = listOf(
                            PlayableItem.Local(song(2, "Initial A")),
                            PlayableItem.Local(song(3, "Initial B"))
                        ),
                        usedOnlineDiscovery = false,
                        onlineDiscoveryFailed = false
                    )
                } else {
                    RadioSuggestResult(
                        items = listOf(duplicate, duplicate),
                        usedOnlineDiscovery = false,
                        onlineDiscoveryFailed = false
                    )
                }
            }
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Seed"))),
                rotate = false
            )
            fixture.controller.state = Player.STATE_READY
            fixture.runtime.startRadio(mode = RadioMode.KNOWN)
            fixture.controller.transitionTo(1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

            val queue = fixture.runtime.queue.value
            assertEquals(5, queue.size)
            assertEquals(queue.size, queue.map { it.queueEntryId }.toSet().size)
            assertEquals(2, queue.count { it.title == "Repeated refill" })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun radioRefillDuringShuffle_keepsTimelineAndNextLinear() {
        val radioCalls = AtomicInteger(0)
        val fixture = fixture(
            radioSuggester = PlaybackRuntimeRadioSuggester {
                when (radioCalls.incrementAndGet()) {
                    1 -> RadioSuggestResult(
                        items = listOf(
                            PlayableItem.Local(song(2, "Initial A")),
                            PlayableItem.Local(song(3, "Initial B"))
                        ),
                        usedOnlineDiscovery = false,
                        onlineDiscoveryFailed = false
                    )
                    else -> RadioSuggestResult(
                        items = listOf(
                            PlayableItem.Local(song(4, "Refill A")),
                            PlayableItem.Local(song(5, "Refill B"))
                        ),
                        usedOnlineDiscovery = false,
                        onlineDiscoveryFailed = false
                    )
                }
            }
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Seed"))),
                rotate = false
            )
            fixture.controller.state = Player.STATE_READY
            fixture.runtime.startRadio(mode = RadioMode.KNOWN)
            fixture.runtime.toggleShuffle()
            val syncsBeforeRefill = fixture.controller.shuffleOrderSyncCount

            fixture.controller.transitionTo(1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

            assertEquals(2, radioCalls.get())
            assertTrue(fixture.controller.shuffleOrderSyncCount > syncsBeforeRefill)
            assertPhysicalQueueAndNextAreLinear(fixture)
            val expectedNext = fixture.runtime.queue.value[2]
            fixture.runtime.skipToNext()
            assertEquals(expectedNext.queueEntryId, fixture.runtime.currentItem.value?.queueEntryId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun confirmedNewRadio_clearsPreviousEmptyRefillCooldown() {
        val radioCalls = AtomicInteger(0)
        val fixture = fixture(
            radioSuggester = PlaybackRuntimeRadioSuggester {
                when (radioCalls.incrementAndGet()) {
                    1 -> RadioSuggestResult(
                        items = listOf(
                            PlayableItem.Local(song(2, "Initial A")),
                            PlayableItem.Local(song(3, "Initial B"))
                        ),
                        usedOnlineDiscovery = false,
                        onlineDiscoveryFailed = false
                    )
                    2 -> RadioSuggestResult(emptyList(), false, false)
                    3 -> RadioSuggestResult(
                        items = listOf(
                            PlayableItem.Local(song(4, "Restart A")),
                            PlayableItem.Local(song(5, "Restart B"))
                        ),
                        usedOnlineDiscovery = false,
                        onlineDiscoveryFailed = false
                    )
                    else -> RadioSuggestResult(
                        items = listOf(PlayableItem.Local(song(6, "Fresh refill"))),
                        usedOnlineDiscovery = false,
                        onlineDiscoveryFailed = false
                    )
                }
            }
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Seed"))),
                rotate = false
            )
            fixture.controller.state = Player.STATE_READY
            fixture.runtime.startRadio(mode = RadioMode.KNOWN)
            fixture.controller.transitionTo(1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            assertEquals(2, radioCalls.get())

            // Time has not advanced beyond RADIO_EMPTY_COOLDOWN_MS.
            fixture.runtime.startRadio(mode = RadioMode.KNOWN)
            fixture.controller.transitionTo(2, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

            assertEquals(4, radioCalls.get())
            assertTrue(fixture.runtime.queue.value.any { it.title == "Fresh refill" })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun detachUi_keepsRadioAutoStartOnNaturalEnd() {
        val radioCalls = AtomicInteger(0)
        val fixture = fixture(
            radioSuggester = PlaybackRuntimeRadioSuggester {
                radioCalls.incrementAndGet()
                RadioSuggestResult(
                    items = listOf(PlayableItem.Local(song(8, "Auto radio"))),
                    usedOnlineDiscovery = false,
                    onlineDiscoveryFailed = false
                )
            }
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Seed"))),
                rotate = false,
                origin = DiscoverPlaybackOrigin.CfRecommendations
            )
            fixture.runtime.attachUi()
            fixture.runtime.detachUi()

            fixture.controller.endNaturally()

            assertTrue(radioCalls.get() >= 1)
            assertTrue(fixture.runtime.radioActive.value)
            assertEquals("Auto radio", fixture.runtime.currentItem.value?.title)
            assertEquals(
                DiscoverPlaybackOrigin.None,
                fixture.runtime.discoverPlaybackOrigin.value
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedControllerFuture_isClearedAndRetriedWithBackoff() = runBlocking {
        val backoffAttempts = mutableListOf<Int>()
        val fixture = fixture(
            attachController = false,
            controllerReconnectBackoffMs = {
                backoffAttempts += it
                0L
            }
        )
        val connected = FakeController()
        val connector = SequencedConnector(
            listOf(
                Result.failure(IllegalStateException("first connect failed")),
                Result.success(connected)
            )
        )
        try {
            fixture.runtime.attachUi()
            fixture.runtime.connectForTest(connector)

            withTimeout(2_000L) {
                while (!fixture.runtime.controllerConnectedForTest) delay(10L)
            }
            assertEquals(2, connector.attemptCount.get())
            assertEquals(listOf(1), backoffAttempts)
            assertTrue(connected.listenerAttached)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disconnectedController_releasesAndReconnectsThroughRealRuntimeListener() = runBlocking {
        val fixture = fixture(
            attachController = false,
            controllerReconnectBackoffMs = { 0L }
        )
        val first = FakeController()
        val second = FakeController()
        val connector = SequencedConnector(
            listOf(Result.success(first), Result.success(second))
        )
        try {
            fixture.runtime.attachUi()
            fixture.runtime.connectForTest(connector)
            assertTrue(first.listenerAttached)

            first.disconnect()

            withTimeout(2_000L) {
                while (connector.attemptCount.get() < 2 || !second.listenerAttached) delay(10L)
            }
            assertEquals(1, first.releaseCount.get())
            assertTrue(fixture.runtime.controllerConnectedForTest)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disconnectedController_restoresRuntimeIntentSlotAndSampledPositionWithoutReadingIt() =
        runBlocking {
            val firstItem = PlayableItem.Local(song(1, "First"))
            val currentItem = PlayableItem.Local(song(2, "Current"))
            val first = FakeController().apply {
                seedTimeline(
                    items = listOf(firstItem, currentItem),
                    currentIndex = 1,
                    positionMs = 12_000L,
                    playWhenReady = true,
                    playbackState = Player.STATE_READY,
                    isPlaying = true
                )
            }
            val second = FakeController()
            val fixture = fixture(
                attachController = false,
                controllerReconnectBackoffMs = { 0L }
            )
            val connector = SequencedConnector(
                listOf(Result.success(first), Result.success(second))
            )
            try {
                fixture.runtime.attachUi()
                fixture.runtime.connectForTest(connector)
                first.positionMs = 27_500L
                fixture.runtime.tickForTest()

                first.disconnect() // Every facade read now throws; release remains legal.

                withTimeout(2_000L) {
                    while (second.mediaItemCount != 2 || !second.wantsPlay) delay(10L)
                }
                assertEquals(
                    currentItem.queueEntryId,
                    second.items()[second.currentMediaItemIndex].queueEntryId
                )
                assertEquals(27_500L, second.currentPosition)
                assertTrue(second.wantsPlay)
                assertEquals(1, first.releaseCount.get())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun pausedSeek_debouncesAndPersistsLatestSnapshot() = runBlocking {
        val persistence = FakePersistence()
        val fixture = fixture(persistence = persistence)
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Seekable"))),
                rotate = false
            )
            fixture.runtime.togglePlayPause()

            fixture.runtime.seekTo(10_000L)
            fixture.runtime.seekTo(22_000L)
            fixture.runtime.seekTo(33_333L)

            withTimeout(2_000L) {
                while (persistence.lastQueue?.positionMs != 33_333L) delay(10L)
            }
            assertEquals(33_333L, fixture.runtime.playbackPositionMs.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleResolveCompletion_cannotPauseOrSkipReplacementCollection() = runBlocking {
        val delayed = NonCancellableDelayedFailureStreamAccess("old")
        val fixture = fixture(streamAccess = delayed)
        try {
            fixture.runtime.playPlayableCollection(
                listOf(remote("old", "Old remote")),
                rotate = false
            )
            delayed.started.await()

            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(9, "Replacement"))),
                rotate = false
            )
            delayed.allowCompletion.complete(Unit)
            yield()

            assertEquals("Replacement", fixture.runtime.currentItem.value?.title)
            assertEquals(0, fixture.controller.currentMediaItemIndex)
            assertTrue(fixture.controller.wantsPlay)
            assertEquals(listOf("Replacement"), fixture.controller.items().map { it.title })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun stalePrefetchAndRecoveryCompletions_cannotMutateReplacementCollection() = runBlocking {
        val delayedPrefetch = NonCancellableDelayedSuccessStreamAccess("prefetch-old")
        val prefetchFixture = fixture(streamAccess = delayedPrefetch)
        try {
            prefetchFixture.runtime.playPlayableCollection(
                listOf(
                    PlayableItem.Local(song(1, "Current local")),
                    remote("prefetch-old", "Old prefetched remote")
                ),
                rotate = false
            )
            delayedPrefetch.started.await()

            prefetchFixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(2, "After prefetch"))),
                rotate = false
            )
            delayedPrefetch.allowCompletion.complete(Unit)
            yield()

            assertEquals(
                listOf("After prefetch"),
                prefetchFixture.runtime.queue.value.map { it.title }
            )
        } finally {
            prefetchFixture.close()
        }

        val delayedRecovery = NonCancellableDelayedSuccessStreamAccess("recovery-old")
        val recoveryFixture = fixture(streamAccess = delayedRecovery)
        try {
            recoveryFixture.runtime.playPlayableCollection(
                listOf(
                    remote("recovery-old", "Old recovery").copy(
                        resolved = ResolvedStream(
                            audioUrl = "https://cdn.example/stale",
                            userAgent = "fake-UA",
                            videoId = "stale-video",
                            resolvedAtEpochMs = 10_000L
                        )
                    )
                ),
                rotate = false
            )
            recoveryFixture.controller.failCurrentItem()
            delayedRecovery.started.await()

            recoveryFixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(3, "After recovery"))),
                rotate = false
            )
            delayedRecovery.allowCompletion.complete(Unit)
            yield()

            assertEquals(
                listOf("After recovery"),
                recoveryFixture.runtime.queue.value.map { it.title }
            )
            assertTrue(recoveryFixture.controller.wantsPlay)
        } finally {
            recoveryFixture.close()
        }
    }

    @Test
    fun normalStart_fallbackTriesBeyondFiveRemotesAndFindsLocal() {
        val stream = SelectiveStreamAccess(successfulQueries = emptySet())
        val fixture = fixture(streamAccess = stream)
        try {
            val items = (0 until 6).map { remote("bad-$it", "Bad $it") } +
                PlayableItem.Local(song(50, "Playable local"))

            fixture.runtime.playPlayableCollection(items, rotate = false)

            assertEquals((0 until 6).map { "bad-$it" }, stream.resolvedQueries.take(6))
            assertEquals("Playable local", fixture.runtime.currentItem.value?.title)
            assertEquals(6, fixture.controller.currentMediaItemIndex)
            assertTrue(fixture.controller.wantsPlay)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun errorRecovery_usesCircularPlannerBeyondFiveWithoutInfiniteCascade() {
        val stream = SelectiveStreamAccess(successfulQueries = setOf("initial"))
        val settings = MutableStateFlow(PlaybackSettings(streamSkipGraceSeconds = 0))
        val fixture = fixture(streamAccess = stream, playbackSettings = settings)
        try {
            val items = listOf(remote("initial", "Initial")) +
                (0 until 6).map { remote("bad-$it", "Bad $it") } +
                PlayableItem.Local(song(80, "Recovery local"))
            fixture.runtime.playPlayableCollection(items, rotate = false)

            fixture.controller.failCurrentItem()

            assertEquals("Recovery local", fixture.runtime.currentItem.value?.title)
            assertEquals(7, fixture.controller.currentMediaItemIndex)
            assertTrue(fixture.controller.wantsPlay)
        } finally {
            fixture.close()
        }

        val allBroken = fixture(
            playbackSettings = MutableStateFlow(PlaybackSettings(streamSkipGraceSeconds = 0))
        )
        try {
            allBroken.runtime.playPlayableCollection(
                (0 until 7).map { PlayableItem.Local(song(it.toLong() + 1, "Broken $it")) },
                rotate = false
            )
            repeat(7) { allBroken.controller.failCurrentItem() }

            assertFalse(allBroken.controller.wantsPlay)
            assertEquals(
                6,
                allBroken.controller.operations.count { it == "seekToIndex" }
            )
        } finally {
            allBroken.close()
        }
    }

    @Test
    fun remoteRecovery_reusesOriginalDeadlineAcrossFailedRefreshRounds() {
        val stream = FakeStreamAccess()
        val fixture = fixture(
            streamAccess = stream,
            playbackSettings = MutableStateFlow(PlaybackSettings(streamSkipGraceSeconds = 3)),
            controller = FakeController(prepareBecomesReady = false)
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(
                    remote("deadline", "Deadline remote"),
                    PlayableItem.Local(song(90, "Fallback local"))
                ),
                rotate = false
            )

            fixture.controller.failCurrentItem()
            assertEquals("Deadline remote", fixture.runtime.currentItem.value?.title)

            fixture.clock.set(12_999L)
            fixture.controller.failCurrentItem()
            assertEquals("Deadline remote", fixture.runtime.currentItem.value?.title)

            fixture.clock.set(13_000L)
            fixture.controller.failCurrentItem()

            assertEquals(2, stream.invalidateCount.get())
            assertEquals("Fallback local", fixture.runtime.currentItem.value?.title)
            assertEquals(1, fixture.controller.currentMediaItemIndex)
            assertTrue(fixture.controller.wantsPlay)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun expiredRemoteRecovery_pausesWithoutGrantingAnotherWindowUntilProgress() {
        val stream = FakeStreamAccess()
        val fixture = fixture(
            streamAccess = stream,
            playbackSettings = MutableStateFlow(PlaybackSettings(streamSkipGraceSeconds = 3)),
            controller = FakeController(prepareBecomesReady = false)
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(remote("only-deadline", "Only remote")),
                rotate = false
            )
            fixture.controller.failCurrentItem()

            fixture.clock.set(13_000L)
            fixture.controller.failCurrentItem()
            assertFalse(fixture.controller.wantsPlay)
            assertEquals(1, stream.invalidateCount.get())

            fixture.runtime.togglePlayPause()
            assertTrue(fixture.controller.wantsPlay)
            fixture.controller.failCurrentItem()

            assertFalse(fixture.controller.wantsPlay)
            assertEquals(
                "an expired slot must not receive a fresh resolve window without playback progress",
                1,
                stream.invalidateCount.get()
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun remoteRecovery_realPlaybackProgressStartsANewWindow() {
        val stream = FakeStreamAccess()
        val fixture = fixture(
            streamAccess = stream,
            playbackSettings = MutableStateFlow(PlaybackSettings(streamSkipGraceSeconds = 3))
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(
                    remote("progress", "Progress remote"),
                    PlayableItem.Local(song(91, "Should not fallback"))
                ),
                rotate = false
            )
            fixture.controller.failCurrentItem()
            assertTrue(fixture.controller.playing)

            fixture.clock.set(13_000L)
            fixture.controller.failCurrentItem()

            assertEquals(2, stream.invalidateCount.get())
            assertEquals("Progress remote", fixture.runtime.currentItem.value?.title)
            assertEquals(0, fixture.controller.currentMediaItemIndex)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun autosaveAndRadio_waitForFirstRealListenBrainzSettings() = runBlocking {
        val listenSettings = MutableStateFlow(ListenBrainzSettings())
        val listenReady = MutableStateFlow(false)
        val saver = FakeSaveDownloads()
        val radioCalls = AtomicInteger(0)
        val fixture = fixture(
            listenSettings = listenSettings,
            listenSettingsReady = listenReady,
            saveDownloads = saver,
            radioSuggester = PlaybackRuntimeRadioSuggester {
                radioCalls.incrementAndGet()
                RadioSuggestResult(
                    listOf(PlayableItem.Local(song(2, "Suggested"))),
                    usedOnlineDiscovery = false,
                    onlineDiscoveryFailed = false
                )
            }
        )
        try {
            fixture.runtime.playPlayableCollection(
                listOf(remote("listen-ready", "Listen ready", durationMs = 100_000L)),
                rotate = false
            )
            fixture.controller.positionMs = 30_000L
            fixture.controller.durationMs = 100_000L
            fixture.clock.set(30_000L)
            fixture.runtime.tickForTest()
            fixture.runtime.startRadio(mode = RadioMode.KNOWN)

            assertEquals(0, saver.saveCount.get())
            assertEquals(0, radioCalls.get())

            listenSettings.value = ListenBrainzSettings(
                saveWhileListening = true,
                saveWhileListeningPercent = 25
            )
            listenReady.value = true

            withTimeout(2_000L) {
                while (saver.saveCount.get() == 0 || radioCalls.get() == 0) delay(10L)
            }
            assertEquals(1, saver.saveCount.get())
            assertEquals(1, radioCalls.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun externalRepeatShuffleAndTimelineChanges_areReconciledFromCallbacks() {
        val fixture = fixture()
        try {
            fixture.runtime.playPlayableCollection(
                listOf(
                    PlayableItem.Local(song(1, "One")),
                    PlayableItem.Local(song(2, "Two")),
                    PlayableItem.Local(song(3, "Three"))
                ),
                rotate = false
            )
            val original = fixture.runtime.queue.value

            fixture.controller.externalSetRepeatMode(Player.REPEAT_MODE_ALL)
            assertEquals(RepeatMode.ALL, fixture.runtime.repeatMode.value)

            fixture.controller.externalSetShuffleEnabled(true)
            assertTrue(fixture.runtime.isShuffle.value)
            assertTrue(fixture.controller.shuffleModeEnabled)

            fixture.controller.externalSetShuffleEnabled(false)
            assertFalse(fixture.runtime.isShuffle.value)
            assertEquals(
                original.map { it.queueEntryId },
                fixture.runtime.queue.value.map { it.queueEntryId }
            )

            fixture.controller.externalSetTimeline(
                items = listOf(original[2], original[0]),
                currentIndex = 1,
                positionMs = 4_444L
            )
            assertEquals(
                listOf(original[2].queueEntryId, original[0].queueEntryId),
                fixture.runtime.queue.value.map { it.queueEntryId }
            )
            assertEquals(original[0].queueEntryId, fixture.runtime.currentItem.value?.queueEntryId)
            assertEquals(4_444L, fixture.runtime.playbackPositionMs.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun shuffleAppendAndPlayNext_keepTimelineAndNextLinear() {
        val fixture = fixture()
        try {
            fixture.runtime.playPlayableCollection(
                listOf(
                    PlayableItem.Local(song(1, "One")),
                    PlayableItem.Local(song(2, "Two")),
                    PlayableItem.Local(song(3, "Three"))
                ),
                rotate = false
            )
            fixture.runtime.toggleShuffle()

            val syncsBeforeAppend = fixture.controller.shuffleOrderSyncCount
            fixture.runtime.addPlayableBatch(
                listOf(PlayableItem.Local(song(4, "Appended")))
            )
            assertTrue(fixture.controller.shuffleOrderSyncCount > syncsBeforeAppend)
            assertPhysicalQueueAndNextAreLinear(fixture)
            val nextAfterAppend = fixture.runtime.queue.value[1]
            fixture.runtime.skipToNext()
            assertEquals(
                nextAfterAppend.queueEntryId,
                fixture.runtime.currentItem.value?.queueEntryId
            )

            val syncsBeforePlayNext = fixture.controller.shuffleOrderSyncCount
            fixture.runtime.playNextBatch(
                listOf(PlayableItem.Local(song(5, "Play next")))
            )
            assertTrue(fixture.controller.shuffleOrderSyncCount > syncsBeforePlayNext)
            assertPhysicalQueueAndNextAreLinear(fixture)
            val currentIndex = fixture.controller.currentMediaItemIndex
            val insertedNext = fixture.runtime.queue.value[currentIndex + 1]
            fixture.runtime.skipToNext()
            assertEquals(
                insertedNext.queueEntryId,
                fixture.runtime.currentItem.value?.queueEntryId
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun queueTapWhileShuffled_selectsPhysicalSlotWithoutClearingOrRotatingQueue() {
        val fixture = fixture()
        try {
            fixture.runtime.playPlayableCollection(
                listOf(
                    PlayableItem.Local(song(1, "One")),
                    PlayableItem.Local(song(2, "Two")),
                    PlayableItem.Local(song(3, "Three")),
                    PlayableItem.Local(song(4, "Four"))
                ),
                rotate = false
            )
            fixture.runtime.toggleShuffle()
            val queueBeforeTap = fixture.runtime.displayQueue.value
            val selectedIndex = queueBeforeTap.lastIndex
            val selected = queueBeforeTap[selectedIndex]

            fixture.runtime.skipToQueueIndex(selectedIndex)

            assertTrue(fixture.runtime.isShuffle.value)
            assertEquals(
                queueBeforeTap.map { it.queueEntryId },
                fixture.runtime.displayQueue.value.map { it.queueEntryId }
            )
            assertEquals(selected.queueEntryId, fixture.runtime.currentItem.value?.queueEntryId)
            assertEquals(selectedIndex, fixture.controller.currentMediaItemIndex)
            assertEquals(
                queueBeforeTap.map { it.queueEntryId },
                fixture.controller.items().map { it.queueEntryId }
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun hydration_waitsForFirstRealPlaybackSettingsEmission() = runBlocking {
        val first = song(1, "First")
        val second = song(2, "Second")
        val persisted = QueueSnapshot(
            currentIndex = 0,
            positionMs = 9_000L,
            items = listOf(persistedLocal(first), persistedLocal(second)),
            shufflePlayOrder = listOf(1, 0)
        )
        val persistence = FakePersistence(queueToLoad = persisted)
        val settings = MutableStateFlow(PlaybackSettings())
        val settingsReady = MutableStateFlow(false)
        val fixture = fixture(
            persistence = persistence,
            libraryUpdates = MutableStateFlow(listOf(first, second)),
            playbackSettings = settings,
            playbackSettingsReady = settingsReady
        )
        try {
            assertEquals(0, persistence.loadQueueCount.get())
            assertTrue(fixture.runtime.queue.value.isEmpty())

            settings.value = PlaybackSettings(
                autoplayOnLaunch = false,
                rememberShuffleOnLaunch = true,
                lastShuffleEnabled = true
            )
            settingsReady.value = true

            withTimeout(2_000L) {
                while (fixture.runtime.queue.value.size != 2) delay(10L)
            }
            assertEquals(1, persistence.loadQueueCount.get())
            assertTrue(fixture.runtime.isShuffle.value)
            assertEquals(0, fixture.controller.mediaItemCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun volumeBoostRestore_waitsForPlaybackSettingsReadiness() = runBlocking {
        val settings = MutableStateFlow(PlaybackSettings())
        val settingsReady = MutableStateFlow(false)
        val fixture = fixture(
            playbackSettings = settings,
            playbackSettingsReady = settingsReady
        )
        try {
            val restored = CompletableDeferred<PlaybackSettings>()
            val waiter = launch {
                restored.complete(fixture.runtime.awaitPlaybackSettings())
            }
            yield()

            assertFalse("default settings must not escape before DataStore is ready", restored.isCompleted)

            val loaded = PlaybackSettings(
                volumeBoostEnabled = true,
                volumeBoostAmount = 0.65f
            )
            settings.value = loaded
            settingsReady.value = true

            assertEquals(loaded, withTimeout(2_000L) { restored.await() })
            waiter.cancel()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun metadataOnlySystemResumption_doesNotMutateRuntimeOrResolve() = runBlocking {
        val local = song(2, "Local")
        val persistence = FakePersistence(
            queueToLoad = QueueSnapshot(
                currentIndex = 0,
                positionMs = 9_876L,
                items = listOf(
                    PersistedQueueItem.Remote(
                        identity = TrackIdentity(title = "Remote", artist = "Artist"),
                        youtubeQueryOrId = "remote query"
                    ),
                    persistedLocal(local)
                )
            )
        )
        val streamAccess = FakeStreamAccess()
        val fixture = fixture(
            streamAccess = streamAccess,
            persistence = persistence,
            libraryUpdates = MutableStateFlow(listOf(local)),
            attachController = false
        )
        try {
            val snapshot = fixture.runtime.systemResumptionMetadataSnapshot()

            assertNotNull(snapshot)
            assertEquals(1, persistence.loadQueueCount.get())
            assertEquals(0, fixture.controller.mediaItemCount)
            assertTrue(fixture.runtime.queue.value.isEmpty())
            assertEquals(null, fixture.runtime.currentItem.value)
            assertTrue(streamAccess.resolvedQueries.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun externalCollectionStaging_doesNotInventPlayIntentOrMutateTimeline() {
        val fixture = fixture()
        try {
            val plan = fixture.runtime.stageExternalPlayableCollection(
                items = listOf(PlayableItem.Local(song(1, "External"))),
                startIndex = 0,
                startPositionMs = 321L
            )

            assertNotNull(plan)
            assertEquals("External", fixture.runtime.currentItem.value?.title)
            assertEquals(321L, fixture.runtime.playbackPositionMs.value)
            assertFalse(fixture.controller.wantsPlay)
            assertEquals(0, fixture.controller.timelineMutationCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun externalCollectionStaging_preservesActivePlayIntentUntilMedia3UpdatesIt() {
        val fixture = fixture()
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Playing"))),
                rotate = false
            )
            assertTrue(fixture.controller.wantsPlay)

            fixture.runtime.stageExternalPlayableCollection(
                items = listOf(PlayableItem.Local(song(2, "External"))),
                startIndex = 0,
                startPositionMs = 0L
            )
            fixture.runtime.togglePlayPause()

            assertFalse(fixture.controller.wantsPlay)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun displayOnlyHydration_queueActionsStayInMemoryUntilFirstPlay() {
        val first = song(1, "First")
        val current = song(2, "Current")
        val third = song(3, "Third")
        val persistence = FakePersistence(
            queueToLoad = QueueSnapshot(
                currentIndex = 1,
                positionMs = 12_345L,
                items = listOf(
                    persistedLocal(first),
                    persistedLocal(current),
                    persistedLocal(third)
                )
            )
        )
        val fixture = fixture(
            persistence = persistence,
            libraryUpdates = MutableStateFlow(listOf(first, current, third))
        )
        try {
            assertEquals(0, fixture.controller.timelineMutationCount)
            assertEquals("Current", fixture.runtime.currentItem.value?.title)

            fixture.runtime.addPlayableBatch(listOf(PlayableItem.Local(song(4, "Added"))))
            fixture.runtime.playNextBatch(listOf(PlayableItem.Local(song(5, "Next"))))
            fixture.runtime.removeFromQueue(0)
            fixture.runtime.moveQueueItem(fromIndex = 3, toIndex = 1)
            fixture.runtime.toggleShuffle()

            assertEquals(0, fixture.controller.timelineMutationCount)
            assertEquals(0, fixture.controller.prepareCount)
            assertEquals(0, fixture.controller.playCallCount)
            assertTrue(fixture.runtime.isShuffle.value)
            assertEquals("Current", fixture.runtime.currentItem.value?.title)
            assertEquals(12_345L, fixture.runtime.playbackPositionMs.value)

            fixture.runtime.togglePlayPause()

            assertEquals(1, fixture.controller.setMediaItemsCount)
            assertEquals(
                fixture.runtime.queue.value.map { it.queueEntryId },
                fixture.controller.items().map { it.queueEntryId }
            )
            assertEquals(
                fixture.runtime.queue.value.indexOfFirst {
                    it.queueEntryId == fixture.runtime.currentItem.value?.queueEntryId
                },
                fixture.controller.currentMediaItemIndex
            )
            assertEquals(12_345L, fixture.controller.currentPosition)
            assertTrue(
                fixture.controller.operations.indexOf("setMediaItems") <
                    fixture.controller.operations.indexOf("prepare")
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun pauseDuringDelayedHydratedResolve_neverResumesAndQueueMaterializesFirst() = runBlocking {
        val tail = song(2, "Tail")
        val delayed = DelayedStreamAccess("slow")
        val persistence = FakePersistence(
            queueToLoad = QueueSnapshot(
                currentIndex = 0,
                positionMs = 7_777L,
                items = listOf(
                    PersistedQueueItem.Remote(
                        identity = TrackIdentity(title = "Slow", artist = "Artist"),
                        youtubeQueryOrId = "slow"
                    ),
                    persistedLocal(tail)
                )
            )
        )
        val fixture = fixture(
            streamAccess = delayed,
            persistence = persistence,
            libraryUpdates = MutableStateFlow(listOf(tail))
        )
        try {
            assertEquals(0, fixture.controller.mediaItemCount)
            assertFalse("autoplay off must not resolve the hydrated Remote", delayed.started.isCompleted)

            fixture.runtime.togglePlayPause()
            delayed.started.await()

            assertEquals(2, fixture.controller.mediaItemCount)
            assertEquals(0, fixture.controller.currentMediaItemIndex)
            assertEquals(7_777L, fixture.controller.currentPosition)
            assertEquals(0, fixture.controller.prepareCount)
            assertTrue(fixture.controller.wantsPlay)

            fixture.runtime.togglePlayPause()
            assertFalse(fixture.controller.wantsPlay)
            val prepareAfterPause = fixture.controller.prepareCount
            val mutationsAfterPause = fixture.controller.timelineMutationCount
            delayed.allowResolution.complete(Unit)

            withTimeout(2_000L) {
                while (fixture.runtime.resolvingRemote.value) delay(10L)
            }
            assertFalse(fixture.controller.wantsPlay)
            assertFalse(fixture.controller.playing)
            assertEquals(1, fixture.controller.playCallCount)
            assertEquals(
                "pause must cancel in-flight resolve so it cannot prepare/replace",
                prepareAfterPause,
                fixture.controller.prepareCount
            )
            assertEquals(mutationsAfterPause, fixture.controller.timelineMutationCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun pauseWhileBuffering_usesPlayWhenReadyAndReadyCallbackCannotResume() {
        val fixture = fixture()
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Buffering"))),
                rotate = false
            )
            fixture.controller.enterBuffering()
            assertFalse(fixture.controller.playing)
            assertTrue(fixture.controller.wantsPlay)

            fixture.runtime.togglePlayPause()
            fixture.controller.becomeReady()

            assertFalse(fixture.controller.wantsPlay)
            assertFalse(fixture.controller.playing)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun pausedLiveSession_doesNotResolveOrPrefetchUntilPlay() {
        val stream = FakeStreamAccess()
        val controller = FakeController().apply {
            seedTimeline(
                items = listOf(remote("live-0", "Live 0"), remote("live-1", "Live 1")),
                currentIndex = 0,
                positionMs = 4_000L,
                playWhenReady = false,
                playbackState = Player.STATE_IDLE
            )
        }
        val fixture = fixture(streamAccess = stream, controller = controller)
        try {
            assertTrue(stream.resolvedQueries.isEmpty())

            fixture.runtime.togglePlayPause()

            assertTrue("current live item resolves on Play", "live-0" in stream.resolvedQueries)
            assertTrue("prefetch starts only after Play", "live-1" in stream.resolvedQueries)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun persistenceWriter_serializesMainSnapshotsSoOlderWriteCannotWin() = runBlocking {
        val persistence = BlockingFirstSavePersistence()
        val fixture = fixture(persistence = persistence)
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "One"))),
                rotate = false
            )
            fixture.runtime.addPlayableBatch(listOf(PlayableItem.Local(song(2, "Two"))))
            persistence.firstSaveStarted.await()
            fixture.runtime.addPlayableBatch(listOf(PlayableItem.Local(song(3, "Three"))))
            persistence.allowFirstSave.complete(Unit)

            withTimeout(2_000L) {
                while (persistence.savedQueues.size < 2) delay(10L)
            }
            assertEquals(listOf("One", "Two"), persistence.savedQueues[0].titles())
            assertEquals(
                listOf("One", "Two", "Three"),
                persistence.savedQueues.last().titles()
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun controllerAndTicker_releaseOnlyAfterUiPlaybackAndPendingQueueAreGone() {
        val idle = fixture(startTicker = true)
        try {
            idle.runtime.attachUi()
            assertFalse(idle.runtime.tickerActiveForTest)
            idle.runtime.detachUi()
            assertEquals(1, idle.controller.releaseCount.get())
            assertFalse(idle.runtime.controllerConnectedForTest)
        } finally {
            idle.close()
        }

        val active = fixture(startTicker = true)
        try {
            active.runtime.attachUi()
            active.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Playing"))),
                rotate = false
            )
            assertTrue(active.runtime.tickerActiveForTest)

            active.runtime.detachUi()
            assertEquals(0, active.controller.releaseCount.get())
            assertTrue(active.runtime.controllerConnectedForTest)

            active.runtime.togglePlayPause()
            assertFalse(active.runtime.tickerActiveForTest)
            assertEquals(0, active.controller.releaseCount.get())

            active.runtime.removeFromQueue(0)
            assertEquals(1, active.controller.releaseCount.get())
            assertFalse(active.runtime.controllerConnectedForTest)
        } finally {
            active.close()
        }
    }

    private fun fixture(
        streamAccess: PlaybackRuntimeStreamAccess = FakeStreamAccess(),
        persistence: PlaybackRuntimePersistence = FakePersistence(),
        tracker: FakeListenTracker = FakeListenTracker(),
        libraryUpdates: MutableStateFlow<List<Song>> =
            MutableStateFlow<List<Song>>(emptyList()),
        playbackSettings: MutableStateFlow<PlaybackSettings> =
            MutableStateFlow(PlaybackSettings()),
        playbackSettingsReady: MutableStateFlow<Boolean> = MutableStateFlow(true),
        listenSettings: MutableStateFlow<ListenBrainzSettings> =
            MutableStateFlow(ListenBrainzSettings()),
        listenSettingsReady: MutableStateFlow<Boolean> = MutableStateFlow(true),
        saveDownloads: PlaybackRuntimeSaveDownloads = FakeSaveDownloads(),
        radioSuggester: PlaybackRuntimeRadioSuggester = PlaybackRuntimeRadioSuggester {
            RadioSuggestResult(emptyList(), false, false)
        },
        controller: FakeController = FakeController(),
        attachController: Boolean = true,
        controllerReconnectBackoffMs: (Int) -> Long = { 0L },
        startTicker: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        clockMs: (() -> Long)? = null
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val clock = AtomicLong(10_000L)
        val runtime = PlaybackRuntime(
            PlaybackRuntimeDependencies(
                scope = scope,
                libraryUpdates = libraryUpdates,
                playbackSettings = playbackSettings,
                playbackSettingsReady = playbackSettingsReady,
                listenSettings = listenSettings,
                listenSettingsReady = listenSettingsReady,
                persistence = persistence,
                listenTracker = tracker,
                streamAccess = streamAccess,
                saveDownloads = saveDownloads,
                radioSuggester = radioSuggester,
                isOnline = { true },
                clockMs = clockMs ?: clock::get,
                elapsedRealtimeMs = clock::get,
                controllerReconnectBackoffMs = controllerReconnectBackoffMs,
                startTicker = startTicker
            )
        )
        if (attachController) runtime.attachControllerForTest(controller)
        return Fixture(runtime, controller, scope, clock)
    }

    private data class Fixture(
        val runtime: PlaybackRuntime,
        val controller: FakeController,
        val scope: CoroutineScope,
        val clock: AtomicLong
    ) {
        fun close() = scope.cancel()
    }

    private fun assertPhysicalQueueAndNextAreLinear(fixture: Fixture) {
        assertTrue(fixture.runtime.isShuffle.value)
        assertEquals(
            fixture.runtime.queue.value.map { it.queueEntryId },
            fixture.controller.items().map { it.queueEntryId }
        )
        val currentIndex = fixture.controller.currentMediaItemIndex
        assertEquals(
            fixture.runtime.queue.value.getOrNull(currentIndex + 1)?.queueEntryId,
            fixture.controller.nextQueueEntryId()
        )
    }

    private class FakeController(
        private val prepareBecomesReady: Boolean = true
    ) : PlaybackControllerFacade {
        private var listener: PlaybackControllerFacade.Listener? = null
        private val timeline = mutableListOf<PlayableItem>()
        private val shuffleOrder = mutableListOf<Int>()
        val operations = mutableListOf<String>()
        val releaseCount = AtomicInteger(0)
        var setMediaItemsCount = 0
            private set
        var prepareCount = 0
            private set
        var playCallCount = 0
            private set
        var timelineMutationCount = 0
            private set
        var shuffleOrderSyncCount = 0
            private set
        val listenerAttached: Boolean get() = listener != null
        var positionMs = 0L
        var durationMs = 180_000L
        var playing = false
        var wantsPlay = false
        var state = Player.STATE_IDLE
        private var index = 0
        private var valid = true
        private var repeatModeValue = Player.REPEAT_MODE_OFF
        private var shuffle = false

        override val mediaItemCount: Int get() = checked { timeline.size }
        override val currentMediaItemIndex: Int get() = checked { index }
        override val currentPosition: Long get() = checked { positionMs }
        override val duration: Long get() = checked { durationMs }
        override val isPlaying: Boolean get() = checked { playing }
        override val playWhenReady: Boolean get() = checked { wantsPlay }
        override val playbackState: Int get() = checked { state }
        override var repeatMode: Int
            get() = checked { repeatModeValue }
            set(value) {
                checkValid()
                if (repeatModeValue == value) return
                repeatModeValue = value
                listener?.onRepeatModeChanged(value)
            }
        override var shuffleModeEnabled: Boolean
            get() = checked { shuffle }
            set(value) {
                checkValid()
                val changed = shuffle != value
                shuffle = value
                shuffleOrder.clear()
                shuffleOrder.addAll(timeline.indices)
                shuffleOrderSyncCount++
                operations += "syncShuffleOrder"
                if (changed) listener?.onShuffleModeEnabledChanged(value)
            }

        override fun addListener(listener: PlaybackControllerFacade.Listener) {
            checkValid()
            this.listener = listener
        }

        override fun items(): List<PlayableItem> = checked { timeline.toList() }

        override fun setMediaItems(
            items: List<PlayableItem>,
            startIndex: Int,
            startPositionMs: Long
        ) {
            checkValid()
            operations += "setMediaItems"
            setMediaItemsCount++
            timelineMutationCount++
            timeline.clear()
            timeline.addAll(items)
            index = startIndex
            positionMs = startPositionMs
            state = Player.STATE_IDLE
            updatePlaying(false)
            regenerateShuffleOrderAfterTimelineMutation()
            listener?.onTimelineChanged()
        }

        override fun replaceMediaItem(index: Int, item: PlayableItem) {
            checkValid()
            operations += "replaceMediaItem"
            timelineMutationCount++
            timeline[index] = item
            listener?.onTimelineChanged()
        }

        override fun addMediaItems(items: List<PlayableItem>) {
            checkValid()
            operations += "addMediaItems"
            timelineMutationCount++
            timeline.addAll(items)
            regenerateShuffleOrderAfterTimelineMutation()
            listener?.onTimelineChanged()
        }

        override fun addMediaItems(index: Int, items: List<PlayableItem>) {
            checkValid()
            operations += "addMediaItemsAt"
            timelineMutationCount++
            val currentQueueEntryId = timeline.getOrNull(this.index)?.queueEntryId
            timeline.addAll(index, items)
            this.index = currentQueueEntryId?.let { queueEntryId ->
                timeline.indexOfFirst { it.queueEntryId == queueEntryId }
            }?.takeIf { it >= 0 } ?: this.index
            regenerateShuffleOrderAfterTimelineMutation()
            listener?.onTimelineChanged()
        }

        override fun removeMediaItem(index: Int) {
            checkValid()
            operations += "removeMediaItem"
            timelineMutationCount++
            val currentQueueEntryId = timeline.getOrNull(this.index)?.queueEntryId
            timeline.removeAt(index)
            this.index = currentQueueEntryId?.let { queueEntryId ->
                timeline.indexOfFirst { it.queueEntryId == queueEntryId }
            }?.takeIf { it >= 0 }
                ?: this.index.coerceAtMost(timeline.lastIndex.coerceAtLeast(0))
            regenerateShuffleOrderAfterTimelineMutation()
            listener?.onTimelineChanged()
        }

        override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
            checkValid()
            operations += "removeMediaItems"
            timelineMutationCount++
            val currentQueueEntryId = timeline.getOrNull(index)?.queueEntryId
            repeat(toIndex - fromIndex) { timeline.removeAt(fromIndex) }
            index = currentQueueEntryId?.let { queueEntryId ->
                timeline.indexOfFirst { it.queueEntryId == queueEntryId }
            }?.takeIf { it >= 0 }
                ?: index.coerceAtMost(timeline.lastIndex.coerceAtLeast(0))
            regenerateShuffleOrderAfterTimelineMutation()
            listener?.onTimelineChanged()
        }

        override fun moveMediaItem(fromIndex: Int, toIndex: Int) {
            checkValid()
            operations += "moveMediaItem"
            timelineMutationCount++
            val currentQueueEntryId = timeline.getOrNull(index)?.queueEntryId
            timeline.add(toIndex, timeline.removeAt(fromIndex))
            index = currentQueueEntryId?.let { queueEntryId ->
                timeline.indexOfFirst { it.queueEntryId == queueEntryId }
            }?.takeIf { it >= 0 } ?: index
            regenerateShuffleOrderAfterTimelineMutation()
            listener?.onTimelineChanged()
        }

        override fun prepare() {
            checkValid()
            operations += "prepare"
            prepareCount++
            state = if (prepareBecomesReady) Player.STATE_READY else Player.STATE_IDLE
            updatePlaying(prepareBecomesReady && wantsPlay)
        }

        override fun play() {
            checkValid()
            operations += "play"
            playCallCount++
            updatePlayWhenReady(true)
            updatePlaying(state == Player.STATE_READY)
        }

        override fun pause() {
            checkValid()
            operations += "pause"
            updatePlayWhenReady(false)
            updatePlaying(false)
        }

        override fun seekTo(positionMs: Long) {
            checkValid()
            this.positionMs = positionMs
            listener?.onPositionDiscontinuity(positionMs)
        }

        override fun seekTo(index: Int, positionMs: Long) {
            checkValid()
            operations += "seekToIndex"
            this.index = index
            this.positionMs = positionMs
            listener?.onPositionDiscontinuity(positionMs)
            listener?.onMediaItemTransition(
                timeline.getOrNull(index),
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            )
        }

        override fun seekToNextMediaItem() {
            checkValid()
            nextMediaItemIndex()?.let {
                transitionTo(it, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            }
        }

        override fun seekToPreviousMediaItem() {
            checkValid()
            previousMediaItemIndex()?.let {
                transitionTo(it, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            }
        }

        override fun hasNextMediaItem(): Boolean = checked { nextMediaItemIndex() != null }
        override fun hasPreviousMediaItem(): Boolean = checked { previousMediaItemIndex() != null }
        override fun release() {
            releaseCount.incrementAndGet()
            listener = null
        }

        fun transitionTo(nextIndex: Int, reason: Int) {
            checkValid()
            index = nextIndex
            positionMs = 0L
            listener?.onMediaItemTransition(timeline[nextIndex], reason)
        }

        fun failCurrentItem() {
            checkValid()
            state = Player.STATE_IDLE
            updatePlaying(false)
            listener?.onPlayerError()
        }

        fun endNaturally() {
            checkValid()
            updatePlaying(false)
            state = Player.STATE_ENDED
            listener?.onPlaybackStateChanged(Player.STATE_ENDED)
        }

        fun disconnect() {
            checkValid()
            val attached = listener
            valid = false
            attached?.onDisconnected(this)
        }

        fun seedTimeline(
            items: List<PlayableItem>,
            currentIndex: Int,
            positionMs: Long,
            playWhenReady: Boolean,
            playbackState: Int,
            isPlaying: Boolean = false
        ) {
            checkValid()
            timeline.clear()
            timeline.addAll(items)
            index = currentIndex
            this.positionMs = positionMs
            wantsPlay = playWhenReady
            state = playbackState
            playing = isPlaying
            regenerateShuffleOrderAfterTimelineMutation()
            operations.clear()
            timelineMutationCount = 0
            setMediaItemsCount = 0
            prepareCount = 0
            playCallCount = 0
        }

        fun externalSetTimeline(
            items: List<PlayableItem>,
            currentIndex: Int,
            positionMs: Long
        ) {
            checkValid()
            timeline.clear()
            timeline.addAll(items)
            index = currentIndex
            this.positionMs = positionMs
            regenerateShuffleOrderAfterTimelineMutation()
            listener?.onTimelineChanged()
        }

        fun externalSetRepeatMode(value: Int) {
            repeatMode = value
        }

        fun externalSetShuffleEnabled(value: Boolean) {
            shuffleModeEnabled = value
        }

        fun enterBuffering() {
            state = Player.STATE_BUFFERING
            updatePlaying(false)
        }

        fun becomeReady() {
            state = Player.STATE_READY
            updatePlaying(wantsPlay)
        }

        fun nextQueueEntryId(): String? =
            nextMediaItemIndex()?.let { timeline.getOrNull(it)?.queueEntryId }

        private fun nextMediaItemIndex(): Int? = adjacentMediaItemIndex(offset = 1)

        private fun previousMediaItemIndex(): Int? = adjacentMediaItemIndex(offset = -1)

        private fun adjacentMediaItemIndex(offset: Int): Int? {
            if (!shuffle) return (index + offset).takeIf { it in timeline.indices }
            val traversalIndex = shuffleOrder.indexOf(index)
            return shuffleOrder.getOrNull(traversalIndex + offset)
        }

        private fun regenerateShuffleOrderAfterTimelineMutation() {
            shuffleOrder.clear()
            if (!shuffle || timeline.size <= 1) {
                shuffleOrder.addAll(timeline.indices)
                return
            }
            val currentIndex = index.coerceIn(timeline.indices)
            shuffleOrder += currentIndex
            shuffleOrder += timeline.indices.reversed().filter { it != currentIndex }
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

        private fun checkValid() {
            check(valid) { "Fake controller is invalid after disconnect" }
        }

        private inline fun <T> checked(block: () -> T): T {
            checkValid()
            return block()
        }
    }

    private class FakeStreamAccess : PlaybackRuntimeStreamAccess {
        val resolvedQueries = mutableListOf<String>()
        val invalidateCount = AtomicInteger(0)

        override fun needsResolve(item: PlayableItem.Remote): Boolean =
            item.resolved?.audioUrl.isNullOrBlank()

        override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote {
            val query = item.youtubeQueryOrId.orEmpty()
            resolvedQueries += query
            return item.copy(
                resolved = ResolvedStream(
                    audioUrl = "https://cdn.example/$query",
                    userAgent = "fake-UA",
                    videoId = "video-$query",
                    resolvedAtEpochMs = 10_000L
                )
            )
        }

        override suspend fun invalidate(item: PlayableItem.Remote) {
            invalidateCount.incrementAndGet()
        }
    }

    private class DelayedStreamAccess(
        private val delayedQuery: String
    ) : PlaybackRuntimeStreamAccess {
        val started = CompletableDeferred<Unit>()
        val allowResolution = CompletableDeferred<Unit>()

        override fun needsResolve(item: PlayableItem.Remote): Boolean =
            item.resolved?.audioUrl.isNullOrBlank()

        override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote {
            val query = item.youtubeQueryOrId.orEmpty()
            if (query == delayedQuery) {
                started.complete(Unit)
                allowResolution.await()
            }
            return resolvedRemote(item, query)
        }

        override suspend fun invalidate(item: PlayableItem.Remote) = Unit
    }

    private class NonCancellableDelayedFailureStreamAccess(
        private val delayedQuery: String
    ) : PlaybackRuntimeStreamAccess {
        val started = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()

        override fun needsResolve(item: PlayableItem.Remote): Boolean = true

        override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote? {
            if (item.youtubeQueryOrId == delayedQuery) {
                started.complete(Unit)
                withContext(NonCancellable) { allowCompletion.await() }
            }
            return null
        }

        override suspend fun invalidate(item: PlayableItem.Remote) = Unit
    }

    private class NonCancellableDelayedSuccessStreamAccess(
        private val delayedQuery: String
    ) : PlaybackRuntimeStreamAccess {
        val started = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()

        override fun needsResolve(item: PlayableItem.Remote): Boolean =
            item.resolved?.audioUrl.isNullOrBlank()

        override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote {
            if (item.youtubeQueryOrId == delayedQuery) {
                started.complete(Unit)
                withContext(NonCancellable) { allowCompletion.await() }
            }
            return resolvedRemote(item, item.youtubeQueryOrId.orEmpty())
        }

        override suspend fun invalidate(item: PlayableItem.Remote) = Unit
    }

    private class SelectiveStreamAccess(
        private val successfulQueries: Set<String>
    ) : PlaybackRuntimeStreamAccess {
        val resolvedQueries = mutableListOf<String>()

        override fun needsResolve(item: PlayableItem.Remote): Boolean =
            item.resolved?.audioUrl.isNullOrBlank()

        override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote? {
            val query = item.youtubeQueryOrId.orEmpty()
            resolvedQueries += query
            return if (query in successfulQueries) resolvedRemote(item, query) else null
        }

        override suspend fun invalidate(item: PlayableItem.Remote) = Unit
    }

    private class FakePersistence(
        private val lastPlayedToLoad: LastPlayedSnapshot? = null,
        private val queueToLoad: QueueSnapshot? = null
    ) : PlaybackRuntimePersistence {
        val loadQueueCount = AtomicInteger(0)
        @Volatile
        var lastQueue: QueueSnapshot? = null
        override suspend fun loadLastPlayed(): LastPlayedSnapshot? = lastPlayedToLoad
        override suspend fun loadQueue(): QueueSnapshot? {
            loadQueueCount.incrementAndGet()
            return queueToLoad
        }

        override suspend fun saveSession(
            lastPlayed: LastPlayedSnapshot?,
            queue: QueueSnapshot?,
            clearQueue: Boolean
        ) {
            lastQueue = queue
        }
    }

    private class BlockingFirstSavePersistence : PlaybackRuntimePersistence {
        val firstSaveStarted = CompletableDeferred<Unit>()
        val allowFirstSave = CompletableDeferred<Unit>()
        val savedQueues = CopyOnWriteArrayList<QueueSnapshot>()
        private val callCount = AtomicInteger(0)

        override suspend fun saveSession(
            lastPlayed: LastPlayedSnapshot?,
            queue: QueueSnapshot?,
            clearQueue: Boolean
        ) {
            if (callCount.incrementAndGet() == 1) {
                firstSaveStarted.complete(Unit)
                allowFirstSave.await()
            }
            queue?.let(savedQueues::add)
        }
    }

    private class SequencedConnector(
        results: List<Result<PlaybackControllerFacade>>
    ) : PlaybackControllerConnector {
        private val remaining = ArrayDeque(results)
        val attemptCount = AtomicInteger(0)

        override fun connect(): PlaybackControllerConnection {
            attemptCount.incrementAndGet()
            return ImmediateConnection(
                checkNotNull(remaining.removeFirstOrNull()) {
                    "No fake controller result left"
                }
            )
        }
    }

    private class ImmediateConnection(
        private val result: Result<PlaybackControllerFacade>
    ) : PlaybackControllerConnection {
        override fun addListener(listener: () -> Unit) = listener()
        override fun get(): PlaybackControllerFacade = result.getOrThrow()
        override fun cancel() = Unit
    }

    private class FakeListenTracker : PlaybackRuntimeListenTracker {
        val tickCount = AtomicInteger(0)
        @Volatile
        var lastChangedSongId: Long? = null

        override fun onTrackChanged(song: Song?, hint: PlaybackChangeHint) {
            lastChangedSongId = song?.id
        }

        override fun onDurationKnown(songId: Long, durationMs: Long) = Unit

        override fun onPlaybackTick(isPlaying: Boolean, elapsedRealtimeMs: Long) {
            tickCount.incrementAndGet()
        }

        override fun onStopped() = Unit
    }

    private class FakeSaveDownloads(
        private val resultFor: (PlayableItem.Remote) -> SaveWhileListeningDownloadResult = { remote ->
            SaveWhileListeningDownloadResult.Saved(
                Song(
                    id = 99L,
                    uriString = "/saved/${remote.title}.m4a",
                    title = remote.title,
                    artist = remote.artist
                )
            )
        }
    ) : PlaybackRuntimeSaveDownloads {
        override val downloads = MutableStateFlow<List<ActiveDownload>>(emptyList())
        val saveCount = AtomicInteger(0)

        override suspend fun save(
            remote: PlayableItem.Remote
        ): SaveWhileListeningDownloadResult {
            saveCount.incrementAndGet()
            return resultFor(remote)
        }

        override fun dismiss(id: String) = Unit
    }

    private fun QueueSnapshot.titles(): List<String> = items.map { item ->
        when (item) {
            is PersistedQueueItem.Local -> item.title
            is PersistedQueueItem.Remote -> item.identity.title
        }
    }

    companion object {
        private fun persistedLocal(song: Song): PersistedQueueItem.Local =
            PersistedQueueItem.Local(
                songId = song.id,
                uriString = song.uriString,
                identity = TrackIdentity(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    artworkUri = song.artworkUri,
                    durationMs = song.durationMs,
                    trackNumber = song.trackNumber
                )
            )

        private fun resolvedRemote(
            item: PlayableItem.Remote,
            query: String
        ): PlayableItem.Remote = item.copy(
            resolved = ResolvedStream(
                audioUrl = "https://cdn.example/$query",
                userAgent = "fake-UA",
                videoId = "video-$query",
                resolvedAtEpochMs = 10_000L
            )
        )

        private fun remote(
            query: String,
            title: String,
            durationMs: Long = 180_000L
        ): PlayableItem.Remote = PlayableItem.remoteFrom(
            identity = TrackIdentity(
                title = title,
                artist = "Artist",
                durationMs = durationMs
            ),
            youtubeQueryOrId = query
        )

        private fun song(id: Long, title: String): Song = Song(
            id = id,
            uriString = "/music/$id.mp3",
            title = title,
            artist = "Artist",
            durationMs = 180_000L
        )
    }
}
