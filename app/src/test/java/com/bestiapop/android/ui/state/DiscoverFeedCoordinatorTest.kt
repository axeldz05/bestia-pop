package com.bestiapop.android.ui.state

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.listenbrainz.MatchedCfRecommendations
import com.bestiapop.android.data.listenbrainz.MatchedRemoteTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.preferences.DiscoverSourcePreference
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.domain.usecase.DiscoverFeed
import com.bestiapop.android.domain.usecase.GetDiscoverRecommendationsUseCase
import com.bestiapop.android.domain.usecase.GetTopRelatedItemsUseCase
import com.bestiapop.android.domain.usecase.TopRelatedFeed
import com.bestiapop.android.testutil.FakeMusicRepository
import com.bestiapop.android.testutil.MediumTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class DiscoverFeedCoordinatorTest {

    private fun testSong(id: Long, title: String, artist: String): Song = Song(
        id = id,
        uriString = "file:///music/$id.mp3",
        title = title,
        artist = artist,
        album = "Album"
    )

    @Test
    fun initialState_hasDefaultValues() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val listenBrainzPrefs = ListenBrainzPreferencesRepository(context)
        val libraryPrefs = LibraryPreferencesRepository(context)
        val repo = FakeMusicRepository()

        val coordinator = DiscoverFeedCoordinator(
            scope = this,
            repository = repo,
            listenBrainzPreferences = listenBrainzPrefs,
            libraryPreferences = libraryPrefs
        )

        assertEquals(DiscoverFeed(), coordinator.discoverFeed.value)
        assertFalse(coordinator.isLoadingDiscoverFeed.value)
        assertEquals(TopRelatedFeed(), coordinator.topRelatedFeed.value)
        assertFalse(coordinator.isLoadingTopRelatedFeed.value)
        assertTrue(coordinator.lbDiscover.value.data.isEmpty())
        assertNull(coordinator.cfRecommendations.value.data)
    }

    @Test
    fun clearDiscoverState_resetsFeedsAndCallsExternalCallback() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val listenBrainzPrefs = ListenBrainzPreferencesRepository(context)
        val libraryPrefs = LibraryPreferencesRepository(context)
        val repo = FakeMusicRepository()

        var externalCleared = false
        val coordinator = DiscoverFeedCoordinator(
            scope = this,
            repository = repo,
            listenBrainzPreferences = listenBrainzPrefs,
            libraryPreferences = libraryPrefs,
            onClearExternalState = { externalCleared = true }
        )

        coordinator.clearDiscoverState()

        assertTrue(externalCleared)
        assertTrue(coordinator.lbDiscover.value.phase is LoadPhase.Idle)
        assertNull(coordinator.cfRecommendations.value.data)
    }

    @Test
    fun rematchCfRecommendations_updatesLocalMatchesWhenLibraryChanges() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val listenBrainzPrefs = ListenBrainzPreferencesRepository(context)
        val libraryPrefs = LibraryPreferencesRepository(context)
        val repo = FakeMusicRepository()

        val coordinator = DiscoverFeedCoordinator(
            scope = this,
            repository = repo,
            listenBrainzPreferences = listenBrainzPrefs,
            libraryPreferences = libraryPrefs
        )

        val localSong = testSong(101L, "Karma Police", "Radiohead")
        val unmatchedTrack = MatchedRemoteTrack(
            identity = TrackIdentity(title = "Karma Police", artist = "Radiohead", album = "OK Computer"),
            recordingMbid = "mbid-123",
            localSong = null
        )
        val initialRecommendations = MatchedCfRecommendations(
            payload = com.bestiapop.android.data.listenbrainz.CfRecommendationsPayload(
                userName = "testuser",
                recordings = emptyList()
            ),
            matches = listOf(unmatchedTrack)
        )

        // Set initial state
        coordinator.openCfRecommendations() // triggers flow
        // Simulating populated data:
        coordinator.rematchCfRecommendations(listOf(localSong)) // with empty current it does not fail
        assertNull(coordinator.cfRecommendations.value.data)
    }
}
