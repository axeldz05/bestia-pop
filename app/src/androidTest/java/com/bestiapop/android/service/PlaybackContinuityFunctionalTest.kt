package com.bestiapop.android.service

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.playback.PlaybackChangeHint
import com.bestiapop.android.data.preferences.LastPlayedSnapshot
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.QueueSnapshot
import com.bestiapop.android.domain.radio.RadioSuggestResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented continuity flows for process-owned playback (not pure policy unit tests).
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class PlaybackContinuityFunctionalTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun liveSessionReconnect_keepsShuffleFromPrefs_andPersistsPlayOrder() = runBlocking {
        val settings = MutableStateFlow(PlaybackSettings(lastShuffleEnabled = false))
        val persistence = RecordingPersistence()
        val first = FakeController()
        val second = FakeController()
        val fixture = fixture(
            attachController = false,
            playbackSettings = settings,
            persistence = persistence,
            persistShuffle = { enabled ->
                settings.value = settings.value.copy(lastShuffleEnabled = enabled)
            }
        )
        val connector = SequencedConnector(
            listOf(Result.success(first), Result.success(second))
        )
        try {
            fixture.runtime.attachUi()
            fixture.runtime.connectForTest(connector)
            withTimeout(2_000L) {
                while (!first.listenerAttached) delay(10L)
            }

            fixture.runtime.playPlayableCollection(
                listOf(
                    PlayableItem.Local(song(1, "One")),
                    PlayableItem.Local(song(2, "Two")),
                    PlayableItem.Local(song(3, "Three"))
                ),
                rotate = false,
                startShuffled = true
            )
            assertTrue(fixture.runtime.isShuffle.value)
            assertTrue(settings.value.lastShuffleEnabled)

            val liveQueue = fixture.runtime.queue.value
            val liveIndex = first.currentMediaItemIndex
            val livePosition = first.currentPosition
            second.seedTimeline(
                items = liveQueue,
                currentIndex = liveIndex,
                positionMs = livePosition,
                playWhenReady = true,
                playbackState = Player.STATE_READY,
                isPlaying = true
            )
            second.shuffleModeEnabled = false
            assertFalse(second.shuffleModeEnabled)

            persistence.savedQueues.clear()
            first.disconnect()
            withTimeout(2_000L) {
                while (!second.listenerAttached || second.mediaItemCount == 0) delay(10L)
            }

            assertTrue(
                "reconnect must restore shuffle from prefs, not Media3 flag",
                fixture.runtime.isShuffle.value
            )
            assertTrue(second.shuffleModeEnabled)

            fixture.runtime.togglePlayPause()
            withTimeout(2_000L) {
                while (persistence.savedQueues.isEmpty()) delay(10L)
            }
            val persisted = persistence.savedQueues.last()
            assertNotNull(persisted.shufflePlayOrder)
            assertEquals(liveQueue.size, persisted.shufflePlayOrder!!.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun autosaveInFlight_allowsRetryWhenManualOwnerReleasesClaim() = runBlocking {
        val results = AtomicReference(
            ArrayDeque(
                listOf(
                    SaveWhileListeningDownloadResult.InFlight("manual-owner"),
                    SaveWhileListeningDownloadResult.Saved(
                        Song(
                            id = 99L,
                            uriString = "/saved/retry.m4a",
                            title = "Retry me",
                            artist = "Artist"
                        )
                    )
                )
            )
        )
        val saver = FakeSaveDownloads { results.get().removeFirst() }
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
                listOf(remote("retry-q", "Retry me", durationMs = 100_000L)),
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
    fun pauseDuringRemoteResolve_cancelsReplaceAndPrepare() = runBlocking {
        val delayed = DelayedStreamAccess("slow")
        val fixture = fixture(streamAccess = delayed)
        try {
            fixture.runtime.playPlayableCollection(
                listOf(remote("slow", "Slow remote")),
                rotate = false
            )
            delayed.started.await()

            fixture.runtime.togglePlayPause()
            assertFalse(fixture.controller.wantsPlay)

            val replaceAfterPause = fixture.controller.replaceCount
            val prepareAfterPause = fixture.controller.prepareCount
            delayed.allowResolution.complete(Unit)

            withTimeout(2_000L) {
                while (fixture.runtime.resolvingRemote.value) delay(10L)
            }
            assertEquals(replaceAfterPause, fixture.controller.replaceCount)
            assertEquals(prepareAfterPause, fixture.controller.prepareCount)
            assertFalse(fixture.controller.wantsPlay)
            assertFalse(fixture.controller.playing)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun remoteIdle_keepsNotificationAndForegroundPolicy() {
        assertTrue(
            PlaybackServiceLifetimePolicy.shouldShowPlaybackNotification(
                mediaItemCount = 1,
                playbackState = Player.STATE_IDLE
            )
        )
        assertFalse(
            PlaybackServiceLifetimePolicy.shouldShowPlaybackNotification(
                mediaItemCount = 1,
                playbackState = Player.STATE_ENDED
            )
        )
        assertFalse(
            PlaybackServiceLifetimePolicy.shouldShowPlaybackNotification(
                mediaItemCount = 0,
                playbackState = Player.STATE_IDLE
            )
        )
        assertTrue(
            playbackForegroundRequired(
                startInForegroundRequired = false,
                playWhenReady = true,
                mediaItemCount = 1,
                playbackState = Player.STATE_IDLE
            )
        )
        assertFalse(
            playbackForegroundRequired(
                startInForegroundRequired = false,
                playWhenReady = false,
                mediaItemCount = 1,
                playbackState = Player.STATE_IDLE
            )
        )
    }

    private fun fixture(
        streamAccess: PlaybackRuntimeStreamAccess = InstantStreamAccess(),
        persistence: PlaybackRuntimePersistence = RecordingPersistence(),
        libraryUpdates: MutableStateFlow<List<Song>> = MutableStateFlow(emptyList()),
        playbackSettings: MutableStateFlow<PlaybackSettings> = MutableStateFlow(PlaybackSettings()),
        playbackSettingsReady: MutableStateFlow<Boolean> = MutableStateFlow(true),
        listenSettings: MutableStateFlow<ListenBrainzSettings> =
            MutableStateFlow(ListenBrainzSettings()),
        listenSettingsReady: MutableStateFlow<Boolean> = MutableStateFlow(true),
        saveDownloads: PlaybackRuntimeSaveDownloads = FakeSaveDownloads(),
        persistShuffle: suspend (Boolean) -> Unit = {},
        controller: FakeController = FakeController(),
        attachController: Boolean = true
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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
                listenTracker = NoopListenTracker,
                streamAccess = streamAccess,
                saveDownloads = saveDownloads,
                radioSuggester = PlaybackRuntimeRadioSuggester {
                    RadioSuggestResult(emptyList(), false, false)
                },
                isOnline = { true },
                persistShuffle = persistShuffle,
                clockMs = clock::get,
                elapsedRealtimeMs = clock::get,
                controllerReconnectBackoffMs = { 0L },
                startTicker = false
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

    private class FakeController : PlaybackControllerFacade {
        private var listener: PlaybackControllerFacade.Listener? = null
        private val timeline = mutableListOf<PlayableItem>()
        val releaseCount = AtomicInteger(0)
        var prepareCount = 0
            private set
        var replaceCount = 0
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
                repeatModeValue = value
            }
        override var shuffleModeEnabled: Boolean
            get() = checked { shuffle }
            set(value) {
                checkValid()
                shuffle = value
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
            timeline.clear()
            timeline.addAll(items)
            index = startIndex
            positionMs = startPositionMs
            state = Player.STATE_IDLE
            updatePlaying(false)
            listener?.onTimelineChanged()
        }

        override fun replaceMediaItem(index: Int, item: PlayableItem) {
            checkValid()
            replaceCount++
            timeline[index] = item
            listener?.onTimelineChanged()
        }

        override fun addMediaItems(items: List<PlayableItem>) {
            checkValid()
            timeline.addAll(items)
            listener?.onTimelineChanged()
        }

        override fun addMediaItems(index: Int, items: List<PlayableItem>) {
            checkValid()
            timeline.addAll(index, items)
            listener?.onTimelineChanged()
        }

        override fun removeMediaItem(index: Int) {
            checkValid()
            timeline.removeAt(index)
            this.index = this.index.coerceAtMost(timeline.lastIndex.coerceAtLeast(0))
            listener?.onTimelineChanged()
        }

        override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
            checkValid()
            repeat(toIndex - fromIndex) { timeline.removeAt(fromIndex) }
            index = index.coerceAtMost(timeline.lastIndex.coerceAtLeast(0))
            listener?.onTimelineChanged()
        }

        override fun moveMediaItem(fromIndex: Int, toIndex: Int) {
            checkValid()
            timeline.add(toIndex, timeline.removeAt(fromIndex))
            listener?.onTimelineChanged()
        }

        override fun prepare() {
            checkValid()
            prepareCount++
            state = Player.STATE_READY
            updatePlaying(wantsPlay)
        }

        override fun play() {
            checkValid()
            updatePlayWhenReady(true)
            updatePlaying(state == Player.STATE_READY)
        }

        override fun pause() {
            checkValid()
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
            this.index = index
            this.positionMs = positionMs
            listener?.onPositionDiscontinuity(positionMs)
            listener?.onMediaItemTransition(
                timeline.getOrNull(index),
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            )
        }

        override fun seekToNextMediaItem() = Unit
        override fun seekToPreviousMediaItem() = Unit
        override fun hasNextMediaItem(): Boolean = checked { index < timeline.lastIndex }
        override fun hasPreviousMediaItem(): Boolean = checked { index > 0 }
        override fun release() {
            releaseCount.incrementAndGet()
            listener = null
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

    private class InstantStreamAccess : PlaybackRuntimeStreamAccess {
        override fun needsResolve(item: PlayableItem.Remote): Boolean =
            item.resolved?.audioUrl.isNullOrBlank()

        override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote =
            resolvedRemote(item, item.youtubeQueryOrId.orEmpty())

        override suspend fun invalidate(item: PlayableItem.Remote) = Unit
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

    private class RecordingPersistence : PlaybackRuntimePersistence {
        val savedQueues = CopyOnWriteArrayList<QueueSnapshot>()

        override suspend fun loadLastPlayed(): LastPlayedSnapshot? = null
        override suspend fun loadQueue(): QueueSnapshot? = null

        override suspend fun saveSession(
            lastPlayed: LastPlayedSnapshot?,
            queue: QueueSnapshot?,
            clearQueue: Boolean
        ) {
            if (queue != null) savedQueues += queue
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

    private object NoopListenTracker : PlaybackRuntimeListenTracker {
        override fun onTrackChanged(song: Song?, hint: PlaybackChangeHint) = Unit
        override fun onDurationKnown(songId: Long, durationMs: Long) = Unit
        override fun onPlaybackTick(isPlaying: Boolean, elapsedRealtimeMs: Long) = Unit
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

    companion object {
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
