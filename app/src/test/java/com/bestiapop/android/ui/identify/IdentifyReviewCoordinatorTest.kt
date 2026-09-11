package com.bestiapop.android.ui.identify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.IdentifyReviewStore
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.service.ProcessIdentifyRuntime
import com.bestiapop.android.testutil.FakeMusicRepository
import com.bestiapop.android.testutil.MediumTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class IdentifyReviewCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repository: IMusicRepository = FakeMusicRepository()
    private lateinit var processIdentifyRuntime: ProcessIdentifyRuntime
    private lateinit var identifyReviewStore: IdentifyReviewStore
    private val rawSongs = MutableStateFlow<List<Song>>(emptyList())
    private val toastMessages = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        identifyReviewStore = IdentifyReviewStore(app)
        processIdentifyRuntime = ProcessIdentifyRuntime(
            scope = CoroutineScope(dispatcher),
            dependencies = ProcessIdentifyRuntime.Dependencies(
                getSong = { null },
                getSongs = { emptyList() },
                propose = { song, _, _ ->
                    com.bestiapop.android.data.model.IdentifyProposal(
                        songId = song.id,
                        queryTitle = song.title,
                        queryArtist = song.artist
                    )
                },
                apply = { _, _, _ -> com.bestiapop.android.data.model.IdentifyResult.NoMatch },
                listenBrainzToken = { null },
                pendingSongIds = { emptySet() },
                appendReview = { _, _ -> },
                loadWork = { null },
                saveWork = {},
                isOnline = { true },
                acquireExecutionLease = { AutoCloseable {} },
                loadScopedAlbumTracks = { _, _ -> null }
            )
        )
        toastMessages.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createCoordinator(customRepo: IMusicRepository = repository): IdentifyReviewCoordinator {
        return IdentifyReviewCoordinator(
            scope = CoroutineScope(dispatcher),
            repository = customRepo,
            identifyReviewStore = identifyReviewStore,
            processIdentifyRuntime = processIdentifyRuntime,
            rawSongs = rawSongs,
            awaitCatalogLoaded = {},
            clearCatalogPreview = {},
            toast = { toastMessages += it },
            uiAttached = { true },
            ioDispatcher = dispatcher
        )
    }

    @Test
    fun setupLifecycle_openUpdateDismiss() = runTest(dispatcher) {
        val coordinator = createCoordinator()
        assertNull(coordinator.identifySetup.value)

        val testSongs = listOf(
            Song(
                id = 1L,
                title = "Unknown Track",
                artist = "Unknown Artist",
                album = "Unknown Album",
                durationMs = 180_000L,
                trackNumber = 1,
                year = 2024,
                genre = "Rock",
                uriString = "content://media/audio/1"
            )
        )

        coordinator.openIdentifySetup(testSongs, contextTitle = "Setup Test")
        val setupState = coordinator.identifySetup.value
        assertNotNull(setupState)
        assertEquals(1, setupState?.songs?.size)
        assertEquals("Setup Test", setupState?.contextTitle)
        assertEquals(IdentifyApplyFields.ALL, setupState?.applyFields)

        val customFields = IdentifyApplyFields(
            title = false,
            artist = true,
            album = true,
            year = true,
            trackNumber = false,
            artwork = true
        )
        coordinator.setIdentifySetupFields(customFields)
        assertEquals(customFields, coordinator.identifySetup.value?.applyFields)

        coordinator.dismissIdentifySetup()
        assertNull(coordinator.identifySetup.value)
    }

    @Test
    fun searchDraftAndFilters_updateCorrectly() = runTest(dispatcher) {
        val coordinator = createCoordinator()

        coordinator.setIdentifySearchDraft("Pink Floyd")
        assertEquals("Pink Floyd", coordinator.identifyReview.value.searchQueryDraft)

        coordinator.setIdentifySearchFilterArtist("Pink Floyd")
        coordinator.setIdentifySearchFilterAlbum("The Dark Side of the Moon")
        coordinator.setIdentifySearchFilterYear("1973abc")

        assertEquals("Pink Floyd", coordinator.identifyReview.value.searchFilterArtist)
        assertEquals("The Dark Side of the Moon", coordinator.identifyReview.value.searchFilterAlbum)
        assertEquals("1973", coordinator.identifyReview.value.searchFilterYear)

        coordinator.toggleIdentifySearchField(show = true)
        assertTrue(coordinator.identifyReview.value.showSearchField)

        coordinator.toggleIdentifySearchFilters(show = true)
        assertTrue(coordinator.identifyReview.value.showSearchFilters)

        coordinator.toggleIdentifySearchField(show = false)
        assertFalse(coordinator.identifyReview.value.showSearchField)
        assertFalse(coordinator.identifyReview.value.showSearchFilters)
    }

    @Test
    fun albumCandidates_searchAndSelect() = runTest(dispatcher) {
        val fakeRepo = object : FakeMusicRepository() {
            override suspend fun searchAlbums(query: String): List<com.bestiapop.android.data.model.CatalogAlbum> {
                return listOf(
                    com.bestiapop.android.data.model.CatalogAlbum(
                        id = "a1",
                        title = "Absolution",
                        artist = "Muse",
                        coverUrl = "https://img.example/cover.jpg",
                        trackCount = 14,
                        releaseYear = "2003"
                    )
                )
            }
        }
        val coordinator = createCoordinator(customRepo = fakeRepo)

        val groupKey = "muse_absolution"

        coordinator.searchAlbumCandidates(groupKey, "Absolution")
        dispatcher.scheduler.advanceUntilIdle()

        val candidates = coordinator.identifyReview.value.albumGroupCandidates[groupKey]
        assertNotNull(candidates)
        assertEquals(1, candidates?.size)
        assertEquals("Absolution", candidates?.first()?.title)
        assertEquals(0, coordinator.identifyReview.value.albumGroupSelectedIndices[groupKey])

        coordinator.selectAlbumCandidate(groupKey, 2)
        assertEquals(2, coordinator.identifyReview.value.albumGroupSelectedIndices[groupKey])
    }
}

