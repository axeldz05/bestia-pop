package com.bestiapop.android

import android.app.Application
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.data.util.CrashReporter
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

class BestiaPopApplication : Application() {
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
        // Collect crashes/non-fatals on release/beta builds only (not local debug noise).
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        CrashReporter.log("BestiaPopApplication.onCreate version=${BuildConfig.VERSION_NAME}")

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
}
