package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.DownloadMessages

enum class ItemLibraryStatus {
    NOT_IN_LIBRARY,
    SAVED_REMOTE,
    DOWNLOADED;

    val isDownloaded: Boolean get() = this == DOWNLOADED
    val isSavedRemote: Boolean get() = this == SAVED_REMOTE
    val isPresent: Boolean get() = this != NOT_IN_LIBRARY

    val albumMessage: String
        get() = when (this) {
            DOWNLOADED -> DownloadMessages.alreadyDownloadedAlbum
            SAVED_REMOTE -> "El álbum ya está guardado en tu biblioteca"
            NOT_IN_LIBRARY -> ""
        }

    val trackMessage: String
        get() = when (this) {
            DOWNLOADED -> DownloadMessages.alreadyDownloadedSong
            SAVED_REMOTE -> "Canción guardada en la biblioteca"
            NOT_IN_LIBRARY -> ""
        }
}
