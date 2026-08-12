package com.bestiapop.android.service

import android.content.Context
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.DuplicateSongException
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.ConnectivityObserver
import com.bestiapop.android.data.preferences.DownloadPreferencesRepository
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.domain.usecase.DownloadAudioTrackUseCase
import com.bestiapop.android.domain.util.TrackMatchKeys
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Process-owned subset of the download pipeline used by Guardar al escuchar.
 *
 * Execution survives the ViewModel and shares process-wide claims, permits, queue, persistence, and
 * cancellation with manual/catalog downloads through [ProcessDownloadCoordinator].
 */
internal class ProcessSaveWhileListeningCoordinator(
    private val scope: CoroutineScope,
    private val processDownloads: ProcessDownloadCoordinator,
    private val dependencies: Dependencies
) : PlaybackRuntimeSaveDownloads {
    internal data class Dependencies(
        val findSong: suspend (artist: String, title: String) -> Song?,
        val download: suspend (
            track: OnlineCatalogTrack,
            onProgress: (DownloadPhase) -> Unit
        ) -> Result<Song>,
        val isMetered: () -> Boolean,
        val downloadOnMeteredNetwork: suspend () -> Boolean
    )

    constructor(
        context: Context,
        scope: CoroutineScope,
        repository: MusicRepository,
        connectivity: ConnectivityObserver,
        processDownloads: ProcessDownloadCoordinator
    ) : this(
        scope = scope,
        processDownloads = processDownloads,
        dependencies = productionDependencies(context, repository, connectivity)
    )

    override val downloads: StateFlow<List<ActiveDownload>> = processDownloads.downloads
        .map { rows -> rows.filter { it.source == ActiveDownloadSource.SAVE_WHILE_LISTENING } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun save(remote: PlayableItem.Remote): SaveWhileListeningDownloadResult {
        val id = TrackMatchKeys.downloadIdFor(remote.artist, remote.title)
        if (id.isBlank()) {
            return SaveWhileListeningDownloadResult.Failed(
                IllegalArgumentException("Identidad de canción incompleta")
            )
        }
        dependencies.findSong(remote.artist, remote.title)?.let {
            return SaveWhileListeningDownloadResult.Saved(it)
        }

        val track = remote.toOnlineCatalogTrack()
        return try {
            when (
                val coordinated = processDownloads.execute(
                    downloadId = id,
                    artist = remote.artist,
                    title = remote.title,
                    onRegistered = {
                        processDownloads.upsert(
                            ActiveDownload.queued(
                                id = id,
                                source = ActiveDownloadSource.SAVE_WHILE_LISTENING,
                                candidates = listOf(track)
                            )
                        )
                    }
                ) {
                    // A track may enter the library while this autosave waits for the global permit.
                    dependencies.findSong(remote.artist, remote.title)?.let { existing ->
                        processDownloads.remove(id)
                        return@execute Result.success(existing)
                    }
                    if (dependencies.isMetered() &&
                        !dependencies.downloadOnMeteredNetwork()
                    ) {
                        val message = DownloadMessages.blockedOnMetered
                        processDownloads.update(id) {
                            ActiveDownload.error(
                                id = id,
                                source = ActiveDownloadSource.SAVE_WHILE_LISTENING,
                                candidates = listOf(track),
                                errorMessage = message,
                                targetPlaylistId = it.targetPlaylistId
                            )
                        }
                        return@execute Result.failure(IllegalStateException(message))
                    }
                    processDownloads.update(id) {
                        ActiveDownload.downloading(
                            id = id,
                            source = ActiveDownloadSource.SAVE_WHILE_LISTENING,
                            candidates = listOf(track),
                            targetPlaylistId = it.targetPlaylistId
                        )
                    }
                    val result = dependencies.download(track) { phase ->
                        processDownloads.update(id) {
                            it.copy(
                                state = CandidateDownloadState.DOWNLOADING,
                                progressMessage = phase.userMessage,
                                progressPercent = phase.percent
                            )
                        }
                    }
                    result.fold(
                        onSuccess = { song ->
                            val targetPlaylistId = processDownloads.downloads.value
                                .firstOrNull { it.id == id }
                                ?.targetPlaylistId
                            processDownloads.update(id) {
                                ActiveDownload.success(
                                    id = id,
                                    source = ActiveDownloadSource.SAVE_WHILE_LISTENING,
                                    song = song,
                                    candidates = listOf(track),
                                    targetPlaylistId = targetPlaylistId
                                )
                            }
                            processDownloads.recordCompletedDownload(song)
                        },
                        onFailure = { error ->
                            processDownloads.update(id) {
                                ActiveDownload.error(
                                    id = id,
                                    source = ActiveDownloadSource.SAVE_WHILE_LISTENING,
                                    candidates = listOf(track),
                                    errorMessage = mapError(error),
                                    targetPlaylistId = it.targetPlaylistId
                                )
                            }
                        }
                    )
                    result
                }
            ) {
                is CoordinatedDownloadResult.Completed -> coordinated.result.fold(
                    onSuccess = SaveWhileListeningDownloadResult::Saved,
                    onFailure = SaveWhileListeningDownloadResult::Failed
                )
                is CoordinatedDownloadResult.AlreadyRunning ->
                    SaveWhileListeningDownloadResult.InFlight(coordinated.downloadId)
            }
        } catch (cancelled: CancellationException) {
            processDownloads.update(id) {
                it.copy(
                    state = CandidateDownloadState.ERROR,
                    progressMessage = null,
                    progressPercent = 0,
                    errorMessage = DownloadMessages.interrupted
                )
            }
            throw cancelled
        }
    }

    override fun dismiss(id: String) {
        processDownloads.dismiss(id)
    }

    private fun mapError(error: Throwable): String = when (error) {
        is DuplicateSongException ->
            "Ya existe en la biblioteca: ${error.existing.artist} — ${error.existing.title}"
        else -> DownloadMessages.downloadFailed(error.localizedMessage ?: "Error de red.")
    }

    companion object {
        private fun productionDependencies(
            context: Context,
            repository: MusicRepository,
            connectivity: ConnectivityObserver
        ): Dependencies {
            val useCase = DownloadAudioTrackUseCase(repository)
            val preferences = DownloadPreferencesRepository(context)
            return Dependencies(
                findSong = repository::findSongByArtistTitle,
                download = { track, onProgress -> useCase.execute(track, onProgress) },
                isMetered = connectivity::isMetered,
                downloadOnMeteredNetwork = {
                    preferences.settingsFlow.first().downloadOnMeteredNetwork
                }
            )
        }
    }
}
