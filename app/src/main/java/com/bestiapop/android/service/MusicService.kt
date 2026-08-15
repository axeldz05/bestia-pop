package com.bestiapop.android.service

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.MainActivity
import com.bestiapop.android.R
import com.bestiapop.android.data.preferences.MAX_VOLUME_BOOST_GAIN_MB
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.clampStereoGain
import com.bestiapop.android.data.system.BackgroundExecutionProbe
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.PlaybackDiagnostics
import com.bestiapop.android.service.library.BestiaPopMediaLibraryCallback
import com.bestiapop.android.service.library.MediaLibraryBrowseProvider
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MusicService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var boundAudioSessionId: Int = 0
    private var latestPlaybackSettings: PlaybackSettings = PlaybackSettings()
    private var appliedSettings = MusicServiceAppliedSettings(1f, 1f, 0)
    private var foregroundPromoteRetryScheduled = false
    private var foregroundPromoteRetryAttempts = 0
    private var restrictionNoticePosted = false
    private var appOpsWatcher: AppOpsManager.OnOpChangedListener? = null
    private var serviceWakeLock: PowerManager.WakeLock? = null
    private val stereoBalanceProcessor = StereoBalanceAudioProcessor()
    private val audioStore by lazy { MusicFileStore(this) }
    private val libraryBrowseProvider by lazy {
        MediaLibraryBrowseProvider(
            repository = (application as BestiaPopApplication).musicRepository,
            scope = serviceScope
        )
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_SERVICE, "MusicService.onCreate() starting")
        createPlaybackNotificationChannel()
        watchBackgroundAppOps()
        maybeNotifyBackgroundRestriction("onCreate")
        val notificationProvider = RefreshingMediaNotificationProvider(
            context = this,
            notificationId = PLAYBACK_NOTIFICATION_ID,
            channelId = PLAYBACK_CHANNEL_ID,
            channelNameResourceId = R.string.playback_notification_channel,
            requestNotificationRefresh = ::triggerNotificationUpdate
        ).apply { setSmallIcon(R.drawable.ic_stat_bestiapop) }
        setMediaNotificationProvider(notificationProvider)
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
        setForegroundServiceTimeoutMs(MediaSessionService.DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS)
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                handleForegroundServiceStartDenied()
            }
        })
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(stereoBalanceProcessor))
                    .build()
            }
        }

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NONE)
            .setMediaSourceFactory(UserAgentMediaSourceFactory(this))
            .build()

        // Buffers the first seconds of upcoming items. prefetchAround only re-resolves the CDN *URL*
        // for N+1 / N+2; without this nothing is downloaded until the track actually starts, so a
        // slow or expiring stream produced an audible gap (or an error) right at the transition.
        player?.setPreloadConfiguration(
            ExoPlayer.PreloadConfiguration(PRELOAD_TARGET_DURATION_US)
        )

        player?.let { p ->
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    PlaybackDiagnostics.logPlayerState(
                        event = "ExoPlayer.onPlaybackStateChanged",
                        isPlaying = p.isPlaying,
                        playWhenReady = p.playWhenReady,
                        playbackState = playbackState,
                        currentMediaId = p.currentMediaItem?.mediaId,
                        positionMs = p.currentPosition
                    )
                    updateWakeMode()
                    if (playbackState == Player.STATE_BUFFERING) {
                        acquireTransientWakeLock(30_000L)
                    } else if (playbackState == Player.STATE_READY && p.isPlaying) {
                        releaseTransientWakeLock()
                    } else if (playbackState == Player.STATE_ENDED && p.playWhenReady && p.mediaItemCount > 0) {
                        acquireTransientWakeLock(30_000L)
                    }
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    PlaybackDiagnostics.log(
                        PlaybackDiagnostics.TAG_SERVICE,
                        "ExoPlayer.onAudioSessionIdChanged: sessionId=$audioSessionId"
                    )
                    if (audioSessionId == 0 || audioSessionId == boundAudioSessionId) return
                    releaseLoudnessEnhancer()
                    applyBoost(latestPlaybackSettings)
                }

                override fun onPlayerError(error: PlaybackException) {
                    PlaybackDiagnostics.error(
                        PlaybackDiagnostics.TAG_PLAYBACK,
                        "ExoPlayer.onPlayerError: errorCode=${error.errorCodeName} (${error.errorCode}), msg=${error.message}, mediaId=${p.currentMediaItem?.mediaId}",
                        error
                    )
                    releaseTransientWakeLock()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    val reasonStr = when (reason) {
                        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
                        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                        else -> "REASON_$reason"
                    }
                    PlaybackDiagnostics.logPlayerState(
                        event = "ExoPlayer.onPlayWhenReadyChanged(playWhenReady=$playWhenReady, reason=$reasonStr)",
                        isPlaying = p.isPlaying,
                        playWhenReady = playWhenReady,
                        playbackState = p.playbackState,
                        currentMediaId = p.currentMediaItem?.mediaId,
                        positionMs = p.currentPosition
                    )
                    foregroundPromoteRetryAttempts = 0
                    persistPlaybackEngaged(isPlaybackEngaged())
                    updateWakeMode()
                    if (!playWhenReady) {
                        releaseTransientWakeLock()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    PlaybackDiagnostics.logPlayerState(
                        event = "ExoPlayer.onIsPlayingChanged(isPlaying=$isPlaying)",
                        isPlaying = isPlaying,
                        playWhenReady = p.playWhenReady,
                        playbackState = p.playbackState,
                        currentMediaId = p.currentMediaItem?.mediaId,
                        positionMs = p.currentPosition
                    )
                    if (isPlaying) {
                        foregroundPromoteRetryAttempts = 0
                        releaseTransientWakeLock()
                    }
                    persistPlaybackEngaged(isPlaybackEngaged())
                    updateWakeMode()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val reasonStr = when (reason) {
                        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO (next track)"
                        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                        else -> "REASON_$reason"
                    }
                    PlaybackDiagnostics.log(
                        PlaybackDiagnostics.TAG_PLAYBACK,
                        "ExoPlayer.onMediaItemTransition: mediaId='${mediaItem?.mediaId}', title='${mediaItem?.mediaMetadata?.title}', reason=$reasonStr"
                    )
                    updateWakeMode()
                    if (p.playWhenReady) {
                        acquireTransientWakeLock(30_000L)
                    }
                }
            })
            val callback = BestiaPopMediaLibraryCallback(
                scope = serviceScope,
                application = application as BestiaPopApplication,
                audioStore = audioStore,
                browseProvider = libraryBrowseProvider,
                publishShuffleExtras = ::publishShuffleExtras,
                applyShuffleOrder = ::applyShuffleOrder
            )
            mediaLibrarySession = MediaLibrarySession.Builder(this, p, callback)
                .setSessionActivity(mainActivityPendingIntent())
                .build()
        }

        val playbackPreferences = PlaybackPreferencesRepository(this)
        serviceScope.launch {
            playbackPreferences.settingsFlow.collectLatest { settings ->
                latestPlaybackSettings = settings
                applyStereoBalance(settings)
                applyBoost(settings)
            }
        }
    }

    private fun applyStereoBalance(settings: PlaybackSettings) {
        appliedSettings = appliedSettings.copy(
            leftGain = clampStereoGain(settings.stereoLeftGain),
            rightGain = clampStereoGain(settings.stereoRightGain)
        )
        stereoBalanceProcessor.leftGain = appliedSettings.leftGain
        stereoBalanceProcessor.rightGain = appliedSettings.rightGain
        publishAppliedSettings()
    }

    private fun applyBoost(settings: PlaybackSettings) {
        val clampedAmount =
            if (settings.volumeBoostEnabled) settings.volumeBoostAmount.coerceIn(0f, 1f) else 0f
        if (clampedAmount <= 0f) {
            try {
                loudnessEnhancer?.setTargetGain(0)
                loudnessEnhancer?.enabled = false
            } catch (_: Exception) {
            }
            appliedSettings = appliedSettings.copy(targetGainMb = 0)
            publishAppliedSettings()
            return
        }
        val gainMb = (clampedAmount * MAX_VOLUME_BOOST_GAIN_MB).toInt()
        appliedSettings = appliedSettings.copy(targetGainMb = gainMb)
        ensureLoudnessEnhancer()
        val enhancer = loudnessEnhancer
        if (enhancer == null) {
            publishAppliedSettings()
            return
        }
        try {
            enhancer.setTargetGain(gainMb)
            enhancer.enabled = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        publishAppliedSettings()
    }

    private fun publishAppliedSettings() {
        MusicServiceSettingsProbe.publish(appliedSettings)
    }

    private fun ensureLoudnessEnhancer() {
        val sessionId = player?.audioSessionId ?: 0
        if (sessionId == 0) return
        if (loudnessEnhancer != null && boundAudioSessionId == sessionId) return
        releaseLoudnessEnhancer()
        try {
            loudnessEnhancer = LoudnessEnhancer(sessionId)
            boundAudioSessionId = sessionId
        } catch (e: Exception) {
            e.printStackTrace()
            loudnessEnhancer = null
            boundAudioSessionId = 0
        }
    }

    private fun releaseLoudnessEnhancer() {
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {
        }
        loudnessEnhancer = null
        boundAudioSessionId = 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_SERVICE,
            "MusicService.onStartCommand(intentAction=${intent?.action}, flags=$flags, startId=$startId)"
        )
        super.onStartCommand(intent, flags, startId)
        if (shouldResumeAfterStickyRestart(intent == null, wasPlaybackEngaged())) {
            (application as BestiaPopApplication).playbackRuntime.requestResumeAfterServiceRestart()
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_SERVICE,
            "MusicService.onGetSession(callerPackage=${controllerInfo.packageName}, uid=${controllerInfo.uid})"
        )
        return mediaLibrarySession
    }

    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ): ListenableFuture<Void?> {
        val p = player
        val isEngaged = isPlaybackEngaged()
        val calculatedForeground = playbackForegroundRequired(
            startInForegroundRequired = startInForegroundRequired,
            playWhenReady = p?.playWhenReady == true,
            mediaItemCount = p?.mediaItemCount ?: 0,
            playbackState = p?.playbackState ?: Player.STATE_IDLE
        )
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_SERVICE,
            "MusicService.onUpdateNotificationAsync: media3StartForegroundRequired=$startInForegroundRequired, " +
                "calculatedForeground=$calculatedForeground, isPlaybackEngaged=$isEngaged, " +
                "isPlaying=${p?.isPlaying}, playWhenReady=${p?.playWhenReady}, items=${p?.mediaItemCount}, state=${p?.playbackState}"
        )
        maybeNotifyBackgroundRestriction("notification")
        return super.onUpdateNotificationAsync(session, calculatedForeground)
    }

    /**
     * Media3's default also requires [Player.isPlaying]. A Remote placeholder is intentionally
     * engaged while IDLE and resolving, so task removal must follow our play intent policy.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        val shouldStop = PlaybackServiceLifetimePolicy.shouldStopAfterTaskRemoved(
            playWhenReady = p?.playWhenReady == true,
            mediaItemCount = p?.mediaItemCount ?: 0,
            playbackState = p?.playbackState ?: Player.STATE_IDLE
        )
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_SERVICE,
            "MusicService.onTaskRemoved(rootIntent=${rootIntent?.action}): shouldStop=$shouldStop, " +
                "isPlaying=${p?.isPlaying}, playWhenReady=${p?.playWhenReady}, items=${p?.mediaItemCount}, state=${p?.playbackState}"
        )
        if (!shouldStop) {
            PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_SERVICE, "MusicService: Retaining playback service after task removed.")
            return
        }
        PlaybackDiagnostics.warn(PlaybackDiagnostics.TAG_SERVICE, "MusicService: Stopping service after task removed (not engaged).")
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        PlaybackDiagnostics.warn(
            PlaybackDiagnostics.TAG_SERVICE,
            "MusicService.onDestroy() invoked! Releasing player and session."
        )
        stopWatchingBackgroundAppOps()
        releaseTransientWakeLock()
        clearListener()
        serviceScope.cancel()
        releaseLoudnessEnhancer()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }

    private fun updateWakeMode() {
        val p = player ?: return
        val currentIsRemote = p.currentMediaItem?.mediaId?.startsWith("http") == true ||
            p.currentMediaItem?.localConfiguration?.uri?.scheme?.startsWith("http") == true
        val nextIndex = p.nextMediaItemIndex
        val nextIsRemote = if (nextIndex != C.INDEX_UNSET && nextIndex < p.mediaItemCount) {
            val nextItem = p.getMediaItemAt(nextIndex)
            nextItem.mediaId.startsWith("http") == true ||
                nextItem.localConfiguration?.uri?.scheme?.startsWith("http") == true
        } else {
            false
        }
        p.setWakeMode(playbackWakeMode(currentIsRemote = currentIsRemote, nextIsRemote = nextIsRemote))
    }

    private fun acquireTransientWakeLock(timeoutMs: Long = 30_000L) {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        try {
            if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
            }
            serviceWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BestiaPop:TransientTransition"
            ).apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
            PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_SERVICE, "MusicService: Acquired transient WakeLock (${timeoutMs}ms)")
        } catch (e: Exception) {
            PlaybackDiagnostics.warn(PlaybackDiagnostics.TAG_SERVICE, "Failed to acquire transient WakeLock: ${e.message}")
        }
    }

    private fun releaseTransientWakeLock() {
        try {
            if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
                PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_SERVICE, "MusicService: Released transient WakeLock")
            }
        } catch (_: Exception) {}
        serviceWakeLock = null
    }

    private fun isPlaybackEngaged(): Boolean {
        val p = player ?: return false
        return PlaybackServiceLifetimePolicy.isPlaybackEngaged(
            playWhenReady = p.playWhenReady,
            mediaItemCount = p.mediaItemCount,
            playbackState = p.playbackState
        )
    }

    private fun createPlaybackNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java) ?: return
            val channelName = getString(R.string.playback_notification_channel)
            val existing = notificationManager.getNotificationChannel(PLAYBACK_CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    PLAYBACK_CHANNEL_ID,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.playback_notification_channel_description)
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
            }
            if (notificationManager.getNotificationChannel(RESTRICTION_CHANNEL_ID) == null) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        RESTRICTION_CHANNEL_ID,
                        getString(R.string.playback_restricted_notification_channel),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = getString(R.string.playback_restricted_notification_text)
                        setShowBadge(true)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )
            }
        }
    }

    private fun mainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun handleForegroundServiceStartDenied() {
        PlaybackDiagnostics.error(
            PlaybackDiagnostics.TAG_SERVICE,
            "handleForegroundServiceStartDenied: Media3 foreground service start was DENIED! " +
                "isPlaying=${player?.isPlaying}, playWhenReady=${player?.playWhenReady}, state=${player?.playbackState}, " +
                "retryAttempts=$foregroundPromoteRetryAttempts, scheduled=$foregroundPromoteRetryScheduled"
        )
        CrashReporter.recordNonFatal(
            IllegalStateException("Media3 foreground service start was denied"),
            mapOf(
                "playback_phase" to "start_foreground",
                "play_when_ready" to (player?.playWhenReady?.toString() ?: "null"),
                "playback_state" to (player?.playbackState?.toString() ?: "null")
            )
        )
        if (BackgroundExecutionProbe.current(this).blocksBackgroundPlayback) {
            maybeNotifyBackgroundRestriction("startForegroundDenied")
            return
        }
        if (foregroundPromoteRetryScheduled ||
            foregroundPromoteRetryAttempts >= FOREGROUND_RETRY_DELAYS_MS.size ||
            !isPlaybackEngaged()
        ) {
            return
        }
        val retryDelay = FOREGROUND_RETRY_DELAYS_MS[foregroundPromoteRetryAttempts]
        foregroundPromoteRetryAttempts++
        foregroundPromoteRetryScheduled = true
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_SERVICE,
            "handleForegroundServiceStartDenied: Scheduling retry attempt $foregroundPromoteRetryAttempts in ${retryDelay}ms"
        )
        serviceScope.launch {
            delay(retryDelay)
            foregroundPromoteRetryScheduled = false
            if (isPlaybackEngaged()) {
                PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_SERVICE, "handleForegroundServiceStartDenied: Executing delayed notification refresh retry")
                triggerNotificationUpdate()
            }
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun persistPlaybackEngaged(engaged: Boolean) {
        getSharedPreferences(PLAYBACK_LIFETIME_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PLAYBACK_ENGAGED, engaged)
            .commit()
    }

    private fun wasPlaybackEngaged(): Boolean =
        getSharedPreferences(PLAYBACK_LIFETIME_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_PLAYBACK_ENGAGED, false)

    private fun watchBackgroundAppOps() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val appOps = getSystemService(AppOpsManager::class.java) ?: return
        val watchedPackage = packageName
        val watcher = AppOpsManager.OnOpChangedListener { _, changedPackage ->
            if (changedPackage != null && changedPackage != watchedPackage) return@OnOpChangedListener
            serviceScope.launch { maybeNotifyBackgroundRestriction("appops") }
        }
        appOpsWatcher = watcher
        appOps.startWatchingMode(
            com.bestiapop.android.data.system.OPSTR_RUN_ANY_IN_BACKGROUND,
            packageName,
            watcher
        )
    }

    private fun stopWatchingBackgroundAppOps() {
        val watcher = appOpsWatcher ?: return
        getSystemService(AppOpsManager::class.java)?.stopWatchingMode(watcher)
        appOpsWatcher = null
    }

    private fun maybeNotifyBackgroundRestriction(source: String) {
        val status = BackgroundExecutionProbe.current(this)
        if (!status.blocksBackgroundPlayback) {
            if (restrictionNoticePosted) {
                getSystemService(NotificationManager::class.java)
                    ?.cancel(RESTRICTION_NOTIFICATION_ID)
                restrictionNoticePosted = false
            }
            return
        }
        if (!isPlaybackEngaged() && !wasPlaybackEngaged()) return
        if (restrictionNoticePosted) return
        restrictionNoticePosted = true
        PlaybackDiagnostics.warn(
            PlaybackDiagnostics.TAG_SERVICE,
            "MusicService: background execution blocked while engaged (source=$source)"
        )
        notifyBackgroundRestrictionBlockedForeground()
    }

    private fun notifyBackgroundRestrictionBlockedForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            BackgroundExecutionProbe.applicationDetailsIntent(this, newTask = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, RESTRICTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bestiapop)
            .setContentTitle(getString(R.string.playback_restricted_notification_title))
            .setContentText(getString(R.string.playback_restricted_notification_text))
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(getString(R.string.playback_restricted_notification_text))
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        notificationManager.notify(RESTRICTION_NOTIFICATION_ID, notification)
    }

    companion object {
        const val PLAYBACK_CHANNEL_ID = "playback_channel"
        const val PLAYBACK_NOTIFICATION_ID = 1001
        const val ACTION_SET_SHUFFLE_ORDER = "com.bestiapop.android.SET_SHUFFLE_ORDER"
        const val EXTRA_SHUFFLE_ORDER = "shuffle_order"
        /** Head start buffered for upcoming queue items (10s). */
        private const val PRELOAD_TARGET_DURATION_US = 10_000_000L
        private val FOREGROUND_RETRY_DELAYS_MS = longArrayOf(750L, 2_000L, 5_000L)
        private const val PLAYBACK_LIFETIME_PREFS = "playback_lifetime"
        private const val KEY_PLAYBACK_ENGAGED = "engaged"
        private const val RESTRICTION_NOTIFICATION_ID = 1002
        const val RESTRICTION_CHANNEL_ID = "playback_restricted_channel"

        fun shuffleOrderFromPlayer(player: Player): IntArray? {
            if (!player.shuffleModeEnabled) return null
            val timeline = player.currentTimeline
            val n = timeline.windowCount
            if (n <= 0) return null
            val order = IntArray(n)
            var idx = timeline.getFirstWindowIndex(true)
            var i = 0
            while (idx != C.INDEX_UNSET && i < n) {
                order[i++] = idx
                idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, true)
            }
            return if (i == n) order else null
        }
    }

    private fun applyShuffleOrder(indices: IntArray?) {
        val p = player ?: return
        if (indices == null || indices.isEmpty()) {
            p.shuffleModeEnabled = false
            publishShuffleExtras()
            return
        }
        val playlistSize = p.mediaItemCount
        // Media3 requires order.length == playlistSize; mismatch used to crash the process.
        if (indices.size != playlistSize) {
            CrashReporter.recordNonFatal(
                IllegalArgumentException("shuffle_order_size_mismatch"),
                mapOf(
                    "playback_phase" to "set_shuffle_order",
                    "order_size" to indices.size.toString(),
                    "playlist_size" to playlistSize.toString()
                )
            )
            if (playlistSize <= 0) p.shuffleModeEnabled = false
            publishShuffleExtras()
            return
        }
        try {
            p.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(indices, /* randomSeed */ 0L))
            p.shuffleModeEnabled = true
        } catch (e: Exception) {
            CrashReporter.recordNonFatal(
                e,
                mapOf(
                    "playback_phase" to "set_shuffle_order",
                    "order_size" to indices.size.toString(),
                    "playlist_size" to playlistSize.toString()
                )
            )
            p.shuffleModeEnabled = false
        }
        publishShuffleExtras()
    }

    private fun publishShuffleExtras() {
        val session = mediaLibrarySession ?: return
        val extras = Bundle()
        val p = player
        if (p != null && p.shuffleModeEnabled) {
            shuffleOrderFromPlayer(p)?.let { extras.putIntArray(EXTRA_SHUFFLE_ORDER, it) }
        }
        session.setSessionExtras(extras)
    }

}

