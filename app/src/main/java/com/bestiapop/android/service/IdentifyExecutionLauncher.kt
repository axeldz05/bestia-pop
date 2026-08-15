package com.bestiapop.android.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal enum class IdentifyExecutionBackend {
    USER_INITIATED_JOB,
    FOREGROUND_SERVICE
}

internal fun identifyExecutionBackend(sdkInt: Int): IdentifyExecutionBackend =
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        IdentifyExecutionBackend.USER_INITIATED_JOB
    } else {
        IdentifyExecutionBackend.FOREGROUND_SERVICE
    }

internal class IdentifyExecutionLease(
    private val backend: IdentifyExecutionBackend,
    private val release: (IdentifyExecutionBackend) -> Unit
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        release(backend)
    }
}

internal object IdentifyExecutionLauncher {
    const val UIDT_JOB_ID = 0xB359

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
    private val states = IdentifyExecutionBackend.entries.associateWith { BackendState() }

    suspend fun acquire(context: Context): IdentifyExecutionLease {
        val backend = identifyExecutionBackend(Build.VERSION.SDK_INT)
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
            return IdentifyExecutionLease(backend, ::release)
        } catch (error: Throwable) {
            release(backend)
            throw error
        }
    }

    suspend fun awaitNoLeases(backend: IdentifyExecutionBackend) {
        states.getValue(backend).leaseCount.first { it == 0 }
    }

    suspend fun settleBackend(backend: IdentifyExecutionBackend) {
        do {
            awaitNoLeases(backend)
        } while (!closeIfIdle(backend))
    }

    fun closeIfIdle(backend: IdentifyExecutionBackend): Boolean = synchronized(lock) {
        val state = states.getValue(backend)
        if (state.leaseCount.value != 0) {
            false
        } else {
            state.status.value = BackendStatus.Idle
            true
        }
    }

    fun markRunning(backend: IdentifyExecutionBackend, running: Boolean) {
        synchronized(lock) {
            val state = states.getValue(backend)
            state.status.value = if (running) {
                BackendStatus.Running
            } else {
                BackendStatus.Idle
            }
        }
    }

    private fun release(backend: IdentifyExecutionBackend) {
        synchronized(lock) {
            val state = states.getValue(backend)
            state.leaseCount.value = (state.leaseCount.value - 1).coerceAtLeast(0)
        }
    }

    private fun startBackend(context: Context, backend: IdentifyExecutionBackend) {
        when (backend) {
            IdentifyExecutionBackend.USER_INITIATED_JOB -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    scheduleUserInitiatedJob(context)
                } else {
                    error("UIDT requiere Android 14+")
                }
            }
            IdentifyExecutionBackend.FOREGROUND_SERVICE ->
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, IdentifyForegroundService::class.java)
                )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUserInitiatedJob(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val info = JobInfo.Builder(
            UIDT_JOB_ID,
            ComponentName(context, IdentifyJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setUserInitiated(true)
            .build()
        check(scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS) {
            "No se pudo iniciar la identificación en segundo plano"
        }
    }
}
