package com.bestiapop.android.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.TemporaryPreferencesDataStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
class IdentifyWorkStoreIntegrationTest {

    @Test
    fun remainingIds_surviveRestartAndClear() = runTest {
        val storage = TemporaryPreferencesDataStore(
            ApplicationProvider.getApplicationContext(),
            "identify-work"
        )
        try {
            val store = IdentifyWorkStore(storage.dataStore)
            store.save(
                IdentifyWorkSnapshot(
                    remainingSongIds = listOf(4L, 5L),
                    force = true,
                    processedCount = 1,
                    totalCount = 3,
                    interrupted = true
                )
            )
            storage.restart()
            val restored = IdentifyWorkStore(storage.dataStore).load()
            checkNotNull(restored)
            assertEquals(listOf(4L, 5L), restored.remainingSongIds)
            assertTrue(restored.force)
            assertTrue(restored.interrupted)

            IdentifyWorkStore(storage.dataStore).clear()
            storage.restart()
            assertNull(IdentifyWorkStore(storage.dataStore).load())
        } finally {
            storage.close()
        }
    }
}
