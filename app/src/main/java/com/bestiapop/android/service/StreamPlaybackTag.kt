package com.bestiapop.android.service

/**
 * Carried on [androidx.media3.common.MediaItem] localConfiguration.tag so
 * [MusicService] can apply the correct User-Agent for googlevideo CDN streams.
 */
data class StreamPlaybackTag(
    val userAgent: String,
    val videoId: String? = null
)
