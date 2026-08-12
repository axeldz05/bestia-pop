package com.bestiapop.android.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.model.DownloadConflictPolicy
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.DuplicateSongException
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.network.YouTubeExtractResult
import com.bestiapop.android.data.network.YouTubeStreamResult
import com.bestiapop.android.data.stream.StreamResolver
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.MockWebServerRule
import com.bestiapop.android.testutil.RoomTestDatabaseRule
import com.bestiapop.android.testutil.TemporaryMusicFiles
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class MusicRepositoryDownloadIntegrationTest {
    @get:Rule
    val database = RoomTestDatabaseRule()

    @get:Rule
    val server = MockWebServerRule()

    @get:Rule
    val files = TemporaryMusicFiles()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun truncatedBody_exhaustsRetries_withoutPublishingFileOrSong() = runTest {
        repeat(5) {
            server.enqueue(truncatedResponse(bytes = byteArrayOf(1, 2), declaredLength = 8))
        }
        val repository = repository(resolver(server.url("/truncated.m4a").toString()))

        expectFailure {
            repository.downloadAndSaveOnlineTrack(track(title = "Truncated"))
        }

        assertTrue(database.musicDao.getAllSongs().isEmpty())
        assertTrue(files.root.listFiles().orEmpty().isEmpty())
        repeat(5) { assertNotNull(server.takeRequest()) }
    }

    @Test
    fun partialRetry_with206_appendsExactlyOnce() = runTest {
        val prefix = byteArrayOf(1, 2, 3)
        val suffix = byteArrayOf(4, 5, 6, 7, 8)
        server.enqueue(truncatedResponse(prefix, declaredLength = 8))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 3-7/8")
                .setBody(Buffer().write(suffix))
        )
        val repository = repository(resolver(server.url("/resume.m4a").toString()))

        val saved = repository.downloadAndSaveOnlineTrack(track(title = "Resume"))

        assertArrayEquals(prefix + suffix, java.io.File(saved.uriString).readBytes())
        assertEquals(null, server.takeRequest().getHeader("Range"))
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
        assertEquals(saved.id, database.musicDao.getAllSongs().single().id)
    }

    @Test
    fun partialRetry_whenServerReturns200_restartsFromZero() = runTest {
        val partial = byteArrayOf(9, 9, 9)
        val complete = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        server.enqueue(truncatedResponse(partial, declaredLength = complete.size.toLong()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(complete)))
        val repository = repository(resolver(server.url("/restart.m4a").toString()))

        val saved = repository.downloadAndSaveOnlineTrack(track(title = "Restart"))

        assertArrayEquals(complete, java.io.File(saved.uriString).readBytes())
        server.takeRequest()
        assertEquals("bytes=${partial.size}-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun forbiddenAndGone_forceFreshResolve_andRestartWithoutRange() = runTest {
        for (code in listOf(403, 410)) {
            val calls = AtomicInteger()
            val expired = server.url("/expired-$code.m4a").toString()
            val fresh = server.url("/fresh-$code.m4a").toString()
            server.enqueue(MockResponse().setResponseCode(code))
            server.enqueue(MockResponse().setResponseCode(200).setBody("fresh-$code"))
            val repository = repository(resolver(listOf(expired, fresh), calls))

            val saved = repository.downloadAndSaveOnlineTrack(track(title = "Refresh $code"))

            assertEquals("fresh-$code", java.io.File(saved.uriString).readText())
            assertEquals(2, calls.get())
            val expiredRequest = server.takeRequest()
            val freshRequest = server.takeRequest()
            assertEquals("/expired-$code.m4a", expiredRequest.path)
            assertEquals("/fresh-$code.m4a", freshRequest.path)
            assertEquals(null, freshRequest.getHeader("Range"))
        }
    }

    @Test
    fun cancellationDuringRetry_deletesPartialAndPropagatesCancellation() = runTest {
        server.enqueue(truncatedResponse(byteArrayOf(1, 2, 3), declaredLength = 8))
        val repository = repository(
            streamResolver = resolver(server.url("/cancel.m4a").toString()),
            retryDelay = { throw CancellationException("cancel test") }
        )

        try {
            repository.downloadAndSaveOnlineTrack(track(title = "Cancelled"))
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: callers own cancellation state.
        }

        assertTrue(database.musicDao.getAllSongs().isEmpty())
        assertTrue(files.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationWhileHttpIsBlocked_cancelsCallWithoutPublishingAnything() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val repository = repository(resolver(server.url("/blocked.m4a").toString()))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.downloadAndSaveOnlineTrack(track(title = "Blocked"))
        }
        server.takeRequest(timeoutMs = 5_000L)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(database.musicDao.getAllSongs().isEmpty())
        assertTrue(files.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun duplicateConflict_preservesExisting_thenOverwriteAndSaveAsApplyChosenPolicy() = runTest {
        val existingFile = files.create("existing.m4a", "old".toByteArray())
        val existingId = database.musicDao.insertSong(
            Song(
                uriString = existingFile.absolutePath,
                title = "Conflict",
                artist = "Artist",
                album = "Old Album",
                lyrics = "existing lyrics",
                lastPlayedAt = 44L
            )
        )
        val repository = repository(resolver(server.url("/conflict.m4a").toString()))
        val requested = track(title = "Conflict")

        try {
            repository.downloadAndSaveOnlineTrack(requested)
            fail("Expected duplicate conflict")
        } catch (conflict: DuplicateSongException) {
            assertEquals(existingId, conflict.existing.id)
            assertEquals("Conflict", conflict.track.title)
        }
        assertEquals("old", existingFile.readText())
        assertEquals(1, database.musicDao.getAllSongs().size)

        server.enqueue(MockResponse().setResponseCode(200).setBody("overwrite"))
        val overwritten = repository.downloadAndSaveOnlineTrack(
            requested,
            conflictPolicy = DownloadConflictPolicy.Overwrite(existingId)
        )
        assertEquals(existingId, overwritten.id)
        assertEquals("existing lyrics", overwritten.lyrics)
        assertEquals(44L, overwritten.lastPlayedAt)
        assertEquals("overwrite", java.io.File(overwritten.uriString).readText())
        assertEquals(1, database.musicDao.getAllSongs().size)

        server.enqueue(MockResponse().setResponseCode(200).setBody("save-as"))
        val savedAs = repository.downloadAndSaveOnlineTrack(
            requested,
            conflictPolicy = DownloadConflictPolicy.SaveAs("Conflict copy")
        )
        assertEquals("Conflict copy", savedAs.title)
        assertEquals(2, database.musicDao.getAllSongs().size)
        assertEquals("save-as", java.io.File(savedAs.uriString).readText())
    }

    @Test
    fun successfulDownload_publishesCompleteBytesAndPersistsTrackMetadata() = runTest {
        val bytes = "complete audio".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))
        val phases = mutableListOf<DownloadPhase>()
        val repository = repository(resolver(server.url("/success.m4a").toString()))
        val requested = track(title = "Success", trackNumber = 7)

        val saved = repository.downloadAndSaveOnlineTrack(requested, phases::add)

        val persisted = database.musicDao.getSongById(saved.id)
        assertArrayEquals(bytes, java.io.File(saved.uriString).readBytes())
        assertEquals("Success", persisted?.title)
        assertEquals("Artist", persisted?.artist)
        assertEquals("Studio Album", persisted?.album)
        assertEquals(7, persisted?.trackNumber)
        assertEquals(210_000L, persisted?.durationMs)
        assertTrue(phases.first() is DownloadPhase.Searching)
        assertTrue(phases.last() is DownloadPhase.Completed)
    }

    private fun repository(
        streamResolver: StreamResolver,
        retryDelay: suspend (Long) -> Unit = {}
    ) = MusicRepository(
        context = context,
        database = database.database,
        streamResolver = streamResolver,
        audioStore = TemporaryRepositoryFileStore(files.root),
        downloadCallFactory = OkHttpClient(),
        metadataSource = NoNetworkRepositoryMetadata,
        downloadRetryDelay = retryDelay
    )

    private fun resolver(
        url: String,
        calls: AtomicInteger = AtomicInteger()
    ): StreamResolver = resolver(listOf(url), calls)

    private fun resolver(
        urls: List<String>,
        calls: AtomicInteger = AtomicInteger()
    ): StreamResolver {
        var index = 0
        return StreamResolver(
            extract = {
                calls.incrementAndGet()
                val url = urls[index.coerceAtMost(urls.lastIndex)]
                index++
                YouTubeExtractResult.Success(
                    YouTubeStreamResult(
                        identity = TrackIdentity(title = ""),
                        videoId = "video-$index",
                        audioUrl = url,
                        userAgent = "BestiaPop-Test"
                    )
                )
            }
        )
    }

    private fun track(
        title: String,
        trackNumber: Int = 0
    ): OnlineCatalogTrack = OnlineCatalogTrack(
        identity = TrackIdentity(
            title = title,
            artist = "Artist",
            album = "Studio Album",
            artworkUri = "https://images.invalid/art.jpg",
            durationMs = 210_000L,
            trackNumber = trackNumber
        ),
        id = "catalog-$title",
        provider = "Test"
    )

    private fun truncatedResponse(
        bytes: ByteArray,
        declaredLength: Long
    ): MockResponse = MockResponse()
        .setResponseCode(200)
        .setBody(Buffer().write(bytes))
        .setHeader("Content-Length", declaredLength)
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected download failure")
        } catch (_: java.io.IOException) {
            // Expected.
        }
    }
}
