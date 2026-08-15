package com.bestiapop.android.service

import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import com.bestiapop.android.BestiaPopApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android 14+ User-Initiated Data Transfer lease for user-requested identify batches.
 * [ProcessIdentifyRuntime] owns the lookup jobs; this service gives them a system-managed lifetime.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class IdentifyJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null
    private var notificationCollector: Job? = null

    private val app: BestiaPopApplication
        get() = application as BestiaPopApplication

    override fun onStartJob(params: JobParameters): Boolean {
        IdentifyExecutionLauncher.markRunning(
            IdentifyExecutionBackend.USER_INITIATED_JOB,
            true
        )
        val helper = IdentifyNotificationHelper(this)
        updateNotification(params, helper.buildStarting(ongoing = true))
        notificationCollector?.cancel()
        notificationCollector = serviceScope.collectIdentifyNotifications(
            progress = app.processIdentifyRuntime.progress,
            helper = helper,
            publish = { notification -> updateNotification(params, notification) }
        )
        runner?.cancel()
        runner = serviceScope.launch {
            settleIdentifyLifetime(
                runtime = app.processIdentifyRuntime,
                backend = IdentifyExecutionBackend.USER_INITIATED_JOB,
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
        reportIdentifyJobStop(params.stopReason)
        return handleIdentifyJobStop(
            backend = IdentifyExecutionBackend.USER_INITIATED_JOB,
            userStopped = params.stopReason == JobParameters.STOP_REASON_USER,
            cancelUser = { app.processIdentifyRuntime.cancelUser() },
            interruptNow = { app.processIdentifyRuntime.interruptNow() }
        )
    }

    override fun onDestroy() {
        IdentifyExecutionLauncher.markRunning(
            IdentifyExecutionBackend.USER_INITIATED_JOB,
            false
        )
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(params: JobParameters, notification: Notification) {
        setNotification(
            params,
            IdentifyNotificationHelper.NOTIFICATION_ID,
            notification,
            JOB_END_NOTIFICATION_POLICY_REMOVE
        )
    }
}
