package com.bestiapop.android.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackNotificationFactoryInstrumentedTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun notification_exposesThreeMediaSessionServiceActions() {
        val notification = notification(showPauseAction = true)
        val specs = PlaybackNotificationFactory.actionSpecs(showPauseAction = true)

        assertEquals(3, NotificationCompat.getActionCount(notification))
        specs.forEachIndexed { index, spec ->
            val action = NotificationCompat.getAction(notification, index)!!
            val actionIntent = requireNotNull(action.actionIntent)
            assertEquals(spec.iconResId, action.iconCompat?.resId)
            assertEquals(context.getString(spec.titleResId), action.title)
            assertEquals(context.packageName, actionIntent.creatorPackage)

            val mediaButtonIntent =
                PlaybackNotificationFactory.mediaButtonIntent(context, spec.keyCode)
            assertEquals(Intent.ACTION_MEDIA_BUTTON, mediaButtonIntent.action)
            assertEquals(
                ComponentName(context, MusicService::class.java),
                mediaButtonIntent.component
            )
            assertEquals(spec.keyCode, mediaKeyCode(mediaButtonIntent))
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun actions_areServicePendingIntents_neverActivityPendingIntents() {
        val pauseNotification = notification(showPauseAction = true)
        val pauseActions = (0..2).map {
            requireNotNull(NotificationCompat.getAction(pauseNotification, it)!!.actionIntent)
        }
        pauseActions.forEach { pendingIntent ->
            assertTrue(pendingIntent.isService)
            assertFalse(pendingIntent.isActivity)
            assertFalse(pendingIntent.isForegroundService)
        }

        val playAction = requireNotNull(
            NotificationCompat.getAction(
                notification(showPauseAction = false),
                1
            )!!.actionIntent
        )
        assertTrue(playAction.isForegroundService)
        assertFalse(playAction.isActivity)
    }

    private fun notification(showPauseAction: Boolean) =
        PlaybackNotificationFactory.builder(
            context = context,
            title = "Track",
            text = "Artist",
            contentIntent = PendingIntent.getActivity(
                context,
                99,
                Intent("com.bestiapop.android.TEST_OPEN").setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE
            ),
            showPauseAction = showPauseAction,
            ongoing = showPauseAction
        ).build()

    @Suppress("DEPRECATION")
    private fun mediaKeyCode(intent: Intent): Int {
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        assertEquals(KeyEvent.ACTION_DOWN, event?.action)
        return event?.keyCode ?: KeyEvent.KEYCODE_UNKNOWN
    }
}
