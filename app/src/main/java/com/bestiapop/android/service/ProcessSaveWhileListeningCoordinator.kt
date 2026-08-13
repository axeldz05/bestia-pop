package com.bestiapop.android.service

import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.DownloadLane
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.forLane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val runtime: ProcessDownloadRuntime
) : PlaybackRuntimeSaveDownloads {
    override val downloads: StateFlow<List<ActiveDownload>> = runtime.downloads
        .map { rows -> rows.forLane(DownloadLane.AUTOSAVE) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun save(
        remote: PlayableItem.Remote
    ): SaveWhileListeningDownloadResult = runtime.saveWhileListening(remote)

    override fun dismiss(id: String) {
        runtime.dismiss(id)
    }
}
