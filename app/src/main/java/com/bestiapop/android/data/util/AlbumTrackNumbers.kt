package com.bestiapop.android.data.util

/**
 * MediaStore-compatible album track encoding: `disc * 1000 + track` when disc > 1,
 * otherwise just [track]. Unknown / missing → 0.
 */
fun encodeAlbumTrack(track: Int, disc: Int = 0): Int {
    val t = track.coerceAtLeast(0)
    if (t <= 0) return 0
    val d = disc.coerceAtLeast(0)
    return if (d > 1) d * 1000 + t else t
}

/** Visible track index (1–999). Encoded MediaStore values use modulo 1000. */
fun albumTrackDisplayNumber(encoded: Int): Int {
    if (encoded <= 0) return 0
    val track = encoded % 1000
    return if (track > 0) track else encoded
}

/** Disc index from MediaStore encoding; 0 when unset / single-disc. */
fun albumDiscNumber(encoded: Int): Int =
    if (encoded >= 1000) encoded / 1000 else 0

/**
 * Sort key so disc1-track5 stored as `5` or `1005` collate together,
 * then disc 2, etc. Missing track → last.
 */
fun albumTrackSortKey(encoded: Int): Int {
    if (encoded <= 0) return Int.MAX_VALUE
    val disc = if (encoded >= 1000) encoded / 1000 else 1
    val track = if (encoded >= 1000) encoded % 1000 else encoded
    return disc * 1000 + track
}

/** Parse MMR `METADATA_KEY_CD_TRACK_NUMBER` / `DISC_NUMBER` (`"3/12"` or `"3"`). */
fun parseCdTrackNumber(cdTrack: String?, disc: String?): Int {
    val track = cdTrack?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
    val discNum = disc?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
    return encodeAlbumTrack(track, discNum)
}
