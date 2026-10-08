package com.bestiapop.android.data.model

enum class PlaylistPlatform(
    val displayName: String,
) {
    YOUTUBE("YouTube Music"),
    SPOTIFY("Spotify"),
    DEEZER("Deezer"),
}

sealed interface ParsedLinkTarget {
    data class Playlist(
        val platform: PlaylistPlatform,
        val playlistId: String,
    ) : ParsedLinkTarget

    data class Track(
        val platform: PlaylistPlatform,
        val trackIdOrUrl: String,
    ) : ParsedLinkTarget

    data class DirectAudio(
        val url: String,
    ) : ParsedLinkTarget

    data class Unsupported(
        val rawUrl: String,
    ) : ParsedLinkTarget
}

data class ImportedPlaylistData(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val platform: PlaylistPlatform,
    val tracks: List<TrackIdentity>,
)

sealed interface PlaylistImportError {
    data class PrivateOrUnavailable(
        val platform: String,
    ) : PlaylistImportError

    data class EmptyPlaylist(
        val platform: String,
    ) : PlaylistImportError

    data object UnsupportedUrl : PlaylistImportError

    data class NetworkError(
        val message: String? = null,
    ) : PlaylistImportError

    data class Generic(
        val message: String,
    ) : PlaylistImportError

    fun userFacingMessage(): String =
        when (this) {
            is PrivateOrUnavailable -> {
                "La playlist es privada o no se encuentra disponible. " +
                    "Asegurate de que esté configurada como pública o deslistada en $platform para poder importarla."
            }

            is EmptyPlaylist -> {
                "La playlist no contiene canciones para importar."
            }

            is UnsupportedUrl -> {
                "Enlace no reconocido. Pegá un enlace de YouTube, YouTube Music, Spotify o Deezer."
            }

            is NetworkError -> {
                message ?: "Sin conexión a internet o error al conectar con el servicio."
            }

            is Generic -> {
                message
            }
        }
}

data class PlaylistImportSummary(
    val playlistId: Long,
    val playlistTitle: String,
    val coverUrl: String?,
    val totalTracks: Int,
    val matchedLocalCount: Int,
    val pendingStreamCount: Int,
    val isDownloading: Boolean,
)

sealed class PlaylistImportException(
    override val message: String,
    val error: PlaylistImportError,
) : Exception(message) {
    class PrivateOrUnavailable(
        platform: String,
    ) : PlaylistImportException(
            "Playlist privada o no disponible en $platform",
            PlaylistImportError.PrivateOrUnavailable(platform),
        )

    class EmptyPlaylist(
        platform: String,
    ) : PlaylistImportException(
            "La playlist está vacía",
            PlaylistImportError.EmptyPlaylist(platform),
        )

    class NetworkError(
        detail: String? = null,
    ) : PlaylistImportException(
            detail ?: "Error de red al importar playlist",
            PlaylistImportError.NetworkError(detail),
        )
}
