package com.bestiapop.android.data.db

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.testutil.MediumTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class AppDatabaseMigrationTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetAppDatabaseSingleton()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        resetAppDatabaseSingleton()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migration8To9_preservesStateAndDefaultsPendingTrackNumber() = runTest {
        createLegacyDatabase(version = 8, schema = ::createVersion8Schema) { db ->
            db.execSQL(
                """
                INSERT INTO songs (
                    id, uriString, title, artist, album, genre, durationMs, year, trackNumber,
                    artworkUri, lyrics, folderPath, dateAdded
                ) VALUES (
                    7, '/music/before.mp3', 'Before migration', 'Artist', 'Album', 'Rock',
                    123000, 2020, 4, 'file:///cover.jpg', 'lyrics', '/music', 111
                )
                """.trimIndent()
            )
            db.execSQL("UPDATE songs SET lastPlayedAt = 555 WHERE id = 7")
            db.execSQL(
                "INSERT INTO playlists (playlistId, name, description, coverUri, createdAt) " +
                    "VALUES (3, 'Kept playlist', 'Description', 'file:///playlist.jpg', 222)"
            )
            db.execSQL(
                "INSERT INTO playlist_song_cross_ref (playlistId, songId, position) VALUES (3, 7, 5)"
            )
            db.execSQL(
                """
                INSERT INTO pending_listens (
                    id, listenedAt, trackName, artistName, releaseName, durationMs,
                    createdAt, attempts, lastError
                ) VALUES (9, 333, 'Pending', 'Artist', 'Album', 123000, 444, 2, 'offline')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO playlist_pending_tracks (
                    id, playlistId, title, artist, releaseName, recordingMbid, position
                ) VALUES (11, 3, 'Remote', 'Remote Artist', 'Remote Album', 'recording-id', 6)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO album_overrides (
                    albumKey, displayName, artist, genre, year, artworkUri
                ) VALUES ('Album', 'Display Album', 'Artist', 'Rock', 2020, 'file:///album.jpg')
                """.trimIndent()
            )
        }

        val database = AppDatabase.getDatabase(context)
        val musicDao = database.musicDao()
        val migratedSong = musicDao.getSongById(7L)

        assertEquals("Before migration", migratedSong?.title)
        assertEquals(555L, migratedSong?.lastPlayedAt)
        assertEquals(listOf(3L), musicDao.getPlaylistIdsForSong(7L))
        assertEquals("Description", musicDao.getPlaylistById(3L)?.description)
        val migratedPending = musicDao.getPlaylistPendingTracksFlow(3L).first().single()
        assertEquals("Remote Album", migratedPending.releaseName)
        assertEquals(0, migratedPending.trackNumber)
        assertEquals("Display Album", musicDao.getAlbumOverride("Album")?.displayName)
        assertEquals("offline", database.pendingListenDao().getOldest(10).single().lastError)

        musicDao.updateLastPlayedAt(7L, 9_999L)
        assertEquals(9_999L, musicDao.getSongById(7L)?.lastPlayedAt)
    }

    @Test
    fun migration1To9_runsWholeChainAndKeepsLegacyLibraryDataUsable() = runTest {
        createLegacyDatabase(version = 1, schema = ::createVersion1Schema) { db ->
            db.execSQL(
                legacySongInsert(
                    id = 1,
                    uri = "/music/duplicate.mp3",
                    title = "Duplicate kept"
                )
            )
            db.execSQL(
                legacySongInsert(
                    id = 2,
                    uri = "/music/duplicate.mp3",
                    title = "Duplicate removed"
                )
            )
            db.execSQL(
                legacySongInsert(
                    id = 3,
                    uri = "/music/playlist.mp3",
                    title = "Playlist song"
                )
            )
            db.execSQL(
                "INSERT INTO playlists (playlistId, name, createdAt) VALUES (5, 'Legacy playlist', 555)"
            )
            db.execSQL(
                "INSERT INTO playlist_song_cross_ref (playlistId, songId, position) VALUES (5, 3, 2)"
            )
        }

        val database = AppDatabase.getDatabase(context)
        val musicDao = database.musicDao()
        val songs = musicDao.getAllSongs()

        assertEquals(2, songs.size)
        assertEquals(1, songs.count { it.uriString == "/music/duplicate.mp3" })
        assertEquals(
            "Duplicate kept",
            songs.single { it.uriString == "/music/duplicate.mp3" }.title
        )
        assertEquals(0L, songs.single { it.id == 3L }.lastPlayedAt)
        assertEquals(listOf(5L), musicDao.getPlaylistIdsForSong(3L))
        assertEquals("Legacy playlist", musicDao.getPlaylistById(5L)?.name)
        assertNull(musicDao.getPlaylistById(5L)?.description)
        assertEquals(
            -1L,
            musicDao.insertSong(
                Song(
                    uriString = "/music/playlist.mp3",
                    title = "Must not replace",
                    artist = "Other"
                )
            )
        )

        musicDao.upsertAlbumOverride(
            AlbumOverride(albumKey = "Legacy Album", displayName = "Restored Album")
        )
        musicDao.insertPlaylistPendingTracks(
            listOf(
                PlaylistPendingTrackEntity(
                    playlistId = 5L,
                    title = "Pending remote",
                    artist = "Remote Artist",
                    releaseName = "Remote Album",
                    trackNumber = 5
                )
            )
        )
        database.pendingListenDao().insert(
            PendingListenEntity(
                listenedAt = 777L,
                trackName = "Queued listen",
                artistName = "Artist",
                createdAt = 888L
            )
        )

        assertEquals("Restored Album", musicDao.getAlbumOverride("Legacy Album")?.displayName)
        assertEquals(
            "Pending remote",
            musicDao.getPlaylistPendingTracksFlow(5L).first().single().title
        )
        assertEquals(
            5,
            musicDao.getPlaylistPendingTracksFlow(5L).first().single().trackNumber
        )
        assertEquals(1, database.pendingListenDao().count())
    }

    private fun createLegacyDatabase(
        version: Int,
        schema: (SupportSQLiteDatabase) -> Unit,
        seed: (SupportSQLiteDatabase) -> Unit
    ) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DATABASE_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            schema(db)
                            seed(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) {
                            error("Unexpected legacy helper upgrade $oldVersion->$newVersion")
                        }
                    }
                )
                .build()
        )
        try {
            helper.writableDatabase
        } finally {
            helper.close()
        }
    }

    private fun createVersion1Schema(db: SupportSQLiteDatabase) {
        createSongsTableWithoutLastPlayed(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlists (
                playlistId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        createPlaylistCrossRefTable(db)
    }

    private fun createVersion7Schema(db: SupportSQLiteDatabase) {
        createSongsTableWithoutLastPlayed(db)
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_songs_uriString ON songs (uriString)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlists (
                playlistId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                coverUri TEXT,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        createPlaylistCrossRefTable(db)
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_playlist_song_cross_ref_songId " +
                "ON playlist_song_cross_ref (songId)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_listens (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                listenedAt INTEGER NOT NULL,
                trackName TEXT NOT NULL,
                artistName TEXT NOT NULL,
                releaseName TEXT,
                durationMs INTEGER,
                createdAt INTEGER NOT NULL,
                attempts INTEGER NOT NULL,
                lastError TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_listens_listenedAt " +
                "ON pending_listens (listenedAt)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_pending_tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                playlistId INTEGER NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                releaseName TEXT,
                recordingMbid TEXT,
                position INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_playlist_pending_tracks_playlistId " +
                "ON playlist_pending_tracks (playlistId)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_playlist_pending_tracks_playlistId_artist_title " +
                "ON playlist_pending_tracks (playlistId, artist, title)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS album_overrides (
                albumKey TEXT NOT NULL PRIMARY KEY,
                displayName TEXT NOT NULL,
                artist TEXT,
                genre TEXT,
                year INTEGER NOT NULL,
                artworkUri TEXT
            )
            """.trimIndent()
        )
    }

    private fun createVersion8Schema(db: SupportSQLiteDatabase) {
        createVersion7Schema(db)
        db.execSQL(
            "ALTER TABLE songs ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0"
        )
    }

    private fun createSongsTableWithoutLastPlayed(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                uriString TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                genre TEXT NOT NULL,
                durationMs INTEGER NOT NULL,
                year INTEGER NOT NULL,
                trackNumber INTEGER NOT NULL,
                artworkUri TEXT,
                lyrics TEXT,
                folderPath TEXT NOT NULL,
                dateAdded INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createPlaylistCrossRefTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_song_cross_ref (
                playlistId INTEGER NOT NULL,
                songId INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (playlistId, songId)
            )
            """.trimIndent()
        )
    }

    private fun legacySongInsert(id: Long, uri: String, title: String): String =
        """
        INSERT INTO songs (
            id, uriString, title, artist, album, genre, durationMs, year, trackNumber,
            artworkUri, lyrics, folderPath, dateAdded
        ) VALUES (
            $id, '$uri', '$title', 'Legacy Artist', 'Legacy Album', 'Rock',
            180000, 2010, 1, NULL, NULL, '/music', 123
        )
        """.trimIndent()

    private fun resetAppDatabaseSingleton() {
        val instanceField = AppDatabase::class.java.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        (instanceField.get(null) as? AppDatabase)?.close()
        instanceField.set(null, null)
    }

    private companion object {
        const val DATABASE_NAME = "bestiapop_music_db"
    }
}
