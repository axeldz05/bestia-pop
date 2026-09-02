package com.bestiapop.android.ui.state

enum class ItemLibraryStatus {
    NOT_IN_LIBRARY,
    SAVED_REMOTE,
    DOWNLOADED;

    val isDownloaded: Boolean get() = this == DOWNLOADED
    val isSavedRemote: Boolean get() = this == SAVED_REMOTE
    val isPresent: Boolean get() = this != NOT_IN_LIBRARY

    val albumMessage: String
        get() = when (this) {
            DOWNLOADED -> "El álbum ya está descargado en tu biblioteca"
            SAVED_REMOTE -> "El álbum ya está guardado en tu biblioteca"
            NOT_IN_LIBRARY -> ""
        }

    val trackMessage: String
        get() = when (this) {
            DOWNLOADED -> "Canción ya descargada en la biblioteca"
            SAVED_REMOTE -> "Canción guardada en la biblioteca"
            NOT_IN_LIBRARY -> ""
        }
}
