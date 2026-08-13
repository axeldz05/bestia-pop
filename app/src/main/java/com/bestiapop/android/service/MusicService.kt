package com.bestiapop.android.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
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
    private val stereoBalanceProcessor = StereoBalanceAudioProcessor()
    private val audioStore by lazy { MusicFileStore(this) }
    private val libraryBrowseProvider by lazy {
        MediaLibraryBrowseProvider((application as BestiaPopApplication).musicRepository)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
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
            // NETWORK: CPU + wifi lock while playing (local files + remote streams).
            .setWakeMode(C.WAKE_MODE_NETWORK)
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
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    if (audioSessionId == 0 || audioSessionId == boundAudioSessionId) return
                    releaseLoudnessEnhancer()
                    applyBoost(latestPlaybackSettings)
                }

                override fun onPlayerError(error: PlaybackException) {
                    CrashReporter.recordNonFatal(
                        error,
                        mapOf(
                            "playback_phase" to "player_error",
                            "error_code" to error.errorCodeName,
                            "media_id" to (p.currentMediaItem?.mediaId ?: "none")
                        )
                    )
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    foregroundPromoteRetryAttempts = 0
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) foregroundPromoteRetryAttempts = 0
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ): ListenableFuture<Void?> = super.onUpdateNotificationAsync(
        session,
        playbackForegroundRequired(
            startInForegroundRequired = startInForegroundRequired,
            playWhenReady = player?.playWhenReady == true,
            mediaItemCount = player?.mediaItemCount ?: 0,
            playbackState = player?.playbackState ?: Player.STATE_IDLE
        )
    )

    /**
     * Media3's default also requires [Player.isPlaying]. A Remote placeholder is intentionally
     * engaged while IDLE and resolving, so task removal must follow our play intent policy.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!PlaybackServiceLifetimePolicy.shouldStopAfterTaskRemoved(
                playWhenReady = player?.playWhenReady == true,
                mediaItemCount = player?.mediaItemCount ?: 0,
                playbackState = player?.playbackState ?: Player.STATE_IDLE
            )
        ) {
            return
        }
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        clearListener()
        serviceScope.cancel()
        releaseLoudnessEnhancer()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        player = null
        super.onDestroy()
    }

    private fun isPlaybackEngaged(): Boolean {
        val p = player ?: return false
        return PlaybackServiceLifetimePolicy.isPlaybackEngaged(
            playWhenReady = p.playWhenReady,
            mediaItemCount = p.mediaItemCount,
            playbackState = p.playbackState
        )
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
        CrashReporter.recordNonFatal(
            IllegalStateException("Media3 foreground service start was denied"),
            mapOf(
                "playback_phase" to "start_foreground",
                "play_when_ready" to (player?.playWhenReady?.toString() ?: "null"),
                "playback_state" to (player?.playbackState?.toString() ?: "null")
            )
        )
        if (foregroundPromoteRetryScheduled ||
            foregroundPromoteRetryAttempts >= FOREGROUND_RETRY_DELAYS_MS.size ||
            !isPlaybackEngaged() ||
            BackgroundExecutionProbe.current(this).backgroundRestricted
        ) {
            return
        }
        val retryDelay = FOREGROUND_RETRY_DELAYS_MS[foregroundPromoteRetryAttempts]
        foregroundPromoteRetryAttempts++
        foregroundPromoteRetryScheduled = true
        serviceScope.launch {
            delay(retryDelay)
            foregroundPromoteRetryScheduled = false
            if (isPlaybackEngaged()) triggerNotificationUpdate()
        }
    }

    companion object {
        const val PLAYBACK_CHANNEL_ID = "playback_channel"
        const val PLAYBACK_NOTIFICATION_ID = 1001
        const val ACTION_SET_SHUFFLE_ORDER = "com.bestiapop.android.SET_SHUFFLE_ORDER"
        const val EXTRA_SHUFFLE_ORDER = "shuffle_order"
        /** Head start buffered for upcoming queue items (10s). */
        private const val PRELOAD_TARGET_DURATION_US = 10_000_000L
        private val FOREGROUND_RETRY_DELAYS_MS = longArrayOf(750L, 2_000L, 5_000L)

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
private class UserAgentMediaSourceFactory(
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

internal fun playbackForegroundRequired(
    startInForegroundRequired: Boolean,
    playWhenReady: Boolean,
    mediaItemCount: Int,
    playbackState: Int
): Boolean = startInForegroundRequired ||
    PlaybackServiceLifetimePolicy.isPlaybackEngaged(
        playWhenReady = playWhenReady,
        mediaItemCount = mediaItemCount,
        playbackState = playbackState
    )

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
