package com.bestiapop.android.persistence

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.PersistedQueueItem
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSessionStore
import com.bestiapop.android.data.preferences.QueueSnapshot
import com.bestiapop.android.service.MusicService
import com.bestiapop.android.service.PlaybackRuntime
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.testutil.PcmWavFixture
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real process-death E2E. Never run these methods in one instrumentation invocation: the host
 * script runs phase 1, kills the target package without clearing data, then runs phase 2.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@HostOrchestratedProcessDeathTest
class PlaybackProcessDeathE2ETest {

    @get:Rule
    val deviceAwakeRule = DeviceAwakeRule()

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext
    private val application
        get() = context.applicationContext as BestiaPopApplication
    private val fixtureDir
        get() = File(context.filesDir, FIXTURE_DIRECTORY)
    private val phaseMarker
        get() = File(fixtureDir, PHASE_MARKER_FILE)

    @Test
    @HostOrchestratedProcessDeathTest
    fun phase1_persistPausedQueueForHostKill() {
        val app = application
        val runtime = app.playbackRuntime
        val preferences = PlaybackPreferencesRepository(context)
        val sessionStore = PlaybackSessionStore(context)
        val previousSettings = runBlocking { preferences.settingsFlow.first() }
        var runtimeAttached = false
        var controller: MediaController? = null
        var phaseCompleted = false

        try {
            cleanupFixtureRowsAndFiles()
            check(fixtureDir.mkdirs()) { "Could not create $fixtureDir" }
            runBlocking {
                sessionStore.clearQueue()
                preferences.setAutoplayOnLaunch(false)
                preferences.setLastShuffleEnabled(false)
                preferences.setLastRepeatMode(RepeatMode.OFF)
            }
            await("deterministic launch settings reach the real runtime") {
                runBlocking { preferences.settingsFlow.first() }.let {
                    !it.autoplayOnLaunch &&
                        !it.lastShuffleEnabled &&
                        it.lastRepeatMode == RepeatMode.OFF
                } &&
                    runtime.playbackSettings.value.let {
                        !it.autoplayOnLaunch &&
                            !it.lastShuffleEnabled &&
                            it.lastRepeatMode == RepeatMode.OFF
                    }
            }

            val songs = createAndPersistWavSongs(app)
            onMain { runtime.attachUi() }
            runtimeAttached = true
            await("PlaybackRuntime connects its real MediaController") {
                runtime.controllerConnectedForTest
            }

            val connectedController = connectController()
            controller = connectedController
            onMain {
                connectedController.volume = 0f
                runtime.playPlayableCollection(
                    items = songs.map { PlayableItem.Local(it) },
                    startIndex = EXPECTED_CURRENT_INDEX,
                    rotate = false
                )
            }
            await("the synthetic WAV queue reaches real ExoPlayer") {
                onMain {
                    connectedController.mediaItemCount == EXPECTED_TITLES.size &&
                        connectedController.currentMediaItemIndex == EXPECTED_CURRENT_INDEX &&
                        connectedController.playWhenReady &&
                        connectedController.playbackState == Player.STATE_READY
                }
            }
            await("the selected WAV starts") {
                runtime.isPlaying.value && runtime.playbackPositionMs.value > 0L
            }

            onMain { runtime.togglePlayPause() }
            await("the real player pauses before persistence") {
                onMain { !connectedController.playWhenReady && !connectedController.isPlaying } &&
                    !runtime.isPlaying.value
            }
            onMain { runtime.seekTo(EXPECTED_POSITION_MS) }
            await("the paused seek reaches Media3") {
                onMain {
                    kotlin.math.abs(
                        connectedController.currentPosition - EXPECTED_POSITION_MS
                    ) <=
                        POSITION_TOLERANCE_MS
                }
            }

            val persisted = awaitValue("queue, index, and paused position persisted") {
                runBlocking { sessionStore.loadQueue() }?.takeIf(::isExpectedSnapshot)
            }
            assertExpectedSnapshot(persisted)
            phaseMarker.writeText(
                JSONObject()
                    .put("phase1Pid", Process.myPid())
                    .put("previousAutoplay", previousSettings.autoplayOnLaunch)
                    .put("previousShuffle", previousSettings.lastShuffleEnabled)
                    .put("previousRepeat", previousSettings.lastRepeatMode.name)
                    .toString()
            )
            phaseCompleted = true
        } finally {
            controller?.let { connected ->
                runCatching { onMain { connected.release() } }
            }
            if (runtimeAttached) {
                runCatching { onMain { runtime.detachUi() } }
            }
            if (!phaseCompleted) {
                cleanupAfterFailedPhaseOne(
                    runtime = runtime,
                    sessionStore = sessionStore,
                    preferences = preferences,
                    previousAutoplay = previousSettings.autoplayOnLaunch,
                    previousShuffle = previousSettings.lastShuffleEnabled,
                    previousRepeat = previousSettings.lastRepeatMode
                )
            }
        }
    }

