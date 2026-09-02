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
import com.bestiapop.android.data.model.IdentifyApplyRequest
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.RoomTestDatabaseRule
import com.bestiapop.android.testutil.TaggedAudioFixtures
import com.bestiapop.android.testutil.TemporaryMusicFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private fun repository(
        metadataSource: RepositoryMetadataSource = NoNetworkRepositoryMetadata
    ) = MusicRepository(
        context = context,
        database = database.database,
        audioStore = TemporaryRepositoryFileStore(files.root),
        metadataSource = metadataSource,
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
    fun migrateCanonicalAudioUris_doesNotWipeLyrics() = runTest {
        val audio = files.create("lyrics-keep.m4a", byteArrayOf(1, 2, 3))
        val id = database.musicDao.insertSong(
            Song(
                uriString = audio.toURI().toString(),
                title = "Keep lyrics",
                artist = "Artist",
                lyrics = "[00:01.00]secret"
            )
        )

        repository().migrateCanonicalAudioUris()

        assertEquals("[00:01.00]secret", database.musicDao.getSongById(id)?.lyrics)
        assertEquals(audio.absolutePath, database.musicDao.getSongById(id)?.uriString)
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
    fun applySongIdentity_withSelectiveFields_updatesOnlySelectedFields() = runTest {
        val songId = database.musicDao.insertSong(
            song("selective.mp3", "Original title", album = "Original Album", artist = "Original Artist")
                .copy(
                    artworkUri = "content://local/original_art.jpg",
                    year = 2010,
                    trackNumber = 1
                )
        )
        val candidate = IdentifyCandidate(
            track = OnlineCatalogTrack(
                identity = TrackIdentity(
                    title = "New Title",
                    artist = "New Artist",
                    album = "New Album",
                    artworkUri = "https://images.invalid/new_art.jpg",
                    trackNumber = 5
                ),
                id = "catalog-id-2",
                provider = "Test",
                year = 2024
            ),
            score = 0.99f
        )

        // Only update artwork and year; keep original title, artist, album, trackNumber
        val fields = com.bestiapop.android.data.model.IdentifyApplyFields(
            artwork = true,
            title = false,
            artist = false,
            album = false,
            year = true,
            trackNumber = false
        )

        val result = repository().applySongIdentity(songId, candidate, fields)

        val updated = checkNotNull(database.musicDao.getSongById(songId))
        assertEquals(IdentifyResult.Updated(songId), result)
        assertEquals("Original title", updated.title)
        assertEquals("Original Artist", updated.artist)
        assertEquals("Original Album", updated.album)
        assertEquals(1, updated.trackNumber)
        assertEquals("https://images.invalid/new_art.jpg", updated.artworkUri)
        assertEquals(2024, updated.year)
    }

    @Test
    fun allSongsFlow_skipsLyricsBlobs_getSongByIdKeepsThem() = runTest {
        val id = database.musicDao.insertSong(
            song("lyrics.mp3", "With Lyrics").copy(lyrics = "[00:01.00]secret")
        )
        val listed = database.musicDao.getAllSongsFlow().first().single()
        assertNull(listed.lyrics)
        assertEquals("With Lyrics", listed.title)
        assertEquals("[00:01.00]secret", database.musicDao.getSongById(id)?.lyrics)
    }

    @Test
    fun touchSongLastPlayed_writesPlayStatsWithoutChangingIdentityFlow() = runTest {
        val id = database.musicDao.insertSong(song("played.mp3", "Played"))
        val listedBefore = repository().allSongsFlow.first().single { it.id == id }
        repository().touchSongLastPlayed(id, 12_345L)
        val listedAfter = repository().allSongsFlow.first().single { it.id == id }
        assertEquals(listedBefore, listedAfter)
        assertEquals(0L, listedAfter.lastPlayedAt)
        assertEquals(12_345L, database.musicDao.getPlayStat(id))
        assertEquals(12_345L, repository().songPlayStatsFlow.first()[id])
    }

    @Test
    fun enhanceSongMetadataAndLyrics_usesPersistedRowWhenListSongIsSlim() = runTest {
        val trap = object : RepositoryMetadataSource by NoNetworkRepositoryMetadata {
            var lyricFetches = 0
            override suspend fun fetchLyrics(artist: String, title: String): String? {
                lyricFetches++
                return "SHOULD NOT APPLY"
            }
        }
        val id = database.musicDao.insertSong(
            song("full.mp3", "Kept").copy(
                lyrics = "[00:01.00]kept",
                artworkUri = "file:///art.jpg",
                durationMs = 120_000L
            )
        )
        val repo = repository(metadataSource = trap)
        val slim = database.musicDao.getAllSongsFlow().first().single { it.id == id }
        assertNull(slim.lyrics)
        repo.enhanceSongMetadataAndLyrics(slim)
        assertEquals(0, trap.lyricFetches)
        assertEquals("[00:01.00]kept", database.musicDao.getSongById(id)?.lyrics)
    }

    @Test
    fun applySongIdentities_batchKeepsLyricsAndLocalDuration() = runTest {
        val firstId = database.musicDao.insertSong(
            song("batch-a.mp3", "Local A", album = "Unknown Album", artist = "Unknown Artist")
                .copy(durationMs = 111_000L, lyrics = "[00:01.00]kept a")
        )
        val secondId = database.musicDao.insertSong(
            song("batch-b.mp3", "Local B", album = "Unknown Album", artist = "Unknown Artist")
                .copy(durationMs = 222_000L, lyrics = "[00:02.00]kept b")
        )
        val untouchedId = database.musicDao.insertSong(
            song("batch-c.mp3", "Leave me", album = "Other Album", artist = "Other Artist")
                .copy(lyrics = "untouched lyrics")
        )
        val repo = repository()
        val applied = repo.applySongIdentities(
            listOf(
                IdentifyApplyRequest(firstId, catalogCandidate("A", 180_000L, 1)),
                IdentifyApplyRequest(secondId, catalogCandidate("B", 190_000L, 2))
            )
        )

        assertEquals(setOf(firstId, secondId), applied)
        val first = checkNotNull(database.musicDao.getSongById(firstId))
        val second = checkNotNull(database.musicDao.getSongById(secondId))
        val untouched = checkNotNull(database.musicDao.getSongById(untouchedId))
        assertEquals("Catalog A", first.title)
        assertEquals("Catalog B", second.title)
        assertEquals(111_000L, first.durationMs)
        assertEquals(222_000L, second.durationMs)
        assertEquals("[00:01.00]kept a", first.lyrics)
        assertEquals("[00:02.00]kept b", second.lyrics)
        assertEquals("Leave me", untouched.title)
        assertEquals("untouched lyrics", untouched.lyrics)
        // Auto-write tags is off by default: apply returns without waiting on ID3 I/O.
    }

    private fun catalogCandidate(suffix: String, durationMs: Long, trackNumber: Int) =
        IdentifyCandidate(
            track = OnlineCatalogTrack(
                identity = TrackIdentity(
                    title = "Catalog $suffix",
                    artist = "Catalog artist",
                    album = "Catalog album",
                    durationMs = durationMs,
                    trackNumber = trackNumber
                ),
                id = "catalog-$suffix",
                provider = "Test"
            ),
            score = 0.9f
        )

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

    @Test
    fun migrateEmbeddedFileTags_fillsUnknownFromJaudiotagger() = runTest {
        val file = files.create("02__________Black_Hole_.mp3", byteArrayOf(1))
        TaggedAudioFixtures.writeTaggedMp3(
            dest = file,
            title = "ブラックホール / Black Hole",
            artist = "namitape; Kaai Yuki",
            album = "Flitter"
        )
        database.musicDao.insertSong(
            Song(
                uriString = files.create("alive.mp3", byteArrayOf(1)).absolutePath,
                title = "Alive",
                artist = "Namitape",
                album = "Flitter",
                genre = "Electronica",
                durationMs = 180_000L
            )
        )
        val unknownId = database.musicDao.insertSong(
            Song(
                uriString = file.absolutePath,
                title = "Black Hole",
                artist = "Unknown Artist",
                album = "Unknown Album",
                genre = "Music",
                durationMs = 214_204L,
                artworkUri = "https://cdn.example/wrong.jpg",
                lyrics = "I'd rather be a light"
            )
        )

        val leftover = repository().migrateEmbeddedFileTags()
        val updated = checkNotNull(database.musicDao.getSongById(unknownId))
        assertEquals("Namitape", updated.artist)
        assertEquals("Flitter", updated.album)
        assertEquals("ブラックホール / Black Hole", updated.title)
        assertEquals("Electronica; Vocaloid", updated.genre)
        assertEquals(2023, updated.year)
        assertEquals("目にブラックホールがあります", updated.lyrics)
        assertFalse(updated.artworkUri.orEmpty().startsWith("https://"))
        assertTrue(leftover.none { it.id == unknownId })
    }

    @Test
    fun enhanceSongMetadataAndLyrics_skipsCatalogWhenArtistIsPlaceholder() = runTest {
        val trap = object : RepositoryMetadataSource by NoNetworkRepositoryMetadata {
            var lyricFetches = 0
            var artFetches = 0
            override suspend fun fetchLyrics(artist: String, title: String): String? {
                lyricFetches++
                return "WRONG"
            }
            override suspend fun fetchAlbumArtUrl(artist: String, titleOrAlbum: String): String? {
                artFetches++
                return "https://cdn.example/wrong.jpg"
            }
        }
        val id = database.musicDao.insertSong(
            song("unknown.mp3", "Black Hole", album = "Unknown Album", artist = "Unknown Artist")
                .copy(durationMs = 214_204L, artworkUri = null, lyrics = null)
        )
        repository(metadataSource = trap).enhanceSongMetadataAndLyrics(
            checkNotNull(database.musicDao.getSongById(id))
        )
        assertEquals(0, trap.lyricFetches)
        assertEquals(0, trap.artFetches)
        val persisted = checkNotNull(database.musicDao.getSongById(id))
        assertNull(persisted.lyrics)
        assertNull(persisted.artworkUri)
    }

    @Test
    fun proposeSongIdentity_queriesListenBrainzBeforeCatalog_evenForGenericTitle() = runTest {
        val calls = mutableListOf<String>()
        val lbTrack = OnlineCatalogTrack(
            id = "lb-1",
            title = "ブラックホール",
            artist = "namitape",
            album = "Flitter",
            durationMs = 214_204L,
            audioUrl = "",
            provider = "ListenBrainz"
        )
        val deezerTrack = OnlineCatalogTrack(
            id = "dz-1",
            title = "Black Hole",
            artist = "Muse",
            album = "Absolution",
            durationMs = 214_000L,
            audioUrl = "",
            provider = "Deezer"
        )
        val source = object : RepositoryMetadataSource by NoNetworkRepositoryMetadata {
            override suspend fun lookupListenBrainzIdentifyTrack(
                artist: String,
                title: String,
                releaseName: String?,
                token: String
            ): OnlineCatalogTrack? {
                calls += "lb"
                assertEquals("token-1", token)
                return lbTrack
            }

            override suspend fun searchMusicBrainzRecordings(
                query: String,
                durationMs: Long,
                limit: Int
            ): List<OnlineCatalogTrack> {
                calls += "mb"
                return emptyList()
            }

            override suspend fun fetchFullTrackMetadata(
                artist: String,
                title: String
            ): TrackIdentity? {
                calls += "exact"
                return null
            }

            override suspend fun searchOnlineCatalog(
                query: String,
                limit: Int,
                index: Int
            ): List<OnlineCatalogTrack> {
                calls += "catalog"
                return listOf(deezerTrack)
            }
        }
        val id = database.musicDao.insertSong(
            song("hole.mp3", "Black Hole", album = "Unknown Album", artist = "Unknown Artist")
                .copy(durationMs = 214_204L)
        )
        val song = checkNotNull(database.musicDao.getSongById(id))
        val proposal = repository(metadataSource = source).proposeSongIdentity(
            song = song,
            listenBrainzToken = "token-1"
        )
        assertEquals(listOf("lb", "mb", "catalog"), calls.take(3))
        assertTrue(proposal.usedListenBrainz)
        assertTrue(proposal.candidates.any { it.provider == "ListenBrainz" })
        assertNotEquals(
            com.bestiapop.android.data.model.IdentifyConfidence.HIGH,
            proposal.confidence
        )
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
