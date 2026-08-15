package com.bestiapop.android.service

import android.app.Notification
import com.bestiapop.android.data.model.LibraryJobProgress
import com.bestiapop.android.data.util.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal fun CoroutineScope.collectIdentifyNotifications(
    progress: StateFlow<LibraryJobProgress?>,
    helper: IdentifyNotificationHelper,
    publish: (Notification) -> Unit
): Job = launch {
    progress.collect { current ->
        helper.build(current, ongoing = true)?.let(publish)
    }
}

internal suspend fun settleIdentifyLifetime(
    runtime: ProcessIdentifyRuntime,
    backend: IdentifyExecutionBackend,
    autoResume: Boolean
) {
    runtime.settle(autoResume)
    IdentifyExecutionLauncher.settleBackend(backend)
}

internal fun handleIdentifyJobStop(
    backend: IdentifyExecutionBackend,
    userStopped: Boolean,
    cancelUser: () -> Unit,
    interruptNow: () -> Unit
): Boolean {
    IdentifyExecutionLauncher.markRunning(backend, false)
    if (userStopped) {
        cancelUser()
    } else {
        interruptNow()
    }
    return !userStopped
}

internal fun reportIdentifyJobStop(stopReason: Int) {
    CrashReporter.setKey("identify_job_stop_reason", onlineDownloadStopReasonName(stopReason))
    CrashReporter.log("identify_job_stopped reason=${onlineDownloadStopReasonName(stopReason)}")
}
