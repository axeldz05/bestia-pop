package com.bestiapop.android.service

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.MainActivity
import com.bestiapop.android.testutil.DeviceAwakeRule
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level smoke for the real MediaLibraryService and ExoPlayer pipeline.
 *
 * The fixture writes its own PCM WAV files, so this test is deterministic and never uses network
 * or commercial audio. Polling is bounded because Media3 state changes are asynchronous.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LocalPlaybackServiceFunctionalTest {

    @get:Rule
    val deviceAwakeRule = DeviceAwakeRule()

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext
    private var activityScenario: ActivityScenario<MainActivity>? = null

    @Before
    fun grantStartupPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requiredPermissions.forEach { permission ->
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    permission
                )
            }
        }
        await("startup permissions and notifications enabled") {
            requiredPermissions.all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            } &&
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }
    }

    @Test
    fun localWav_mediaControllerPlaysMediaKeysAndReconnects() {
        val fixtureDir = File(context.cacheDir, "media3-service-test-${System.nanoTime()}")
        val firstFile = File(fixtureDir, "first.wav")
        val secondFile = File(fixtureDir, "second.wav")
        var controller: MediaController? = null

        try {
            assertTrue(fixtureDir.mkdirs())
            writePcmWav(firstFile)
            writePcmWav(secondFile)

            val launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            activityScenario = launchedActivity
            launchedActivity.moveToState(Lifecycle.State.RESUMED)
            await("MainActivity RESUMED and focused before playback") {
                mainActivityIsForeground(launchedActivity)
            }

            val firstController = connectController()
            controller = firstController
            val items = listOf(
                mediaItem("local-first", "Instrumented first", firstFile),
                mediaItem("local-second", "Instrumented second", secondFile)
            )
            onMain {
                firstController.volume = 0f
                firstController.setMediaItems(items)
                firstController.prepare()
                firstController.play()
            }

            await("local WAV reaches READY") {
                onMain {
                    firstController.playbackState == Player.STATE_READY &&
                        firstController.playWhenReady
                }
            }
            val readyPosition = onMain { firstController.currentPosition }
            await("playback position advances") {
                onMain { firstController.currentPosition >= readyPosition + MIN_POSITION_ADVANCE_MS }
            }

            val playingNotification = awaitValue("visible playback notification") {
                playbackNotification()
            }
            assertTrue(playingNotification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(3, NotificationCompat.getActionCount(playingNotification))
            assertEquals(
                "Instrumented first",
                playingNotification.extras.getCharSequence(Notification.EXTRA_TITLE)
            )
            await("MusicService foreground while playing") {
                musicServiceInfo()?.foreground == true
            }

            sendNotificationAction(playingNotification, PLAY_PAUSE_ACTION_INDEX)
            await("notification pause reaches ExoPlayer") {
                onMain { !firstController.playWhenReady && !firstController.isPlaying }
            }
            await("MusicService leaves foreground while paused") {
                musicServiceInfo()?.foreground == false
            }

            val pausedNotification = awaitValue("paused notification exposes Play") {
                playbackNotification()?.takeIf {
                    it.flags and Notification.FLAG_ONGOING_EVENT == 0
                }
            }
            sendNotificationAction(pausedNotification, PLAY_PAUSE_ACTION_INDEX)
            await("notification play reaches ExoPlayer") {
                onMain { firstController.playWhenReady }
            }
            await("notification play re-enters foreground") {
                musicServiceInfo()?.foreground == true
            }

            sendNotificationAction(requireNotNull(playbackNotification()), NEXT_ACTION_INDEX)
            await("notification Next advances the queue") {
                onMain { firstController.currentMediaItemIndex == 1 }
            }
            sendNotificationAction(requireNotNull(playbackNotification()), PREVIOUS_ACTION_INDEX)
            await("notification Previous returns to first item") {
                onMain { firstController.currentMediaItemIndex == 0 }
            }

            val positionBeforeReconnect = onMain { firstController.currentPosition }
            onMain { firstController.release() }
            controller = null
            await("service remains foreground across controller release") {
                musicServiceInfo()?.foreground == true
            }

            val reconnectedController = connectController()
            controller = reconnectedController
            await("new controller receives the live session") {
                onMain {
                    reconnectedController.mediaItemCount == 2 &&
                        reconnectedController.currentMediaItemIndex == 0 &&
                        reconnectedController.playWhenReady &&
                        reconnectedController.currentPosition >= positionBeforeReconnect
                }
            }

            onMain { reconnectedController.pause() }
            await("reconnected controller pauses playback") {
                onMain { !reconnectedController.playWhenReady }
            }
            await("pause demotes MusicService foreground") {
                musicServiceInfo()?.foreground == false
            }
            awaitValue("pause keeps playback notification") {
                playbackNotification()
            }
        } finally {
            controller?.let { connected ->
                runCatching {
                    onMain {
                        connected.stop()
                        connected.clearMediaItems()
                        connected.release()
                    }
                }
            }
            runCatching { activityScenario?.close() }
            activityScenario = null
            context.stopService(Intent(context, MusicService::class.java))
            context.getSystemService(NotificationManager::class.java)
                .cancel(MusicService.PLAYBACK_NOTIFICATION_ID)
            fixtureDir.deleteRecursively()
        }
    }

    private fun connectController(): MediaController {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        return MediaController.Builder(context, token)
            .buildAsync()
            .get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun mediaItem(mediaId: String, title: String, file: File): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist("BestiaPop instrumentation")
                    .build()
            )
            .build()

    private fun playbackNotification(): Notification? =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.id == MusicService.PLAYBACK_NOTIFICATION_ID }
            ?.notification

    private fun sendNotificationAction(notification: Notification, index: Int) {
        val action = requireNotNull(NotificationCompat.getAction(notification, index))
        requireNotNull(action.actionIntent).send()
    }

    private fun mainActivityIsForeground(
        scenario: ActivityScenario<MainActivity>
    ): Boolean {
        var foreground = false
        scenario.onActivity { activity ->
            foreground =
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                activity.hasWindowFocus() &&
                !activity.isFinishing &&
                !activity.isDestroyed
        }
        return foreground
    }

    @Suppress("DEPRECATION")
    private fun musicServiceInfo(): ActivityManager.RunningServiceInfo? {
        val serviceComponent = ComponentName(context, MusicService::class.java)
        return context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service == serviceComponent }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + ASYNC_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError(
            "Timed out waiting for $description; ${playbackDiagnostics()}"
        )
    }

    private fun <T : Any> awaitValue(description: String, value: () -> T?): T {
        var result: T? = null
        await(description) {
            value()?.also { result = it } != null
        }
        return requireNotNull(result)
    }

    private fun playbackDiagnostics(): String {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val activeNotifications = runCatching {
            notificationManager.activeNotifications.joinToString(
                prefix = "[",
                postfix = "]"
            ) { "${it.id}:flags=${it.notification.flags}" }
        }.getOrElse { "[error=${it.javaClass.simpleName}]" }
        val service = runCatching { musicServiceInfo() }.getOrNull()
        val channel = notificationManager.getNotificationChannel(
            MusicService.PLAYBACK_CHANNEL_ID
        )
        val permission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            "pre-33"
        } else {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS).toString()
        }
        val scenario = activityScenario
        val activityForeground = scenario?.let {
            runCatching { mainActivityIsForeground(it) }.getOrNull()
        }
        return "permission=$permission, " +
            "notificationsEnabled=${notificationManager.areNotificationsEnabled()}, " +
            "channelImportance=${channel?.importance}, " +
            "activeNotifications=$activeNotifications, " +
            "activityState=${scenario?.state}, activityForeground=$activityForeground, " +
            "serviceRunning=${service != null}, serviceForeground=${service?.foreground}"
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun writePcmWav(file: File) {
        val dataSize = SAMPLE_RATE_HZ * DURATION_SECONDS * CHANNEL_COUNT * BYTES_PER_SAMPLE
        FileOutputStream(file).buffered().use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(36 + dataSize)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(PCM_FORMAT)
            output.writeLittleEndianShort(CHANNEL_COUNT)
            output.writeLittleEndianInt(SAMPLE_RATE_HZ)
            output.writeLittleEndianInt(SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE)
            output.writeLittleEndianShort(CHANNEL_COUNT * BYTES_PER_SAMPLE)
            output.writeLittleEndianShort(BITS_PER_SAMPLE)
            output.write("data".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(dataSize)
            output.write(ByteArray(dataSize))
        }
    }

    private fun java.io.OutputStream.writeLittleEndianInt(value: Int) {
        repeat(Int.SIZE_BYTES) { byteIndex ->
            write(value ushr (byteIndex * Byte.SIZE_BITS) and 0xff)
        }
    }

    private fun java.io.OutputStream.writeLittleEndianShort(value: Int) {
        repeat(Short.SIZE_BYTES) { byteIndex ->
            write(value ushr (byteIndex * Byte.SIZE_BITS) and 0xff)
        }
    }

    private companion object {
        const val PREVIOUS_ACTION_INDEX = 0
        const val PLAY_PAUSE_ACTION_INDEX = 1
        const val NEXT_ACTION_INDEX = 2
        const val CONNECTION_TIMEOUT_SECONDS = 10L
        const val ASYNC_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 25L
        const val MIN_POSITION_ADVANCE_MS = 100L

        const val PCM_FORMAT = 1
        const val CHANNEL_COUNT = 1
        const val SAMPLE_RATE_HZ = 16_000
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / Byte.SIZE_BITS
        const val DURATION_SECONDS = 20
    }
}
