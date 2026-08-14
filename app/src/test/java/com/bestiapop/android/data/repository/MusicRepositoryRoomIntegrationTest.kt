package com.bestiapop.android.data.repository

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.db.PlaylistSongCrossRef
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.RoomTestDatabaseRule
import com.bestiapop.android.testutil.TemporaryMusicFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class MusicRepositoryRoomIntegrationTest {
    @get:Rule
    val database = RoomTestDatabaseRule()

    @get:Rule
    val files = TemporaryMusicFiles()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun repository() = MusicRepository(
        context = context,
        database = database.database,
        audioStore = TemporaryRepositoryFileStore(files.root),
        metadataSource = NoNetworkRepositoryMetadata,
        downloadRetryDelay = {}
    )

    @Test
    fun duplicateUri_updatesInPlace_withoutClobberingAppStateOrPlaylistMembership() = runTest {
        val uri = files.create("same-uri.mp3", byteArrayOf(1)).absolutePath
        val originalId = database.musicDao.insertSong(
            Song(
                uriString = uri,
                title = "Original",
                artist = "Original Artist",
                album = "Original Album",
                lyrics = "kept lyrics",
                dateAdded = 1234L,
                lastPlayedAt = 5678L
            )
        )
        val playlistId = database.musicDao.insertPlaylist(
            com.bestiapop.android.data.db.PlaylistEntity(name = "Favorites")
        )
        database.musicDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, originalId))

        val returnedId = repository().saveUploadedSong(
            Song(
                uriString = uri,
                title = "Retagged",
                artist = "Retagged Artist",
                album = "Retagged Album",
                genre = "Electronic",
                durationMs = 222_000L,
                lyrics = null,
                dateAdded = 9999L,
                lastPlayedAt = 0L
            )
        )

        val persisted = database.musicDao.getAllSongs().single()
        assertEquals(originalId, returnedId)
        assertEquals(originalId, persisted.id)
        assertEquals("Retagged", persisted.title)
        assertEquals("kept lyrics", persisted.lyrics)
        assertEquals(1234L, persisted.dateAdded)
        assertEquals(5678L, persisted.lastPlayedAt)
        assertEquals(listOf(playlistId), repository().getPlaylistIdsForSong(originalId))
        assertEquals(listOf(originalId), repository().getPlaylistSongsFlow(playlistId).first().map { it.id })
    }

    @Test
    fun deleteSongsFromApp_removesEveryPlaylistReference() = runTest {
        val firstId = database.musicDao.insertSong(song("first.mp3", "First"))
        val secondId = database.musicDao.insertSong(song("second.mp3", "Second"))
        val playlistId = repository().createPlaylist("Queue")
        repository().addSongToPlaylist(playlistId, firstId)
        repository().addSongToPlaylist(playlistId, secondId)

        repository().deleteSongsFromApp(
            listOf(
                checkNotNull(database.musicDao.getSongById(firstId)),
                checkNotNull(database.musicDao.getSongById(secondId))
            )
        )

        assertTrue(database.musicDao.getAllSongs().isEmpty())
        assertTrue(repository().getPlaylistSongsFlow(playlistId).first().isEmpty())
        assertTrue(repository().getPlaylistIdsForSong(firstId).isEmpty())
        assertTrue(repository().getPlaylistIdsForSong(secondId).isEmpty())
    }

    @Test
    fun canonicalUriCollision_remapsEveryPlaylistBeforeDeletingDuplicateRow() = runTest {
        val audio = files.create("canonical.m4a", byteArrayOf(1, 2, 3))
        val canonicalId = database.musicDao.insertSong(
            Song(uriString = audio.absolutePath, title = "Canonical", artist = "Artist")
        )
        val duplicateId = database.musicDao.insertSong(
            Song(uriString = audio.toURI().toString(), title = "Duplicate", artist = "Artist")
        )
        val firstPlaylist = repository().createPlaylist("First")
        val secondPlaylist = repository().createPlaylist("Second")
        repository().addSongToPlaylist(firstPlaylist, canonicalId)
        repository().addSongToPlaylist(secondPlaylist, duplicateId)

        repository().migrateCanonicalAudioUris()

        val remaining = database.musicDao.getAllSongs().single()
        assertEquals(canonicalId, remaining.id)
        assertEquals(audio.absolutePath, remaining.uriString)
        assertEquals(setOf(firstPlaylist, secondPlaylist), repository().getPlaylistIdsForSong(canonicalId).toSet())
        assertNull(database.musicDao.getSongById(duplicateId))
    }

    @Test
    fun applySongIdentity_changesCatalogIdentity_butKeepsMeasuredLocalDuration() = runTest {
        val songId = database.musicDao.insertSong(
            song("identify.mp3", "Local title", album = "Unknown Album", artist = "Unknown Artist")
                .copy(durationMs = 187_654L)
        )
        val candidate = IdentifyCandidate(
            track = OnlineCatalogTrack(
                identity = TrackIdentity(
                    title = "Catalog title",
                    artist = "Catalog artist",
                    album = "Catalog album",
                    artworkUri = "https://images.invalid/catalog.jpg",
                    durationMs = 222_000L,
                    trackNumber = 4
                ),
                id = "catalog-id",
                provider = "Test"
            ),
            score = 0.95f
        )

        val result = repository().applySongIdentity(songId, candidate)

        val updated = checkNotNull(database.musicDao.getSongById(songId))
        assertEquals(IdentifyResult.Updated(songId), result)
        assertEquals("Catalog title", updated.title)
        assertEquals("Catalog artist", updated.artist)
        assertEquals("Catalog album", updated.album)
        assertEquals(4, updated.trackNumber)
        assertEquals(187_654L, updated.durationMs)
    }

    @Test
    fun albumCoverImport_copiesBytesIntoAppFilesBeforeSourceDisappears() = runTest {
        val source = files.create("cover.jpg", byteArrayOf(7, 8, 9))
        val repository = repository()

        repository.upsertAlbumOverride(
            AlbumOverride(
                albumKey = "Persistent cover",
                displayName = "Persistent cover",
                artworkUri = source.toURI().toString()
            )
        )
        val storedUri = repository.getAlbumOverride("Persistent cover")?.artworkUri
        val storedFile = storedUri?.let { java.io.File(java.net.URI(it)) }
        source.delete()

        assertNotNull(storedUri)
        assertTrue(storedFile?.isFile == true)
        assertEquals(listOf<Byte>(7, 8, 9), storedFile?.readBytes()?.toList())
    }

    @Test
    fun albumCoverImport_copiesEphemeralContentUri_beforeProviderAccessDisappears() {
        val namespace = UUID.randomUUID().toString()
        val authority = "com.bestiapop.android.test.cover.$namespace"
        val source = files.create("cover-$namespace.png", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
        EphemeralCoverContentProvider.source = source
        EphemeralCoverContentProvider.readable = true
        val provider: ContentProviderController<EphemeralCoverContentProvider> = Robolectric
            .buildContentProvider(EphemeralCoverContentProvider::class.java)
            .create(authority)
        var copiedFile: File? = null

        try {
            val sourceUri = Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath("cover.png")
                .build()
            val storedUri = repository().saveAlbumCoverImage(sourceUri.toString())
            val persistedFile = checkNotNull(storedUri).let { File(java.net.URI(it)) }
            copiedFile = persistedFile

            EphemeralCoverContentProvider.readable = false
            source.delete()

            assertTrue(persistedFile.isFile)
            assertEquals(
                listOf<Byte>(0x89.toByte(), 0x50, 0x4e, 0x47),
                persistedFile.readBytes().toList()
            )
            assertTrue(
                runCatching { context.contentResolver.openInputStream(sourceUri) }
                    .exceptionOrNull() is FileNotFoundException
            )
        } finally {
            provider.shutdown()
            EphemeralCoverContentProvider.readable = false
            EphemeralCoverContentProvider.source = null
            copiedFile?.delete()
            copiedFile?.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
        }
    }

    @Test
    fun albumOverrideOnly_doesNotRewriteSongs_thenPropagateUpdatesEverySibling() = runTest {
        val firstId = database.musicDao.insertSong(
            song("album-1.mp3", "One", album = "Source", artist = "Old Artist")
        )
        val secondId = database.musicDao.insertSong(
            song("album-2.mp3", "Two", album = "Source", artist = "Old Artist")
        )
        val repository = repository()

        repository.upsertAlbumOverride(
            AlbumOverride(
                albumKey = "Source",
                displayName = "Display Only",
                artist = "Override Artist",
                genre = "Ambient",
                year = 2020
            )
        )

        assertEquals("Display Only", repository.getAlbumOverride("Source")?.displayName)
        assertEquals(
            setOf("Old Artist"),
            database.musicDao.getSongsForAlbum("Source").map { it.artist }.toSet()
        )

        repository.updateAlbumMetadataPropagateToSongs(
            AlbumOverride(
                albumKey = "Source",
                displayName = "Renamed",
                artist = "New Artist",
                genre = "Post-rock",
                year = 2024
            )
        )

        val updated = listOf(firstId, secondId).map { checkNotNull(database.musicDao.getSongById(it)) }
        assertTrue(updated.all { it.album == "Renamed" })
        assertTrue(updated.all { it.artist == "New Artist" })
        assertTrue(updated.all { it.genre == "Post-rock" && it.year == 2024 })
        assertNull(repository.getAlbumOverride("Source"))
        assertEquals("Renamed", repository.getAlbumOverride("Renamed")?.displayName)
    }

    @Test
    fun editOneSong_leavesSiblingAndAlbumOverrideUntouched() = runTest {
        val editedId = database.musicDao.insertSong(song("edit.mp3", "Edit", album = "Shared"))
        val siblingId = database.musicDao.insertSong(song("sibling.mp3", "Sibling", album = "Shared"))
        val repository = repository()
        val override = AlbumOverride("Shared", "Shared display", artist = "Album Artist", year = 1999)
        repository.upsertAlbumOverride(override)

        repository.updateSongMetadata(
            songId = editedId,
            title = "Edited",
            artist = "Solo Artist",
            album = "Solo Album",
            genre = "Jazz",
            year = 2001,
            trackNumber = 7
        )

        assertEquals("Solo Album", database.musicDao.getSongById(editedId)?.album)
        assertEquals("Shared", database.musicDao.getSongById(siblingId)?.album)
        assertEquals(override, repository.getAlbumOverride("Shared"))
    }

    @Test
    fun mergeAlbum_foldsUnicodeAndMojibakeEquivalentKeysIntoCanonicalTarget() = runTest {
        val canonical = "Takk..."
        val mojibake = "Takk\u00E2\u20AC\u00A6"
        database.musicDao.insertSong(
            song("target.mp3", "Target", album = canonical, artist = "Sigur Rós").copy(
                genre = "Post-rock",
                year = 2005
            )
        )
        database.musicDao.insertSong(song("source.mp3", "Source", album = mojibake, artist = "Old"))
        database.musicDao.insertSong(song("unicode.mp3", "Unicode", album = "Takk\u2026", artist = "Old"))
        val repository = repository()
        repository.upsertAlbumOverride(
            AlbumOverride(canonical, canonical, artist = "Sigur Rós", genre = "Post-rock", year = 2005)
        )
        repository.upsertAlbumOverride(AlbumOverride(mojibake, "Broken"))

        repository.mergeAlbumInto(mojibake, canonical)

        val songs = database.musicDao.getAllSongs()
        assertEquals(setOf(canonical), songs.map { it.album }.toSet())
        assertTrue(songs.all { it.artist == "Sigur Rós" && it.genre == "Post-rock" && it.year == 2005 })
        assertNull(repository.getAlbumOverride(mojibake))
        assertEquals(canonical, repository.getAlbumOverride(canonical)?.albumKey)
    }

    @Test
    fun playlistCrudAndPending_roundTripsRoomWithoutPersistingCdn() = runTest {
        val songId = database.musicDao.insertSong(
            song("playlist.mp3", "Local").copy(artworkUri = "file:///song-art.jpg")
        )
        val initialCover = files.create("playlist-cover.jpg", byteArrayOf(4, 5, 6))
        val updatedCover = files.create("new-playlist-cover.jpg", byteArrayOf(7, 8, 9))
        val repository = repository()
        val playlistId = repository.createPlaylist(
            name = "Draft",
            description = "",
            coverUri = initialCover.toURI().toString()
        )
        repository.addSongToPlaylist(playlistId, songId)
        repository.addPlaylistPendingTracks(
            listOf(
                PlaylistPendingTrack(
                    identity = TrackIdentity(
                        title = "Remote",
                        artist = "Remote Artist",
                        album = "Remote Album",
                        artworkUri = "https://images.invalid/cover.jpg"
                    ),
                    playlistId = playlistId,
                    recordingMbid = "recording-mbid",
                    position = 2
                )
            )
        )

        val created = repository.getPlaylistDetailsFlow(playlistId).first()
        val pending = repository.getPlaylistPendingTracksFlow(playlistId).first().single()
        assertEquals("Draft", created?.first?.name)
        assertEquals(listOf(songId), created?.second?.map { it.id })
        assertNull(created?.first?.description)
        val persistedInitialCover = checkNotNull(created?.first?.coverUri)
        assertEquals(
            listOf<Byte>(4, 5, 6),
            java.io.File(java.net.URI(persistedInitialCover)).readBytes().toList()
        )
        assertEquals("file:///song-art.jpg", created?.second?.single()?.artworkUri)
        assertEquals("Remote Album", pending.album)
        assertEquals("recording-mbid", pending.recordingMbid)
        assertTrue(pending.toOnlineCatalogTrack().audioUrl.isEmpty())

        repository.updatePlaylist(
            playlistId,
            "Published",
            "Description",
            updatedCover.toURI().toString()
        )
        val updatedPlaylist = repository.playlistsFlow.first().single()
        assertEquals("Published", updatedPlaylist.name)
        val persistedUpdatedCover = checkNotNull(updatedPlaylist.coverUri)
        assertEquals(
            listOf<Byte>(7, 8, 9),
            java.io.File(java.net.URI(persistedUpdatedCover)).readBytes().toList()
        )
        assertEquals("file:///song-art.jpg", database.musicDao.getSongById(songId)?.artworkUri)
        repository.removeSongFromPlaylist(playlistId, songId)
        repository.removePlaylistPendingTrack(playlistId, "remote artist", "REMOTE")
        assertTrue(repository.getPlaylistSongsFlow(playlistId).first().isEmpty())
        assertTrue(repository.getPlaylistPendingTracksFlow(playlistId).first().isEmpty())

        repository.addSongToPlaylist(playlistId, songId)
        repository.addPlaylistPendingTracks(listOf(pending.copy(id = 0)))
        repository.deletePlaylist(playlistId)

        assertTrue(repository.playlistsFlow.first().isEmpty())
        assertNull(repository.getPlaylistDetailsFlow(playlistId).first())
        assertTrue(repository.getPlaylistPendingTracksFlow(playlistId).first().isEmpty())
        assertFalse(repository.getPlaylistIdsForSong(songId).contains(playlistId))
    }

    private fun song(
        fileName: String,
        title: String,
        album: String = "Album",
        artist: String = "Artist"
    ): Song = Song(
        uriString = files.create(fileName, byteArrayOf(1)).absolutePath,
        title = title,
        artist = artist,
        album = album,
        durationMs = 180_000L
    )

    @Test
    fun migrateDateAddedFromDevice_updatesExistingSongsWithFileModificationTime() = runTest {
        val file = files.create("migrated_track.mp3", byteArrayOf(1, 2, 3))
        file.setLastModified(1600000000000L)

        val id = database.musicDao.insertSong(
            Song(
                uriString = file.absolutePath,
                title = "Migrated Track",
                artist = "Artist",
                durationMs = 60_000L,
                dateAdded = 1700000000000L
            )
        )

        val repo = repository()
        repo.migrateDateAddedFromDevice()

        val updated = database.musicDao.getSongById(id)
        assertNotNull(updated)
        assertEquals(1600000000000L, updated?.dateAdded)
    }
}

internal class EphemeralCoverContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!readable) throw FileNotFoundException("Ephemeral provider invalidated")
        val file = source?.takeIf(File::isFile)
            ?: throw FileNotFoundException("Ephemeral source missing")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "image/png"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        var source: File? = null
        var readable: Boolean = false
    }
}
