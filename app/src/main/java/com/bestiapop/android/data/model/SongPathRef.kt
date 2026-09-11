package com.bestiapop.android.data.model

/**
 * Lightweight projection for file path operations (skips title, artist, artwork and lyrics blobs).
 */
data class SongPathRef(
    val uriString: String,
    val folderPath: String = ""
)
