package com.bestiapop.android.ui.discover

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
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.ListenBrainzEndpoints
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.network.MetadataFetcherEndpoints
import com.bestiapop.android.data.network.YouTubeEndpoints
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.data.preferences.DownloadPreferencesRepository
import com.bestiapop.android.data.preferences.DownloadSettings
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.NAV_PLAYLISTS
import com.bestiapop.android.data.preferences.UiNavSnapshot
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.service.DownloadNotificationHelper
import com.bestiapop.android.testutil.PcmWavFixture
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * Full production graph fixture for Discover. Only the three public HTTP boundaries are redirected
 * to MockWebServer; ListenBrainz preferences, Room, playback and downloads remain real.
 */
internal class DiscoverE2ETestFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as BestiaPopApplication
    private val repository = application.musicRepository
    private val dao = AppDatabase.getDatabase(context).musicDao()
    private val libraryPreferences = LibraryPreferencesRepository(context)
    private val listenPreferences = ListenBrainzPreferencesRepository(context)
    private val downloadPreferences = DownloadPreferencesRepository(context)
    private val audioStore = MusicFileStore(context)
    private val notificationHelper = DownloadNotificationHelper(context)
    private val server = MockWebServer()
    private val token = UUID.randomUUID().toString().replace("-", "").take(10)
    private val playlistMbid = UUID.randomUUID().toString()
    private val localRecordingMbid = UUID.randomUUID().toString()
    private val remoteRecordingMbid = UUID.randomUUID().toString()
    private val artistMbid = UUID.randomUUID().toString()
    private val videoId = "Bp${token.take(9)}"
    private val audioBytes = PcmWavFixture.generate(durationMs = WAV_DURATION_MS, toneHz = 660.0)
    private val localFile = File(context.cacheDir, "discover-local-$token.wav")
    private val audioRequests = AtomicInteger()
    private val lbRequests = AtomicInteger()
    private val youtubeSearchRequests = AtomicInteger()
    private val youtubePlayerRequests = AtomicInteger()

    val username = "discover_user_$token"
    val fakeToken = "discover_token_$token"
    val playlistTitle = "Para Ti Hermético $token"
    val localTitle = "Local Discover $token"
    val localArtist = "BestiaPop Discover Artist $token"
    val remoteTitle = "Remote Discover $token"
    val remoteArtist = "BestiaPop Remote Artist $token"
    val remoteAlbum = "Discover Album $token"
    val downloadId: String = TrackMatchKeys.downloadIdFor(remoteArtist, remoteTitle)

    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousInitialScanCompleted: Boolean? = null
    private var previousNavSnapshot: UiNavSnapshot? = null
    private var previousListenSettings: ListenBrainzSettings? = null
    private var previousDownloadSettings: DownloadSettings? = null

    fun prepare() {
        grantStartupPermissions()
        server.dispatcher = FixtureDispatcher()
        server.start()
        configureNetworkOverrides()

        runBlocking {
            withTimeout(STATE_TIMEOUT_MS) {
                application.processDownloads.awaitHydrated()
                previousInitialScanCompleted = libraryPreferences.isInitialScanCompleted()
                previousNavSnapshot = libraryPreferences.navSnapshotFlow.first()
                previousListenSettings = listenPreferences.settingsFlow.first()
                previousDownloadSettings = downloadPreferences.settingsFlow.first()

                deleteFixtureArtifacts()
                seedLocalSong()
                installFakePreferences()
            }
        }
        notificationHelper.cancel()
    }

    fun launchMainActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java).also {
            it.moveToState(Lifecycle.State.RESUMED)
        }
    }

    fun remoteDownloadSucceeded(): Boolean {
        val download = application.processDownloads.findByTrack(
            downloadId = downloadId,
            artist = remoteArtist,
            title = remoteTitle
        )
        return download?.state == CandidateDownloadState.SUCCESS &&
            download.source == ActiveDownloadSource.DISCOVER &&
            fixtureRemoteSongs().size == 1
    }

    fun saveWhileListeningSucceeded(): Boolean = fixtureRemoteSongs().size == 1

    fun verifyExactlyOnePersistedRemoteAndFile(expectedSource: ActiveDownloadSource? = null) {
        val songs = fixtureRemoteSongs()
        check(songs.size == 1) {
            "Expected exactly one remote Song, found ${songs.size}. ${diagnostic()}"
        }
        val song = songs.single()
        check(song.title == remoteTitle)
        check(song.artist == remoteArtist)
        check(song.album == remoteAlbum)

        expectedSource?.let { source ->
            val download = application.processDownloads.findByTrack(downloadId, remoteArtist, remoteTitle)
            check(download?.source == source) {
                "Expected download source $source, got ${download?.source}. ${diagnostic()}"
            }
        }

        val persistentNames = audioStore.listManagedNames()
            .filter { it.contains(token, ignoreCase = true) }
        check(persistentNames.size == 1) {
            "Expected exactly one persistent fixture file, found $persistentNames"
        }

        val descriptor = checkNotNull(
            audioStore.openRead(audioStore.canonicalize(song.uriString, song.folderPath))
        ) {
            "Persisted remote audio cannot be opened: ${song.uriString}"
        }
        val stored = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        check(stored.contentEquals(audioBytes)) {
            "Persisted remote bytes differ: expected=${audioBytes.size}, actual=${stored.size}"
        }
        check(lbRequests.get() > 0) { "ListenBrainz fake boundary was not exercised" }
        check(youtubeSearchRequests.get() > 0) { "YouTube search fake was not exercised" }
        check(youtubePlayerRequests.get() > 0) { "YouTube player fake was not exercised" }
        check(audioRequests.get() > 0) { "Local WAV endpoint was not exercised" }
    }

    fun diagnostic(): String {
        val songs = runCatching { fixtureRemoteSongs() }
            .getOrDefault(emptyList())
            .joinToString { "${it.id}:${it.uriString}" }
        val download = application.processDownloads.findByTrack(downloadId, remoteArtist, remoteTitle)
        return "remoteSongs=[$songs], download=${download?.state}:${download?.source}:" +
            "${download?.progressPercent}:${download?.errorMessage}, " +
            "runtime=${application.playbackRuntime.currentItem.value?.title}:" +
            "${application.playbackRuntime.playbackPositionMs.value}, " +
            "requests=lb:${lbRequests.get()},ytSearch:${youtubeSearchRequests.get()}," +
            "ytPlayer:${youtubePlayerRequests.get()},audio:${audioRequests.get()}"
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
        cleanup {
            runBlocking {
                withTimeout(STATE_TIMEOUT_MS) {
                    deleteFixtureArtifacts()
                    previousInitialScanCompleted?.let {
                        libraryPreferences.setInitialScanCompleted(it)
                    }
                    previousNavSnapshot?.let { libraryPreferences.setNavSnapshot(it) }
                    previousListenSettings?.let { restoreListenSettings(it) }
                    previousDownloadSettings?.let { downloadPreferences.restoreForTest(it) }
                }
            }
        }
        cleanup { notificationHelper.cancel() }
        cleanup {
            context.getSystemService(NotificationManager::class.java)
                .cancel(DownloadNotificationHelper.NOTIFICATION_ID)
        }
        cleanup { ListenBrainzClient.resetTestOverrides() }
        cleanup { MetadataFetcher.resetTestOverrides() }
        cleanup { YouTubeExtractor.resetTestOverrides() }
        cleanup { server.shutdown() }
        firstFailure?.let { throw it }
    }

    private fun configureNetworkOverrides() {
        val baseUrl = server.url("/").toString()
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .build()
        ListenBrainzClient.configureForTest(
            http = client,
            endpoints = ListenBrainzEndpoints(
                apiBaseUrl = server.url("/1").toString().trimEnd('/')
            )
        )
        MetadataFetcher.configureForTest(
            http = client,
            endpoints = MetadataFetcherEndpoints(
                deezerBaseUrl = baseUrl,
                itunesBaseUrl = baseUrl,
                lyricsBaseUrl = baseUrl
            )
        )
        YouTubeExtractor.configureForTest(
            http = client,
            endpoints = YouTubeEndpoints(
                webBaseUrl = baseUrl,
                googleApiBaseUrl = baseUrl
            )
        )
    }

    private suspend fun installFakePreferences() {
        libraryPreferences.setInitialScanCompleted(true)
        libraryPreferences.setNavSnapshot(UiNavSnapshot(navIndex = NAV_PLAYLISTS))
        downloadPreferences.setDownloadOnMeteredNetwork(true)

        listenPreferences.clear()
        listenPreferences.setToken(fakeToken)
        listenPreferences.setUsername(username)
        listenPreferences.setEnabled(true)
        listenPreferences.setDiscoverEnabled(true)
        listenPreferences.setSaveWhileListening(true)
        listenPreferences.setSaveWhileListeningPercent(5)
    }

    private suspend fun restoreListenSettings(settings: ListenBrainzSettings) {
        listenPreferences.clear()
        listenPreferences.setToken(settings.userToken)
        listenPreferences.setUsername(settings.username)
        listenPreferences.setEnabled(settings.enabled)
        listenPreferences.setDiscoverEnabled(settings.discoverEnabled)
        listenPreferences.setSaveWhileListening(settings.saveWhileListening)
        listenPreferences.setSaveWhileListeningPercent(settings.saveWhileListeningPercent)
        settings.lastSyncAt?.let { listenPreferences.setLastSyncAt(it) }
    }

    private suspend fun seedLocalSong() {
        localFile.writeBytes(audioBytes)
        val id = dao.insertSong(
            Song(
                uriString = localFile.absolutePath,
                title = localTitle,
                artist = localArtist,
                album = "Local Discover Album $token",
                genre = "Fixture",
                durationMs = WAV_DURATION_MS.toLong(),
                folderPath = localFile.parent.orEmpty(),
                dateAdded = System.currentTimeMillis()
            )
        )
        check(id > 0L) { "Could not seed local Discover Song" }
    }

    private fun fixtureRemoteSongs(): List<Song> = runBlocking {
        repository.getAllSongsSync().filter {
            it.artist == remoteArtist && it.title == remoteTitle
        }
    }

    private suspend fun deleteFixtureArtifacts() {
        instrumentation.runOnMainSync {
            application.playbackRuntime.stopRadio()
            application.playbackRuntime.queue.value.indices
                .filter { index ->
                    val item = application.playbackRuntime.queue.value.getOrNull(index)
                    item?.title?.contains(token) == true || item?.artist?.contains(token) == true
                }
                .sortedDescending()
                .forEach(application.playbackRuntime::removeFromQueue)
        }

        val downloadIds = application.processDownloads.downloads.value
            .filter { it.title.contains(token) || it.artist.contains(token) }
            .map { it.id }
            .toSet() + downloadId
        downloadIds.forEach { id ->
            application.processDownloads.cancelAndJoin(id)
            application.processDownloads.dismiss(id)
        }

        val songs = repository.getAllSongsSync().filter {
            it.title.contains(token) || it.artist.contains(token)
        }
        if (songs.isNotEmpty()) repository.deleteSongsFromDevice(songs)

        StorageUtils.publicBestiaPopDir()
            .listFiles()
            .orEmpty()
            .filter { it.name.contains(token, ignoreCase = true) }
            .forEach { file ->
                check(!file.exists() || file.delete()) {
                    "Could not delete Discover fixture file ${file.absolutePath}"
                }
            }
        context.cacheDir.listFiles()
            .orEmpty()
            .filter { it.name.contains(token, ignoreCase = true) }
            .forEach { file ->
                check(!file.exists() || file.deleteRecursively()) {
                    "Could not delete Discover cache fixture ${file.absolutePath}"
                }
            }
        application.processDownloads.flush()
    }

    private fun grantStartupPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissions.forEach { permission ->
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
        }
    }

    private inner class FixtureDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath.orEmpty()
            return when {
                path.startsWith("/1/") -> {
                    lbRequests.incrementAndGet()
                    listenBrainzResponse(path)
                }
                path == "/youtubei/v1/search" -> youtubeSearchResponse(request)
                path == "/youtubei/v1/player" -> youtubePlayerResponse(request)
                path == "/watch" -> MockResponse()
                    .setResponseCode(200)
                    .setBody("""<html>"visitorData":"discover-fixture-visitor"</html>""")
                path == "/audio/discover.wav" -> {
                    audioRequests.incrementAndGet()
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "audio/wav")
                        .setBody(Buffer().write(audioBytes))
                }
                path == "/api/get" -> jsonResponse(
                    """{"plainLyrics":"Hermetic Discover fixture lyric"}"""
                )
                path == "/api/search" -> jsonResponse("[]")
                path == "/search" -> {
                    if (request.requestUrl?.queryParameter("term") != null) {
                        jsonResponse("""{"results":[]}""")
                    } else {
                        jsonResponse("""{"data":[]}""")
                    }
                }
                path.startsWith("/artist/") ||
                    path in setOf(
                        "/search/track",
                        "/search/album",
                        "/search/playlist",
                        "/search/artist"
                    ) -> jsonResponse("""{"data":[]}""")
                path == "/results" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun listenBrainzResponse(path: String): MockResponse = when {
            path == "/1/user/$username/playlists/createdfor" -> jsonResponse(createdForJson())
            path == "/1/playlist/$playlistMbid" -> jsonResponse(playlistDetailJson())
            path == "/1/cf/recommendation/user/$username/recording" ->
                jsonResponse(cfRecommendationsJson())
            path == "/1/metadata/recording/" -> jsonResponse(recordingMetadataJson())
            path == "/1/metadata/lookup/" -> jsonResponse(
                """{"artist_mbids":["$artistMbid"],"recording_mbid":"$localRecordingMbid"}"""
            )
            path == "/1/lb-radio/artist/$artistMbid" -> jsonResponse(
                """
                {
                  "$artistMbid": [{
                    "recording_mbid": "$remoteRecordingMbid",
                    "similar_artist_mbid": "$artistMbid",
                    "similar_artist_name": "$remoteArtist",
                    "total_listen_count": 42
                  }]
                }
                """.trimIndent()
            )
            path == "/1/submit-listens" -> jsonResponse("""{"status":"ok"}""")
            else -> MockResponse().setResponseCode(404)
        }

        private fun youtubeSearchResponse(request: RecordedRequest): MockResponse {
            youtubeSearchRequests.incrementAndGet()
            val query = runCatching {
                JSONObject(request.body.readUtf8()).optString("query")
            }.getOrDefault("")
            return if (query == "$remoteArtist $remoteTitle") {
                jsonResponse(youtubeSearchJson())
            } else {
                jsonResponse("""{"contents":{"sectionListRenderer":{"contents":[]}}}""")
            }
        }

        private fun youtubePlayerResponse(request: RecordedRequest): MockResponse {
            youtubePlayerRequests.incrementAndGet()
            val requestedId = runCatching {
                JSONObject(request.body.readUtf8()).optString("videoId")
            }.getOrDefault("")
            return if (requestedId == videoId) {
                jsonResponse(youtubePlayerJson())
            } else {
                jsonResponse("""{"playabilityStatus":{"status":"ERROR","reason":"fixture only"}}""")
            }
        }

        private fun createdForJson(): String = """
            {
              "playlists": [{
                "playlist": {
                  "identifier": "https://listenbrainz.org/playlist/$playlistMbid",
                  "title": "$playlistTitle",
                  "annotation": "Mixta Local + Remote",
                  "num_tracks": 2
                }
              }]
            }
        """.trimIndent()

        private fun playlistDetailJson(): String = """
            {
              "playlist": {
                "identifier": "https://listenbrainz.org/playlist/$playlistMbid",
                "title": "$playlistTitle",
                "annotation": "Mixta Local + Remote",
                "track": [
                  {
                    "title": "$localTitle",
                    "creator": "$localArtist",
                    "album": "Local Discover Album $token",
                    "identifier": "https://musicbrainz.org/recording/$localRecordingMbid"
                  },
                  {
                    "title": "$remoteTitle",
                    "creator": "$remoteArtist",
                    "album": "$remoteAlbum",
                    "identifier": "https://musicbrainz.org/recording/$remoteRecordingMbid"
                  }
                ]
              }
            }
        """.trimIndent()

        private fun cfRecommendationsJson(): String = """
            {
              "payload": {
                "user_name": "$username",
                "type": "top",
                "total_mbid_count": 2,
                "last_updated": 1700000000,
                "mbids": [
                  {"recording_mbid":"$localRecordingMbid","score":9.0},
                  {"recording_mbid":"$remoteRecordingMbid","score":8.0}
                ]
              }
            }
        """.trimIndent()

        private fun recordingMetadataJson(): String = """
            {
              "$localRecordingMbid": {
                "recording": {"name": "$localTitle"},
                "artist": {"name": "$localArtist"},
                "release": {"name": "Local Discover Album $token"}
              },
              "$remoteRecordingMbid": {
                "recording": {"name": "$remoteTitle"},
                "artist": {"name": "$remoteArtist"},
                "release": {"name": "$remoteAlbum"}
              }
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
                          "videoId": "$videoId",
                          "title": {"runs": [{"text": "$remoteArtist - $remoteTitle (Official Audio)"}]},
                          "ownerText": {"runs": [{"text": "$remoteArtist - Topic"}]},
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
                "title": "$remoteTitle",
                "author": "$remoteArtist",
                "lengthSeconds": "3"
              },
              "streamingData": {
                "adaptiveFormats": [{
                  "url": "${server.url("/audio/discover.wav")}",
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
        const val WAV_DURATION_MS = 2_500
        const val STATE_TIMEOUT_MS = 15_000L
    }
}
