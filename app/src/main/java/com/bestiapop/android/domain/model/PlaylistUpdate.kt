package com.bestiapop.android.domain.model

/**
 * Encapsulates parameters for updating a playlist.
 * Used to reduce parameter passing in repository and view model methods.
 */
data class PlaylistUpdate(
    val id: Long,
    val name: String,
    val description: String? = null,
    val coverUri: String? = null
)
