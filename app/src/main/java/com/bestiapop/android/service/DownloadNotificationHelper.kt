package com.bestiapop.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.isInFlight

/**
 * Shared notification renderer for the UIDT job and legacy foreground service.
 * Tap opens [MainActivity] on the Descargas tab.
 */
class DownloadNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "downloads_channel"
        const val NOTIFICATION_ID = 3101
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_DOWNLOADS = "downloads"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureChannel()
    }

    fun sync(downloads: List<ActiveDownload>) {
        val notification = build(downloads, ongoing = false)
        if (notification == null) {
            cancel()
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — badge / Descargas tab still work.
        }
    }

    fun build(downloads: List<ActiveDownload>, ongoing: Boolean): Notification? {
        val active = downloads.filter { it.state.isInFlight }
        if (active.isEmpty()) return null
        val latest = active.first()
        val count = active.size
        val title = if (count == 1) {
            DownloadMessages.downloadingQuoted(latest.displayLabel)
        } else {
            DownloadMessages.downloadingCount(count)
        }
        val text = latest.progressMessage?.takeIf { it.isNotBlank() }
            ?: "${latest.progressPercent}%"

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setProgress(100, latest.progressPercent.coerceIn(0, 100), latest.progressPercent <= 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    fun buildStarting(ongoing: Boolean): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Preparando descargas…")
            .setContentText(DownloadMessages.queued)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAB, TAB_DOWNLOADS)
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Descargas",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progreso de descargas de música online"
            }
        )
    }
}
