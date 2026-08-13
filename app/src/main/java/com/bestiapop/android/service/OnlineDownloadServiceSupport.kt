package com.bestiapop.android.service

import android.app.Notification
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.DownloadLane
import com.bestiapop.android.data.model.forLane
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
