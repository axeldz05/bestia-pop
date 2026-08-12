package com.bestiapop.android.data.repository

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.RoomTestDatabaseRule
import com.bestiapop.android.testutil.TemporaryMusicFiles
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
class MusicRepositoryScanIntegrationTest {
    @get:Rule
    val database = RoomTestDatabaseRule()

    @get:Rule
    val files = TemporaryMusicFiles()

    private var providerController: ContentProviderController<ScanMediaStoreProvider>? = null

    @After
    fun closeProvider() {
        providerController?.shutdown()
        providerController = null
        ScanMediaStoreProvider.rows = emptyList()
    }

    @Test
    fun scanMediaStore_skipsBestiaPopAndKnownSafPath_thenRemainsIdempotent() = runTest {
        val knownPath = "/storage/emulated/0/Music/Imported/known-through-saf.wav"
        database.musicDao.insertSong(
            Song(
                uriString = "content://com.example.documents/document/known",
                folderPath = knownPath,
                title = "Different SAF title",
                artist = "Different SAF artist",
                durationMs = 31_000L
            )
        )
        ScanMediaStoreProvider.rows = listOf(
            MediaRow(
                id = 1L,
                title = "Managed duplicate",
                artist = "BestiaPop",
                path = "/storage/emulated/0/Music/BestiaPop/managed.wav"
            ),
            MediaRow(
                id = 2L,
                title = "MediaStore alias",
                artist = "Unrelated metadata",
                path = knownPath
            ),
            MediaRow(
                id = 3L,
                title = "External song",
                artist = "External artist",
                path = "/storage/emulated/0/Music/Elsewhere/external.wav"
            )
        )
        providerController = Robolectric
            .buildContentProvider(ScanMediaStoreProvider::class.java)
            .create(MediaStore.AUTHORITY)
        val repository = MusicRepository(
            context = ApplicationProvider.getApplicationContext(),
            database = database.database,
            audioStore = TemporaryRepositoryFileStore(files.root),
            metadataSource = NoNetworkRepositoryMetadata,
            downloadRetryDelay = {}
        )

        repository.scanMediaStore()
        repository.scanMediaStore()

        val songs = database.musicDao.getAllSongs()
        assertEquals(2, songs.size)
        assertEquals(
            setOf("Different SAF title", "External song"),
            songs.map { it.title }.toSet()
        )
        assertEquals(
            1,
            songs.count {
                it.uriString == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    .buildUpon()
                    .appendPath("3")
                    .build()
                    .toString()
            }
        )
    }
}

private data class MediaRow(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val album: String = "Fixture album",
    val durationMs: Long = 31_000L,
    val year: Int = 2024,
    val track: Int = 1,
    val albumId: Long = -1L
)

private class ScanMediaStoreProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = requireNotNull(projection)
        return MatrixCursor(columns).apply {
            rows.forEach { media ->
                addRow(
                    columns.map { column ->
                        when (column) {
                            MediaStore.Audio.Media._ID -> media.id
                            MediaStore.Audio.Media.TITLE -> media.title
                            MediaStore.Audio.Media.ARTIST -> media.artist
                            MediaStore.Audio.Media.ALBUM -> media.album
                            MediaStore.Audio.Media.DURATION -> media.durationMs
                            MediaStore.Audio.Media.YEAR -> media.year
                            MediaStore.Audio.Media.TRACK -> media.track
                            MediaStore.Audio.Media.DATA -> media.path
                            MediaStore.Audio.Media.ALBUM_ID -> media.albumId
                            else -> null
                        }
                    }
                )
            }
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/audio"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        var rows: List<MediaRow> = emptyList()
    }
}
