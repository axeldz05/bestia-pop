package com.bestiapop.android.service

import android.content.Context
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * Routes async artwork completion back through MediaSessionService's normal update hook.
 *
 * Media3's internal artwork callback recomputes foreground demand without the Remote-IDLE
 * override supplied by MusicService. Triggering a fresh update keeps that policy in one place.
 */
@UnstableApi
internal class RefreshingMediaNotificationProvider(
    context: Context,
    notificationId: Int,
    channelId: String,
    channelNameResourceId: Int,
    private val requestNotificationRefresh: () -> Unit
) : MediaNotification.Provider {
    private val delegate = DefaultMediaNotificationProvider.Builder(context)
        .setNotificationId(notificationId)
        .setChannelId(channelId)
        .setChannelName(channelNameResourceId)
        .build()

    fun setSmallIcon(@DrawableRes resourceId: Int) {
        delegate.setSmallIcon(resourceId)
    }

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification = delegate.createNotification(
        mediaSession,
        mediaButtonPreferences,
        actionFactory
    ) {
        requestNotificationRefresh()
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean = delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        delegate.notificationChannelInfo
}
