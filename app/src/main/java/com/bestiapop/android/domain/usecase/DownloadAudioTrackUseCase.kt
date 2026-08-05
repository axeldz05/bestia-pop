package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.repository.IMusicRepository

class DownloadAudioTrackUseCase(private val repository: IMusicRepository) {

    suspend fun execute(
        track: OnlineCatalogTrack,
        onProgress: ((String) -> Unit)? = null,
        conflictPolicy: com.bestiapop.android.data.model.DownloadConflictPolicy? = null
    ): Result<Song> {
        return try {
            val song = repository.downloadAndSaveOnlineTrack(track, onProgress, conflictPolicy)
            Result.success(song)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
