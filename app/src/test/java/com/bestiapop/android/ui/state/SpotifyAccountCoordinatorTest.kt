package com.bestiapop.android.ui.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.preferences.SpotifyPreferencesRepository
import com.bestiapop.android.testutil.FakeMusicRepository
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.TemporaryPreferencesDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class SpotifyAccountCoordinatorTest {
    @Test
    fun handleAuthCallback_whenStateMismatches_rejectsWithCsrfErrorAndClearsPending() =
        runTest {
            val storage =
                TemporaryPreferencesDataStore(
                    ApplicationProvider.getApplicationContext(),
                    "spotify-coord-test-1",
                )
            try {
                val testDispatcher = StandardTestDispatcher(testScheduler)
                val spotifyPrefs = SpotifyPreferencesRepository(storage.dataStore)
                spotifyPrefs.savePendingAuthSession(
                    verifier = "my-test-verifier",
                    state = "expected-state-123",
                )

                val coordinator =
                    SpotifyAccountCoordinator(
                        scope = this,
                        repository = FakeMusicRepository(),
                        spotifyPrefs = spotifyPrefs,
                        enqueuePendingDownloads = { _, _, _ -> },
                        toast = {},
                        ioDispatcher = testDispatcher,
                    )
                advanceUntilIdle()

                // Intento con state alterado / ataque CSRF
                val job =
                    coordinator.handleAuthCallback(
                        code = "auth-code-abc",
                        state = "attacker-state-999",
                        error = null,
                    )
                job.join()
                advanceUntilIdle()

                val state = coordinator.uiState.value
                assertTrue(state is SpotifyAccountUiState.Disconnected)
                val disconnected = state as SpotifyAccountUiState.Disconnected
                assertTrue(disconnected.error?.contains("CSRF") == true)

                // Sesión pendiente fue purgada
                assertNull(spotifyPrefs.getPendingState())
                assertNull(spotifyPrefs.getPendingCodeVerifier())
            } finally {
                storage.close()
            }
        }

    @Test
    fun handleAuthCallback_whenErrorProvided_transitionsToDisconnectedAndClearsPending() =
        runTest {
            val storage =
                TemporaryPreferencesDataStore(
                    ApplicationProvider.getApplicationContext(),
                    "spotify-coord-test-2",
                )
            try {
                val testDispatcher = StandardTestDispatcher(testScheduler)
                val spotifyPrefs = SpotifyPreferencesRepository(storage.dataStore)
                spotifyPrefs.savePendingAuthSession(
                    verifier = "my-test-verifier",
                    state = "expected-state-123",
                )

                val coordinator =
                    SpotifyAccountCoordinator(
                        scope = this,
                        repository = FakeMusicRepository(),
                        spotifyPrefs = spotifyPrefs,
                        enqueuePendingDownloads = { _, _, _ -> },
                        toast = {},
                        ioDispatcher = testDispatcher,
                    )
                advanceUntilIdle()

                val job =
                    coordinator.handleAuthCallback(
                        code = null,
                        state = "expected-state-123",
                        error = "access_denied",
                    )
                job.join()
                advanceUntilIdle()

                val state = coordinator.uiState.value
                assertTrue(state is SpotifyAccountUiState.Disconnected)
                val disconnected = state as SpotifyAccountUiState.Disconnected
                assertTrue(disconnected.error?.contains("access_denied") == true)

                assertNull(spotifyPrefs.getPendingState())
                assertNull(spotifyPrefs.getPendingCodeVerifier())
            } finally {
                storage.close()
            }
        }
}
