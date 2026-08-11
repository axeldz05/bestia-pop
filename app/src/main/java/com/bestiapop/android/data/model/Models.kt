package com.bestiapop.android.data.model

enum class RepeatMode {
    OFF,
    ONE,
    ALL
}

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
    data class Updated(val songId: Long) : IdentifyResult()

    data object NoMatch : IdentifyResult()
    data object Skipped : IdentifyResult()
}

/** Ranked catalog hit for identify (top-N after multi-signal scoring). */
data class IdentifyCandidate(
    val track: OnlineCatalogTrack,
    val score: Float,
    val reasons: List<String> = emptyList()
) : TrackMeta by track {
    val provider: String get() = track.provider
}

enum class IdentifyConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}

/**
 * Lookup result for one library song before apply / review.
 * [alreadyIdentified] = song did not need identify (artist+album usable).
 */
data class IdentifyProposal(
    val songId: Long,
    val queryArtist: String,
    val queryTitle: String,
    val sourceHints: String? = null,
    val candidates: List<IdentifyCandidate> = emptyList(),
    val confidence: IdentifyConfidence = IdentifyConfidence.NONE,
    val suggested: IdentifyCandidate? = null,
    val alreadyIdentified: Boolean = false,
    /** True when ListenBrainz lookup ran and contributed a catalog track for this proposal. */
    val usedListenBrainz: Boolean = false
)

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

data class Artist(
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val photoUri: String? = null,
    val genre: String? = null,
    val dateAdded: Long? = null
)

/** Aggregated library genre row for browse chips (not catalog [CatalogGenre]). */
data class GenreGroup(
    val name: String,
    val songCount: Int,
    val artworkUri: String? = null,
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
    val identity: TrackIdentity,
    val id: Long = 0,
    val playlistId: Long,
    val recordingMbid: String? = null,
    val position: Int = 0
) : TrackMeta by identity {
    fun toOnlineCatalogTrack(): OnlineCatalogTrack =
        identity.toListenBrainzCatalogTrack(recordingMbid)
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
    val identity: TrackIdentity,
    val id: String,
    val audioUrl: String = "",
    val provider: String = "YouTube",
    val userAgent: String = DEFAULT_CATALOG_USER_AGENT
) : TrackMeta by identity {
    companion object {
        /** L2: flat catalog construction (identity is Level 1). */
        operator fun invoke(
            id: String,
            title: String,
            artist: String,
            album: String = "",
            artworkUri: String? = null,
            durationMs: Long = 0L,
            audioUrl: String = "",
            provider: String = "YouTube",
            userAgent: String = DEFAULT_CATALOG_USER_AGENT,
            trackNumber: Int = 0
        ): OnlineCatalogTrack = OnlineCatalogTrack(
            identity = TrackIdentity(
                title = title,
                artist = artist,
                album = album,
                artworkUri = artworkUri,
                durationMs = durationMs,
                trackNumber = trackNumber
            ),
            id = id,
            audioUrl = audioUrl,
            provider = provider,
            userAgent = userAgent
        )
    }
}


enum class CatalogCategory {
    SONGS,
    ALBUMS,
    PLAYLISTS,
    GENRES,
    CHARTS
}

/** Lightweight Deezer genre row for catalog browse (not a TrackIdentity). */
data class CatalogGenre(
    val id: Long,
    val name: String,
    val pictureUrl: String? = null
)

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
    val identity: TrackIdentity,
    val candidates: List<OnlineCatalogTrack>,
    val currentCandidateIndex: Int = 0,
    val isSelected: Boolean = true
) : TrackMeta by identity {
    val currentTrack: OnlineCatalogTrack?
        get() = candidates.getOrNull(currentCandidateIndex)
}

enum class ActiveDownloadSource {
    CATALOG,
    LINK,
    SAVE_WHILE_LISTENING,
    BATCH,
    LB_IMPORT,
    /** Manual download of a streamed Remote (Para Ti / Recomendados / Now Playing). */
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
    val candidates: List<OnlineCatalogTrack>,
    val currentCandidateIndex: Int,
    val targetPlaylistId: Long? = null,
    val applyToRemainingBatch: Boolean = false,
    val lookupIdentity: TrackIdentity? = null
)

/**
 * Unified in-memory download job for the Descargas center.
 * Shows QUEUED / DOWNLOADING / ERROR / IDLE (conflict) / SUCCESS (kept until dismissed).
 * Display metadata is [currentTrack] ([TrackMeta]); do not clone title/artist/art beside it.
 * [targetPlaylistId] — when set (e.g. LB_IMPORT / catalog playlist), success adds the song to that playlist.
 * [resultSongId] — set on SUCCESS so the UI can play the local song.
 */
