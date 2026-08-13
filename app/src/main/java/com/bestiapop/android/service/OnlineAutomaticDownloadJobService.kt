package com.bestiapop.android.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.data.model.DownloadLane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Constraint-aware background lifetime for automatic Guardar al escuchar transfers. */
class OnlineAutomaticDownloadJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null

    private val app: BestiaPopApplication
        get() = application as BestiaPopApplication

    override fun onStartJob(params: JobParameters): Boolean {
        OnlineDownloadServiceLauncher.markRunning(OnlineDownloadBackend.BACKGROUND_JOB, true)
        runner?.cancel()
        runner = serviceScope.launch {
            settleOnlineDownloadLifetime(
                runtime = app.processDownloadRuntime,
                backend = OnlineDownloadBackend.BACKGROUND_JOB,
                autoResume = app.shouldAutoResumeDownloads
            )
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runner?.cancel()
        val userStopped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            params.stopReason == JobParameters.STOP_REASON_USER
        return handleOnlineDownloadJobStop(
            backend = OnlineDownloadBackend.BACKGROUND_JOB,
            userStopped = userStopped,
            dismissRunning = {
                app.processDownloadRuntime.dismissRunning(DownloadLane.AUTOSAVE)
            },
            interruptNow = {
                app.processDownloadRuntime.interruptNow(DownloadLane.AUTOSAVE)
            }
        )
    }

    override fun onDestroy() {
        OnlineDownloadServiceLauncher.markRunning(OnlineDownloadBackend.BACKGROUND_JOB, false)
        serviceScope.cancel()
        super.onDestroy()
    }
}
