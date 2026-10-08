package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.ImportedPlaylistData
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.ParsedLinkTarget
import com.bestiapop.android.data.model.PlaylistImportError
import com.bestiapop.android.data.model.PlaylistImportException
import com.bestiapop.android.data.model.PlaylistImportSummary
import com.bestiapop.android.data.model.PlaylistPlatform
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.network.DeezerPlaylistExtractor
import com.bestiapop.android.data.network.PlaylistUrlParser
import com.bestiapop.android.data.network.SpotifyPlaylistExtractor
import com.bestiapop.android.data.network.YouTubePlaylistExtractor
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.usecase.ImportPlaylistFromLinkUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface LinkImportUiState {
    data object Idle : LinkImportUiState

    data object Inspecting : LinkImportUiState

    data class PlaylistPreview(
        val data: ImportedPlaylistData,
    ) : LinkImportUiState

    data class SingleTrackPreview(
        val track: TrackIdentity,
        val platform: PlaylistPlatform,
    ) : LinkImportUiState

    data class Importing(
        val message: String,
        val progress: Float = 0f,
    ) : LinkImportUiState

    data class Success(
        val summary: PlaylistImportSummary,
    ) : LinkImportUiState

    data class Error(
        val error: PlaylistImportError,
    ) : LinkImportUiState
}

