package com.bestiapop.android.service

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.bestiapop.android.data.preferences.MAX_VOLUME_BOOST_GAIN_MB
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.clampStereoGain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MusicService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var boundAudioSessionId: Int = 0
    private var latestPlaybackSettings: PlaybackSettings = PlaybackSettings()
    private val stereoBalanceProcessor = StereoBalanceAudioProcessor()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
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
            .setMediaSourceFactory(UserAgentMediaSourceFactory(this))
            .build()

        player?.let { p ->
            p.addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    if (audioSessionId == 0 || audioSessionId == boundAudioSessionId) return
                    releaseLoudnessEnhancer()
                    applyBoost(latestPlaybackSettings)
                }
            })
            mediaLibrarySession = MediaLibrarySession.Builder(this, p, LibraryCallback())
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

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        // Default media library session callbacks
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
        val tag = mediaItem.localConfiguration?.tag as? StreamPlaybackTag
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
