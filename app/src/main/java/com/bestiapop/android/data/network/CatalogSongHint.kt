package com.bestiapop.android.data.network

/**
 * Lightweight catalog metadata for radio / discovery fills.
 * Never includes a stream URL — audio is resolved via YouTube at play time.
 */
data class CatalogSongHint(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0
)
