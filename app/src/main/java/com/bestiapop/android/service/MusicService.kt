package com.bestiapop.android.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

@OptIn(UnstableApi::class)
class MusicService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(UserAgentMediaSourceFactory(this))
            .build()

        player?.let { p ->
            mediaLibrarySession = MediaLibrarySession.Builder(this, p, LibraryCallback())
                .build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
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
