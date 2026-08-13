package com.bestiapop.android.service

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.bestiapop.android.data.model.PlayableItem

internal data class PlaybackCollectionSnapshot(
    val items: List<PlayableItem>,
    val currentIndex: Int,
    val positionMs: Long
) {
    val currentItem: PlayableItem
        get() = items[currentIndex]
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun playbackResumptionMetadataItem(
    item: PlayableItem,
    positionMs: Long
): MediaItem {
    val durationMs = item.durationMs.coerceAtLeast(0L)
    val progress = if (durationMs > 0L) {
        (positionMs.coerceIn(0L, durationMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
    val completionStatus = when {
        progress >= FULLY_PLAYED_THRESHOLD ->
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
        progress > 0.0 ->
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        else ->
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
    }
    val extras = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, completionStatus)
        if (completionStatus == MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED) {
            putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, progress)
        }
    }
    val metadata = item.mediaMetadataBuilder(
        artworkUriOverride = localArtworkUri(item.artworkUri)
    )
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .apply {
            if (durationMs > 0L) setDurationMs(durationMs)
        }
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(item.mediaId)
        .setMediaMetadata(metadata)
        .build()
}

private fun localArtworkUri(value: String?): Uri? {
    val uri = value?.takeIf(String::isNotBlank)?.let(Uri::parse) ?: return null
    return uri.takeIf {
        it.scheme == "content" ||
            it.scheme == "file" ||
            it.scheme == "android.resource"
    }
}

private const val FULLY_PLAYED_THRESHOLD = 0.95
