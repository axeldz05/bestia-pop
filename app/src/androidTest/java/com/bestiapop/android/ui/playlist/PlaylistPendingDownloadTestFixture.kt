package com.bestiapop.android.ui.playlist

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.network.MetadataFetcherEndpoints
import com.bestiapop.android.data.network.YouTubeEndpoints
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.data.preferences.DownloadPreferencesRepository
import com.bestiapop.android.data.preferences.DownloadSettings
import com.bestiapop.android.data.preferences.LibraryDisplaySettings
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.NAV_PLAYLISTS
import com.bestiapop.android.data.preferences.PLAYLIST_DETAIL_LOCAL
import com.bestiapop.android.data.preferences.UiNavSnapshot
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.service.DownloadNotificationHelper
import com.bestiapop.android.testutil.PcmWavFixture
import java.io.File
import java.io.FileOutputStream
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
 * Hermetic HTTP boundary and exact persistent-state owner for pending playlist conversion.
 *
 * MainActivity, BestiaPopApplication, Room, ProcessDownloadCoordinator and file persistence are the
 * production graph. Only MetadataFetcher/YouTubeExtractor endpoints and clients are overridden.
 */
internal class PlaylistPendingDownloadTestFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as BestiaPopApplication
    private val repository = application.musicRepository
    private val libraryPreferences = LibraryPreferencesRepository(context)
    private val downloadPreferences = DownloadPreferencesRepository(context)
    private val notificationHelper = DownloadNotificationHelper(context)
    private val audioStore = MusicFileStore(context)
    private val server = MockWebServer()
    private val token = UUID.randomUUID().toString().replace("-", "").take(10)
    private val fixtureDir = File(context.cacheDir, "$FIXTURE_DIR_PREFIX$token")
    private val audioBytes = PcmWavFixture.generate(
        durationMs = WAV_DURATION_MS,
        toneHz = 550.0
    )
    private val searchRequests = AtomicInteger()
    private val playerRequests = AtomicInteger()
    private val audioRequests = AtomicInteger()
    private val lyricsRequests = AtomicInteger()

    val playlistName = "$PLAYLIST_PREFIX$token"
    val title = "$SONG_PREFIX$token"
    val artist = "$ARTIST_PREFIX$token"
    val album = "$ALBUM_PREFIX$token"
    private val videoId = "Bp${token.take(9)}"
    private val downloadId = TrackMatchKeys.downloadIdFor(artist, title)
    private val audioFileName =
        "${artist}_$title".replace(Regex("[^a-zA-Z0-9_.-]"), "_") + ".wav"

    private var playlistId = 0L
    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousInitialScanCompleted: Boolean? = null
    private var previousNavSnapshot: UiNavSnapshot? = null
    private var previousDisplaySettings: LibraryDisplaySettings? = null
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
                previousDisplaySettings = libraryPreferences.displaySettingsFlow.first()
                previousDownloadSettings = downloadPreferences.settingsFlow.first()

                deleteFixtureArtifacts()
                playlistId = createPendingPlaylist()

                libraryPreferences.setInitialScanCompleted(true)
                libraryPreferences.setSortOptionName("DATE_ADDED")
                libraryPreferences.setViewModeName("FLAT")
                libraryPreferences.setNavSnapshot(
                    UiNavSnapshot(
                        navIndex = NAV_PLAYLISTS,
                        playlistDetailKind = PLAYLIST_DETAIL_LOCAL,
                        playlistLocalId = playlistId
                    )
                )
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

    fun conversionComplete(): Boolean {
        val details = runBlocking { repository.getPlaylistDetailsFlow(playlistId).first() }
        val pending = runBlocking { repository.getPlaylistPendingTracksFlow(playlistId).first() }
        val download = application.processDownloads.findByTrack(
            downloadId = downloadId,
            artist = artist,
            title = title
        )
        val song = details?.second?.singleOrNull() ?: return false
        return song.title == title &&
            song.artist == artist &&
            pending.isEmpty() &&
            download?.state == CandidateDownloadState.SUCCESS &&
            download.resultSongId == song.id
    }

    fun verifyPersistedConversion() {
        val details = runBlocking { repository.getPlaylistDetailsFlow(playlistId).first() }
            ?: error("Fixture playlist disappeared")
        val pending = runBlocking { repository.getPlaylistPendingTracksFlow(playlistId).first() }
        val song = details.second.singleOrNull()
            ?: error("Expected one local playlist Song, found ${details.second.size}")

        check(pending.isEmpty()) { "Pending row survived conversion: $pending" }
        check(song.title == title)
        check(song.artist == artist)
        check(song.album == album)
        check(song.trackNumber == TRACK_NUMBER) {
            "Pending track number was not preserved: expected=$TRACK_NUMBER, " +
                "actual=${song.trackNumber}"
        }
        check(repositorySongIds().singleOrNull() == song.id) {
            "Downloaded Song was not the exact Room row attached to the playlist"
        }

        val descriptor = checkNotNull(
            audioStore.openRead(audioStore.canonicalize(song.uriString, song.folderPath))
        ) {
            "Downloaded fixture audio cannot be opened: ${song.uriString}"
        }
        val storedBytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        check(storedBytes.contentEquals(audioBytes)) {
            "Stored WAV differs: expected=${audioBytes.size}, actual=${storedBytes.size}"
        }

        check(searchRequests.get() >= 1) { "Mock YouTube search endpoint was not used" }
        check(playerRequests.get() >= 1) { "Mock YouTube player endpoint was not used" }
        check(audioRequests.get() == 1) {
            "Expected one local audio transfer, got ${audioRequests.get()}"
        }
        check(lyricsRequests.get() >= 1) { "Mock metadata endpoint was not used" }
    }

    fun diagnostic(): String {
        val details = runCatching {
            runBlocking { repository.getPlaylistDetailsFlow(playlistId).first() }
        }.getOrNull()
        val pending = runCatching {
            runBlocking { repository.getPlaylistPendingTracksFlow(playlistId).first() }
        }.getOrDefault(emptyList())
        val download = application.processDownloads.findByTrack(downloadId, artist, title)
        return "playlistId=$playlistId, local=${details?.second?.map { "${it.id}:${it.title}" }}, " +
            "pending=${pending.map { "${it.id}:${it.title}" }}, " +
            "download=${download?.state}:${download?.progressPercent}:${download?.errorMessage}, " +
            "requests=search:${searchRequests.get()},player:${playerRequests.get()}," +
            "audio:${audioRequests.get()},lyrics:${lyricsRequests.get()}"
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
                    application.processDownloads.cancelAndJoin(downloadId)
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
                previousDisplaySettings?.let { previous ->
                    libraryPreferences.setSortOptionName(previous.sortOptionName)
                    libraryPreferences.setSortDirectionName(
                        previous.sortDirectionName,
                        previous.sortOptionName
                    )
                    libraryPreferences.setViewModeName(previous.viewModeName)
                }
                previousNavSnapshot?.let { libraryPreferences.setNavSnapshot(it) }
                previousDownloadSettings?.let { downloadPreferences.restoreForTest(it) }
            }
        }
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

    private suspend fun createPendingPlaylist(): Long {
        check(fixtureDir.mkdirs() || fixtureDir.isDirectory) {
            "Could not create ${fixtureDir.absolutePath}"
        }
        val artworkFile = File(fixtureDir, "pending-artwork.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA)
            FileOutputStream(artworkFile).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write fixture artwork"
                }
            }
        } finally {
            bitmap.recycle()
        }

        val id = repository.createPlaylist(playlistName, "Pending download E2E")
        check(id > 0L) { "Could not create fixture playlist" }
        repository.addPlaylistPendingTracks(
            listOf(
                PlaylistPendingTrack(
                    identity = TrackIdentity(
                        title = title,
                        artist = artist,
                        album = album,
                        artworkUri = artworkFile.toURI().toString(),
                        durationMs = WAV_DURATION_MS.toLong(),
                        trackNumber = TRACK_NUMBER
                    ),
                    playlistId = id,
                    recordingMbid = "fixture-mbid-$token",
                    position = 0
                )
            )
        )
        return id
    }

    private fun repositorySongIds(): List<Long> = runBlocking {
        repository.getAllSongsSync()
            .filter { it.artist == artist && it.title == title }
            .map { it.id }
    }

    private suspend fun deleteFixtureArtifacts() {
        val fixtureDownloadIds = application.processDownloads.downloads.value
            .filter {
                it.artist == artist && it.title == title
            }
            .map { it.id }
            .toSet() + downloadId
        fixtureDownloadIds.forEach { application.processDownloads.cancelAndJoin(it) }

        repository.playlistsFlow.first()
            .filter { it.name == playlistName }
            .forEach { repository.deletePlaylist(it.id) }

        val songs = repository.getAllSongsSync().filter {
            it.artist == artist && it.title == title
        }
        if (songs.isNotEmpty()) repository.deleteSongsFromDevice(songs)

        val expectedAudioFile = File(StorageUtils.publicBestiaPopDir(), audioFileName)
        audioStore.delete(
            audioStore.canonicalize(
                expectedAudioFile.absolutePath,
                expectedAudioFile.parent.orEmpty()
            )
        )
        context.cacheDir.listFiles()
            .orEmpty()
            .filter {
                it.isFile && it.name.startsWith("bp_") && it.name.endsWith(audioFileName)
            }
            .forEach { file ->
                check(!file.exists() || file.delete()) {
                    "Could not delete exact pending fixture audio ${file.absolutePath}"
                }
            }
        check(!fixtureDir.exists() || fixtureDir.deleteRecursively()) {
            "Could not delete exact pending fixture directory ${fixtureDir.absolutePath}"
        }
    }

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
            return when (request.requestUrl?.encodedPath.orEmpty()) {
                "/youtubei/v1/search" -> youtubeSearchResponse(request)
                "/youtubei/v1/player" -> youtubePlayerResponse(request)
                "/watch" -> MockResponse()
                    .setResponseCode(200)
                    .setBody("""<html>"visitorData":"playlist-fixture-visitor"</html>""")
                "/audio/pending.wav" -> {
                    audioRequests.incrementAndGet()
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "audio/wav")
                        .setBody(Buffer().write(audioBytes))
                }
                "/api/get" -> {
                    lyricsRequests.incrementAndGet()
                    jsonResponse("""{"plainLyrics":"Hermetic playlist fixture lyric"}""")
                }
                "/api/search" -> jsonResponse("[]")
                "/search", "/search/track", "/search/album", "/search/playlist", "/search/artist" ->
                    jsonResponse("""{"data":[]}""")
                "/results" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun youtubeSearchResponse(request: RecordedRequest): MockResponse {
            searchRequests.incrementAndGet()
            val query = runCatching {
                JSONObject(request.body.readUtf8()).optString("query")
            }.getOrDefault("")
            return if (query == "$artist $title") {
                jsonResponse(
                    """
                    {
                      "contents": {
                        "sectionListRenderer": {
                          "contents": [{
                            "itemSectionRenderer": {
                              "contents": [{
                                "videoRenderer": {
                                  "videoId": "$videoId",
                                  "title": {"runs": [{"text": "$artist - $title (Official Audio)"}]},
                                  "ownerText": {"runs": [{"text": "$artist - Topic"}]},
                                  "lengthText": {"simpleText": "0:01"}
                                }
                              }]
                            }
                          }]
                        }
                      }
                    }
                    """.trimIndent()
                )
            } else {
                jsonResponse("""{"contents":{"sectionListRenderer":{"contents":[]}}}""")
            }
        }

        private fun youtubePlayerResponse(request: RecordedRequest): MockResponse {
            playerRequests.incrementAndGet()
            val requestedId = runCatching {
                JSONObject(request.body.readUtf8()).optString("videoId")
            }.getOrDefault("")
            return if (requestedId == videoId) {
                jsonResponse(
                    """
                    {
                      "playabilityStatus": {"status": "OK"},
                      "videoDetails": {
                        "title": "$title",
                        "author": "$artist",
                        "lengthSeconds": "1"
                      },
                      "streamingData": {
                        "adaptiveFormats": [{
                          "url": "${server.url("/audio/pending.wav")}",
                          "mimeType": "audio/wav",
                          "bitrate": 128000
                        }]
                      }
                    }
                    """.trimIndent()
                )
            } else {
                jsonResponse("""{"playabilityStatus":{"status":"ERROR","reason":"fixture only"}}""")
            }
        }

        private fun jsonResponse(body: String): MockResponse = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private companion object {
        const val PLAYLIST_PREFIX = "BestiaPop E2E Pending Playlist "
        const val SONG_PREFIX = "BestiaPop E2E Pending Song "
        const val ARTIST_PREFIX = "BestiaPop E2E Pending Artist "
        const val ALBUM_PREFIX = "BestiaPop E2E Pending Album "
        const val FIXTURE_DIR_PREFIX = "playlist-pending-e2e-"
        const val WAV_DURATION_MS = 1_000
        const val TRACK_NUMBER = 4
        const val STATE_TIMEOUT_MS = 12_000L
    }
}
