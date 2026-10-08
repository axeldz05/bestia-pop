package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.ImportedPlaylistData
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlaylistImportError
import com.bestiapop.android.data.model.PlaylistPlatform
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.testutil.FakeMusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkImportCoordinatorTest {
    private class CoordinatorFakeRepo : FakeMusicRepository() {
        val songsFlow = MutableStateFlow<List<Song>>(emptyList())
        override val allSongsFlow: Flow<List<Song>> = songsFlow

        override suspend fun createPlaylist(
            name: String,
            description: String?,
            coverUri: String?,
        ): Long = 77L
    }

    @Test
    fun inspectLink_whenBlank_setsIdle() =
        runTest {
            val repo = CoordinatorFakeRepo()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                LinkImportCoordinator(
                    scope = this,
                    repository = repo,
                    enqueuePendingDownloads = { _, _, _ -> },
                    downloadOnlineTrack = { _, _ -> },
                    isOnline = { true },
                    toast = {},
                    ioDispatcher = testDispatcher,
                )

            coordinator.inspectLink("   ")
            advanceUntilIdle()

            assertEquals(LinkImportUiState.Idle, coordinator.uiState.value)
        }

    @Test
    fun inspectLink_whenOffline_emitsNetworkError() =
        runTest {
            val repo = CoordinatorFakeRepo()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                LinkImportCoordinator(
                    scope = this,
                    repository = repo,
                    enqueuePendingDownloads = { _, _, _ -> },
                    downloadOnlineTrack = { _, _ -> },
                    isOnline = { false },
                    toast = {},
                    ioDispatcher = testDispatcher,
                )

            coordinator.inspectLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
            advanceUntilIdle()

            val state = coordinator.uiState.value
            assertTrue("Expected Error state but was $state", state is LinkImportUiState.Error)
            val error = (state as LinkImportUiState.Error).error
            assertTrue(error is PlaylistImportError.NetworkError)
        }

    @Test
    fun inspectLink_unsupportedUrl_emitsUnsupportedUrlError() =
        runTest {
            val repo = CoordinatorFakeRepo()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                LinkImportCoordinator(
                    scope = this,
                    repository = repo,
                    enqueuePendingDownloads = { _, _, _ -> },
                    downloadOnlineTrack = { _, _ -> },
                    isOnline = { true },
                    toast = {},
                    ioDispatcher = testDispatcher,
                )

            coordinator.inspectLink("https://unknown.com/playlist/123")
            advanceUntilIdle()

            val state = coordinator.uiState.value
            assertEquals(LinkImportUiState.Error(PlaylistImportError.UnsupportedUrl), state)
        }

    @Test
    fun clear_resetsStateToIdle() =
        runTest {
            val repo = CoordinatorFakeRepo()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                LinkImportCoordinator(
                    scope = this,
                    repository = repo,
                    enqueuePendingDownloads = { _, _, _ -> },
                    downloadOnlineTrack = { _, _ -> },
                    isOnline = { false },
                    toast = {},
                    ioDispatcher = testDispatcher,
                )

            coordinator.inspectLink("https://invalid")
            advanceUntilIdle()
            assertTrue(coordinator.uiState.value is LinkImportUiState.Error)

            coordinator.clear()
            assertEquals(LinkImportUiState.Idle, coordinator.uiState.value)
        }

    @Test
    fun inspectLink_directAudioUrl_emitsSingleTrackPreview() =
        runTest {
            val repo = CoordinatorFakeRepo()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                LinkImportCoordinator(
                    scope = this,
                    repository = repo,
                    enqueuePendingDownloads = { _, _, _ -> },
                    downloadOnlineTrack = { _, _ -> },
                    isOnline = { true },
                    toast = {},
                    ioDispatcher = testDispatcher,
                )

            coordinator.inspectLink("https://example.com/music/test-song.mp3")
            advanceUntilIdle()

            val state = coordinator.uiState.value
            assertTrue("Expected SingleTrackPreview but was $state", state is LinkImportUiState.SingleTrackPreview)
            val preview = state as LinkImportUiState.SingleTrackPreview
            assertEquals("test-song.mp3", preview.track.title)
        }

    @Test
    fun downloadSingleTrackFromPreview_triggersCallbackAndResetsIdle() =
        runTest {
            val repo = CoordinatorFakeRepo()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            var downloadedTrackTitle: String? = null
            var downloadedSource: ActiveDownloadSource? = null

            val coordinator =
                LinkImportCoordinator(
                    scope = this,
                    repository = repo,
                    enqueuePendingDownloads = { _, _, _ -> },
                    downloadOnlineTrack = { track, source ->
                        downloadedTrackTitle = track.title
                        downloadedSource = source
                    },
                    isOnline = { true },
                    toast = {},
                    ioDispatcher = testDispatcher,
                )

            coordinator.inspectLink("https://example.com/music/test-song.mp3")
            advanceUntilIdle()

            coordinator.downloadSingleTrackFromPreview()

            assertEquals("test-song.mp3", downloadedTrackTitle)
            assertEquals(ActiveDownloadSource.LINK, downloadedSource)
            assertEquals(LinkImportUiState.Idle, coordinator.uiState.value)
        }
}
