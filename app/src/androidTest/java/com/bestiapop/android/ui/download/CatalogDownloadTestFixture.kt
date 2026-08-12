package com.bestiapop.android.ui.download

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.network.MetadataFetcherEndpoints
import com.bestiapop.android.data.network.YouTubeEndpoints
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.data.preferences.DownloadPreferencesRepository
import com.bestiapop.android.data.preferences.DownloadSettings
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.UiNavSnapshot
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.service.DownloadNotificationHelper
import com.bestiapop.android.testutil.PcmWavFixture
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject

internal object CatalogDownloadTestContract {
    private val token = UUID.randomUUID().toString().replace("-", "")

    val SEARCH_QUERY = "bestiapop hermetic catalog fixture $token"
    val TITLE = "BestiaPopFixtureCatalogTrack$token"
    val ARTIST = "BestiaPopFixtureArtist$token"
    val ALBUM = "BestiaPopFixtureAlbum$token"
    val VIDEO_ID = "Bp${token.take(9)}"
    const val TRACK_NUMBER = 3
    val FILE_NAME = "${ARTIST}_${TITLE}.wav"
    val DOWNLOAD_ID: String = TrackMatchKeys.downloadIdFor(ARTIST, TITLE)
}

/**
 * Local HTTP boundary and exact persistent-state owner for [CatalogDownloadFunctionalTest].
 *
 * MainActivity, BestiaPopApplication, Room, the process download coordinator and storage are the
 * production graph. Only MetadataFetcher/YouTubeExtractor endpoints and their clients are replaced.
 */
