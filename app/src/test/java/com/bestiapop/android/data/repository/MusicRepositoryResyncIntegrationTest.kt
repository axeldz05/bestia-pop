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
    fun resyncAppManagedMusic_rebuildsOneRow_once() =
        runTest {
            val managed = files.create("Fixture Artist - Recovered.wav", byteArrayOf(1, 2, 3))
            val repository =
                MusicRepository(
                    context = ApplicationProvider.getApplicationContext(),
                    database = database.database,
                    audioStore = TemporaryRepositoryFileStore(files.root),
                    metadataSource = NoNetworkRepositoryMetadata,
                    downloadRetryDelay = {},
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
    fun resyncAppManagedMusic_ignoresCorruptTrackNumberZeroDurationFiles() =
        runTest {
            files.create("08.______.mp3", byteArrayOf(1, 2, 3))
            val repository =
                MusicRepository(
                    context = ApplicationProvider.getApplicationContext(),
                    database = database.database,
                    audioStore = TemporaryRepositoryFileStore(files.root),
                    metadataSource = NoNetworkRepositoryMetadata,
                    downloadRetryDelay = {},
                )

            val count = repository.resyncAppManagedMusic()
            assertEquals(0, count.size)
            assertEquals(0, database.musicDao.getAllSongs().size)
        }

    @Test
    fun pruneUnplayableCorruptSongs_removesZeroDurationZombieTrack() =
        runTest {
            val repository =
                MusicRepository(
                    context = ApplicationProvider.getApplicationContext(),
                    database = database.database,
                    audioStore = TemporaryRepositoryFileStore(files.root),
                    metadataSource = NoNetworkRepositoryMetadata,
                    downloadRetryDelay = {},
                )
            val corruptSong =
                com.bestiapop.android.data.model.Song(
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
                    dateAdded = 1000L,
                )
            database.musicDao.insertSong(corruptSong)
            assertEquals(1, database.musicDao.getAllSongs().size)

            val pruned = repository.pruneUnplayableCorruptSongs()
            assertEquals(1, pruned.size)
            assertEquals("08", pruned.single().title)
            assertEquals(0, database.musicDao.getAllSongs().size)
        }

    @Test
    fun pruneUnplayableCorruptSongs_removesLocalSongWithoutPhysicalFile() =
        runTest {
            val repository =
                MusicRepository(
                    context = ApplicationProvider.getApplicationContext(),
                    database = database.database,
                    audioStore = TemporaryRepositoryFileStore(files.root),
                    metadataSource = NoNetworkRepositoryMetadata,
                    downloadRetryDelay = {},
                )
            val missingSong =
                com.bestiapop.android.data.model.Song(
                    id = 0,
                    title = "Missing Track",
                    artist = "Artist",
                    album = "Album",
                    genre = "Rock",
                    durationMs = 180_000L,
                    artworkUri = null,
                    uriString = "${files.root.absolutePath}/non_existent_file.mp3",
                    folderPath = files.root.absolutePath,
                    trackNumber = 1,
                    year = 2024,
                    dateAdded = 1000L,
                )
            database.musicDao.insertSong(missingSong)
            assertEquals(1, database.musicDao.getAllSongs().size)

            val pruned = repository.pruneUnplayableCorruptSongs()
            assertEquals(1, pruned.size)
            assertEquals("Missing Track", pruned.single().title)
            assertEquals(0, database.musicDao.getAllSongs().size)
        }

    @Test
    fun pruneUnplayableCorruptSongs_keepsLocalSongWithExistingPhysicalFile() =
        runTest {
            val file = files.create("Artist - Present.mp3", byteArrayOf(1, 2, 3, 4, 5))
            val repository =
                MusicRepository(
                    context = ApplicationProvider.getApplicationContext(),
                    database = database.database,
                    audioStore = TemporaryRepositoryFileStore(files.root),
                    metadataSource = NoNetworkRepositoryMetadata,
                    downloadRetryDelay = {},
                )
            val presentSong =
                com.bestiapop.android.data.model.Song(
                    id = 0,
                    title = "Present Track",
                    artist = "Artist",
                    album = "Album",
                    genre = "Rock",
                    durationMs = 180_000L,
                    artworkUri = null,
                    uriString = file.absolutePath,
                    folderPath = files.root.absolutePath,
                    trackNumber = 1,
                    year = 2024,
                    dateAdded = 1000L,
                )
            database.musicDao.insertSong(presentSong)
            assertEquals(1, database.musicDao.getAllSongs().size)

            val pruned = repository.pruneUnplayableCorruptSongs()
            assertEquals(0, pruned.size)
            assertEquals(1, database.musicDao.getAllSongs().size)
        }

    @Test
    fun pruneUnplayableCorruptSongs_neverPrunesRemoteSongs() =
        runTest {
            val repository =
                MusicRepository(
                    context = ApplicationProvider.getApplicationContext(),
                    database = database.database,
                    audioStore = TemporaryRepositoryFileStore(files.root),
                    metadataSource = NoNetworkRepositoryMetadata,
                    downloadRetryDelay = {},
                )
            val remoteSong =
                com.bestiapop.android.data.model.Song(
                    id = 0,
                    title = "Stream Track",
                    artist = "Artist",
                    album = "Album",
                    genre = "Pop",
                    durationMs = 210_000L,
                    artworkUri = null,
                    uriString = "remote://catalog/123456",
                    folderPath = "",
                    trackNumber = 1,
                    year = 2024,
                    dateAdded = 1000L,
                )
            database.musicDao.insertSong(remoteSong)
            assertEquals(1, database.musicDao.getAllSongs().size)

            val pruned = repository.pruneUnplayableCorruptSongs()
            assertEquals(0, pruned.size)
            assertEquals(1, database.musicDao.getAllSongs().size)
        }

    @Test
    fun pruneUnplayableCorruptSongs_withBatchCallback_reportsPrunedBatches() =
        runTest {
            val repository =
                MusicRepository(
                    context = ApplicationProvider.getApplicationContext(),
                    database = database.database,
                    audioStore = TemporaryRepositoryFileStore(files.root),
                    metadataSource = NoNetworkRepositoryMetadata,
                    downloadRetryDelay = {},
                )
            val missingSong =
                com.bestiapop.android.data.model.Song(
                    id = 0,
                    title = "Missing Track 1",
                    artist = "Artist",
                    album = "Album",
                    genre = "Rock",
                    durationMs = 180_000L,
                    artworkUri = null,
                    uriString = "${files.root.absolutePath}/non_existent_1.mp3",
                    folderPath = files.root.absolutePath,
                    trackNumber = 1,
                    year = 2024,
                    dateAdded = 1000L,
                )
            database.musicDao.insertSong(missingSong)

            val batches = mutableListOf<List<com.bestiapop.android.data.model.Song>>()
            val pruned =
                repository.pruneUnplayableCorruptSongs(
                    throttleDelayMs = 0L,
                    onBatchPruned = { batches.add(it) },
                )
            assertEquals(1, pruned.size)
            assertEquals(1, batches.size)
            assertEquals("Missing Track 1", batches.single().single().title)
            assertEquals(0, database.musicDao.getAllSongs().size)
        }
}
