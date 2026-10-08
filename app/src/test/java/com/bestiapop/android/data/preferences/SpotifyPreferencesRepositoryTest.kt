package com.bestiapop.android.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.TemporaryPreferencesDataStore
import kotlinx.coroutines.flow.first
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
class SpotifyPreferencesRepositoryTest {
    @Test
    fun saveTokensAndUser_persistsDataAndCalculatesConnection() =
        runTest {
            val storage =
                TemporaryPreferencesDataStore(
                    ApplicationProvider.getApplicationContext(),
                    "spotify-prefs-test-1",
                )
            try {
                val repository = SpotifyPreferencesRepository(storage.dataStore)
                assertFalse(repository.getAuthData().isConnected)

                repository.saveTokens(
                    accessToken = "access-token-123",
                    refreshToken = "refresh-token-456",
                    expiresInSeconds = 3600L,
                )
                repository.saveUserProfile(displayName = "Test User", userId = "user-id-99")

                val auth = repository.getAuthData()
                assertTrue(auth.isConnected)
                assertFalse(auth.isTokenExpired)
                assertEquals("access-token-123", auth.accessToken)
                assertEquals("refresh-token-456", auth.refreshToken)
                assertEquals("Test User", auth.userDisplayName)
                assertEquals("user-id-99", auth.userId)

                repository.clearAuth()
                val cleared = repository.getAuthData()
                assertFalse(cleared.isConnected)
                assertNull(cleared.accessToken)
                assertNull(cleared.refreshToken)
                assertNull(cleared.userDisplayName)
            } finally {
                storage.close()
            }
        }

    @Test
    fun pendingAuthSession_savesAndClearsProperly() =
        runTest {
            val storage =
                TemporaryPreferencesDataStore(
                    ApplicationProvider.getApplicationContext(),
                    "spotify-prefs-test-2",
                )
            try {
                val repository = SpotifyPreferencesRepository(storage.dataStore)
                assertNull(repository.getPendingCodeVerifier())
                assertNull(repository.getPendingState())

                repository.savePendingAuthSession(
                    verifier = "verifier-xyz",
                    state = "state-abc",
                )

                assertEquals("verifier-xyz", repository.getPendingCodeVerifier())
                assertEquals("state-abc", repository.getPendingState())

                repository.clearPendingAuthSession()
                assertNull(repository.getPendingCodeVerifier())
                assertNull(repository.getPendingState())
            } finally {
                storage.close()
            }
        }
}
