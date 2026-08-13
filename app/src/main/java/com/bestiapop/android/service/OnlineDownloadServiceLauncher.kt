package com.bestiapop.android.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.DownloadLane
import com.bestiapop.android.data.model.lane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal enum class OnlineDownloadBackend {
    USER_INITIATED_JOB,
    FOREGROUND_SERVICE,
    BACKGROUND_JOB
}

internal val OnlineDownloadBackend.lane: DownloadLane
    get() = when (this) {
        OnlineDownloadBackend.BACKGROUND_JOB -> DownloadLane.AUTOSAVE
        OnlineDownloadBackend.USER_INITIATED_JOB,
        OnlineDownloadBackend.FOREGROUND_SERVICE -> DownloadLane.EXPLICIT
    }

internal fun onlineDownloadBackend(
    sdkInt: Int,
    source: ActiveDownloadSource
): OnlineDownloadBackend = when {
    source.lane == DownloadLane.AUTOSAVE ->
        OnlineDownloadBackend.BACKGROUND_JOB
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        OnlineDownloadBackend.USER_INITIATED_JOB
    else -> OnlineDownloadBackend.FOREGROUND_SERVICE
}

internal class OnlineDownloadLease(
    private val backend: OnlineDownloadBackend,
    private val release: (OnlineDownloadBackend) -> Unit
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        release(backend)
    }
}

/**
 * Reference-counted platform lifetimes. Admission and closing share one lock: a request either joins
 * a running backend before it can close, or observes it closed and schedules a fresh backend.
 */
internal object OnlineDownloadServiceLauncher {
    const val UIDT_JOB_ID = 0xB357
    const val AUTOMATIC_JOB_ID = 0xB358

    private sealed interface BackendStatus {
        data object Idle : BackendStatus
        data object Pending : BackendStatus
        data object Running : BackendStatus
        data class Failed(val error: Throwable) : BackendStatus
    }

    private data class BackendState(
        val leaseCount: MutableStateFlow<Int> = MutableStateFlow(0),
        val status: MutableStateFlow<BackendStatus> = MutableStateFlow(BackendStatus.Idle)
    )

    private val lock = Any()
    private val states = OnlineDownloadBackend.entries.associateWith { BackendState() }

    suspend fun acquire(context: Context, source: ActiveDownloadSource): OnlineDownloadLease {
        val backend = onlineDownloadBackend(Build.VERSION.SDK_INT, source)
        val shouldStart = synchronized(lock) {
            val state = states.getValue(backend)
            state.leaseCount.value++
            when (state.status.value) {
                BackendStatus.Idle,
                is BackendStatus.Failed -> {
                    state.status.value = BackendStatus.Pending
                    true
                }
                BackendStatus.Pending,
                BackendStatus.Running -> false
            }
        }
        try {
            if (shouldStart) {
                try {
                    startBackend(context.applicationContext, backend)
                } catch (error: Throwable) {
                    synchronized(lock) {
                        states.getValue(backend).status.value = BackendStatus.Failed(error)
                    }
                    throw error
                }
            }
            when (
                val status = states.getValue(backend).status.first {
                    it == BackendStatus.Running || it is BackendStatus.Failed
                }
            ) {
                is BackendStatus.Failed -> throw status.error
                else -> Unit
            }
            return OnlineDownloadLease(backend, ::release)
        } catch (error: Throwable) {
            release(backend)
            throw error
        }
    }

    suspend fun awaitNoLeases(backend: OnlineDownloadBackend) {
        states.getValue(backend).leaseCount.first { it == 0 }
    }

    suspend fun settleBackend(backend: OnlineDownloadBackend) {
        do {
            awaitNoLeases(backend)
        } while (!closeIfIdle(backend))
    }

    /** Atomically closes admission only if no request joined since the preceding idle observation. */
    fun closeIfIdle(backend: OnlineDownloadBackend): Boolean = synchronized(lock) {
        val state = states.getValue(backend)
        if (state.leaseCount.value != 0) {
            false
        } else {
            state.status.value = BackendStatus.Idle
            true
        }
    }

    fun markRunning(backend: OnlineDownloadBackend, running: Boolean) {
        synchronized(lock) {
            val state = states.getValue(backend)
            state.status.value = if (running) {
                BackendStatus.Running
            } else {
                BackendStatus.Idle
            }
        }
    }

    private fun release(backend: OnlineDownloadBackend) {
        synchronized(lock) {
            val state = states.getValue(backend)
            state.leaseCount.value = (state.leaseCount.value - 1).coerceAtLeast(0)
        }
    }

    private fun startBackend(context: Context, backend: OnlineDownloadBackend) {
        when (backend) {
            OnlineDownloadBackend.USER_INITIATED_JOB -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    scheduleUserInitiatedJob(context)
                } else {
                    error("UIDT requiere Android 14+")
                }
            }
            OnlineDownloadBackend.FOREGROUND_SERVICE ->
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, OnlineDownloadForegroundService::class.java)
                )
            OnlineDownloadBackend.BACKGROUND_JOB -> scheduleAutomaticJob(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUserInitiatedJob(context: Context) {
        scheduleJob(
            context = context,
            jobId = UIDT_JOB_ID,
            serviceClass = OnlineDownloadJobService::class.java,
            failureMessage = "No se pudo iniciar la descarga en segundo plano"
        ) {
            setUserInitiated(true)
        }
    }

    private fun scheduleAutomaticJob(context: Context) {
        scheduleJob(
            context = context,
            jobId = AUTOMATIC_JOB_ID,
            serviceClass = OnlineAutomaticDownloadJobService::class.java,
            failureMessage = "No se pudo programar Guardar al escuchar"
        )
    }

    private fun scheduleJob(
        context: Context,
        jobId: Int,
        serviceClass: Class<out JobService>,
        failureMessage: String,
        configure: JobInfo.Builder.() -> Unit = {}
    ) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val info = JobInfo.Builder(
            jobId,
            ComponentName(context, serviceClass)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .apply(configure)
            .build()
        check(scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS) {
            failureMessage
        }
    }
}
