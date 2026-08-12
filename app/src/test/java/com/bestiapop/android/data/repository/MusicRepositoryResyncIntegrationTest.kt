package com.bestiapop.android.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.RoomTestDatabaseRule
import com.bestiapop.android.testutil.TemporaryMusicFiles
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class MusicRepositoryResyncIntegrationTest {
    @get:Rule
    val database = RoomTestDatabaseRule()

    @get:Rule
    val files = TemporaryMusicFiles()

    @Test
    fun resyncAppManagedMusic_rebuildsOneRow_once() = runTest {
        val managed = files.create("Fixture Artist - Recovered.wav", byteArrayOf(1, 2, 3))
        val repository = MusicRepository(
            context = ApplicationProvider.getApplicationContext(),
            database = database.database,
            audioStore = TemporaryRepositoryFileStore(files.root),
            metadataSource = NoNetworkRepositoryMetadata,
            downloadRetryDelay = {}
        )

        val firstCount = repository.resyncAppManagedMusic()
        val secondCount = repository.resyncAppManagedMusic()

        val persisted = database.musicDao.getAllSongs().single()
        assertEquals(1, firstCount)
        assertEquals(0, secondCount)
        assertEquals(managed.absolutePath, persisted.uriString)
        assertEquals("Fixture Artist", persisted.artist)
        assertEquals("Recovered", persisted.title)
    }
}
