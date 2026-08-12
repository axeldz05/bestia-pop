package com.bestiapop.android.persistence

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSessionStore
import com.bestiapop.android.service.MusicService
import com.bestiapop.android.service.PlaybackRuntime
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.testutil.PcmWavFixture
import com.bestiapop.android.testutil.SideloadPlaybackAppOps
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-orchestrated task-removal fixture.
 *
 * Phase 1 intentionally leaves MainActivity and MusicService alive after instrumentation exits. The
 * host removes the Android task through ActivityManager, relaunches MainActivity without killing the
 * PID, and signals the process-resident probe. Never call these methods as a normal connected test.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@HostOrchestratedProcessDeathTest
class PlaybackTaskRemovalE2ETest {

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
    private val markerFile
        get() = File(fixtureDir, MARKER_FILE)

    @Test
    @HostOrchestratedProcessDeathTest
    fun phase1_startPlayingWavAndArmHostProbe() {
        runPhaseOne(paused = false)
    }

    @Test
    @HostOrchestratedProcessDeathTest
    fun phase1_startPausedWavForStopPolicy() {
        runPhaseOne(paused = true)
    }

    @Test
    @HostOrchestratedProcessDeathTest
    fun cleanupHostFixture() {
        val marker = markerFile.takeIf(File::isFile)?.let {
            runCatching { JSONObject(it.readText()) }.getOrNull()
        }
        val runtime = application.playbackRuntime
        val sessionStore = PlaybackSessionStore(context)
        val preferences = PlaybackPreferencesRepository(context)

        runCleanupSteps(
            {
                onMain {
                    while (runtime.queue.value.isNotEmpty()) {
                        runtime.removeFromQueue(runtime.queue.value.lastIndex)
                    }
                }
            },
            { runBlocking { sessionStore.clearQueue() } },
            {
                marker?.let {
                    runBlocking {
                        preferences.setAutoplayOnLaunch(it.getBoolean("previousAutoplay"))
                        preferences.setLastShuffleEnabled(it.getBoolean("previousShuffle"))
                        preferences.setLastRepeatMode(
                            RepeatMode.valueOf(it.getString("previousRepeat"))
                        )
                    }
                }
            },
            { cleanupFixtureRowsAndFiles() },
            { context.stopService(Intent(context, MusicService::class.java)) },
            {
                context.getSystemService(NotificationManager::class.java)
                    .cancel(MusicService.PLAYBACK_NOTIFICATION_ID)
            }
        )
    }

