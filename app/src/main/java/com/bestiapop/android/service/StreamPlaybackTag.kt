package com.bestiapop.android.service

import android.os.Bundle
import androidx.media3.common.MediaItem

/**
 * Per-item HTTP User-Agent for googlevideo CDN streams: the CDN rejects requests whose UA differs
 * from the one used to extract the URL, so [MusicService] must replay the extraction UA.
 */
data class StreamPlaybackTag(
    val userAgent: String,
    val videoId: String? = null
)

private const val EXTRA_USER_AGENT = "bestiapop.stream.userAgent"
private const val EXTRA_VIDEO_ID = "bestiapop.stream.videoId"

/**
 * Travels in `RequestMetadata.extras`, not in `localConfiguration.tag`: `LocalConfiguration.toBundle`
 * does not serialize `tag`, so anything set there is dropped when the item crosses the
 * MediaController → MediaSession boundary and never reaches the service.
 */
fun MediaItem.Builder.setStreamPlaybackTag(tag: StreamPlaybackTag): MediaItem.Builder =
    setRequestMetadata(
        MediaItem.RequestMetadata.Builder()
            .setExtras(
                Bundle().apply {
                    putString(EXTRA_USER_AGENT, tag.userAgent)
                    tag.videoId?.let { putString(EXTRA_VIDEO_ID, it) }
                }
            )
            .build()
    )

fun MediaItem.streamPlaybackTag(): StreamPlaybackTag? {
    val extras = requestMetadata.extras ?: return null
    val userAgent = extras.getString(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() } ?: return null
    return StreamPlaybackTag(userAgent = userAgent, videoId = extras.getString(EXTRA_VIDEO_ID))
}