/**
 * Builds media sources with a per-item HTTP User-Agent when [StreamPlaybackTag] is set.
 * Local file / content URIs still go through [DefaultDataSource].
 */
@UnstableApi
internal class UserAgentMediaSourceFactory(
    private val context: Context
) : MediaSource.Factory {

    private val extractorsFactory = DefaultExtractorsFactory()

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider
    ): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
    ): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray =
        intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val tag = mediaItem.streamPlaybackTag()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        if (tag != null && tag.userAgent.isNotBlank()) {
            httpFactory.setUserAgent(tag.userAgent)
        }
        val upstreamFactory = DefaultDataSource.Factory(context, httpFactory)
        val dataSourceFactory = ResolvingDataSource.Factory(
            upstreamFactory,
            ::boundGoogleVideoRequest
        )
        return DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            .createMediaSource(mediaItem)
    }
}

internal fun shouldResumeAfterStickyRestart(
    intentNull: Boolean,
    wasEngaged: Boolean
): Boolean = intentNull && wasEngaged

/** Local files: AudioTrack holds the native lock; a Java WakeLock trips OEM killers. */
@OptIn(UnstableApi::class)
internal fun playbackWakeMode(
    currentIsRemote: Boolean,
    nextIsRemote: Boolean
): Int = if (currentIsRemote || nextIsRemote) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE

internal fun playbackForegroundRequired(
    startInForegroundRequired: Boolean,
    playWhenReady: Boolean,
    mediaItemCount: Int,
    playbackState: Int
): Boolean = startInForegroundRequired ||
    (playWhenReady && mediaItemCount > 0)

@OptIn(UnstableApi::class)
internal fun boundGoogleVideoRequest(dataSpec: DataSpec): DataSpec {
    val uri = dataSpec.uri
    val remainingLength = googleVideoBoundedLength(
        host = uri.host,
        contentLengthParam = uri.getQueryParameter("clen"),
        position = dataSpec.position,
        requestedLength = dataSpec.length
    ) ?: return dataSpec
    return dataSpec.subrange(0L, remainingLength)
}

internal fun googleVideoBoundedLength(
    host: String?,
    contentLengthParam: String?,
    position: Long,
    requestedLength: Long
): Long? {
    if (requestedLength != C.LENGTH_UNSET.toLong()) return null
    if (host?.endsWith(".googlevideo.com") != true) return null
    val contentLength = contentLengthParam
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?: return null
    val remainingLength = contentLength - position
    return remainingLength.takeIf { it > 0L }
}
