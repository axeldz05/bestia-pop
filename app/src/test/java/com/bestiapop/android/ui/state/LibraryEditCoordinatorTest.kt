package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase
import com.bestiapop.android.testutil.FakeMusicRepository
import com.bestiapop.android.testutil.MediumTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(MediumTest::class)
class LibraryEditCoordinatorTest {

    private class TestLibraryEditRepo : FakeMusicRepository() {
        var updatedSongMetadata: Triple<Long, String, String>? = null
        var deletedFromAppSongs: List<Song>? = null
        var deletedFromDeviceSongs: List<Song>? = null
        var setAlbumArtworkCall: Pair<String, String?>? = null
        var upsertedOverride: AlbumOverride? = null
        var propagatedOverride: AlbumOverride? = null
        var mergedAlbums: Pair<String, String>? = null
        var savedAlbumTracks: Pair<String, String>? = null
        var removedAlbum: Pair<String, String>? = null
        var songsSync: List<Song> = emptyList()
        var overridesSync: List<AlbumOverride> = emptyList()

        override suspend fun getAllSongsSync(): List<Song> = songsSync
        override val albumOverridesFlow = flowOf(overridesSync)

        override suspend fun updateSongMetadata(
            songId: Long,
            title: String,
            artist: String,
            album: String,
            genre: String,
            year: Int,
            trackNumber: Int
        ) {
            updatedSongMetadata = Triple(songId, title, artist)
        }

        override suspend fun deleteSongsFromApp(songs: List<Song>) {
            deletedFromAppSongs = songs
        }

        override suspend fun deleteSongsFromDevice(songs: List<Song>) {
            deletedFromDeviceSongs = songs
        }

        override suspend fun setAlbumArtwork(albumKey: String, artworkUri: String?) {
            setAlbumArtworkCall = albumKey to artworkUri
        }

        override suspend fun upsertAlbumOverride(override: AlbumOverride) {
            upsertedOverride = override
        }

        override suspend fun updateAlbumMetadataPropagateToSongs(override: AlbumOverride) {
            propagatedOverride = override
        }

        override suspend fun mergeAlbumInto(sourceAlbumKey: String, targetAlbumKey: String) {
            mergedAlbums = sourceAlbumKey to targetAlbumKey
        }

        override suspend fun saveAlbumTracksToLibrary(
            albumTitle: String,
            artistName: String,
            coverUrl: String?,
            year: Int,
            genre: String,
            tracks: List<CatalogTrackCandidate>
        ): List<Song> {
            savedAlbumTracks = albumTitle to artistName
            return emptyList()
        }

        override suspend fun removeSavedAlbumFromLibrary(albumName: String, artistName: String): Int {
            removedAlbum = albumName to artistName
            return 1
        }
    }

    private fun testSong(id: Long, title: String, artist: String, album: String): Song = Song(
        id = id,
        uriString = "file:///music/$id.mp3",
        title = title,
        artist = artist,
        album = album
    )

    @Test
    fun updateSongMetadata_callsRepository() = runTest {
        val repo = TestLibraryEditRepo()
        val coordinator = LibraryEditCoordinator(
            scope = this,
            repository = repo,
            updateAlbumArtworkInQueue = { _, _ -> }
        )

        coordinator.updateSongMetadata(
            songId = 42L,
            title = "New Title",
            artist = "New Artist",
            album = "New Album",
            genre = "Rock"
        )
        testScheduler.advanceUntilIdle()

        assertEquals(Triple(42L, "New Title", "New Artist"), repo.updatedSongMetadata)
    }

    @Test
    fun deleteSongsFromApp_callsRepositoryAndNotifiesCallback() = runTest {
        val repo = TestLibraryEditRepo()
        var deletedIds: Set<Long>? = null
        val coordinator = LibraryEditCoordinator(
            scope = this,
            repository = repo,
            updateAlbumArtworkInQueue = { _, _ -> },
            onSongsDeleted = { deletedIds = it }
        )

        val songs = listOf(testSong(1L, "T1", "A1", "Alb1"), testSong(2L, "T2", "A1", "Alb1"))
        coordinator.deleteSongsFromApp(songs)
        testScheduler.advanceUntilIdle()

        assertEquals(songs, repo.deletedFromAppSongs)
        assertEquals(setOf(1L, 2L), deletedIds)
    }

    @Test
    fun deleteSongsFromDevice_callsRepositoryAndNotifiesCallback() = runTest {
        val repo = TestLibraryEditRepo()
        var deletedIds: Set<Long>? = null
        val coordinator = LibraryEditCoordinator(
            scope = this,
            repository = repo,
            updateAlbumArtworkInQueue = { _, _ -> },
            onSongsDeleted = { deletedIds = it }
        )

        val songs = listOf(testSong(3L, "T3", "A2", "Alb2"))
        coordinator.deleteSongsFromDevice(songs)
        testScheduler.advanceUntilIdle()

        assertEquals(songs, repo.deletedFromDeviceSongs)
        assertEquals(setOf(3L), deletedIds)
    }

    @Test
    fun setAlbumArtwork_updatesQueueAndRepository() = runTest {
        val repo = TestLibraryEditRepo()
        var queueUpdated: Pair<String, String>? = null
        val coordinator = LibraryEditCoordinator(
            scope = this,
            repository = repo,
            updateAlbumArtworkInQueue = { name, uri -> queueUpdated = name to uri },
            ioDispatcher = Dispatchers.Unconfined
        )

        coordinator.setAlbumArtwork("Abbey Road", "file:///cover.jpg")
        testScheduler.advanceUntilIdle()

        assertEquals("Abbey Road" to "file:///cover.jpg", queueUpdated)
        assertEquals("Abbey Road" to "file:///cover.jpg", repo.setAlbumArtworkCall)
    }