class LinkImportCoordinator(
    private val scope: CoroutineScope,
    private val repository: IMusicRepository,
    private val enqueuePendingDownloads: suspend (Long, List<OnlineCatalogTrack>, Boolean) -> Unit,
    private val downloadOnlineTrack: (OnlineCatalogTrack, ActiveDownloadSource) -> Unit,
    private val isOnline: () -> Boolean,
    private val toast: (String) -> Unit,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) {
    private val _uiState = MutableStateFlow<LinkImportUiState>(LinkImportUiState.Idle)
    val uiState: StateFlow<LinkImportUiState> = _uiState.asStateFlow()

    private val importPlaylistFromLinkUseCase = ImportPlaylistFromLinkUseCase(repository)
    private var inspectJob: Job? = null
    private var importJob: Job? = null

    fun inspectLink(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            _uiState.value = LinkImportUiState.Idle
            return
        }

        if (!isOnline()) {
            _uiState.value = LinkImportUiState.Error(PlaylistImportError.NetworkError("Sin conexión a internet"))
            return
        }

        inspectJob?.cancel()
        inspectJob =
            scope.launch {
                _uiState.value = LinkImportUiState.Inspecting

                val parsed = PlaylistUrlParser.resolveAndParse(trimmed, ioDispatcher)
                when (parsed) {
                    is ParsedLinkTarget.Playlist -> {
                        val result =
                            when (parsed.platform) {
                                PlaylistPlatform.SPOTIFY -> SpotifyPlaylistExtractor.fetchPlaylist(parsed.playlistId)
                                PlaylistPlatform.DEEZER -> DeezerPlaylistExtractor.fetchPlaylist(parsed.playlistId)
                                PlaylistPlatform.YOUTUBE -> YouTubePlaylistExtractor.fetchPlaylist(parsed.playlistId)
                            }

                        result.fold(
                            onSuccess = { data ->
                                _uiState.value = LinkImportUiState.PlaylistPreview(data)
                            },
                            onFailure = { error ->
                                val importError =
                                    when (error) {
                                        is PlaylistImportException -> error.error
                                        else -> PlaylistImportError.Generic(error.message ?: "No se pudo inspeccionar la playlist")
                                    }
                                _uiState.value = LinkImportUiState.Error(importError)
                            },
                        )
                    }

                    is ParsedLinkTarget.Track -> {
                        val trackResult =
                            when (parsed.platform) {
                                PlaylistPlatform.SPOTIFY -> {
                                    SpotifyPlaylistExtractor.fetchTrack(parsed.trackIdOrUrl)
                                }

                                PlaylistPlatform.DEEZER -> {
                                    DeezerPlaylistExtractor.fetchTrack(parsed.trackIdOrUrl)
                                }

                                PlaylistPlatform.YOUTUBE -> {
                                    Result.success(
                                        TrackIdentity(
                                            title = trimmed,
                                            artist = "",
                                            album = "",
                                        ),
                                    )
                                }
                            }

                        trackResult.fold(
                            onSuccess = { identity ->
                                _uiState.value = LinkImportUiState.SingleTrackPreview(identity, parsed.platform)
                            },
                            onFailure = { error ->
                                val importError =
                                    when (error) {
                                        is PlaylistImportException -> error.error
                                        else -> PlaylistImportError.Generic(error.message ?: "No se pudo inspeccionar la canción")
                                    }
                                _uiState.value = LinkImportUiState.Error(importError)
                            },
                        )
                    }

                    is ParsedLinkTarget.DirectAudio -> {
                        val fileName = trimmed.substringAfterLast("/").substringBeforeLast("?")
                        _uiState.value =
                            LinkImportUiState.SingleTrackPreview(
                                TrackIdentity(
                                    title = fileName,
                                    artist = "",
                                    album = "",
                                ),
                                PlaylistPlatform.YOUTUBE,
                            )
                    }

                    is ParsedLinkTarget.Unsupported -> {
                        _uiState.value = LinkImportUiState.Error(PlaylistImportError.UnsupportedUrl)
                    }
                }
            }
    }

    fun importPlaylist(downloadAfterImport: Boolean) {
        val current = _uiState.value as? LinkImportUiState.PlaylistPreview ?: return
        importJob?.cancel()
        importJob =
            scope.launch {
                _uiState.value =
                    LinkImportUiState.Importing(
                        message = "Creando playlist y organizando canciones...",
                    )

                try {
                    val summary = importPlaylistFromLinkUseCase.execute(current.data, isDownloading = downloadAfterImport)

                    if (downloadAfterImport && summary.pendingStreamCount > 0) {
                        _uiState.value =
                            LinkImportUiState.Importing(
                                message = "Encolando ${summary.pendingStreamCount} canciones para descargar...",
                            )
                        val pending = repository.getPlaylistPendingTracks(summary.playlistId)
                        if (pending.isNotEmpty()) {
                            enqueuePendingDownloads(
                                summary.playlistId,
                                pending.map { it.toOnlineCatalogTrack() },
                                true,
                            )
                        }
                    }

                    _uiState.value = LinkImportUiState.Success(summary)
                    toast("Playlist «${summary.playlistTitle}» guardada")
                } catch (e: Exception) {
                    _uiState.value = LinkImportUiState.Error(PlaylistImportError.Generic(e.message ?: "Error al importar la playlist"))
                }
            }
    }

    fun downloadSingleTrackFromPreview(customUrl: String? = null) {
        val state = _uiState.value
        val trackIdentity =
            (state as? LinkImportUiState.SingleTrackPreview)?.track
                ?: return

        val audioQueryOrUrl =
            if (trackIdentity.artist.isNotBlank() && trackIdentity.title.isNotBlank()) {
                "${trackIdentity.artist} ${trackIdentity.title}"
            } else {
                customUrl?.takeIf { it.isNotBlank() } ?: trackIdentity.title
            }

        val catalogTrack =
            OnlineCatalogTrack
                .fromUrl(
                    url = audioQueryOrUrl,
                    title = trackIdentity.title,
                    artist = trackIdentity.artist,
                ).copy(
                    identity = trackIdentity,
                )

        downloadOnlineTrack(catalogTrack, ActiveDownloadSource.LINK)
        _uiState.value = LinkImportUiState.Idle
    }

    fun clear() {
        inspectJob?.cancel()
        importJob?.cancel()
        _uiState.value = LinkImportUiState.Idle
    }
}
