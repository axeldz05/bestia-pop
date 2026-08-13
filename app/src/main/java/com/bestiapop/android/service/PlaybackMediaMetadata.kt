package com.bestiapop.android.service

import android.net.Uri
import androidx.media3.common.MediaMetadata
import com.bestiapop.android.data.model.TrackMeta

internal fun TrackMeta.mediaMetadataBuilder(
    artworkUriOverride: Uri? = artworkUri?.takeIf(String::isNotBlank)?.let(Uri::parse)
): MediaMetadata.Builder = MediaMetadata.Builder()
    .setTitle(title)
    .setArtist(artist)
    .setAlbumTitle(album)
    .setArtworkUri(artworkUriOverride)
