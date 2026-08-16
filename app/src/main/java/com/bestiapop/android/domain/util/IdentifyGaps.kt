package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.looksLikeStoragePath

/**
 * Essential missing/placeholder identity: title, artist, album, artwork.
 * Year and track number are not gaps — import/WiFi identify does not look them up.
 */
fun isWeakIdentityTitle(artist: String, title: String): Boolean {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return true
    if (trimmed.startsWith("-") || trimmed.startsWith("_")) return true
    if (looksLikeStoragePath(title)) return true
    if (isTrackNumberLabel(trimmed)) return true
    return IdentifyRanking.isPlaceholderArtist(artist) &&
        (title.contains(" - ") || title.contains("_-_"))
}

fun gapApplyFields(
    artist: String,
    title: String,
    album: String,
    artworkUri: String?
): IdentifyApplyFields = IdentifyApplyFields(
    artwork = !SongPathNormalizer.hasUsableArtwork(artworkUri),
    title = isWeakIdentityTitle(artist, title),
    artist = IdentifyRanking.isPlaceholderArtist(artist),
    album = IdentifyRanking.isGenericAlbum(album),
    year = false,
    trackNumber = false
)

fun gapApplyFields(song: Song): IdentifyApplyFields = gapApplyFields(
    artist = song.artist,
    title = song.title,
    album = song.album,
    artworkUri = song.artworkUri
)

fun needsGapIdentify(
    artist: String,
    title: String,
    album: String,
    artworkUri: String?
): Boolean = gapApplyFields(artist, title, album, artworkUri).hasAny

fun needsGapIdentify(song: Song): Boolean = gapApplyFields(song).hasAny