internal class CatalogDownloadTestFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as BestiaPopApplication
    private val repository = application.musicRepository
    private val audioStore = MusicFileStore(context)
    private val libraryPreferences = LibraryPreferencesRepository(context)
    private val downloadPreferences = DownloadPreferencesRepository(context)
    private val notificationHelper = DownloadNotificationHelper(context)
    private val server = MockWebServer()
    private val releaseAudioResponse = CountDownLatch(1)
    private val audioBytes = PcmWavFixture.generate(durationMs = 3_000, toneHz = 440.0)

    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousInitialScanCompleted: Boolean? = null
    private var previousNavSnapshot: UiNavSnapshot? = null
    private var previousDownloadSettings: DownloadSettings? = null

    fun prepare() {
        grantStartupPermissions()
        server.dispatcher = FixtureDispatcher()
        server.start()

        val localBaseUrl = server.url("/").toString()
        val localClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .build()
        MetadataFetcher.configureForTest(
            http = localClient,
            endpoints = MetadataFetcherEndpoints(
                deezerBaseUrl = localBaseUrl,
                itunesBaseUrl = localBaseUrl,
                lyricsBaseUrl = localBaseUrl
            )
        )
        YouTubeExtractor.configureForTest(
            http = localClient,
            endpoints = YouTubeEndpoints(
                webBaseUrl = localBaseUrl,
                googleApiBaseUrl = localBaseUrl
            )
        )

        runBlocking {
            withTimeout(CLEANUP_TIMEOUT_MS) {
                application.processDownloads.awaitHydrated()
                application.processDownloads.cancelAndJoin(CatalogDownloadTestContract.DOWNLOAD_ID)
                deleteFixtureArtifacts()
                application.processDownloads.flush()

                previousInitialScanCompleted = libraryPreferences.isInitialScanCompleted()
                previousNavSnapshot = libraryPreferences.navSnapshotFlow.first()
                previousDownloadSettings = downloadPreferences.settingsFlow.first()
                libraryPreferences.setInitialScanCompleted(true)
                libraryPreferences.setNavSnapshot(UiNavSnapshot())
                downloadPreferences.setDownloadOnMeteredNetwork(true)
            }
        }
        notificationHelper.cancel()
    }

    fun launchMainActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java).also {
            it.moveToState(Lifecycle.State.RESUMED)
        }
    }

    fun releaseAudioDownload() {
        releaseAudioResponse.countDown()
    }

    fun isDownloadingAt(percent: Int): Boolean =
        fixtureDownload()?.let {
            it.state == CandidateDownloadState.DOWNLOADING &&
                it.progressPercent == percent
        } == true

    fun isDownloadComplete(): Boolean =
        fixtureDownload()?.let {
            it.state == CandidateDownloadState.SUCCESS &&
                it.progressPercent == 100 &&
                it.resultSongId != null
        } == true

    fun persistedSong(): Song {
        val matches = runBlocking { fixtureSongs() }
        check(matches.size == 1) {
            "Expected one fixture Song in Room, found ${matches.size}. ${diagnostic()}"
        }
        return matches.single()
    }

    fun verifyPersistedSongAndFile(song: Song) {
        check(song.id > 0L) { "Fixture Song has no Room id: $song" }
        check(song.title == CatalogDownloadTestContract.TITLE)
        check(song.artist == CatalogDownloadTestContract.ARTIST)
        check(song.album == CatalogDownloadTestContract.ALBUM)
        check(song.trackNumber == CatalogDownloadTestContract.TRACK_NUMBER)
        check(
            SongPathNormalizer.fileName(song.uriString, song.folderPath) ==
                CatalogDownloadTestContract.FILE_NAME
        ) {
            "Unexpected stored filename: uri=${song.uriString}, folder=${song.folderPath}"
        }

        val ref = audioStore.canonicalize(song.uriString, song.folderPath)
        val descriptor = checkNotNull(audioStore.openRead(ref)) {
            "Stored fixture audio cannot be opened: $ref"
        }
        val storedBytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        check(storedBytes.contentEquals(audioBytes)) {
            "Stored fixture bytes differ: expected=${audioBytes.size}, actual=${storedBytes.size}"
        }
    }

    fun diagnostic(): String {
        val rows = listOfNotNull(fixtureDownload()).joinToString {
            "${it.state}:${it.progressPercent}:${it.errorMessage.orEmpty()}"
        }
        val songs = runCatching {
            runBlocking { fixtureSongs() }
                .joinToString { "${it.id}:${it.uriString}" }
        }.getOrElse { "Room diagnostic failed: ${it.message}" }
        val expectedFile = expectedFile()
        return "downloads=[$rows], fixtureSongs=[$songs], " +
            "expectedFile=${expectedFile.absolutePath}:${expectedFile.exists()}"
    }

    override fun close() {
        var firstFailure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            runCatching(block).exceptionOrNull()?.let { failure ->
                if (firstFailure == null) firstFailure = failure
                else firstFailure?.addSuppressed(failure)
            }
        }

        cleanup { scenario?.close() }
        releaseAudioResponse.countDown()
        cleanup {
            runBlocking {
                withTimeout(CLEANUP_TIMEOUT_MS) {
                    application.processDownloads.cancelAndJoin(
                        CatalogDownloadTestContract.DOWNLOAD_ID
                    )
                    deleteFixtureArtifacts()
                    application.processDownloads.flush()
                }
            }
        }
        cleanup { notificationHelper.cancel() }
        cleanup {
            context.getSystemService(NotificationManager::class.java)
                .cancel(DownloadNotificationHelper.NOTIFICATION_ID)
        }
        cleanup {
            runBlocking {
                previousInitialScanCompleted?.let {
                    libraryPreferences.setInitialScanCompleted(it)
                }
            }
        }
        cleanup {
            runBlocking {
                previousNavSnapshot?.let { libraryPreferences.setNavSnapshot(it) }
            }
        }
        cleanup {
            runBlocking {
                previousDownloadSettings?.let { downloadPreferences.restoreForTest(it) }
            }
        }
        cleanup { MetadataFetcher.resetTestOverrides() }
        cleanup { YouTubeExtractor.resetTestOverrides() }
        cleanup { server.shutdown() }
        firstFailure?.let { throw it }
    }

    private suspend fun fixtureSongs(): List<Song> =
        repository.getAllSongsSync().filter {
            it.artist == CatalogDownloadTestContract.ARTIST &&
                it.title == CatalogDownloadTestContract.TITLE
        }

    private fun fixtureDownload() = application.processDownloads.findByTrack(
        downloadId = CatalogDownloadTestContract.DOWNLOAD_ID,
        artist = CatalogDownloadTestContract.ARTIST,
        title = CatalogDownloadTestContract.TITLE
    )

    private suspend fun deleteFixtureArtifacts() {
        var firstFailure: Throwable? = null
        fun recordFailure(failure: Throwable) {
            if (firstFailure == null) firstFailure = failure
            else firstFailure?.addSuppressed(failure)
        }

        runCatching {
            val rows = fixtureSongs()
            if (rows.isNotEmpty()) repository.deleteSongsFromDevice(rows)
        }.exceptionOrNull()?.let(::recordFailure)

        runCatching {
            val exactFile = expectedFile()
            audioStore.delete(
                audioStore.canonicalize(exactFile.absolutePath, exactFile.parent.orEmpty())
            )
        }.exceptionOrNull()?.let(::recordFailure)

        context.cacheDir.listFiles()
            .orEmpty()
            .filter {
                it.name.startsWith("bp_") &&
                    it.name.endsWith(CatalogDownloadTestContract.FILE_NAME)
            }
            .forEach { file ->
                runCatching {
                    check(!file.exists() || file.delete()) {
                        "Could not delete exact catalog fixture cache file ${file.absolutePath}"
                    }
                }.exceptionOrNull()?.let(::recordFailure)
            }

        firstFailure?.let { throw it }
    }

    private fun expectedFile(): File = File(
        StorageUtils.publicBestiaPopDir(),
        CatalogDownloadTestContract.FILE_NAME
    )

    private fun grantStartupPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissions.forEach { permission ->
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    permission
                )
            }
        }
    }

    private inner class FixtureDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath.orEmpty()
            return when (path) {
                "/search" -> catalogSearchResponse(request)
                "/search/album", "/search/playlist", "/search/artist", "/search/track" ->
                    jsonResponse("""{"data":[]}""")
                "/api/get" -> lyricsResponse(request)
                "/api/search" -> jsonResponse("[]")
                "/youtubei/v1/search" -> youtubeSearchResponse(request)
                "/youtubei/v1/player" -> youtubePlayerResponse(request)
                "/watch" -> MockResponse()
                    .setResponseCode(200)
                    .setBody("""<html>"visitorData":"fixture-visitor"</html>""")
                "/audio/fixture.wav" -> audioResponse()
                "/cover.jpg", "/results" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun catalogSearchResponse(request: RecordedRequest): MockResponse {
            val url = request.requestUrl
            return when {
                url?.queryParameter("q") == CatalogDownloadTestContract.SEARCH_QUERY ->
                    jsonResponse(catalogSearchJson())
                url?.queryParameter("term") != null -> jsonResponse("""{"results":[]}""")
                else -> jsonResponse("""{"data":[]}""")
            }
        }

        private fun lyricsResponse(request: RecordedRequest): MockResponse {
            val url = request.requestUrl
            val fixtureRequest =
                url?.queryParameter("artist_name") == CatalogDownloadTestContract.ARTIST &&
                    url.queryParameter("track_name") == CatalogDownloadTestContract.TITLE
            return if (fixtureRequest) {
                jsonResponse("""{"plainLyrics":"Hermetic fixture lyric"}""")
            } else {
                MockResponse().setResponseCode(404)
            }
        }

        private fun youtubeSearchResponse(request: RecordedRequest): MockResponse {
            val query = runCatching {
                JSONObject(request.body.readUtf8()).optString("query")
            }.getOrDefault("")
            val expected = "${CatalogDownloadTestContract.ARTIST} ${CatalogDownloadTestContract.TITLE}"
            return if (query == expected) {
                jsonResponse(youtubeSearchJson())
            } else {
                jsonResponse("""{"contents":{"sectionListRenderer":{"contents":[]}}}""")
            }
        }

        private fun youtubePlayerResponse(request: RecordedRequest): MockResponse {
            val videoId = runCatching {
                JSONObject(request.body.readUtf8()).optString("videoId")
            }.getOrDefault("")
            return if (videoId == CatalogDownloadTestContract.VIDEO_ID) {
                jsonResponse(youtubePlayerJson())
            } else {
                jsonResponse("""{"playabilityStatus":{"status":"ERROR","reason":"fixture only"}}""")
            }
        }

        private fun audioResponse(): MockResponse {
            val released = releaseAudioResponse.await(
                NETWORK_BOUNDARY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            if (!released) {
                return MockResponse()
                    .setResponseCode(504)
                    .setBody("Fixture audio was not released before timeout")
            }
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(audioBytes))
        }

        private fun catalogSearchJson(): String = """
            {
              "data": [{
                "id": 4242,
                "title": "${CatalogDownloadTestContract.TITLE}",
                "duration": 3,
                "track_position": ${CatalogDownloadTestContract.TRACK_NUMBER},
                "artist": {"name": "${CatalogDownloadTestContract.ARTIST}"},
                "album": {
                  "title": "${CatalogDownloadTestContract.ALBUM}",
                  "cover_xl": "${server.url("/cover.jpg")}"
                }
              }]
            }
        """.trimIndent()

        private fun youtubeSearchJson(): String = """
            {
              "contents": {
                "sectionListRenderer": {
                  "contents": [{
                    "itemSectionRenderer": {
                      "contents": [{
                        "videoRenderer": {
                          "videoId": "${CatalogDownloadTestContract.VIDEO_ID}",
                          "title": {"runs": [{"text": "${CatalogDownloadTestContract.ARTIST} - ${CatalogDownloadTestContract.TITLE} (Official Audio)"}]},
                          "ownerText": {"runs": [{"text": "${CatalogDownloadTestContract.ARTIST} - Topic"}]},
                          "lengthText": {"simpleText": "0:03"}
                        }
                      }]
                    }
                  }]
                }
              }
            }
        """.trimIndent()

        private fun youtubePlayerJson(): String = """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {
                "title": "${CatalogDownloadTestContract.TITLE}",
                "author": "${CatalogDownloadTestContract.ARTIST}",
                "lengthSeconds": "3"
              },
              "streamingData": {
                "adaptiveFormats": [{
                  "url": "${server.url("/audio/fixture.wav")}",
                  "mimeType": "audio/wav",
                  "bitrate": 128000
                }]
              }
            }
        """.trimIndent()

        private fun jsonResponse(body: String): MockResponse = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private companion object {
        const val CLEANUP_TIMEOUT_MS = 10_000L
        const val NETWORK_BOUNDARY_TIMEOUT_MS = 20_000L
    }
}
