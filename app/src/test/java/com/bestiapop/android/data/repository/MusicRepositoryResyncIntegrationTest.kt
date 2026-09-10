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
        assertEquals(1, firstCount.size)
        assertEquals(0, secondCount.size)
        assertEquals(managed.absolutePath, persisted.uriString)
        assertEquals("Fixture Artist", persisted.artist)
        assertEquals("Recovered", persisted.title)
    }

    @Test
    fun resyncAppManagedMusic_ignoresCorruptTrackNumberZeroDurationFiles() = runTest {
        files.create("08.______.mp3", byteArrayOf(1, 2, 3))
        val repository = MusicRepository(
            context = ApplicationProvider.getApplicationContext(),
            database = database.database,
            audioStore = TemporaryRepositoryFileStore(files.root),
            metadataSource = NoNetworkRepositoryMetadata,
            downloadRetryDelay = {}
        )

        val count = repository.resyncAppManagedMusic()
        assertEquals(0, count.size)
        assertEquals(0, database.musicDao.getAllSongs().size)
    }

    @Test
    fun pruneUnplayableCorruptSongs_removesZeroDurationZombieTrack() = runTest {
        val repository = MusicRepository(
            context = ApplicationProvider.getApplicationContext(),
            database = database.database,
            audioStore = TemporaryRepositoryFileStore(files.root),
            metadataSource = NoNetworkRepositoryMetadata,
            downloadRetryDelay = {}
        )
        val corruptSong = com.bestiapop.android.data.model.Song(
            id = 0,
            title = "08",
            artist = "Unknown Artist",
            album = "Unknown Album",
            genre = "Unknown",
            durationMs = 0L,
            artworkUri = null,
            uriString = "/storage/emulated/0/Music/BestiaPop/08.______.mp3",
            folderPath = "/storage/emulated/0/Music/BestiaPop",
            trackNumber = 8,
            year = 0,
            dateAdded = 1000L
        )
        database.musicDao.insertSong(corruptSong)
        assertEquals(1, database.musicDao.getAllSongs().size)

        val pruned = repository.pruneUnplayableCorruptSongs()
        assertEquals(1, pruned.size)
        assertEquals("08", pruned.single().title)
        assertEquals(0, database.musicDao.getAllSongs().size)
    }
}
