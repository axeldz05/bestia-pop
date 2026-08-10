package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.DownloadConflictPolicy
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.repository.IMusicRepository

class DownloadAudioTrackUseCase(private val repository: IMusicRepository) {

    suspend fun execute(
        track: OnlineCatalogTrack,
        onProgress: ((DownloadPhase) -> Unit)? = null,
        conflictPolicy: DownloadConflictPolicy? = null
    ): Result<Song> {
        return try {
            val song = repository.downloadAndSaveOnlineTrack(track, onProgress, conflictPolicy)
            Result.success(song)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