    @Test
    @HostOrchestratedProcessDeathTest
    fun phase2_restoreAfterHostKillWithoutAutoplayAndCleanUp() {
        val marker = checkNotNull(
            phaseMarker.takeIf(File::isFile)?.readText()?.let(::JSONObject)
        ) {
            "Missing phase-1 marker. Run the host script instead of this phase directly."
        }
        val phase1Pid = marker.getInt("phase1Pid")
        val previousAutoplay = marker.getBoolean("previousAutoplay")
        val previousShuffle = marker.getBoolean("previousShuffle")
        val previousRepeat = RepeatMode.valueOf(marker.getString("previousRepeat"))
        val app = application
        val runtime = app.playbackRuntime
        val preferences = PlaybackPreferencesRepository(context)
        val sessionStore = PlaybackSessionStore(context)
        var runtimeAttached = false
        var controller: MediaController? = null

        try {
            assertTrue(
                "Phase 2 must execute in a new target process",
                Process.myPid() != phase1Pid
            )
            assertFalse(
                "Autoplay must be off before cold hydration",
                runBlocking { preferences.settingsFlow.first().autoplayOnLaunch }
            )
            assertExpectedSnapshot(
                checkNotNull(runBlocking { sessionStore.loadQueue() }) {
                    "The persisted phase-1 queue disappeared across process death"
                }
            )

            onMain { runtime.attachUi() }
            runtimeAttached = true
            await("cold PlaybackRuntime hydrates the persisted display queue") {
                isExpectedHydratedRuntime(runtime)
            }

            val connectedController = connectController()
            controller = connectedController
            await("autoplay-off keeps the real Media3 timeline empty") {
                onMain {
                    connectedController.mediaItemCount == 0 &&
                        !connectedController.playWhenReady &&
                        connectedController.playbackState == Player.STATE_IDLE
                }
            }

            assertFalse("Hydration must remain paused", runtime.isPlaying.value)
            assertEquals(EXPECTED_TITLES, runtime.queue.value.map { it.title })
            assertEquals(
                EXPECTED_CURRENT_INDEX,
                runtime.queue.value.indexOfFirst {
                    it.queueEntryId == runtime.currentItem.value?.queueEntryId
                }
            )
            assertEquals(EXPECTED_POSITION_MS, runtime.playbackPositionMs.value)
            assertTrue(runtime.queue.value.all { it is PlayableItem.Local })
            assertTrue(
                "The restored queue must contain only local fixture URIs, never CDN URLs",
                runtime.queue.value.all {
                    val local = it as PlayableItem.Local
                    local.song.uriString.startsWith(fixtureDir.absolutePath) &&
                        !local.song.uriString.startsWith("http", ignoreCase = true)
                }
            )
        } finally {
            runCleanupSteps(
                {
                    controller?.let { connected -> onMain { connected.release() } }
                },
                { cleanupRuntimeAndFixtures(runtime, sessionStore) },
                {
                    restorePlaybackPreferences(
                        preferences = preferences,
                        autoplay = previousAutoplay,
                        shuffle = previousShuffle,
                        repeat = previousRepeat
                    )
                },
                {
                    if (runtimeAttached) onMain { runtime.detachUi() }
                }
            )
        }
    }

    private fun createAndPersistWavSongs(app: BestiaPopApplication): List<Song> =
        EXPECTED_TITLES.mapIndexed { index, title ->
            val file = File(fixtureDir, "phase-$index.wav")
            PcmWavFixture.write(
                file = file,
                durationMs = WAV_DURATION_MS,
                toneHz = 220.0 + index * 110.0
            )
            val draft = Song(
                uriString = file.absolutePath,
                title = title,
                artist = FIXTURE_ARTIST,
                album = FIXTURE_ALBUM,
                durationMs = WAV_DURATION_MS.toLong(),
                artworkUri = file.toURI().toString(),
                lyrics = FIXTURE_LYRICS,
                folderPath = fixtureDir.absolutePath
            )
            val id = runBlocking { app.musicRepository.saveUploadedSong(draft) }
            check(id > 0L) { "Could not persist fixture song $title (id=$id)" }
            draft.copy(id = id)
        }

    private fun isExpectedSnapshot(snapshot: QueueSnapshot): Boolean =
        snapshot.currentIndex == EXPECTED_CURRENT_INDEX &&
            snapshot.positionMs == EXPECTED_POSITION_MS &&
            snapshot.items.mapNotNull { (it as? PersistedQueueItem.Local)?.title } ==
            EXPECTED_TITLES &&
            snapshot.items.all(::isFixturePersistedItem)

    private fun assertExpectedSnapshot(snapshot: QueueSnapshot) {
        assertEquals(EXPECTED_CURRENT_INDEX, snapshot.currentIndex)
        assertEquals(EXPECTED_POSITION_MS, snapshot.positionMs)
        assertEquals(EXPECTED_TITLES.size, snapshot.items.size)
        assertTrue(snapshot.items.all(::isFixturePersistedItem))
        assertEquals(
            EXPECTED_TITLES,
            snapshot.items.map { (it as PersistedQueueItem.Local).title }
        )
    }

    private fun isFixturePersistedItem(item: PersistedQueueItem): Boolean =
        (item as? PersistedQueueItem.Local)
            ?.uriString
            ?.startsWith(fixtureDir.absolutePath + File.separator) == true

