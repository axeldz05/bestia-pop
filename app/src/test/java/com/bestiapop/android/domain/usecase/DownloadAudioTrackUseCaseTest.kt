package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.DownloadConflictPolicy
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.testutil.FakeMusicRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DownloadAudioTrackUseCaseTest {

    private val track = OnlineCatalogTrack(
        identity = TrackIdentity(title = "Song", artist = "Artist", album = "Album"),
        id = "yt-1",
        provider = "YouTube",
        audioUrl = "https://example.com/audio"
    )

    @Test
    fun execute_success_forwardsPhasesAndSong() = runBlocking {
        val phases = mutableListOf<DownloadPhase>()
        val song = Song(
            id = 7L,
            uriString = "file:///song",
            title = "Song",
            artist = "Artist",
            album = "Album"
        )
        val useCase = DownloadAudioTrackUseCase(
            DownloadRepo { onProgress, _ ->
                onProgress?.invoke(DownloadPhase.Searching)
                onProgress?.invoke(DownloadPhase.Downloading("Song"))
                song
            }
        )

        val result = useCase.execute(track, onProgress = { phases.add(it) })

        assertTrue(result.isSuccess)
        assertEquals(7L, result.getOrNull()?.id)
        assertEquals(
            listOf(DownloadPhase.Searching, DownloadPhase.Downloading("Song")),
            phases
        )
    }

    @Test
    fun execute_repositoryException_becomesFailure() = runBlocking {
        val useCase = DownloadAudioTrackUseCase(
            DownloadRepo { _, _ -> error("cdn 403") }
        )

        val result = useCase.execute(track)

        assertTrue(result.isFailure)
        assertEquals("cdn 403", result.exceptionOrNull()?.message)
    }

    @Test
    fun execute_cancellation_isRethrown() = runBlocking {
        val useCase = DownloadAudioTrackUseCase(
            DownloadRepo { _, _ -> throw CancellationException("dismissed") }
        )

        try {
            useCase.execute(track)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("dismissed", e.message)
        }
    }

    @Test
    fun execute_forwardsConflictPolicy() = runBlocking {
        var seenPolicy: DownloadConflictPolicy? = null
        val useCase = DownloadAudioTrackUseCase(
            DownloadRepo { _, policy ->
                seenPolicy = policy
                Song(id = 1L, uriString = "file:///x", title = "T", artist = "A", album = "B")
            }
        )

        useCase.execute(track, conflictPolicy = DownloadConflictPolicy.Overwrite(42L))

        assertEquals(DownloadConflictPolicy.Overwrite(42L), seenPolicy)
    }

    private class DownloadRepo(
        private val download: suspend (
            ((DownloadPhase) -> Unit)?,
            DownloadConflictPolicy?
        ) -> Song
    ) : FakeMusicRepository() {
        override suspend fun downloadAndSaveOnlineTrack(
            track: OnlineCatalogTrack,
            onProgress: ((DownloadPhase) -> Unit)?,
            conflictPolicy: DownloadConflictPolicy?
        ): Song = download(onProgress, conflictPolicy)
    }
}
