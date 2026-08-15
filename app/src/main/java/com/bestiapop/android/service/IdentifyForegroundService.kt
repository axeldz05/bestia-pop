package com.bestiapop.android.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.bestiapop.android.BestiaPopApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground `dataSync` fallback for identify batches on Android 8–13. */
class IdentifyForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null
    private var notificationCollector: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId: Int = 0

    private val app: BestiaPopApplication
        get() = application as BestiaPopApplication

    override fun onCreate() {
        super.onCreate()
        IdentifyExecutionLauncher.markRunning(
            IdentifyExecutionBackend.FOREGROUND_SERVICE,
            true
        )
        acquireWakeLock()
        val helper = IdentifyNotificationHelper(this)
        promote(helper.buildStarting(ongoing = true))
        notificationCollector = serviceScope.collectIdentifyNotifications(
            progress = app.processIdentifyRuntime.progress,
            helper = helper,
            publish = ::promote
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        runner?.cancel()
        runner = serviceScope.launch {
            settleIdentifyLifetime(
                runtime = app.processIdentifyRuntime,
                backend = IdentifyExecutionBackend.FOREGROUND_SERVICE,
                autoResume = app.shouldAutoResumeDownloads
            )
            stopIdentifyService(startId)
        }
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        app.processIdentifyRuntime.interruptNow()
        forceStopIdentifyService(startId)
    }

    override fun onDestroy() {
        IdentifyExecutionLauncher.markRunning(
            IdentifyExecutionBackend.FOREGROUND_SERVICE,
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
            IdentifyNotificationHelper.NOTIFICATION_ID,
            notification,
            type
        )
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:identify"
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun stopIdentifyService(startId: Int = latestStartId) {
        if (!stopSelfResult(startId)) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun forceStopIdentifyService(startId: Int) {
        IdentifyExecutionLauncher.markRunning(
            IdentifyExecutionBackend.FOREGROUND_SERVICE,
            false
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private companion object {
        const val MAX_WAKE_LOCK_MS = 6L * 60L * 60L * 1000L
    }
}