    private fun isExpectedHydratedRuntime(runtime: PlaybackRuntime): Boolean {
        val items = runtime.queue.value
        val current = runtime.currentItem.value
        return items.map { it.title } == EXPECTED_TITLES &&
            items.indexOfFirst { it.queueEntryId == current?.queueEntryId } ==
            EXPECTED_CURRENT_INDEX &&
            runtime.playbackPositionMs.value == EXPECTED_POSITION_MS &&
            !runtime.isPlaying.value
    }

    private fun cleanupAfterFailedPhaseOne(
        runtime: PlaybackRuntime,
        sessionStore: PlaybackSessionStore,
        preferences: PlaybackPreferencesRepository,
        previousAutoplay: Boolean,
        previousShuffle: Boolean,
        previousRepeat: RepeatMode
    ) {
        runCleanupSteps(
            { cleanupRuntimeAndFixtures(runtime, sessionStore) },
            {
                restorePlaybackPreferences(
                    preferences = preferences,
                    autoplay = previousAutoplay,
                    shuffle = previousShuffle,
                    repeat = previousRepeat
                )
            }
        )
    }

    private fun restorePlaybackPreferences(
        preferences: PlaybackPreferencesRepository,
        autoplay: Boolean,
        shuffle: Boolean,
        repeat: RepeatMode
    ) {
        runCleanupSteps(
            { runBlocking { preferences.setAutoplayOnLaunch(autoplay) } },
            { runBlocking { preferences.setLastShuffleEnabled(shuffle) } },
            { runBlocking { preferences.setLastRepeatMode(repeat) } }
        )
    }

    private fun cleanupRuntimeAndFixtures(
        runtime: PlaybackRuntime,
        sessionStore: PlaybackSessionStore
    ) {
        runCleanupSteps(
            {
                onMain {
                    while (runtime.queue.value.isNotEmpty()) {
                        runtime.removeFromQueue(runtime.queue.value.lastIndex)
                    }
                }
            },
            { runBlocking { sessionStore.clearQueue() } },
            { cleanupFixtureRowsAndFiles() },
            { context.stopService(Intent(context, MusicService::class.java)) },
            {
                context.getSystemService(NotificationManager::class.java)
                    .cancel(MusicService.PLAYBACK_NOTIFICATION_ID)
            }
        )
    }

    private fun cleanupFixtureRowsAndFiles() {
        runBlocking {
            val dao = AppDatabase.getDatabase(context).musicDao()
            val fixturePrefix = fixtureDir.absolutePath + File.separator
            val fixtureSongs = dao.getAllSongs().filter {
                it.uriString.startsWith(fixturePrefix)
            }
            val ids = fixtureSongs.map(Song::id)
            if (ids.isNotEmpty()) {
                dao.deletePlaylistRefsForSongs(ids)
                dao.deleteSongsByIds(ids)
            }
        }
        fixtureDir.deleteRecursively()
        check(!fixtureDir.exists()) { "Could not delete process-death fixtures" }
    }

    private fun connectController(): MediaController {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        return MediaController.Builder(context, token)
            .buildAsync()
            .get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + ASYNC_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError(
            "Timed out waiting for $description; " +
                "pid=${Process.myPid()}, queueSize=${application.playbackRuntime.queue.value.size}, " +
                "currentIndex=${application.playbackRuntime.queue.value.indexOfFirst { item ->
                    item.queueEntryId == application.playbackRuntime.currentItem.value?.queueEntryId
                }}, " +
                "position=${application.playbackRuntime.playbackPositionMs.value}, " +
                "playing=${application.playbackRuntime.isPlaying.value}"
        )
    }

    private fun runCleanupSteps(vararg steps: () -> Unit) {
        var firstFailure: Throwable? = null
        steps.forEach { step ->
            runCatching(step).exceptionOrNull()?.let { failure ->
                if (firstFailure == null) firstFailure = failure
                else firstFailure?.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }

    private fun <T : Any> awaitValue(description: String, value: () -> T?): T {
        var result: T? = null
        await(description) {
            value()?.also { result = it } != null
        }
        return requireNotNull(result)
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private companion object {
        const val FIXTURE_DIRECTORY = "playback-process-death-e2e"
        const val PHASE_MARKER_FILE = "phase-1.json"
        const val FIXTURE_ARTIST = "BestiaPop process-death E2E"
        const val FIXTURE_ALBUM = "Host-orchestrated persistence"
        const val FIXTURE_LYRICS = "Synthetic fixture; no network metadata lookup"
        const val WAV_DURATION_MS = 30_000
        const val EXPECTED_CURRENT_INDEX = 1
        const val EXPECTED_POSITION_MS = 5_432L
        const val POSITION_TOLERANCE_MS = 100L
        const val CONNECTION_TIMEOUT_SECONDS = 10L
        const val ASYNC_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 25L

        val EXPECTED_TITLES = listOf(
            "Process death fixture A",
            "Process death fixture B",
            "Process death fixture C"
        )
    }
}
