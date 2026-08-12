package com.bestiapop.android.service.concurrent

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.service.MusicService
import com.bestiapop.android.testutil.PcmWavFixture
import com.bestiapop.android.testutil.SideloadPlaybackAppOps
import com.bestiapop.android.ui.MusicPlayerViewModel
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Real Media3 playback owner shared by the concurrent service scenarios.
 *
 * Network/service fixtures retain ownership of their own rows and files; this fixture owns only its
 * generated local WAV, controller, optional foreground Activity, playback notification and service.
 */
internal class ConcurrentPlaybackTestFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as BestiaPopApplication
    private val fixtureDir = File(
        context.cacheDir,
        "concurrent-playback-${System.nanoTime()}"
    )
    private val fixtureFile = File(fixtureDir, "continuous.wav")

    private var scenario: ActivityScenario<MainActivity>? = null
    private var controller: MediaController? = null
    private var sideloadPolicy: AutoCloseable? = null

    fun start(launchForegroundHost: Boolean) {
        check(sideloadPolicy == null) { "Concurrent playback fixture already started" }
        sideloadPolicy = SideloadPlaybackAppOps.acquire()
        check(fixtureDir.mkdirs()) {
            "Could not create concurrent playback fixture directory ${fixtureDir.absolutePath}"
        }
        PcmWavFixture.write(
            fixtureFile,
            durationMs = PLAYBACK_DURATION_MS,
            toneHz = 220.0
        )

        if (launchForegroundHost) launchForegroundHost()

        val connected = connectController()
        controller = connected
        onMain {
            connected.volume = 0f
            application.playbackRuntime.playPlayableCollection(
                items = listOf(
                    PlayableItem.Local(
                        Song(
                            id = FIXTURE_SONG_ID,
                            uriString = fixtureFile.absolutePath,
                            title = PLAYBACK_TITLE,
                            artist = "BestiaPop concurrent instrumentation",
                            durationMs = PLAYBACK_DURATION_MS.toLong()
                        )
                    )
                ),
                rotate = false
            )
        }

        awaitConcurrent("concurrent local WAV READY", diagnostics = ::diagnostics) {
            onMain {
                connected.playbackState == Player.STATE_READY &&
                    connected.playWhenReady &&
                    connected.isPlaying
            }
        }
        val readyPosition = position()
        assertPositionAdvancesFrom(readyPosition, "initial playback warm-up")
        assertPlaybackForeground()
    }

    fun viewModel(): MusicPlayerViewModel {
        val activeScenario = checkNotNull(scenario) {
            "A foreground Activity host is required to obtain MusicPlayerViewModel"
        }
        var result: MusicPlayerViewModel? = null
        activeScenario.onActivity { activity ->
            result = ViewModelProvider(activity)[MusicPlayerViewModel::class.java]
        }
        return checkNotNull(result)
    }

    fun position(): Long = onMain {
        checkNotNull(controller) { "Concurrent playback controller is not connected" }
            .currentPosition
    }

    fun assertPositionAdvancesFrom(startPositionMs: Long, operation: String) {
        awaitConcurrent(
            "playback advances during $operation",
            diagnostics = ::diagnostics
        ) {
            onMain {
                val connected = controller ?: return@onMain false
                connected.playWhenReady &&
                    connected.isPlaying &&
                    connected.currentPosition >= startPositionMs + MIN_POSITION_ADVANCE_MS
            }
        }
    }

    fun assertPlaybackForeground(): Int {
        val service = awaitConcurrentValue(
            "MusicService foreground",
            diagnostics = ::diagnostics
        ) {
            musicServiceInfo()?.takeIf(ActivityManager.RunningServiceInfo::foreground)
        }
        val notification = awaitConcurrentValue(
            "ongoing playback notification",
            diagnostics = ::diagnostics,
            value = ::playbackNotification
        )
        check(notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            "Playback notification is not ongoing: flags=${notification.flags}"
        }
        check(NotificationCompat.getActionCount(notification) == 3) {
            "Playback notification action count changed"
        }
        check(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE) == PLAYBACK_TITLE
        ) {
            "Unexpected playback notification title: " +
                notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        }
        return service.pid
    }

    fun diagnostics(): String {
        val connected = controller
        val controllerState = runCatching {
            onMain {
                "state=${connected?.playbackState}, playWhenReady=${connected?.playWhenReady}, " +
                    "isPlaying=${connected?.isPlaying}, position=${connected?.currentPosition}"
            }
        }.getOrElse { "controllerError=${it.javaClass.simpleName}:${it.message}" }
        val service = runCatching { musicServiceInfo() }.getOrNull()
        val notification = runCatching { playbackNotification() }.getOrNull()
        return "$controllerState, serviceRunning=${service != null}, " +
            "serviceForeground=${service?.foreground}, " +
            "notificationFlags=${notification?.flags}, runtimeQueue=" +
            application.playbackRuntime.queue.value.size
    }

    override fun close() {
        var firstFailure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            runCatching(block).exceptionOrNull()?.let { failure ->
                if (firstFailure == null) firstFailure = failure
                else firstFailure?.addSuppressed(failure)
            }
        }

        controller?.let { connected ->
            cleanup {
                onMain {
                    connected.stop()
                    connected.clearMediaItems()
                }
            }
            cleanup {
                awaitConcurrent(
                    "runtime queue cleared",
                    diagnostics = ::diagnostics
                ) {
                    application.playbackRuntime.queue.value.isEmpty()
                }
            }
            cleanup { onMain { connected.release() } }
        }
        controller = null

        cleanup { context.stopService(Intent(context, MusicService::class.java)) }
        cleanup {
            context.getSystemService(NotificationManager::class.java)
                .cancel(MusicService.PLAYBACK_NOTIFICATION_ID)
        }
        cleanup { scenario?.close() }
        scenario = null
        cleanup { sideloadPolicy?.close() }
        sideloadPolicy = null
        cleanup {
            check(!fixtureDir.exists() || fixtureDir.deleteRecursively()) {
                "Could not delete concurrent playback fixture ${fixtureDir.absolutePath}"
            }
        }
        firstFailure?.let { throw it }
    }

    private fun launchForegroundHost() {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.moveToState(Lifecycle.State.RESUMED)
        awaitConcurrent(
            "MainActivity foreground host",
            diagnostics = ::diagnostics
        ) {
            var foreground = false
            launched.onActivity { activity ->
                foreground =
                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        activity.hasWindowFocus() &&
                        !activity.isFinishing &&
                        !activity.isDestroyed
            }
            foreground
        }
    }

    private fun connectController(): MediaController {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        return MediaController.Builder(context, token)
            .buildAsync()
            .get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun playbackNotification(): Notification? =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.id == MusicService.PLAYBACK_NOTIFICATION_ID }
            ?.notification

    @Suppress("DEPRECATION")
    private fun musicServiceInfo(): ActivityManager.RunningServiceInfo? {
        val component = ComponentName(context, MusicService::class.java)
        return context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service == component }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private companion object {
        const val FIXTURE_SONG_ID = -7_001L
        const val PLAYBACK_TITLE = "Concurrent operations playback"
        const val PLAYBACK_DURATION_MS = 40_000
        const val CONNECTION_TIMEOUT_SECONDS = 10L
        const val MIN_POSITION_ADVANCE_MS = 150L
    }
}

internal fun awaitConcurrent(
    description: String,
    timeoutMs: Long = ASYNC_TIMEOUT_MS,
    diagnostics: () -> String = { "" },
    condition: () -> Boolean
) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        if (condition()) return
        SystemClock.sleep(POLL_INTERVAL_MS)
    }
    throw AssertionError("Timed out waiting for $description; ${diagnostics()}")
}

internal fun <T : Any> awaitConcurrentValue(
    description: String,
    timeoutMs: Long = ASYNC_TIMEOUT_MS,
    diagnostics: () -> String = { "" },
    value: () -> T?
): T {
    var result: T? = null
    awaitConcurrent(description, timeoutMs, diagnostics) {
        value()?.also { result = it } != null
    }
    return requireNotNull(result)
}

private const val ASYNC_TIMEOUT_MS = 15_000L
private const val POLL_INTERVAL_MS = 25L
