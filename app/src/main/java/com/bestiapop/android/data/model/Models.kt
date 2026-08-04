package com.bestiapop.android.data.model

enum class RepeatMode {
    OFF,
    ONE,
    ALL
}

data class Song(
    val id: Long = 0,
    val uriString: String,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val genre: String = "Unknown Genre",
    val durationMs: Long = 0,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val artworkUri: String? = null,
    val lyrics: String? = null,
    val folderPath: String = "",
    val dateAdded: Long = System.currentTimeMillis()
)

data class Album(
    val name: String,
    val artist: String,
    val songCount: Int,
    val artworkUri: String? = null,
    val genre: String? = null,
    val dateAdded: Long? = null
)

data class Artist(
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val photoUri: String? = null,
    val genre: String? = null,
    val dateAdded: Long? = null
)

data class Playlist(
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val coverUri: String? = null,
    val songCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** Metadata-only playlist member awaiting download (no audio file / no CDN URL). */
data class PlaylistPendingTrack(
    val id: Long = 0,
    val playlistId: Long,
    val title: String,
    val artist: String,
    val releaseName: String? = null,
    val recordingMbid: String? = null,
    val position: Int = 0
) {
    fun toOnlineCatalogTrack(): OnlineCatalogTrack = OnlineCatalogTrack(
        id = "${artist} ${title}".trim(),
        title = title,
        artist = artist,
        album = releaseName.orEmpty(),
        artworkUrl = null,
        durationMs = 0L,
        audioUrl = "",
        provider = "ListenBrainz"
    )
}

data class ColorSchemeData(
    val primary: Long,
    val onPrimary: Long,
    val secondary: Long,
    val background: Long,
    val surface: Long,
    val surfaceVariant: Long,
    val accent: Long
)

data class CustomTheme(
    val id: String,
    val name: String,
    val colors: ColorSchemeData,
    val isDark: Boolean = true
)

data class OnlineCatalogTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val durationMs: Long,
    val audioUrl: String,
    val provider: String = "YouTube",
    val userAgent: String = "Mozilla/5.0 (SmartHub; SMART-TV; U; Linux/SmartTV) AppleWebKit/538.1 (KHTML, like Gecko) TV Safari/538.1"
)


enum class CatalogCategory {
    SONGS,
    ALBUMS,
    PLAYLISTS
}

data class CatalogAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val trackCount: Int = 0,
    val releaseYear: String = ""
)

data class CatalogPlaylist(
    val id: String,
    val title: String,
    val creator: String,
    val coverUrl: String?,
    val trackCount: Int = 0
)

enum class CandidateDownloadState {
    IDLE,
    DOWNLOADING,
    SUCCESS,
    ERROR
}

data class CatalogTrackCandidate(
    val trackTitle: String,
    val artist: String,
    val albumName: String,
    val coverUrl: String?,
    val candidates: List<OnlineCatalogTrack>,
    val currentCandidateIndex: Int = 0,
    val isSelected: Boolean = true,
    val downloadState: CandidateDownloadState = CandidateDownloadState.IDLE,
    val downloadProgressPercent: Int = 0,
    val errorMessage: String? = null
) {
    val currentTrack: OnlineCatalogTrack?
        get() = candidates.getOrNull(currentCandidateIndex)
}


sealed class DownloadStatus {
    object Idle : DownloadStatus()
    data class Downloading(val message: String) : DownloadStatus()
    data class Success(val song: Song, val message: String) : DownloadStatus()
    data class Error(val message: String) : DownloadStatus()
}

enum class ActiveDownloadSource {
    CATALOG,
    LINK,
    SAVE_WHILE_LISTENING,
    BATCH,
    LB_IMPORT
}

/**
 * Unified in-memory download job for the Descargas center.
 * SUCCESS items are removed from the list; UI shows DOWNLOADING and ERROR.
 * [targetPlaylistId] — when set (e.g. LB_IMPORT), success adds the song to that playlist.
 */
data class ActiveDownload(
    val id: String,
    val source: ActiveDownloadSource,
    val displayTitle: String,
    val displayArtist: String,
    val artworkUrl: String?,
    val candidates: List<OnlineCatalogTrack>,
    val currentCandidateIndex: Int = 0,
    val state: CandidateDownloadState = CandidateDownloadState.DOWNLOADING,
    val progressMessage: String? = null,
    val progressPercent: Int = 0,
    val errorMessage: String? = null,
    val targetPlaylistId: Long? = null
) {
    val currentTrack: OnlineCatalogTrack?
        get() = candidates.getOrNull(currentCandidateIndex)

    companion object {
        /** Advance to the next YouTube match; expands via [newCandidates] when the list was a single placeholder. */
        fun withCycledCandidate(
            download: ActiveDownload,
            newCandidates: List<OnlineCatalogTrack>
        ): ActiveDownload {
            if (newCandidates.isEmpty()) return download
            val nextIndex = if (download.candidates.size <= 1 && newCandidates.size > 1) {
                // Just expanded from search — pick first result that differs from current id if possible
                val currentId = download.currentTrack?.id
                val alt = newCandidates.indexOfFirst { it.id != currentId }.takeIf { it >= 0 } ?: 0
                alt
            } else {
                (download.currentCandidateIndex + 1) % newCandidates.size
            }
            val next = newCandidates[nextIndex]
            val preservedAlbum = download.currentTrack?.album
                ?.takeIf { it.isNotBlank() && !it.equals("YouTube", ignoreCase = true) }
            val stillFailed = download.state == CandidateDownloadState.ERROR
            return download.copy(
                candidates = newCandidates.mapIndexed { i, t ->
                    if (i == nextIndex) {
                        t.copy(
                            album = preservedAlbum ?: t.album,
                            artworkUrl = t.artworkUrl ?: download.artworkUrl,
                            title = t.title.ifBlank { download.displayTitle },
                            artist = t.artist.ifBlank { download.displayArtist }
                        )
                    } else t
                },
                currentCandidateIndex = nextIndex,
                displayTitle = next.title.ifBlank { download.displayTitle },
                displayArtist = next.artist.ifBlank { download.displayArtist },
                artworkUrl = next.artworkUrl ?: download.artworkUrl,
                state = if (stillFailed) CandidateDownloadState.ERROR else CandidateDownloadState.IDLE,
                progressMessage = null,
                progressPercent = 0,
                errorMessage = if (stillFailed) "Match actualizado — tocá Reintentar" else null
            )
        }
    }
}