data class ActiveDownload(
    val id: String,
    val source: ActiveDownloadSource,
    val candidates: List<OnlineCatalogTrack>,
    val currentCandidateIndex: Int = 0,
    val state: CandidateDownloadState = CandidateDownloadState.DOWNLOADING,
    val progressMessage: String? = null,
    val progressPercent: Int = 0,
    val errorMessage: String? = null,
    val targetPlaylistId: Long? = null,
    val resultSongId: Long? = null,
    /** Save As (or legacy displayTitle); never written into Room song identity. */
    val titleOverride: String? = null
) : TrackMeta {
    val currentTrack: OnlineCatalogTrack?
        get() = candidates.getOrNull(currentCandidateIndex)

    override val title: String get() = currentTrack?.title.orEmpty()
    override val artist: String get() = currentTrack?.artist.orEmpty()
    override val album: String get() = currentTrack?.album.orEmpty()
    override val artworkUri: String? get() = currentTrack?.artworkUri
    override val durationMs: Long get() = currentTrack?.durationMs ?: 0L
    override val trackNumber: Int get() = currentTrack?.trackNumber ?: 0

    /** UI/notif label; never persist this into [TrackIdentity] that goes to Room. */
    val displayLabel: String
        get() = titleOverride?.takeIf { it.isNotBlank() }
            ?: title.ifBlank {
                if (source == ActiveDownloadSource.LINK) "Enlace YouTube" else "Descarga"
            }

    fun withCurrentIdentity(transform: TrackIdentity.() -> TrackIdentity): ActiveDownload {
        val idx = currentCandidateIndex
        val track = currentTrack ?: return this
        return copy(
            candidates = candidates.mapIndexed { i, t ->
                if (i == idx) track.withIdentity(transform) else t
            }
        )
    }

    companion object {
        fun queued(
            id: String,
            source: ActiveDownloadSource,
            candidates: List<OnlineCatalogTrack>,
            currentCandidateIndex: Int = 0,
            targetPlaylistId: Long? = null,
            resultSongId: Long? = null,
            titleOverride: String? = null
        ): ActiveDownload = ActiveDownload(
            id = id,
            source = source,
            candidates = candidates,
            currentCandidateIndex = currentCandidateIndex,
            state = CandidateDownloadState.QUEUED,
            progressMessage = DownloadMessages.queued,
            progressPercent = 0,
            errorMessage = null,
            targetPlaylistId = targetPlaylistId,
            resultSongId = resultSongId,
            titleOverride = titleOverride
        )

        fun downloading(
            id: String,
            source: ActiveDownloadSource,
            candidates: List<OnlineCatalogTrack>,
            currentCandidateIndex: Int = 0,
            targetPlaylistId: Long? = null,
            progressMessage: String = DownloadMessages.starting,
            progressPercent: Int = 20,
            titleOverride: String? = null
        ): ActiveDownload = ActiveDownload(
            id = id,
            source = source,
            candidates = candidates,
            currentCandidateIndex = currentCandidateIndex,
            state = CandidateDownloadState.DOWNLOADING,
            progressMessage = progressMessage,
            progressPercent = progressPercent,
            errorMessage = null,
            targetPlaylistId = targetPlaylistId,
            titleOverride = titleOverride
        )

        fun conflict(
            id: String,
            source: ActiveDownloadSource,
            candidates: List<OnlineCatalogTrack>,
            currentCandidateIndex: Int = 0,
            targetPlaylistId: Long? = null,
            titleOverride: String? = null
        ): ActiveDownload = ActiveDownload(
            id = id,
            source = source,
            candidates = candidates,
            currentCandidateIndex = currentCandidateIndex,
            state = CandidateDownloadState.IDLE,
            progressMessage = DownloadMessages.conflictInLibrary,
            progressPercent = 0,
            errorMessage = null,
            targetPlaylistId = targetPlaylistId,
            titleOverride = titleOverride
        )

        fun success(
            id: String,
            source: ActiveDownloadSource,
            song: Song,
            candidates: List<OnlineCatalogTrack>,
            currentCandidateIndex: Int = 0,
            targetPlaylistId: Long? = null
        ): ActiveDownload {
            val idx = currentCandidateIndex.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
            val merged = candidates.mapIndexed { i, t ->
                if (i != idx) t
                else t.copy(identity = song.toIdentity().mergePreferring(t.identity))
            }
            return ActiveDownload(
                id = id,
                source = source,
                candidates = merged,
                currentCandidateIndex = idx,
                state = CandidateDownloadState.SUCCESS,
                progressMessage = DownloadMessages.downloadedShort,
                progressPercent = 100,
                errorMessage = null,
                targetPlaylistId = targetPlaylistId,
                resultSongId = song.id
            )
        }

        fun error(
            id: String,
            source: ActiveDownloadSource,
            candidates: List<OnlineCatalogTrack>,
            errorMessage: String,
            currentCandidateIndex: Int = 0,
            targetPlaylistId: Long? = null,
            titleOverride: String? = null
        ): ActiveDownload = ActiveDownload(
            id = id,
            source = source,
            candidates = candidates,
            currentCandidateIndex = currentCandidateIndex,
            state = CandidateDownloadState.ERROR,
            progressMessage = null,
            progressPercent = 0,
            errorMessage = errorMessage,
            targetPlaylistId = targetPlaylistId,
            titleOverride = titleOverride
        )

        /** Advance to the next YouTube match; expands via [newCandidates] when the list was a single placeholder. */
        fun withCycledCandidate(
            download: ActiveDownload,
            newCandidates: List<OnlineCatalogTrack>
        ): ActiveDownload {
            if (newCandidates.isEmpty()) return download
            val nextIndex = if (download.candidates.size <= 1 && newCandidates.size > 1) {
                val currentId = download.currentTrack?.id
                val alt = newCandidates.indexOfFirst { it.id != currentId }.takeIf { it >= 0 } ?: 0
                alt
            } else {
                (download.currentCandidateIndex + 1) % newCandidates.size
            }
            val stillFailed = download.state == CandidateDownloadState.ERROR
            return download.copy(
                candidates = newCandidates.mapIndexed { i, t ->
                    if (i == nextIndex) t.preferMetaFrom(download) else t
                },
                currentCandidateIndex = nextIndex,
                state = if (stillFailed) CandidateDownloadState.ERROR else CandidateDownloadState.IDLE,
                progressMessage = null,
                progressPercent = 0,
                errorMessage = if (stillFailed) "Match actualizado — tocá Reintentar" else null,
                titleOverride = null
            )
        }
    }
}



