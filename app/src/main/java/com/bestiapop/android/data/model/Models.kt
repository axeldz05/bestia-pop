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



