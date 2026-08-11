package com.bestiapop.android.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.bestiapop.android.MainActivity
import com.bestiapop.android.R
import com.bestiapop.android.data.preferences.MAX_VOLUME_BOOST_GAIN_MB
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.clampStereoGain
import com.bestiapop.android.data.util.CrashReporter
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
    private var foregroundPromoteRetryScheduled = false
    private val stereoBalanceProcessor = StereoBalanceAudioProcessor()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        ensureMediaNotificationChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(PLAYBACK_CHANNEL_ID)
                .setChannelName(R.string.playback_notification_channel)
                .setNotificationId(PLAYBACK_NOTIFICATION_ID)
                .build()
        )
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
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
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
                    if (playWhenReady) {
                        mediaLibrarySession?.let { promotePlaybackForeground(it) }
                    }
                }

            })
            mediaLibrarySession = MediaLibrarySession.Builder(this, p, LibraryCallback())
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
        stereoBalanceProcessor.leftGain = clampStereoGain(settings.stereoLeftGain)
        stereoBalanceProcessor.rightGain = clampStereoGain(settings.stereoRightGain)
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
            return
        }
        ensureLoudnessEnhancer()
        val enhancer = loudnessEnhancer ?: return
        val gainMb = (clampedAmount * MAX_VOLUME_BOOST_GAIN_MB).toInt()
        try {
            enhancer.setTargetGain(gainMb)
            enhancer.enabled = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val keepForeground = startInForegroundRequired || isPlaybackEngaged()
        if (keepForeground) {
            // Do not call super: Media3 1.5 posts on IMPORTANCE_LOW default_channel_id
            // (cannot be raised) and startForegroundService(), which Android 15 / Moto
            // demotes as soon as the Activity pauses.
            promotePlaybackForeground(session)
            return
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onDestroy() {
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
        if (!p.playWhenReady || p.mediaItemCount <= 0) return false
        // Keep FGS through brief IDLE while resolving/preparing Remote; drop on ENDED/pause.
        return p.playbackState != Player.STATE_ENDED
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

    private fun ensureMediaNotificationChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(
                PLAYBACK_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            )
                .setName(getString(R.string.playback_notification_channel))
                .setLightsEnabled(false)
                .setVibrationEnabled(false)
                .setSound(null, null)
                .build()
        )
    }

    /**
     * Enter/stay in mediaPlayback FGS via [Service.startForeground] only.
     * Media3 1.5 also calls [Context.startForegroundService], which Android 12+
     * rejects once the process is cached (uidState LAST) after FGS was demoted.
     */
    private fun promotePlaybackForeground(session: MediaSession) {
        try {
            val meta = player?.currentMediaItem?.mediaMetadata
            startPlaybackForeground(
                playbackNotificationBuilder(
                    meta?.title?.takeIf { it.isNotBlank() } ?: "Bestia Pop",
                    meta?.artist?.takeIf { it.isNotBlank() } ?: "",
                    session.sessionActivity ?: mainActivityPendingIntent()
                )
                    .setStyle(MediaStyleNotificationHelper.MediaStyle(session))
                    .build()
            )
            foregroundPromoteRetryScheduled = false
        } catch (e: Exception) {
            CrashReporter.recordNonFatal(
                e,
                mapOf(
                    "playback_phase" to "start_foreground",
                    "play_when_ready" to (player?.playWhenReady?.toString() ?: "null"),
                    "playback_state" to (player?.playbackState?.toString() ?: "null")
                )
            )
            if (!foregroundPromoteRetryScheduled && player?.playWhenReady == true) {
                foregroundPromoteRetryScheduled = true
                serviceScope.launch {
                    delay(750)
                    foregroundPromoteRetryScheduled = false
                    val session = mediaLibrarySession ?: return@launch
                    if (player?.playWhenReady == true) {
                        promotePlaybackForeground(session)
                    }
                }
            }
        }
    }

    private fun playbackNotificationBuilder(
        title: CharSequence,
        text: CharSequence,
        contentIntent: PendingIntent
    ) = NotificationCompat.Builder(this, PLAYBACK_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(contentIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    private fun startPlaybackForeground(notification: android.app.Notification) {
        val fgsType = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, PLAYBACK_NOTIFICATION_ID, notification, fgsType)
    }

    companion object {
        const val PLAYBACK_CHANNEL_ID = "playback_channel"
        const val PLAYBACK_NOTIFICATION_ID = 1001
        const val ACTION_SET_SHUFFLE_ORDER = "com.bestiapop.android.SET_SHUFFLE_ORDER"
        const val EXTRA_SHUFFLE_ORDER = "shuffle_order"

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

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            publishShuffleExtras()
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(ACTION_SET_SHUFFLE_ORDER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ACTION_SET_SHUFFLE_ORDER) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            val indices = args.getIntArray(EXTRA_SHUFFLE_ORDER)
                ?: customCommand.customExtras.getIntArray(EXTRA_SHUFFLE_ORDER)
            applyShuffleOrder(indices)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
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
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        return DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            .createMediaSource(mediaItem)
    }
}
