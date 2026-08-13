package com.bestiapop.android.service

import android.app.Notification
import android.app.job.JobParameters
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.DownloadLane
import com.bestiapop.android.data.model.forLane
import com.bestiapop.android.data.util.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun CoroutineScope.collectDownloadNotifications(
    downloads: StateFlow<List<ActiveDownload>>,
    lane: DownloadLane,
    helper: DownloadNotificationHelper,
    publish: (Notification) -> Unit
): Job = launch {
    downloads.collect { rows ->
        helper.build(rows.forLane(lane), ongoing = true)?.let(publish)
    }
}

internal suspend fun settleOnlineDownloadLifetime(
    runtime: ProcessDownloadRuntime,
    backend: OnlineDownloadBackend,
    autoResume: Boolean
) {
    runtime.settleLane(backend.lane, autoResume)
    OnlineDownloadServiceLauncher.settleBackend(backend)
}

internal fun handleOnlineDownloadJobStop(
    backend: OnlineDownloadBackend,
    userStopped: Boolean,
    dismissRunning: () -> Unit,
    interruptNow: () -> Unit
): Boolean {
    OnlineDownloadServiceLauncher.markRunning(backend, false)
    if (userStopped) {
        dismissRunning()
    } else {
        interruptNow()
    }
    return !userStopped
}

internal fun reportOnlineDownloadJobStop(
    backend: OnlineDownloadBackend,
    stopReason: Int
) {
    val reason = onlineDownloadStopReasonName(stopReason)
    CrashReporter.setKey("download_job_stop_reason", reason)
    CrashReporter.log("download_job_stopped backend=${backend.name} reason=$reason")
}

internal fun onlineDownloadStopReasonName(stopReason: Int): String = when (stopReason) {
    JobParameters.STOP_REASON_USER -> "user"
    JobParameters.STOP_REASON_BACKGROUND_RESTRICTION -> "background_restriction"
    JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "connectivity"
    JobParameters.STOP_REASON_QUOTA -> "quota"
    JobParameters.STOP_REASON_TIMEOUT -> "timeout"
    JobParameters.STOP_REASON_APP_STANDBY -> "app_standby"
    JobParameters.STOP_REASON_CANCELLED_BY_APP -> "cancelled_by_app"
    JobParameters.STOP_REASON_PREEMPT -> "preempt"
    JobParameters.STOP_REASON_DEVICE_STATE -> "device_state"
    else -> "other_$stopReason"
}
