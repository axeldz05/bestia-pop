package com.bestiapop.android.data.system

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.bestiapop.android.data.preferences.TelemetryPreferencesRepository
import com.bestiapop.android.data.util.CrashReporter

open class SystemProcessKilledException(message: String) : Exception(message)
class LowMemoryKillException(message: String) : SystemProcessKilledException(message)
class ExcessiveResourceUsageException(message: String) : SystemProcessKilledException(message)
class ApplicationNotRespondingException(message: String) : SystemProcessKilledException(message)
class NativeCrashKillException(message: String) : SystemProcessKilledException(message)

/**
 * Event-driven stability monitor capturing critical OS pressure events and unexpected process terminations:
 * 1. Post-mortem: Evaluates [ApplicationExitInfo] on startup after unexpected kills (LMK, resource abuse, ANR)
 *    and transmits forensic metadata to Firebase Crashlytics with idempotent deduplication.
 * 2. In-flight: Records high-priority OS memory pressure callbacks ([ComponentCallbacks2.onTrimMemory]
 *    critical levels and [ComponentCallbacks2.onLowMemory]) as breadcrumbs and custom keys.
 */
object SystemStabilityMonitor {

    private const val PREFS_NAME = "system_stability_prefs"
    private const val KEY_LAST_REPORTED_EXIT_MS = "last_reported_exit_timestamp_ms"

    /**
     * Inspects historical process exits upon startup. Only acts if the previous exit was an unexpected,
     * anomalous termination (Low Memory Killer, excessive CPU/battery/wakelocks, ANR, native crash).
     * Normal user exits (swiped from recents, back press) are ignored.
     */
    fun checkHistoricalExitReasons(
        context: Context,
        reporter: (Throwable, Map<String, String>) -> Unit = { throwable, keys ->
            CrashReporter.recordNonFatal(throwable, keys)
        }
    ) {
        if (!TelemetryPreferencesRepository.isTelemetryEnabledSync(context)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        }.getOrNull().orEmpty()

        if (exits.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastReportedTimestamp = prefs.getLong(KEY_LAST_REPORTED_EXIT_MS, 0L)
        var newestTimestamp = lastReportedTimestamp

        for (exit in exits) {
            val exitTime = exit.timestamp
            if (exitTime <= lastReportedTimestamp) continue
            if (exitTime > newestTimestamp) {
                newestTimestamp = exitTime
            }

            val throwable = createExceptionForExitReason(exit) ?: continue
            val metadata = buildExitMetadata(exit)
            reporter(throwable, metadata)
        }

        if (newestTimestamp > lastReportedTimestamp) {
            prefs.edit { putLong(KEY_LAST_REPORTED_EXIT_MS, newestTimestamp) }
        }
    }

    /**
     * In-flight telemetry: captures severe memory pressure signals from the Android kernel / framework.
     * Ignores benign transitions such as [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN].
     */
    fun recordMemoryTrim(level: Int) {
        if (!CrashReporter.isEnabled) return
        val label = formatTrimMemoryLevel(level) ?: return
        val timestamp = System.currentTimeMillis().toString()

        CrashReporter.setKey("last_critical_trim_level", label)
        CrashReporter.setKey("last_critical_trim_time", timestamp)
        CrashReporter.log("Kernel/Framework memory pressure: $label (code=$level)")
    }

    /**
     * In-flight telemetry: records global system memory exhaustion warning.
     */
    fun recordLowMemory() {
        if (!CrashReporter.isEnabled) return
        val timestamp = System.currentTimeMillis().toString()
        CrashReporter.setKey("last_low_memory_time", timestamp)
        CrashReporter.log("Kernel global onLowMemory() warning triggered")
    }

    private fun createExceptionForExitReason(exit: ApplicationExitInfo): SystemProcessKilledException? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val description = exit.description?.takeIf { it.isNotBlank() } ?: "No system description"
        val importanceLabel = formatImportance(exit.importance)

        return when (exit.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY ->
                LowMemoryKillException("Process killed by LMK (Low Memory Killer) in state=$importanceLabel (status=${exit.status}): $description")

            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                ExcessiveResourceUsageException("Process killed for excessive resource usage in state=$importanceLabel (status=${exit.status}): $description")

            ApplicationExitInfo.REASON_ANR ->
                ApplicationNotRespondingException("Process terminated due to ANR in state=$importanceLabel (status=${exit.status}): $description")

            ApplicationExitInfo.REASON_CRASH_NATIVE ->
                NativeCrashKillException("Process killed due to native crash in state=$importanceLabel (status=${exit.status}): $description")

            else -> null
        }
    }

    private fun buildExitMetadata(exit: ApplicationExitInfo): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return metadata

        metadata["exit_reason"] = formatReason(exit.reason)
        metadata["importance"] = formatImportance(exit.importance)
        metadata["status"] = exit.status.toString()
        metadata["timestamp"] = exit.timestamp.toString()
        metadata["pid"] = exit.pid.toString()

        val pssMb = exit.pss / 1024L
        val rssMb = exit.rss / 1024L
        metadata["pss_mb"] = pssMb.toString()
        metadata["rss_mb"] = rssMb.toString()

        exit.description?.takeIf { it.isNotBlank() }?.let {
            metadata["system_description"] = it
        }

        return metadata
    }

    internal fun formatReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        else -> "UNKNOWN_$reason"
    }

    internal fun formatImportance(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
        else -> "IMPORTANCE_$importance"
    }

    internal fun formatTrimMemoryLevel(level: Int): String? = when (level) {
        // ComponentCallbacks2 levels: RUNNING_CRITICAL (15), RUNNING_LOW (10), COMPLETE (80), MODERATE (60)
        15 -> "RUNNING_CRITICAL"
        10 -> "RUNNING_LOW"
        80 -> "COMPLETE"
        60 -> "MODERATE"
        else -> null
    }
}
