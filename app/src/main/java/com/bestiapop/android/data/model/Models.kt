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

enum class LibraryJobKind {
    IMPORT,
    IDENTIFY
}

/** In-progress library job (folder import / disk resync / batch identify). */
data class LibraryJobProgress(
    val kind: LibraryJobKind,
    val done: Int,
    val total: Int,
    val label: String
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

sealed class IdentifyResult {
    data class Updated(
        val songId: Long,
        val title: String,
        val artist: String,
        val album: String
    ) : IdentifyResult()

    data object NoMatch : IdentifyResult()
    data object Skipped : IdentifyResult()
}

data class Album(
    /** Storage key matching [Song.album] for filtering/grouping. */
    val name: String,
    val artist: String,
    val songCount: Int,
    val artworkUri: String? = null,
    val genre: String? = null,
    val dateAdded: Long? = null,
    val year: Int = 0,
    /** UI label; may differ from [name] when an override renames without propagating to songs. */
    val displayName: String = name
)

enum class WifiTransferState {
    PENDING,
    UPLOADING,
    PROCESSING,
    DONE,
    ERROR
}

/**
 * A file being received (or already received) via the WiFi sync web server.
 */
data class WifiTransferItem(
    val id: String,
    val fileName: String,
    val title: String,
    val artist: String,
    val state: WifiTransferState = WifiTransferState.PENDING,
    val progressPercent: Int = 0,
    val songId: Long? = null,
    val errorMessage: String? = null,
    val artworkUri: String? = null
)

/** Persisted album-level metadata that can diverge from individual songs. */
data class AlbumOverride(
    val albumKey: String,
    val displayName: String,
    val artist: String? = null,
    val genre: String? = null,
    val year: Int = 0,
    val artworkUri: String? = null
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
    fun toOnlineCatalogTrack(): OnlineCatalogTrack =
        PlayableItem.remoteFrom(
            artist = artist,
            title = title,
            album = releaseName,
            recordingMbid = recordingMbid
        ).toOnlineCatalogTrack(provider = "ListenBrainz")
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
    QUEUED,
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
    LB_IMPORT,
    /** Manual download from Para Ti / Recomendados (stream → library). */
    DISCOVER
}

/** How to resolve a title+artist collision when downloading. */
sealed class DownloadConflictPolicy {
    data class Overwrite(val existingSongId: Long) : DownloadConflictPolicy()
    data class SaveAs(val newTitle: String) : DownloadConflictPolicy()
}

/** Thrown when a download would duplicate an existing library song and no [DownloadConflictPolicy] was provided. */
class DuplicateSongException(
    val existing: Song,
    val track: OnlineCatalogTrack
) : Exception("La canción ya está en la biblioteca: ${existing.artist} — ${existing.title}")

/** Pending user decision for a download that collides with the library. */
data class DownloadConflict(
    val downloadId: String,
    val source: ActiveDownloadSource,
    val track: OnlineCatalogTrack,
    val existing: Song,
    val displayTitle: String,
    val displayArtist: String,
    val artworkUrl: String?,
    val candidates: List<OnlineCatalogTrack>,
    val currentCandidateIndex: Int,
    val mirrorCandidateTitle: String? = null,
    val targetPlaylistId: Long? = null,
    val applyToRemainingBatch: Boolean = false
)

/**
 * Unified in-memory download job for the Descargas center.
 * Shows QUEUED / DOWNLOADING / ERROR / IDLE (conflict) / SUCCESS (kept until dismissed).
 * [targetPlaylistId] — when set (e.g. LB_IMPORT / catalog playlist), success adds the song to that playlist.
 * [resultSongId] — set on SUCCESS so the UI can play the local song.
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
    val targetPlaylistId: Long? = null,
    val resultSongId: Long? = null
) {
    val currentTrack: OnlineCatalogTrack?
        get() = candidates.getOrNull(currentCandidateIndex)

    companion object {
        fun queued(
            id: String,
            source: ActiveDownloadSource,
            displayTitle: String,
            displayArtist: String,
            artworkUrl: String?,
            candidates: List<OnlineCatalogTrack>,
            currentCandidateIndex: Int = 0,
            targetPlaylistId: Long? = null,
            resultSongId: Long? = null
        ): ActiveDownload = ActiveDownload(
            id = id,
            source = source,
            displayTitle = displayTitle,
            displayArtist = displayArtist,
            artworkUrl = artworkUrl,
            candidates = candidates,
            currentCandidateIndex = currentCandidateIndex,
            state = CandidateDownloadState.QUEUED,
            progressMessage = "En cola",
            progressPercent = 0,
            errorMessage = null,
            targetPlaylistId = targetPlaylistId,
            resultSongId = resultSongId
        )

        fun downloading(
            id: String,
            source: ActiveDownloadSource,
            displayTitle: String,
            displayArtist: String,
            artworkUrl: String?,
            candidates: List<OnlineCatalogTrack>,
            currentCandidateIndex: Int = 0,
            targetPlaylistId: Long? = null,
            progressMessage: String = "Iniciando descarga...",
            progressPercent: Int = 20
        ): ActiveDownload = ActiveDownload(
            id = id,
            source = source,
            displayTitle = displayTitle,
            displayArtist = displayArtist,
            artworkUrl = artworkUrl,
            candidates = candidates,
            currentCandidateIndex = currentCandidateIndex,
            state = CandidateDownloadState.DOWNLOADING,
            progressMessage = progressMessage,
            progressPercent = progressPercent,
            errorMessage = null,
            targetPlaylistId = targetPlaylistId
        )

        fun conflict(
            id: String,
            source: ActiveDownloadSource,
            displayTitle: String,
            displayArtist: String,
            artworkUrl: String?,
            candidates: List<OnlineCatalogTrack>,
            currentCandidateIndex: Int = 0,
            targetPlaylistId: Long? = null
        ): ActiveDownload = ActiveDownload(
            id = id,
            source = source,
            displayTitle = displayTitle,
            displayArtist = displayArtist,
            artworkUrl = artworkUrl,
            candidates = candidates,
            currentCandidateIndex = currentCandidateIndex,
            state = CandidateDownloadState.IDLE,
            progressMessage = "Conflicto: ya está en la biblioteca",
            progressPercent = 0,
            errorMessage = null,
            targetPlaylistId = targetPlaylistId
        )

        fun success(
            id: String,
            source: ActiveDownloadSource,
            song: Song,
            displayTitle: String,
            displayArtist: String,
            artworkUrl: String?,
            candidates: List<OnlineCatalogTrack>,
            currentCandidateIndex: Int = 0,
            targetPlaylistId: Long? = null
        ): ActiveDownload = ActiveDownload(
            id = id,
            source = source,
            displayTitle = song.title.ifBlank { displayTitle }.ifBlank { "Descarga" },
            displayArtist = song.artist.ifBlank { displayArtist },
            artworkUrl = song.artworkUri ?: artworkUrl,
            candidates = candidates,
            currentCandidateIndex = currentCandidateIndex,
            state = CandidateDownloadState.SUCCESS,
            progressMessage = "Descargada",
            progressPercent = 100,
            errorMessage = null,
            targetPlaylistId = targetPlaylistId,
            resultSongId = song.id
        )

        fun error(
            id: String,
            source: ActiveDownloadSource,
            displayTitle: String,
            displayArtist: String,
            artworkUrl: String?,
            candidates: List<OnlineCatalogTrack>,
            errorMessage: String,
            currentCandidateIndex: Int = 0,
            targetPlaylistId: Long? = null
        ): ActiveDownload = ActiveDownload(
            id = id,
            source = source,
            displayTitle = displayTitle,
            displayArtist = displayArtist,
            artworkUrl = artworkUrl,
            candidates = candidates,
            currentCandidateIndex = currentCandidateIndex,
            state = CandidateDownloadState.ERROR,
            progressMessage = null,
            progressPercent = 0,
            errorMessage = errorMessage,
            targetPlaylistId = targetPlaylistId
        )

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



