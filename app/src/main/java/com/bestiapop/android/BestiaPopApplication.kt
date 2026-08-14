package com.bestiapop.android

import android.app.Application
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.PlaybackDiagnostics
import com.bestiapop.android.domain.radio.RadioEngine
import com.bestiapop.android.domain.radio.createBestiaPopRadioEngine
import com.bestiapop.android.service.PlaybackRuntime
import com.bestiapop.android.service.OnlineDownloadServiceLauncher
import com.bestiapop.android.service.ProcessDownloadCoordinator
import com.bestiapop.android.service.ProcessDownloadRuntime
import com.bestiapop.android.service.ProcessSaveWhileListeningCoordinator
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BestiaPopApplication : Application(), ImageLoaderFactory {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var musicRepository: MusicRepository
        private set

    lateinit var radioEngine: RadioEngine
        private set

    internal lateinit var processDownloads: ProcessDownloadCoordinator
        private set

    internal lateinit var processDownloadRuntime: ProcessDownloadRuntime
        private set

    val shouldAutoResumeDownloads: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            true
        } else {
            val manager = getSystemService(ActivityManager::class.java)
            manager.getHistoricalProcessExitReasons(packageName, 0, 1)
                .firstOrNull()
                ?.reason != ApplicationExitInfo.REASON_USER_REQUESTED
        }
    }

    lateinit var playbackRuntime: PlaybackRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        PlaybackDiagnostics.init(this)
        // Collect crashes/non-fatals on release/beta builds only (not local debug noise).
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "BestiaPopApplication.onCreate version=${BuildConfig.VERSION_NAME}")
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "${activity.javaClass.simpleName}.onCreate(savedInstanceState=${savedInstanceState != null})")
            }
            override fun onActivityStarted(activity: android.app.Activity) {
                PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "${activity.javaClass.simpleName}.onStart()")
            }
            override fun onActivityResumed(activity: android.app.Activity) {
                PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "${activity.javaClass.simpleName}.onResume() [UI in FOREGROUND]")
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "${activity.javaClass.simpleName}.onPause() [UI losing focus]")
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "${activity.javaClass.simpleName}.onStop() [UI in BACKGROUND]")
            }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) {
                PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "${activity.javaClass.simpleName}.onDestroy(isFinishing=${activity.isFinishing})")
            }
        })

        musicRepository = MusicRepository(this)
        radioEngine = createBestiaPopRadioEngine()
        processDownloads = ProcessDownloadCoordinator.create(
            context = this,
            scope = processScope,
            onPlaylistTargetCompleted = { target, song ->
                musicRepository.addSongToPlaylist(target.playlistId, song.id)
                musicRepository.removePlaylistPendingTrack(
                    playlistId = target.playlistId,
                    artist = target.identity.artist,
                    title = target.identity.title
                )
            }
        )
        processDownloadRuntime = ProcessDownloadRuntime.create(
            context = this,
            scope = processScope,
            repository = musicRepository,
            processDownloads = processDownloads,
            acquireExecutionLease = { source ->
                OnlineDownloadServiceLauncher.acquire(this, source)
            }
        )
        val saveWhileListeningDownloads = ProcessSaveWhileListeningCoordinator(
            scope = processScope,
            runtime = processDownloadRuntime
        )
        playbackRuntime = PlaybackRuntime.create(
            context = this,
            repository = musicRepository,
            radioEngine = radioEngine,
            pendingListenDao = AppDatabase.getDatabase(this).pendingListenDao(),
            saveDownloads = saveWhileListeningDownloads
        )
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.10)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .allowHardware(true)
            .respectCacheHeaders(false)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            try {
                coil.Coil.imageLoader(this).memoryCache?.clear()
            } catch (_: Throwable) {}
        }
    }
}
