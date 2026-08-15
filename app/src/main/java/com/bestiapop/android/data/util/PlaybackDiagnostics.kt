package com.bestiapop.android.data.util

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.Player
import com.bestiapop.android.data.system.BackgroundExecutionProbe

/**
 * High-visibility diagnostics logger for debugging playback, service lifetime,
 * background kills, memory trimming, and uncaught exceptions.
 */
object PlaybackDiagnostics {

    private const val DEFAULT_TAG = "BestiaPop"
    const val TAG_LIFECYCLE = "BestiaPopLifecycle"
    const val TAG_SERVICE = "BestiaPopService"
    const val TAG_PLAYBACK = "BestiaPopPlayback"
    const val TAG_RUNTIME = "BestiaPopRuntime"
    const val TAG_SYSTEM = "BestiaPopSystem"

    fun init(application: Application) {
        log(TAG_SYSTEM, "=== BestiaPop Process Initialized (PID=${android.os.Process.myPid()}) ===")
        logSystemStatus(application)
        logHistoricalExitReasons(application)
        installUncaughtExceptionHandler()
        registerMemoryCallbacks(application)
    }

    fun log(tag: String = DEFAULT_TAG, message: String) {
        safeLogD(tag, message)
        CrashReporter.log("[$tag] $message")
    }

    fun warn(tag: String = DEFAULT_TAG, message: String) {
        safeLogW(tag, message)
        CrashReporter.log("[WARN][$tag] $message")
    }

    fun error(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        safeLogE(tag, message, throwable)
        if (throwable != null) {
            CrashReporter.recordNonFatal(throwable, mapOf("tag" to tag, "message" to message))
        } else {
            CrashReporter.log("[ERROR][$tag] $message")
        }
    }

