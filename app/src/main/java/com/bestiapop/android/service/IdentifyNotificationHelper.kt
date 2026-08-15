package com.bestiapop.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.model.LibraryJobProgress

/**
 * Ongoing identify progress (UIDT/FGS) plus a completion notice that survives job teardown.
 * Tap opens [MainActivity] on Biblioteca and requests the identify-review overlay.
 */
class IdentifyNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "identify_channel"
        const val NOTIFICATION_ID = 3102
        const val COMPLETED_NOTIFICATION_ID = 3103
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_IDENTIFY_REVIEW = "identify_review"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureChannel()
    }

    fun build(progress: LibraryJobProgress?, ongoing: Boolean): Notification? {
        if (progress == null) return null
        val title = if (progress.total > 0) {
            "Identificando ${progress.done}/${progress.total}"
        } else {
            "Identificando…"
        }
        val text = progress.label.takeIf { it.isNotBlank() } ?: "Buscando metadata"
        val percent = if (progress.total > 0) {
            ((progress.fraction * 100f).toInt()).coerceIn(0, 100)
        } else {
            0
        }
        return progressBuilder(title, text, ongoing)
            .setProgress(100, percent, progress.total <= 0)
            .build()
    }

    fun buildStarting(ongoing: Boolean): Notification {
        return progressBuilder("Identificando…", "Buscando metadata", ongoing)
            .setProgress(0, 0, true)
            .build()
    }

    fun notifyCompleted(summary: IdentifyBatchSummary) {
        val title = if (summary.reviewCount > 0) {
            if (summary.reviewCount == 1) "1 para revisar" else "${summary.reviewCount} para revisar"
        } else {
            summary.toastMessage()
        }
        val text = if (summary.reviewCount > 0) {
            "Tocá para retomar la revisión de identidad"
        } else {
            summary.toastMessage()
        }
        val notification = baseBuilder(title, text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(COMPLETED_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — library banner still works.
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun progressBuilder(title: String, text: String, ongoing: Boolean): NotificationCompat.Builder =
        baseBuilder(title, text)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    private fun baseBuilder(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAB, TAB_IDENTIFY_REVIEW)
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
                "Identificar",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progreso de identificación de metadata"
            }
        )
    }
}
