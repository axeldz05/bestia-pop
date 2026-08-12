package com.bestiapop.android.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.session.R as Media3SessionR

internal data class PlaybackNotificationActionSpec(
    val command: Int,
    val keyCode: Int,
    @param:DrawableRes val iconResId: Int,
    @param:StringRes val titleResId: Int,
    val startsForegroundService: Boolean = false
)

/**
 * Builds the single playback notification owned by [MusicService].
 *
 * Media3's default action factory also sends explicit `ACTION_MEDIA_BUTTON` intents to the
 * session service. BestiaPop has one playback session, so omitting Media3's private session URI
 * intentionally uses `MediaSessionService.onGetSession` as the supported fallback.
 */
internal object PlaybackNotificationFactory {
    private val compactActions = intArrayOf(0, 1, 2)

    fun compactActionIndices(): IntArray = compactActions.copyOf()

    fun shouldShowPauseAction(
        playWhenReady: Boolean,
        playbackState: Int
    ): Boolean = playWhenReady && playbackState != Player.STATE_ENDED

    fun actionSpecs(showPauseAction: Boolean): List<PlaybackNotificationActionSpec> = listOf(
        PlaybackNotificationActionSpec(
            command = Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            iconResId = Media3SessionR.drawable.media3_icon_previous,
            titleResId = Media3SessionR.string.media3_controls_seek_to_previous_description
        ),
        PlaybackNotificationActionSpec(
            command = Player.COMMAND_PLAY_PAUSE,
            keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            iconResId = if (showPauseAction) {
                Media3SessionR.drawable.media3_icon_pause
            } else {
                Media3SessionR.drawable.media3_icon_play
            },
            titleResId = if (showPauseAction) {
                Media3SessionR.string.media3_controls_pause_description
            } else {
                Media3SessionR.string.media3_controls_play_description
            },
            startsForegroundService = !showPauseAction
        ),
        PlaybackNotificationActionSpec(
            command = Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
            iconResId = Media3SessionR.drawable.media3_icon_next,
            titleResId = Media3SessionR.string.media3_controls_seek_to_next_description
        )
    )

    fun builder(
        context: Context,
        title: CharSequence,
        text: CharSequence,
        contentIntent: PendingIntent,
        showPauseAction: Boolean,
        ongoing: Boolean
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, MusicService.PLAYBACK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        actionSpecs(showPauseAction).forEach { action ->
            builder.addAction(
                action.iconResId,
                context.getString(action.titleResId),
                mediaButtonPendingIntent(context, action)
            )
        }
        return builder
    }

    internal fun mediaButtonIntent(
        context: Context,
        keyCode: Int
    ): Intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        component = ComponentName(context, MusicService::class.java)
        putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    }

    private fun mediaButtonPendingIntent(
        context: Context,
        action: PlaybackNotificationActionSpec
    ): PendingIntent {
        val intent = mediaButtonIntent(context, action.keyCode)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            action.startsForegroundService
        ) {
            PendingIntent.getForegroundService(
                context,
                action.keyCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                action.keyCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
