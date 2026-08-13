package com.bestiapop.android.service

import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.data.model.DownloadLane
import com.bestiapop.android.data.model.forLane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android 14+ User-Initiated Data Transfer lease for explicit catalog/link/playlist downloads.
 * The process runtime owns transfer jobs; this service gives them a system-managed lifetime.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class OnlineDownloadJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null
    private var notificationCollector: Job? = null

    private val app: BestiaPopApplication
        get() = application as BestiaPopApplication

    override fun onStartJob(params: JobParameters): Boolean {
        OnlineDownloadServiceLauncher.markRunning(
            OnlineDownloadBackend.USER_INITIATED_JOB,
            true
        )
        val helper = DownloadNotificationHelper(this)
        updateNotification(
            params,
            helper.build(
                app.processDownloads.downloads.value.forLane(DownloadLane.EXPLICIT),
                ongoing = true
            ) ?: helper.buildStarting(ongoing = true)
        )
        notificationCollector?.cancel()
        notificationCollector = serviceScope.collectDownloadNotifications(
            downloads = app.processDownloads.downloads,
            lane = DownloadLane.EXPLICIT,
            helper = helper
        ) { notification -> updateNotification(params, notification) }
        runner?.cancel()
        runner = serviceScope.launch {
            settleOnlineDownloadLifetime(
                runtime = app.processDownloadRuntime,
                backend = OnlineDownloadBackend.USER_INITIATED_JOB,
                autoResume = app.shouldAutoResumeDownloads
            )
            notificationCollector?.cancel()
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runner?.cancel()
        notificationCollector?.cancel()
        return handleOnlineDownloadJobStop(
            backend = OnlineDownloadBackend.USER_INITIATED_JOB,
            userStopped = params.stopReason == JobParameters.STOP_REASON_USER,
            dismissRunning = {
                app.processDownloadRuntime.dismissRunning(DownloadLane.EXPLICIT)
            },
            interruptNow = {
                app.processDownloadRuntime.interruptNow(DownloadLane.EXPLICIT)
            }
        )
    }

    override fun onDestroy() {
        OnlineDownloadServiceLauncher.markRunning(
            OnlineDownloadBackend.USER_INITIATED_JOB,
            false
        )
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(params: JobParameters, notification: Notification) {
        setNotification(
            params,
            DownloadNotificationHelper.NOTIFICATION_ID,
            notification,
            JOB_END_NOTIFICATION_POLICY_REMOVE
        )
    }
}