    private fun runPhaseOne(paused: Boolean) {
        val runtime = application.playbackRuntime
        val preferences = PlaybackPreferencesRepository(context)
        val sessionStore = PlaybackSessionStore(context)
        val previousSettings = runBlocking { preferences.settingsFlow.first() }
        var controller: MediaController? = null
        var sideloadPolicy: AutoCloseable? = null
        var phaseCompleted = false

        try {
            cleanupFixtureRowsAndFiles()
            check(fixtureDir.mkdirs()) { "Could not create $fixtureDir" }
            grantNotificationPermission()
            sideloadPolicy = SideloadPlaybackAppOps.acquire()
            runBlocking {
                sessionStore.clearQueue()
                preferences.setAutoplayOnLaunch(false)
                preferences.setLastShuffleEnabled(false)
                preferences.setLastRepeatMode(RepeatMode.OFF)
            }
            val songs = createAndPersistWavSongs()

            @Suppress("UNUSED_VARIABLE")
            val activityKeptForHost = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            instrumentation.waitForIdleSync()
            await("MainActivity attaches PlaybackRuntime") {
                runtime.controllerConnectedForTest
            }

            val connected = connectController()
            controller = connected
            onMain {
                connected.volume = 0f
                runtime.playPlayableCollection(
                    items = songs.map { PlayableItem.Local(it) },
                    startIndex = EXPECTED_CURRENT_INDEX,
                    rotate = false
                )
            }
            await("real WAV playback enters foreground service") {
                onMain {
                    connected.mediaItemCount == EXPECTED_TITLES.size &&
                        connected.currentMediaItemIndex == EXPECTED_CURRENT_INDEX &&
                        connected.playWhenReady &&
                        connected.isPlaying &&
                        connected.playbackState == Player.STATE_READY
                } &&
                    musicServiceInfo()?.foreground == true
            }
            val baselinePosition = onMain { connected.currentPosition }
            await("real WAV progress advances before host orchestration") {
                onMain { connected.currentPosition >= baselinePosition + MIN_POSITION_ADVANCE_MS }
            }

            if (paused) {
                onMain { runtime.togglePlayPause() }
                await("paused task-removal fixture demotes its service") {
                    onMain { !connected.playWhenReady && !connected.isPlaying } &&
                        musicServiceInfo()?.foreground == false
                }
            }

            val marker = JSONObject()
                .put("kind", if (paused) "paused" else "playing")
                .put("phase1Pid", Process.myPid())
                .put("previousAutoplay", previousSettings.autoplayOnLaunch)
                .put("previousShuffle", previousSettings.lastShuffleEnabled)
                .put("previousRepeat", previousSettings.lastRepeatMode.name)
                .put("baselinePositionMs", onMain { connected.currentPosition })
                .put("expectedCurrentIndex", EXPECTED_CURRENT_INDEX)
                .put("expectedTitles", JSONArray(EXPECTED_TITLES))
            markerFile.writeText(marker.toString())

            if (!paused) {
                TaskRemovalProcessProbe.arm(
                    application = application,
                    fixtureDir = fixtureDir,
                    expectedTitles = EXPECTED_TITLES,
                    expectedCurrentIndex = EXPECTED_CURRENT_INDEX,
                    phase1Pid = Process.myPid(),
                    sideloadPolicy = sideloadPolicy
                )
                sideloadPolicy = null
            } else {
                // Keep the sideload allowance active until the host snapshots the paused-stop result.
                TaskRemovalProcessProbe.keepLease(sideloadPolicy)
                sideloadPolicy = null
            }
            phaseCompleted = true
        } finally {
            controller?.let { connected ->
                runCatching { onMain { connected.release() } }
            }
            if (!phaseCompleted) {
                sideloadPolicy?.let { runCatching { it.close() } }
                runCatching {
                    cleanupFailedPhase(
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
    }

    private fun cleanupFailedPhase(
        runtime: PlaybackRuntime,
        sessionStore: PlaybackSessionStore,
        preferences: PlaybackPreferencesRepository,
        previousAutoplay: Boolean,
        previousShuffle: Boolean,
        previousRepeat: RepeatMode
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
            {
                runBlocking {
                    preferences.setAutoplayOnLaunch(previousAutoplay)
                    preferences.setLastShuffleEnabled(previousShuffle)
                    preferences.setLastRepeatMode(previousRepeat)
                }
            },
            { cleanupFixtureRowsAndFiles() },
            { context.stopService(Intent(context, MusicService::class.java)) }
        )
    }

    private fun createAndPersistWavSongs(): List<Song> =
        EXPECTED_TITLES.mapIndexed { index, title ->
            val file = File(fixtureDir, "task-$index.wav")
            PcmWavFixture.write(
                file = file,
                durationMs = WAV_DURATION_MS,
                toneHz = 220.0 + index * 90.0
            )
            val draft = Song(
                uriString = file.absolutePath,
                title = title,
                artist = FIXTURE_ARTIST,
                album = FIXTURE_ALBUM,
                durationMs = WAV_DURATION_MS.toLong(),
                folderPath = fixtureDir.absolutePath
            )
            val id = runBlocking { application.musicRepository.saveUploadedSong(draft) }
            check(id > 0L) { "Could not persist task-removal fixture $title" }
            draft.copy(id = id)
        }

    private fun cleanupFixtureRowsAndFiles() {
        runBlocking {
            val dao = AppDatabase.getDatabase(context).musicDao()
            val fixturePrefix = fixtureDir.absolutePath + File.separator
            val ids = dao.getAllSongs()
                .filter { it.uriString.startsWith(fixturePrefix) }
                .map(Song::id)
            if (ids.isNotEmpty()) {
                dao.deletePlaylistRefsForSongs(ids)
                dao.deleteSongsByIds(ids)
            }
        }
        fixtureDir.deleteRecursively()
        check(!fixtureDir.exists()) { "Could not delete task-removal fixtures" }
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_DENIED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    private fun connectController(): MediaController {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        return MediaController.Builder(context, token)
            .buildAsync()
            .get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @Suppress("DEPRECATION")
    private fun musicServiceInfo(): ActivityManager.RunningServiceInfo? {
        val component = ComponentName(context, MusicService::class.java)
        return context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service == component }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + ASYNC_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Timed out waiting for $description; pid=${Process.myPid()}")
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

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private companion object {
        const val FIXTURE_DIRECTORY = "playback-task-removal-e2e"
        const val MARKER_FILE = "phase-1.json"
        const val FIXTURE_ARTIST = "BestiaPop task-removal E2E"
        const val FIXTURE_ALBUM = "Host ActivityManager task removal"
        const val WAV_DURATION_MS = 120_000
        const val EXPECTED_CURRENT_INDEX = 1
        const val MIN_POSITION_ADVANCE_MS = 150L
        const val CONNECTION_TIMEOUT_SECONDS = 10L
        const val ASYNC_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 25L

        val EXPECTED_TITLES = listOf(
            "Task removal fixture A",
            "Task removal fixture B",
            "Task removal fixture C"
        )
    }
}

/**
 * Lives in the target process after the phase-1 instrumentation object is gone. The host relaunches
 * MainActivity in the same PID, touches the signal file, and reads the bounded probe result.
 */
private object TaskRemovalProcessProbe {
    private const val SIGNAL_FILE = "host-phase2.signal"
    private const val RESULT_FILE = "host-phase2-result.json"
    private const val HOST_SIGNAL_TIMEOUT_MS = 120_000L
    private const val PROGRESS_TIMEOUT_MS = 10_000L
    private const val POLL_INTERVAL_MS = 25L
    private const val MIN_PROGRESS_MS = 150L

    @Volatile
    private var active = false
    @Volatile
    private var retainedLease: AutoCloseable? = null

    fun keepLease(lease: AutoCloseable?) {
        retainedLease = lease
    }

    fun arm(
        application: BestiaPopApplication,
        fixtureDir: File,
        expectedTitles: List<String>,
        expectedCurrentIndex: Int,
        phase1Pid: Int,
        sideloadPolicy: AutoCloseable?
    ) {
        check(!active) { "Task-removal process probe is already armed" }
        active = true
        retainedLease = sideloadPolicy
        val signal = File(fixtureDir, SIGNAL_FILE).apply { delete() }
        val result = File(fixtureDir, RESULT_FILE).apply { delete() }

        Thread({
            val payload = runCatching {
                awaitFile(signal, HOST_SIGNAL_TIMEOUT_MS)
                val runtime = application.playbackRuntime
                check(Process.myPid() == phase1Pid) {
                    "PID changed inside resident probe: ${Process.myPid()} != $phase1Pid"
                }
                check(runtime.controllerConnectedForTest) {
                    "PlaybackRuntime lost its MediaController"
                }
                check(runtime.queue.value.map { it.title } == expectedTitles) {
                    "Queue changed after task removal: ${runtime.queue.value.map { it.title }}"
                }
                val currentIndex = runtime.queue.value.indexOfFirst {
                    it.queueEntryId == runtime.currentItem.value?.queueEntryId
                }
                check(currentIndex == expectedCurrentIndex) {
                    "Current index changed after task removal: $currentIndex"
                }
                check(runtime.isPlaying.value) { "Playback is no longer active after task removal" }
                val positionBefore = runtime.playbackPositionMs.value
                await(PROGRESS_TIMEOUT_MS) {
                    runtime.isPlaying.value &&
                        runtime.playbackPositionMs.value >= positionBefore + MIN_PROGRESS_MS
                }
                JSONObject()
                    .put("passed", true)
                    .put("pid", Process.myPid())
                    .put("queue", JSONArray(runtime.queue.value.map { it.title }))
                    .put("currentIndex", currentIndex)
                    .put("positionBeforeMs", positionBefore)
                    .put("positionAfterMs", runtime.playbackPositionMs.value)
            }.getOrElse { failure ->
                JSONObject()
                    .put("passed", false)
                    .put("pid", Process.myPid())
                    .put("error", "${failure.javaClass.simpleName}: ${failure.message}")
            }
            writeAtomically(result, payload.toString())
            active = false
        }, "BestiaPop-task-removal-host-probe").apply {
            isDaemon = true
            start()
        }
    }

    private fun awaitFile(file: File, timeoutMs: Long) {
        await(timeoutMs) { file.isFile }
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms")
    }

    private fun writeAtomically(destination: File, contents: String) {
        val staged = File(destination.parentFile, "${destination.name}.tmp")
        staged.writeText(contents)
        check(staged.renameTo(destination)) { "Could not publish ${destination.absolutePath}" }
    }
}
