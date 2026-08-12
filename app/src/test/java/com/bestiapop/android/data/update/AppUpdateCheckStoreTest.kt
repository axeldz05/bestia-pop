package com.bestiapop.android.data.update

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.TemporaryPreferencesDataStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class AppUpdateCheckStoreTest {

    @Test
    fun launchThrottleTimestampAndVersionNotes_areAvailableAfterColdStart() = runTest {
        val storage = TemporaryPreferencesDataStore(
            ApplicationProvider.getApplicationContext(),
            "app-update"
        )
        try {
            val repository = AppUpdateCheckStore(storage.dataStore)
            val completedCheckAt = 1_786_503_600_000L
            repository.setLastCheckAtMs(completedCheckAt)
            repository.setCachedNotes(
                versionName = "2.4.1",
                notes = "Mejoras de continuidad y descargas."
            )

            storage.restart()

            val restored = AppUpdateCheckStore(storage.dataStore)
            assertEquals(completedCheckAt, restored.lastCheckAtMs())
            assertEquals(
                "Mejoras de continuidad y descargas.",
                restored.cachedNotes("2.4.1")
            )
            assertNull(restored.cachedNotes("2.4.0"))
        } finally {
            storage.close()
        }
    }
}
