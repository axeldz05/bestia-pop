package com.bestiapop.android.data.repository

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.room.Room
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.AudioPersistRef
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.testutil.PcmWavFixture
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first

internal data class FixtureMediaStoreRow(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val year: Int,
    val trackNumber: Int,
    val absolutePath: String
)

/**
 * One uniquely named MediaStore row owned by the test.
 *
 * Cleanup addresses only [uri] and its exact DATA path. It never queries or deletes a collection.
 */
internal class MediaStoreAudioFixture private constructor(
    private val resolver: ContentResolver,
    val uri: Uri,
    val token: String,
    val displayName: String,
    val row: FixtureMediaStoreRow
) : AutoCloseable {

    val expectedImportedUri: String =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, row.id).toString()

    val exactFile: File
        get() = File(row.absolutePath)

    fun owns(song: Song): Boolean =
        song.uriString == expectedImportedUri ||
            song.uriString == uri.toString() ||
            song.uriString == row.absolutePath ||
            song.title.contains(token)

    override fun close() {
        runCatching { resolver.delete(uri, null, null) }
        if (exactFile.exists()) {
            check(exactFile.delete()) {
                "Could not remove exact MediaStore fixture file ${exactFile.absolutePath}"
            }
        }
        check(!exactFile.exists()) {
            "Exact MediaStore fixture file survived cleanup: ${exactFile.absolutePath}"
        }
    }

    companion object {
        private const val DURATION_MS = 31_250
        private const val SNAPSHOT_TIMEOUT_MS = 5_000L

        fun create(context: Context, relativePath: String): MediaStoreAudioFixture {
            val resolver = context.contentResolver
            val token = UUID.randomUUID().toString().replace("-", "")
            val displayName = "BPFixture$token.wav"
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.TITLE, "BP Title $token")
                put(MediaStore.Audio.Media.ARTIST, "BP Artist $token")
                put(MediaStore.Audio.Media.ALBUM, "BP Album $token")
                put(MediaStore.Audio.Media.DURATION, DURATION_MS.toLong())
                put(MediaStore.Audio.Media.YEAR, 2026)
                put(MediaStore.Audio.Media.TRACK, 7)
            }
            val uri = checkNotNull(resolver.insert(collection, values)) {
                "MediaStore refused the test fixture"
            }
            try {
                checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                    output.write(PcmWavFixture.generate(DURATION_MS, toneHz = 440.0))
                }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null
                )
                val row = awaitFixtureRow(resolver, uri, token)
                return MediaStoreAudioFixture(
                    resolver = resolver,
                    uri = uri,
                    token = token,
                    displayName = displayName,
                    row = row
                )
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }

        private fun awaitFixtureRow(
            resolver: ContentResolver,
            uri: Uri,
            token: String
        ): FixtureMediaStoreRow {
            val deadline = SystemClock.uptimeMillis() + SNAPSHOT_TIMEOUT_MS
            var last: FixtureMediaStoreRow? = null
            while (SystemClock.uptimeMillis() < deadline) {
                last = queryFixtureRow(resolver, uri)
                if (
                    last != null &&
                    last.durationMs >= 30_000L &&
                    last.title.contains(token) &&
                    last.absolutePath.isNotBlank()
                ) {
                    return last
                }
                SystemClock.sleep(50L)
            }
            error("MediaStore fixture metadata did not settle: $last")
        }

        private fun queryFixtureRow(
            resolver: ContentResolver,
            uri: Uri
        ): FixtureMediaStoreRow? {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DATA
            )
            return resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                FixtureMediaStoreRow(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)),
                    title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE))
                        ?: "Track ${ContentUris.parseId(uri)}",
                    artist = cursor
                        .getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
                        .mediaStoreValueOr("Unknown Artist"),
                    album = cursor
                        .getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM))
                        .mediaStoreValueOr("Unknown Album"),
                    durationMs = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    ),
                    year = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)),
                    trackNumber = cursor.getInt(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                    ),
                    absolutePath = cursor
                        .getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                        .orEmpty()
                )
            }
        }

        private fun String?.mediaStoreValueOr(fallback: String): String =
            takeUnless { it.isNullOrEmpty() || it == MediaStore.UNKNOWN_STRING } ?: fallback
    }
}

/**
 * Repository harness whose MediaStore view contains only the fixture URI.
 *
 * The provider delegates that one URI to the real ContentResolver. This exercises the production
 * repository and mapper without allowing the test to enumerate personal media.
 */
internal class MediaStoreRepositoryHarness(
    context: Context,
    fixture: MediaStoreAudioFixture,
    managedFixtureFile: File? = null
) : AutoCloseable {
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val repositoryContext = FixtureOnlyMediaStoreContext(context, fixture.uri)

    val repository = MusicRepository(
        context = repositoryContext,
        database = database,
        audioStore = FixtureOnlyRepositoryFileStore(context, managedFixtureFile)
    )

    suspend fun removeFixtureRows(fixture: MediaStoreAudioFixture) {
        val rows = repository.allSongsFlow.first().filter(fixture::owns)
        repository.deleteSongsFromApp(rows)
    }

    override fun close() {
        database.close()
    }
}

private class FixtureOnlyMediaStoreContext(
    base: Context,
    fixtureUri: Uri
) : ContextWrapper(base) {
    private val fixtureResolver = FixtureOnlyMediaStoreProvider(
        base.contentResolver,
        fixtureUri
    ).let { provider ->
        provider.attachInfo(
            base,
            ProviderInfo().apply {
                authority = MediaStore.AUTHORITY
                exported = false
            }
        )
        ContentResolver.wrap(provider)
    }

    override fun getApplicationContext(): Context = this

    override fun getContentResolver(): ContentResolver = fixtureResolver
}

private class FixtureOnlyMediaStoreProvider(
    private val realResolver: ContentResolver,
    private val fixtureUri: Uri
) : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = realResolver.query(fixtureUri, projection, null, null, sortOrder)

    override fun getType(uri: Uri): String? = realResolver.getType(fixtureUri)

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        error("Fixture-only provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        error("Fixture-only provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = error("Fixture-only provider is read-only")
}

private class FixtureOnlyRepositoryFileStore(
    context: Context,
    private val managedFixtureFile: File?
) : RepositoryFileStore {
    private val delegate = MusicFileStore(context)

    override fun canonicalize(uriString: String, folderPath: String): AudioPersistRef =
        delegate.canonicalize(uriString, folderPath)

    override fun applyDataSource(retriever: MediaMetadataRetriever, ref: AudioPersistRef) =
        delegate.applyDataSource(retriever, ref)

    override fun applyDataSource(extractor: MediaExtractor, ref: AudioPersistRef) =
        delegate.applyDataSource(extractor, ref)

    override fun applyDataSource(player: MediaPlayer, ref: AudioPersistRef) =
        delegate.applyDataSource(player, ref)

    override fun prepareWrite(displayName: String): StorageUtils.PendingWrite =
        error("Fixture repository cannot write managed audio")

    override fun delete(ref: AudioPersistRef): Nothing =
        error("Fixture repository cannot delete audio")

    override fun listManaged(): List<File> = listOfNotNull(managedFixtureFile)

    override fun writableFile(uriString: String, folderPath: String): File? =
        delegate.writableFile(uriString, folderPath)
}
