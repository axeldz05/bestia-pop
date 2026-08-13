package com.bestiapop.android.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.data.model.DownloadLane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground `dataSync` fallback for Android 8–13 and foreground autosave starts. */
class OnlineDownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null
    private var notificationCollector: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId: Int = 0

    private val app: BestiaPopApplication
        get() = application as BestiaPopApplication

    override fun onCreate() {
        super.onCreate()
        OnlineDownloadServiceLauncher.markRunning(
            OnlineDownloadBackend.FOREGROUND_SERVICE,
            true
        )
        acquireWakeLock()
        val helper = DownloadNotificationHelper(this)
        promote(helper.buildStarting(ongoing = true))
        notificationCollector = serviceScope.collectDownloadNotifications(
            downloads = app.processDownloads.downloads,
            lane = DownloadLane.EXPLICIT,
            helper = helper,
            publish = ::promote
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        runner?.cancel()
        runner = serviceScope.launch {
            settleOnlineDownloadLifetime(
                runtime = app.processDownloadRuntime,
                backend = OnlineDownloadBackend.FOREGROUND_SERVICE,
                autoResume = app.shouldAutoResumeDownloads
            )
            stopDownloadService(startId)
        }
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        app.processDownloadRuntime.interruptNow(DownloadLane.EXPLICIT)
        forceStopDownloadService(startId)
    }

    override fun onDestroy() {
        OnlineDownloadServiceLauncher.markRunning(
            OnlineDownloadBackend.FOREGROUND_SERVICE,
            false
        )
        notificationCollector?.cancel()
        serviceScope.cancel()
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promote(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            DownloadNotificationHelper.NOTIFICATION_ID,
            notification,
            type
        )
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:online-downloads"
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun stopDownloadService(startId: Int = latestStartId) {
        if (!stopSelfResult(startId)) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun forceStopDownloadService(startId: Int) {
        OnlineDownloadServiceLauncher.markRunning(
            OnlineDownloadBackend.FOREGROUND_SERVICE,
            false
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private companion object {
        const val MAX_WAKE_LOCK_MS = 6L * 60L * 60L * 1000L
    }
}
