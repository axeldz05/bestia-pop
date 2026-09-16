package com.bestiapop.android.data.util

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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

    @Volatile
    private var appContext: Context? = null

    fun init(application: Application) {
        appContext = application.applicationContext
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

    fun logPlayerError(error: PlaybackException, currentMediaId: String?) {
        val kind = TrackKind.from(currentMediaId)
        val isPendingRemoteFileNotFound =
            kind == TrackKind.REMOTE && error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        if (isPendingRemoteFileNotFound) {
            warn(
                TAG_PLAYBACK,
                "ExoPlayer.onPlayerError: transient remote resolution pending: errorCode=${error.errorCodeName} (${error.errorCode}), msg=${error.message}, trackType=$kind"
            )
        } else {
            error(
                TAG_PLAYBACK,
                "ExoPlayer.onPlayerError: errorCode=${error.errorCodeName} (${error.errorCode}), msg=${error.message}, trackType=$kind",
                error
            )
        }
    }

    fun logMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val reasonStr = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO (next track)"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            else -> "REASON_$reason"
        }
        val kind = TrackKind.from(mediaItem?.mediaId)
        log(TAG_PLAYBACK, "ExoPlayer.onMediaItemTransition: trackType=$kind, reason=$reasonStr")
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
        val kind = TrackKind.from(currentMediaId)
        val typeStr = if (kind != TrackKind.NONE) " trackType=$kind" else ""
        val posStr = if (positionMs >= 0) " pos=${positionMs}ms" else ""
        val extraStr = extra?.let { " extra='$it'" } ?: ""
        log(
            TAG_PLAYBACK,
            "$event: isPlaying=$isPlaying, playWhenReady=$playWhenReady, state=$stateName$typeStr$posStr$extraStr"
        )
        appContext?.let { ctx ->
            com.bestiapop.android.data.system.SystemStabilityMonitor.updatePlaybackState(
                context = ctx,
                isPlaying = isPlaying,
                playWhenReady = playWhenReady,
                trackKind = kind
            )
        }
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
            "System status: Device=${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
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
                val reasonName = com.bestiapop.android.data.system.SystemStabilityMonitor.formatReason(info.reason)
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

    private fun installUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error(
                TAG_SYSTEM,
                "!!! UNCAUGHT EXCEPTION on thread '${thread.name}' !!!: ${throwable.message}",
                throwable
            )
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun registerMemoryCallbacks(application: Application) {
        application.registerComponentCallbacks(DiagnosticMemoryCallbacks(application))
    }

    private class DiagnosticMemoryCallbacks(private val application: Application) : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java", ReplaceWith("onTrimMemory(level)"))
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
            val levelName = com.bestiapop.android.data.system.SystemStabilityMonitor.formatTrimMemoryLevel(level) ?: "LEVEL_$level"
            val runtime = Runtime.getRuntime()
            val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMemMb = runtime.maxMemory() / 1024 / 1024
            log(TAG_SYSTEM, "onTrimMemory(level=$levelName), HeapUsed=${usedMemMb}MB / Max=${maxMemMb}MB")
        }
    }
}

enum class TrackKind {
    LOCAL,
    REMOTE,
    NONE;

    companion object {
        fun from(mediaId: String?): TrackKind = when {
            mediaId.isNullOrBlank() -> NONE
            mediaId.startsWith("remote:") || mediaId.startsWith("remote://") -> REMOTE
            else -> LOCAL
        }

        fun from(item: com.bestiapop.android.data.model.PlayableItem?): TrackKind = when (item) {
            null -> NONE
            is com.bestiapop.android.data.model.PlayableItem.Remote -> REMOTE
            is com.bestiapop.android.data.model.PlayableItem.Local -> LOCAL
        }

        fun fromMediaId(mediaId: String?): TrackKind = from(mediaId)
    }
}
