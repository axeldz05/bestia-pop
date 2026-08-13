package com.bestiapop.android.testutil

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.service.MusicService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal class PlaybackDeviceProbe {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext

    fun connectController(timeoutSeconds: Long = 10L): MediaController {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        return MediaController.Builder(context, token)
            .buildAsync()
            .get(timeoutSeconds, TimeUnit.SECONDS)
    }

    fun playbackNotification(): Notification? =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.id == MusicService.PLAYBACK_NOTIFICATION_ID }
            ?.notification

    @Suppress("DEPRECATION")
    fun musicServiceInfo(): ActivityManager.RunningServiceInfo? {
        val component = ComponentName(context, MusicService::class.java)
        return context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service == component }
    }

    fun executeShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }

    fun await(
        description: String,
        timeoutMs: Long = 15_000L,
        pollMs: Long = 50L,
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(pollMs)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