    private fun safeLogD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    private fun safeLogW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            System.err.println("[WARN][$tag] $msg")
        }
    }

    private fun safeLogE(tag: String, msg: String, tr: Throwable?) {
        try {
            Log.e(tag, msg, tr)
        } catch (_: Throwable) {
            System.err.println("[ERROR][$tag] $msg: ${tr?.message}")
        }
    }

    fun logServiceEvent(event: String, details: Map<String, Any?> = emptyMap()) {
        val detailStr = if (details.isNotEmpty()) {
            " " + details.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }
        } else {
            ""
        }
        log(TAG_SERVICE, "$event$detailStr")
    }

    fun logPlayerState(
        event: String,
        isPlaying: Boolean,
        playWhenReady: Boolean,
        playbackState: Int,
        currentMediaId: String? = null,
        positionMs: Long = -1L,
        extra: String? = null
    ) {
        val stateName = when (playbackState) {
            Player.STATE_IDLE -> "IDLE(1)"
            Player.STATE_BUFFERING -> "BUFFERING(2)"
            Player.STATE_READY -> "READY(3)"
            Player.STATE_ENDED -> "ENDED(4)"
            else -> "UNKNOWN($playbackState)"
        }
        val mediaStr = currentMediaId?.let { " mediaId='$it'" } ?: ""
        val posStr = if (positionMs >= 0) " pos=${positionMs}ms" else ""
        val extraStr = extra?.let { " extra='$it'" } ?: ""
        log(
            TAG_PLAYBACK,
            "$event: isPlaying=$isPlaying, playWhenReady=$playWhenReady, state=$stateName$mediaStr$posStr$extraStr"
        )
    }

    private fun logSystemStatus(context: Context) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val ignoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        val bgRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activityManager?.isBackgroundRestricted == true
        } else {
            false
        }
        val status = BackgroundExecutionProbe.current(context)
        log(
            TAG_SYSTEM,
            "System status: Device=${Build.MANUFACTURER} ${Build.MODEL}, Android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                "IgnoringBatteryOptimizations=$ignoringBattery, BackgroundRestricted=$bgRestricted, " +
                "RunAnyInBackgroundIgnored=${status.runAnyInBackgroundIgnored}, " +
                "BlocksBackgroundPlayback=${status.blocksBackgroundPlayback}, " +
                "OemScreenOffCleanup=${status.oemScreenOffCleanupEnabled}"
        )
    }

    private fun logHistoricalExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        try {
            val exitReasons = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (exitReasons.isEmpty()) {
                log(TAG_SYSTEM, "Historical process exit reasons: none recorded")
                return
            }
            log(TAG_SYSTEM, "Historical process exit reasons (last ${exitReasons.size}):")
            exitReasons.forEachIndexed { index, info ->
                val reasonName = formatExitReason(info.reason)
                val description = info.description ?: "none"
                val pssMb = info.pss / 1024 / 1024
                val rssMb = info.rss / 1024 / 1024
                log(
                    TAG_SYSTEM,
                    "  [$index] reason=$reasonName (${info.reason}), timestamp=${info.timestamp}, " +
                        "importance=${info.importance}, status=${info.status}, RSS=${rssMb}MB, PSS=${pssMb}MB, desc='$description'"
                )
            }
        } catch (e: Exception) {
            warn(TAG_SYSTEM, "Failed to read historical exit reasons: ${e.message}")
        }
    }

    private fun formatExitReason(reason: Int): String = when (reason) {
        1 -> "REASON_EXIT_SELF"
        2 -> "REASON_SIGNALED"
        3 -> "REASON_LOW_MEMORY"
        4 -> "REASON_CRASH"
        5 -> "REASON_CRASH_NATIVE"
        6 -> "REASON_ANR"
        7 -> "REASON_INITIALIZATION_FAILURE"
        8 -> "REASON_PERMISSION_CHANGE"
        9 -> "REASON_EXCESSIVE_RESOURCE_USAGE"
        10 -> "REASON_USER_REQUESTED"
        11 -> "REASON_USER_ACTION"
        12 -> "REASON_DEPENDENCY_DIED"
        13 -> "REASON_OTHER"
        14 -> "REASON_FREEZER"
        15 -> "REASON_PACKAGE_STATE_CHANGE"
        16 -> "REASON_PACKAGE_UPDATED"
        else -> "REASON_UNKNOWN($reason)"
    }

    private fun installUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error(
                TAG_SYSTEM,
                "!!! UNCAUGHT EXCEPTION on thread '${thread.name}' (id=${thread.id}) !!!: ${throwable.message}",
                throwable
            )
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun registerMemoryCallbacks(application: Application) {
        application.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                warn(TAG_SYSTEM, "onLowMemory() received! System is critically low on memory. Clearing memory cache.")
                try {
                    coil.Coil.imageLoader(application).memoryCache?.clear()
                } catch (_: Throwable) {}
                System.gc()
            }

            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    try {
                        coil.Coil.imageLoader(application).memoryCache?.clear()
                    } catch (_: Throwable) {}
                }
                val levelName = when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "TRIM_MEMORY_COMPLETE (80 - process near kill)"
                    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "TRIM_MEMORY_MODERATE (60 - background near kill)"
                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "TRIM_MEMORY_BACKGROUND (40 - entered background)"
                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "TRIM_MEMORY_UI_HIDDEN (20 - UI no longer visible)"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_MEMORY_RUNNING_CRITICAL (15 - app running, sys critical)"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "TRIM_MEMORY_RUNNING_LOW (10 - app running, sys low)"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "TRIM_MEMORY_RUNNING_MODERATE (5 - app running, sys moderate)"
                    else -> "TRIM_MEMORY_UNKNOWN ($level)"
                }
                val runtime = Runtime.getRuntime()
                val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                val maxMemMb = runtime.maxMemory() / 1024 / 1024
                log(TAG_SYSTEM, "onTrimMemory(level=$levelName), HeapUsed=${usedMemMb}MB / Max=${maxMemMb}MB")
            }
        })
    }
}