    @Test
    fun requestSaveAlbumMetadata_noConflict_persistsOverrideDirectly() = runTest {
        val repo = TestLibraryEditRepo().apply {
            songsSync = listOf(testSong(1L, "Song1", "The Beatles", "Revolver"))
        }
        val coordinator = LibraryEditCoordinator(
            scope = this,
            repository = repo,
            getLibrarySongsUseCase = GetLibrarySongsUseCase(),
            updateAlbumArtworkInQueue = { _, _ -> }
        )

        val album = Album(name = "Revolver", artist = "The Beatles", songCount = 1)
        coordinator.requestSaveAlbumMetadata(
            source = album,
            displayName = "Revolver (Remastered)",
            artist = "The Beatles",
            genre = "Rock",
            year = 1966,
            artworkUri = null,
            propagateToSongs = false
        )
        testScheduler.advanceUntilIdle()

        assertNull(coordinator.pendingAlbumMerge.value)
        assertNotNull(repo.upsertedOverride)
        assertEquals("Revolver", repo.upsertedOverride?.albumKey)
        assertEquals("Revolver (Remastered)", repo.upsertedOverride?.displayName)
    }

    @Test
    fun requestSaveAlbumMetadata_withConflict_setsPendingMergeAndConfirms() = runTest {
        val repo = TestLibraryEditRepo().apply {
            songsSync = listOf(
                testSong(1L, "Come Together", "The Beatles", "Abbey Road"),
                testSong(2L, "Taxman", "The Beatles", "Revolver")
            )
        }
        var toastMessage: String? = null
        val coordinator = LibraryEditCoordinator(
            scope = this,
            repository = repo,
            getLibrarySongsUseCase = GetLibrarySongsUseCase(),
            updateAlbumArtworkInQueue = { _, _ -> },
            toast = { toastMessage = it }
        )

        val sourceAlbum = Album(name = "Revolver", artist = "The Beatles", songCount = 1)
        coordinator.requestSaveAlbumMetadata(
            source = sourceAlbum,
            displayName = "Abbey Road",
            artist = "The Beatles",
            genre = "Rock",
            year = 1969,
            artworkUri = null,
            propagateToSongs = true
        )
        testScheduler.advanceUntilIdle()

        val pending = coordinator.pendingAlbumMerge.value
        assertNotNull(pending)
        assertEquals("Revolver", pending?.source?.name)
        assertEquals("Abbey Road", pending?.target?.name)

        // Confirm the merge
        coordinator.confirmPendingAlbumMerge()
        testScheduler.advanceUntilIdle()

        assertNull(coordinator.pendingAlbumMerge.value)
        assertEquals("Revolver" to "Abbey Road", repo.mergedAlbums)
        assertEquals(DownloadMessages.albumsMerged, toastMessage)
    }

    @Test
    fun dismissPendingAlbumMerge_clearsState() = runTest {
        val repo = TestLibraryEditRepo().apply {
            songsSync = listOf(
                testSong(1L, "Song 1", "Artist", "Album A"),
                testSong(2L, "Song 2", "Artist", "Album B")
            )
        }
        val coordinator = LibraryEditCoordinator(
            scope = this,
            repository = repo,
            updateAlbumArtworkInQueue = { _, _ -> }
        )

        coordinator.requestSaveAlbumMetadata(
            source = Album(name = "Album A", artist = "Artist", songCount = 1),
            displayName = "Album B",
            artist = "Artist",
            genre = "",
            year = 0,
            artworkUri = null,
            propagateToSongs = false
        )
        testScheduler.advanceUntilIdle()

        assertNotNull(coordinator.pendingAlbumMerge.value)
        coordinator.dismissPendingAlbumMerge()
        assertNull(coordinator.pendingAlbumMerge.value)
    }

    @Test
    fun saveAlbumToLibrary_and_removeSavedAlbum() = runTest {
        val repo = TestLibraryEditRepo()
        var toastMessage: String? = null
        val coordinator = LibraryEditCoordinator(
            scope = this,
            repository = repo,
            updateAlbumArtworkInQueue = { _, _ -> },
            toast = { toastMessage = it }
        )

        val catalogAlbum = CatalogAlbum(
            id = "cat-1",
            title = "OK Computer",
            artist = "Radiohead",
            coverUrl = "http://art.jpg",
            releaseYear = "1997"
        )
        val candidates = listOf(
            CatalogTrackCandidate(
                identity = TrackIdentity(title = "Airbag", artist = "Radiohead", durationMs = 284000L, trackNumber = 1),
                candidates = emptyList()
            )
        )

        coordinator.saveAlbumToLibrary(catalogAlbum, candidates)
        testScheduler.advanceUntilIdle()

        assertEquals("OK Computer" to "Radiohead", repo.savedAlbumTracks)
        assertEquals(DownloadMessages.albumSaved, toastMessage)

        coordinator.removeSavedAlbum(catalogAlbum)
        testScheduler.advanceUntilIdle()

        assertEquals("OK Computer" to "Radiohead", repo.removedAlbum)
        assertEquals("Álbum eliminado de la biblioteca", toastMessage)
    }
}
