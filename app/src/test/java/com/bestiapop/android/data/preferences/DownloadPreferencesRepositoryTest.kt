package com.bestiapop.android.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.TemporaryPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class DownloadPreferencesRepositoryTest {

    @Test
    fun meteredPolicyAndSeparateByteTotals_surviveColdStart() = runTest {
        val storage = TemporaryPreferencesDataStore(
            ApplicationProvider.getApplicationContext(),
            "download-preferences"
        )
        try {
            val repository = DownloadPreferencesRepository(storage.dataStore)
            repository.setDownloadOnMeteredNetwork(false)
            repository.addDownloadedBytes(byteCount = 1_500L, metered = true)
            repository.addDownloadedBytes(byteCount = 250L, metered = true)
            repository.addDownloadedBytes(byteCount = 4_096L, metered = false)

            val expected = DownloadSettings(
                downloadOnMeteredNetwork = false,
                totalMeteredBytes = 1_750L,
                totalUnmeteredBytes = 4_096L
            )
            assertEquals(expected, repository.settingsFlow.first())

            storage.restart()

            val restored = DownloadPreferencesRepository(storage.dataStore)
                .settingsFlow
                .first()
            assertEquals(expected, restored)
        } finally {
            storage.close()
        }
    }
}
