package com.bestiapop.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadMessages

/**
 * Progress notification while downloads run in-process (no WorkManager / FGS).
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
        val downloading = downloads.filter { it.state == CandidateDownloadState.DOWNLOADING }
        if (downloading.isEmpty()) {
            cancel()
            return
        }
        val latest = downloading.first()
        val count = downloading.size
        val title = if (count == 1) {
            DownloadMessages.downloadingQuoted(latest.displayLabel)
        } else {
            DownloadMessages.downloadingCount(count)
        }
        val text = latest.progressMessage?.takeIf { it.isNotBlank() }
            ?: "${latest.progressPercent}%"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAB, TAB_DOWNLOADS)
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            // Dismissible on purpose: no foreground service backs this, so an ongoing notification
            // outlived a process kill and could not be swiped away until the app was reopened.
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setProgress(100, latest.progressPercent.coerceIn(0, 100), latest.progressPercent <= 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — badge / Descargas tab still work.
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
