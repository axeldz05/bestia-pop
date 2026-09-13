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
    private const val KEY_BB_APP_STATE = "bb_app_state"
    private const val KEY_BB_BG_TIMESTAMP_MS = "bb_bg_timestamp_ms"
    private const val KEY_BB_LAST_PLAYBACK = "bb_last_playback"
    private const val KEY_BB_LAST_TRIM = "bb_last_trim"
    private const val KEY_BB_LAST_TRIM_MS = "bb_last_trim_ms"

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

            val metadata = buildExitMetadata(context, exit)
            val throwable = createExceptionForExitReason(exit, metadata) ?: continue
            reporter(throwable, metadata)
        }

        if (newestTimestamp > lastReportedTimestamp) {
            prefs.edit {
                putLong(KEY_LAST_REPORTED_EXIT_MS, newestTimestamp)
                remove(KEY_BB_BG_TIMESTAMP_MS)
                remove(KEY_BB_LAST_TRIM)
                remove(KEY_BB_LAST_TRIM_MS)
            }
        }
    }

    /**
     * Updates foreground/background tracking for forensic correlation on unexpected process exit.
     */
    fun updateAppForegroundState(context: Context, isForeground: Boolean, screenName: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            if (isForeground) {
                putString(KEY_BB_APP_STATE, "FOREGROUND(${screenName ?: "App"})")
                remove(KEY_BB_BG_TIMESTAMP_MS)
            } else {
                putString(KEY_BB_APP_STATE, "BACKGROUND")
                putLong(KEY_BB_BG_TIMESTAMP_MS, System.currentTimeMillis())
            }
        }
        syncProcessStateSummary(context)
    }

    /**
     * Updates playback state for forensic correlation on unexpected process exit.
     */
    fun updatePlaybackState(context: Context, isPlaying: Boolean, playWhenReady: Boolean, trackDescription: String? = null) {
        val status = when {
            isPlaying -> "PLAYING"
            playWhenReady -> "PREPARING"
            trackDescription != null -> "PAUSED"
            else -> "IDLE"
        }
        val fullStatus = if (trackDescription != null && status != "IDLE") {
            "$status($trackDescription)"
        } else {
            status
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_BB_LAST_PLAYBACK, fullStatus)
        }
        syncProcessStateSummary(context)
    }

    /**
     * In-flight telemetry: captures severe memory pressure signals from the Android kernel / framework.
     * Ignores benign transitions such as [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN].
     */
    fun recordMemoryTrim(level: Int) = recordMemoryTrim(context = null, level = level)

    fun recordMemoryTrim(context: Context?, level: Int) {
        val label = formatTrimMemoryLevel(level) ?: return
        val now = System.currentTimeMillis()
        if (CrashReporter.isEnabled) {
            CrashReporter.setKey("last_critical_trim_level", label)
            CrashReporter.setKey("last_critical_trim_time", now.toString())
            CrashReporter.log("Kernel/Framework memory pressure: $label (code=$level)")
        }
        context?.let { ctx ->
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_BB_LAST_TRIM, label)
                putLong(KEY_BB_LAST_TRIM_MS, now)
            }
            syncProcessStateSummary(ctx)
        }
    }

    /**
     * In-flight telemetry: records global system memory exhaustion warning.
     */
    fun recordLowMemory() = recordLowMemory(context = null)

    fun recordLowMemory(context: Context?) {
        val now = System.currentTimeMillis()
        if (CrashReporter.isEnabled) {
            CrashReporter.setKey("last_low_memory_time", now.toString())
            CrashReporter.log("Kernel global onLowMemory() warning triggered")
        }
        context?.let { ctx ->
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_BB_LAST_TRIM, "ON_LOW_MEMORY")
                putLong(KEY_BB_LAST_TRIM_MS, now)
            }
            syncProcessStateSummary(ctx)
        }
    }

    private fun syncProcessStateSummary(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val appState = prefs.getString(KEY_BB_APP_STATE, null) ?: "UNKNOWN"
            val playback = prefs.getString(KEY_BB_LAST_PLAYBACK, null)
            val trim = prefs.getString(KEY_BB_LAST_TRIM, null)

            val summary = buildString {
                append("last_app_state=").append(appState)
                playback?.let { append(",last_playback=").append(it) }
                trim?.let { append(",last_memory_trim=").append(it) }
            }
            runCatching {
                context.getSystemService(ActivityManager::class.java)
                    ?.setProcessStateSummary(summary.toByteArray(Charsets.UTF_8))
            }
        }
    }

    internal fun createExceptionForExitReason(
        exit: ApplicationExitInfo,
        metadata: Map<String, String> = emptyMap()
    ): SystemProcessKilledException? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val description = exit.description?.takeIf { it.isNotBlank() } ?: "No system description"
        val importanceLabel = formatImportance(exit.importance)
        val pssMb = exit.pss / 1024L
        val rssMb = exit.rss / 1024L

        val contextDetails = buildString {
            append("state=").append(importanceLabel)
            append(", rss=").append(rssMb).append("MB")
            append(", pss=").append(pssMb).append("MB")
            metadata["seconds_in_background"]?.let {
                append(", bg=").append(it).append("s")
            }
            metadata["last_playback"]?.let {
                append(", play=").append(it)
            }
            metadata["last_memory_trim"]?.let {
                append(", trim=").append(it)
            }
            if (exit.status != 0) {
                append(", status=").append(exit.status)
            }
        }

        return when (exit.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY ->
                LowMemoryKillException("LMK kill [$contextDetails]: $description")

            ApplicationExitInfo.REASON_OTHER ->
                LowMemoryKillException("System kill [$contextDetails]: $description")

            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                ExcessiveResourceUsageException("Resource kill [$contextDetails]: $description")

            ApplicationExitInfo.REASON_ANR ->
                ApplicationNotRespondingException("ANR kill [$contextDetails]: $description")

            ApplicationExitInfo.REASON_CRASH_NATIVE ->
                NativeCrashKillException("Native crash kill [$contextDetails]: $description")

            else -> null
        }
    }

    internal fun buildExitMetadata(context: Context?, exit: ApplicationExitInfo): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return metadata

        val pssMb = exit.pss / 1024L
        val rssMb = exit.rss / 1024L

        metadata["exit_reason"] = formatReason(exit.reason)
        metadata["importance"] = formatImportance(exit.importance)
        metadata["status"] = exit.status.toString()
        metadata["timestamp"] = exit.timestamp.toString()
        metadata["pid"] = exit.pid.toString()
        metadata["pss_mb"] = pssMb.toString()
        metadata["rss_mb"] = rssMb.toString()

        exit.description?.takeIf { it.isNotBlank() }?.let {
            metadata["system_description"] = it
        }

        val stateSummaryBytes = exit.processStateSummary
        if (stateSummaryBytes != null && stateSummaryBytes.isNotEmpty()) {
            val stateSummaryStr = runCatching { String(stateSummaryBytes, Charsets.UTF_8) }.getOrNull()
            if (!stateSummaryStr.isNullOrBlank()) {
                metadata["process_state_summary"] = stateSummaryStr
                stateSummaryStr.split(",").forEach { part ->
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) {
                        val k = kv[0].trim()
                        val v = kv[1].trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) {
                            metadata[k] = v
                        }
                    }
                }
            }
        }

        if (context != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastAppState = prefs.getString(KEY_BB_APP_STATE, null)
            val lastBgTime = prefs.getLong(KEY_BB_BG_TIMESTAMP_MS, 0L)
            val lastPlayback = prefs.getString(KEY_BB_LAST_PLAYBACK, null)
            val lastTrim = prefs.getString(KEY_BB_LAST_TRIM, null)
            val lastTrimTime = prefs.getLong(KEY_BB_LAST_TRIM_MS, 0L)

            if (!metadata.containsKey("last_app_state") && lastAppState != null) {
                metadata["last_app_state"] = lastAppState
            }
            if (!metadata.containsKey("seconds_in_background") && lastBgTime > 0L && exit.timestamp >= lastBgTime) {
                val bgSec = (exit.timestamp - lastBgTime) / 1000L
                metadata["seconds_in_background"] = bgSec.toString()
            }
            if (!metadata.containsKey("last_playback") && lastPlayback != null) {
                metadata["last_playback"] = lastPlayback
            }
            if (!metadata.containsKey("last_memory_trim") && lastTrim != null) {
                metadata["last_memory_trim"] = lastTrim
                if (lastTrimTime > 0L && exit.timestamp >= lastTrimTime) {
                    val trimSec = (exit.timestamp - lastTrimTime) / 1000L
                    metadata["seconds_since_last_trim"] = trimSec.toString()
                }
            }
        }

        return metadata
    }

    internal fun formatReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
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
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
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
