package com.bestiapop.android.ui.identify

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.ListenBrainzEndpoints
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.network.MetadataFetcherEndpoints
import com.bestiapop.android.data.preferences.IdentifyReviewStore
import com.bestiapop.android.data.preferences.LibraryDisplaySettings
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.PersistedIdentifyReviewQueue
import com.bestiapop.android.data.preferences.UiNavSnapshot
import com.bestiapop.android.testutil.PcmWavFixture
import com.bestiapop.android.ui.MusicPlayerViewModel
import java.io.File
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

internal object IdentifyE2ETestContract {
    const val SEARCH_FILTER = "BestiaPop Identify E2E"
    const val WEAK_ALBUM = "Unknown Album"
    const val LOCAL_DURATION_MS = 4_321L

    const val HIGH_SOURCE_TITLE = "BestiaPop Identify E2E High"
    const val HIGH_ARTIST = "Identify E2E High Artist"
    const val HIGH_ALBUM = "Identify E2E High Album"
    const val HIGH_TRACK_NUMBER = 7

    const val MEDIUM_SOURCE_TITLE = "BestiaPop Identify E2E Medium"
    const val MEDIUM_CANDIDATE_TITLE = "$MEDIUM_SOURCE_TITLE (Live)"
    const val MEDIUM_ARTIST = "Identify E2E Medium Artist"
    const val MEDIUM_ALBUM = "Identify E2E Medium Album"
    const val MEDIUM_TRACK_NUMBER = 8

    fun songOptionsTag(songId: Long): String = "song-options-$songId"
}

/**
 * Owns the hermetic HTTP boundary and exact persistent artifacts for [IdentifyE2EFunctionalTest].
 *
 * MainActivity, BestiaPopApplication, the production ViewModel/repository graph, Room and DataStore
 * remain real. Only the catalog and optional ListenBrainz HTTP clients point at MockWebServer.
 */
internal class IdentifyE2ETestFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as BestiaPopApplication
    private val repository = application.musicRepository
    private val dao = AppDatabase.getDatabase(context).musicDao()
    private val libraryPreferences = LibraryPreferencesRepository(context)
    private val reviewStore = IdentifyReviewStore(context)
    private val fixtureDir = File(context.filesDir, FIXTURE_DIRECTORY)
    private val server = MockWebServer()

    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousInitialScanCompleted: Boolean? = null
    private var previousNavSnapshot: UiNavSnapshot? = null
    private var previousDisplaySettings: LibraryDisplaySettings? = null
    private var previousReviewQueue: PersistedIdentifyReviewQueue? = null
    private var searchEntered: CountDownLatch? = null
    private var searchGate: CountDownLatch? = null

    var highSongId: Long = 0L
        private set
    var mediumSongId: Long = 0L
        private set

    fun prepare() {
        grantStartupPermissions()
        server.dispatcher = FixtureDispatcher()
        server.start()

        val localBaseUrl = server.url("/").toString()
        val localClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        MetadataFetcher.configureForTest(
            http = localClient,
            endpoints = MetadataFetcherEndpoints(
                deezerBaseUrl = localBaseUrl,
                itunesBaseUrl = localBaseUrl,
                lyricsBaseUrl = localBaseUrl
            )
        )
        ListenBrainzClient.configureForTest(
            http = localClient,
            endpoints = ListenBrainzEndpoints(
                apiBaseUrl = server.url("/1").toString().trimEnd('/')
            )
        )

        runBlocking {
            withTimeout(STATE_TIMEOUT_MS) {
                previousInitialScanCompleted = libraryPreferences.isInitialScanCompleted()
                previousNavSnapshot = libraryPreferences.navSnapshotFlow.first()
                previousDisplaySettings = libraryPreferences.displaySettingsFlow.first()

                val staleRows = fixtureRows()
                val staleIds = staleRows.map(Song::id).toSet()
                previousReviewQueue = reviewStore.load().let { queue ->
                    queue.copy(proposals = queue.proposals.filterNot { it.songId in staleIds })
                }
                deleteFixtureRows(staleRows)
                deleteFixtureFiles()
                reviewStore.save(PersistedIdentifyReviewQueue())

                libraryPreferences.setInitialScanCompleted(true)
                libraryPreferences.setNavSnapshot(UiNavSnapshot())
                libraryPreferences.setSortOptionName("TITLE")
                libraryPreferences.setSortDirectionName("ASC", "TITLE")
                libraryPreferences.setViewModeName("FLAT")

                createFixtureSongs()
            }
        }
    }

    fun launchMainActivity() {
        check(scenario == null) { "MainActivity fixture scenario is already running" }
        scenario = ActivityScenario.launch(MainActivity::class.java).also {
            it.moveToState(Lifecycle.State.RESUMED)
        }
    }

    /** Closes the Activity/ViewModel, then launches a fresh production graph consumer. */
    fun destroyMainActivity() {
        scenario?.close()
        scenario = null
    }

    /** Closes the Activity/ViewModel, then launches a fresh production graph consumer. */
    fun relaunchMainActivity() {
        destroyMainActivity()
        launchMainActivity()
    }

    fun configureGatedSearch() {
        searchEntered = CountDownLatch(1)
        searchGate = CountDownLatch(1)
    }

    fun awaitSearchRequest() {
        val entered = checkNotNull(searchEntered) { "configureGatedSearch was not called" }
        check(entered.await(STATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Catalog search was not reached. ${diagnostic()}"
        }
    }

    fun releaseSearch() {
        checkNotNull(searchGate) { "configureGatedSearch was not called" }.countDown()
    }

    fun startIdentifyFromViewModel(songId: Long) {
        val currentScenario = checkNotNull(scenario) { "MainActivity was not launched" }
        val song = checkNotNull(song(songId)) { "Fixture song $songId missing. ${diagnostic()}" }
        currentScenario.onActivity { activity ->
            val viewModel = ViewModelProvider(activity)[MusicPlayerViewModel::class.java]
            viewModel.identifySongs(listOf(song), force = true, showReview = true)
        }
    }

    fun highIdentityWasAutoApplied(): Boolean =
        song(highSongId)?.let { persisted ->
            persisted.title == IdentifyE2ETestContract.HIGH_SOURCE_TITLE &&
                persisted.artist == IdentifyE2ETestContract.HIGH_ARTIST &&
                persisted.album == IdentifyE2ETestContract.HIGH_ALBUM &&
                persisted.trackNumber == IdentifyE2ETestContract.HIGH_TRACK_NUMBER &&
                persisted.durationMs == IdentifyE2ETestContract.LOCAL_DURATION_MS
        } == true

    fun mediumIdentityWasApplied(): Boolean =
        song(mediumSongId)?.let { persisted ->
            persisted.title == IdentifyE2ETestContract.MEDIUM_CANDIDATE_TITLE &&
                persisted.artist == IdentifyE2ETestContract.MEDIUM_ARTIST &&
                persisted.album == IdentifyE2ETestContract.MEDIUM_ALBUM &&
                persisted.trackNumber == IdentifyE2ETestContract.MEDIUM_TRACK_NUMBER &&
                persisted.durationMs == IdentifyE2ETestContract.LOCAL_DURATION_MS
        } == true

    fun mediumReviewIsPersisted(): Boolean = runBlocking {
        val queue = reviewStore.load()
        queue.proposals.size == 1 &&
            queue.proposals.single().songId == mediumSongId &&
            queue.proposals.single().candidates.firstOrNull()?.title ==
            IdentifyE2ETestContract.MEDIUM_CANDIDATE_TITLE
    }

    fun reviewQueueIsEmpty(): Boolean = runBlocking {
        reviewStore.load().proposals.isEmpty()
    }

    fun assertPersistedIdentities() {
        check(highIdentityWasAutoApplied()) {
            "HIGH identity or local duration was not persisted. ${diagnostic()}"
        }
        check(mediumIdentityWasApplied()) {
            "Selected MEDIUM identity or local duration was not persisted. ${diagnostic()}"
        }
    }

    fun diagnostic(): String {
        val rows = runCatching {
            runBlocking {
                fixtureRows().joinToString {
                    "${it.id}:${it.artist}|${it.title}|${it.album}|${it.durationMs}|${it.trackNumber}"
                }
            }
        }.getOrElse { "Room diagnostic failed: ${it.message}" }
        val queue = runCatching {
            runBlocking { reviewStore.load().proposals.map { it.songId } }
        }.getOrElse { listOf("review diagnostic failed: ${it.message}") }
        return "fixtureRows=[$rows], reviewSongIds=$queue, dir=${fixtureDir.absolutePath}"
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
        scenario = null
        cleanup {
            runBlocking {
                withTimeout(STATE_TIMEOUT_MS) {
                    deleteFixtureRows(fixtureRows())
                    check(fixtureRows().isEmpty()) { "Identify fixture rows survived cleanup" }
                    deleteFixtureFiles()
                    previousReviewQueue?.let { reviewStore.save(it) }
                    previousInitialScanCompleted?.let {
                        libraryPreferences.setInitialScanCompleted(it)
                    }
                    previousNavSnapshot?.let { libraryPreferences.setNavSnapshot(it) }
                    previousDisplaySettings?.let { settings ->
                        libraryPreferences.setSortOptionName(settings.sortOptionName)
                        libraryPreferences.setSortDirectionName(
                            settings.sortDirectionName,
                            settings.sortOptionName
                        )
                        libraryPreferences.setViewModeName(settings.viewModeName)
                    }
                }
            }
        }
        cleanup { MetadataFetcher.resetTestOverrides() }
        cleanup { ListenBrainzClient.resetTestOverrides() }
        cleanup { server.shutdown() }
        firstFailure?.let { throw it }
    }

    private suspend fun createFixtureSongs() {
        check(fixtureDir.mkdirs() || fixtureDir.isDirectory) {
            "Could not create identify fixture directory ${fixtureDir.absolutePath}"
        }
        val highFile = File(fixtureDir, "high.wav")
        val mediumFile = File(fixtureDir, "medium.wav")
        PcmWavFixture.write(
            file = highFile,
            durationMs = IdentifyE2ETestContract.LOCAL_DURATION_MS.toInt(),
            toneHz = 330.0
        )
        PcmWavFixture.write(
            file = mediumFile,
            durationMs = IdentifyE2ETestContract.LOCAL_DURATION_MS.toInt(),
            toneHz = 440.0
        )

        highSongId = persistFixtureSong(
            file = highFile,
            title = IdentifyE2ETestContract.HIGH_SOURCE_TITLE,
            artist = IdentifyE2ETestContract.HIGH_ARTIST
        )
        mediumSongId = persistFixtureSong(
            file = mediumFile,
            title = IdentifyE2ETestContract.MEDIUM_SOURCE_TITLE,
            artist = IdentifyE2ETestContract.MEDIUM_ARTIST
        )
    }

    private suspend fun persistFixtureSong(file: File, title: String, artist: String): Long {
        val id = repository.saveUploadedSong(
            Song(
                uriString = file.absolutePath,
                title = title,
                artist = artist,
                album = IdentifyE2ETestContract.WEAK_ALBUM,
                durationMs = IdentifyE2ETestContract.LOCAL_DURATION_MS,
                folderPath = fixtureDir.absolutePath
            )
        )
        check(id > 0L) { "Could not persist identify fixture $title (id=$id)" }
        val persisted = checkNotNull(dao.getSongById(id)) { "Fixture row $id disappeared" }
        check(persisted.album == IdentifyE2ETestContract.WEAK_ALBUM)
        check(persisted.durationMs == IdentifyE2ETestContract.LOCAL_DURATION_MS)
        return id
    }

    private fun song(id: Long): Song? = runBlocking { dao.getSongById(id) }

    private suspend fun fixtureRows(): List<Song> {
        val prefix = fixtureDir.absolutePath + File.separator
        return dao.getAllSongs().filter { song ->
            song.uriString.startsWith(prefix) || song.folderPath == fixtureDir.absolutePath
        }
    }

    private suspend fun deleteFixtureRows(rows: List<Song>) {
        val ids = rows.map(Song::id)
        if (ids.isEmpty()) return
        dao.deletePlaylistRefsForSongs(ids)
        dao.deleteSongsByIds(ids)
    }

    private fun deleteFixtureFiles() {
        check(!fixtureDir.exists() || fixtureDir.deleteRecursively()) {
            "Could not delete exact identify fixture directory ${fixtureDir.absolutePath}"
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
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
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
                "/api/get" -> MockResponse().setResponseCode(404)
                "/api/search" -> jsonResponse("[]")
                else -> if (path.startsWith("/1/")) {
                    jsonResponse("""{"error":"identify fixture has no ListenBrainz match"}""", 503)
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }

        private fun catalogSearchResponse(request: RecordedRequest): MockResponse {
            searchEntered?.countDown()
            searchGate?.await(STATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val query = request.requestUrl?.queryParameter("q")
                ?: request.requestUrl?.queryParameter("term")
                ?: ""
            val body = when {
                query.contains(IdentifyE2ETestContract.HIGH_SOURCE_TITLE, ignoreCase = true) ->
                    catalogJson(
                        id = 7001,
                        title = IdentifyE2ETestContract.HIGH_SOURCE_TITLE,
                        artist = IdentifyE2ETestContract.HIGH_ARTIST,
                        album = IdentifyE2ETestContract.HIGH_ALBUM,
                        trackNumber = IdentifyE2ETestContract.HIGH_TRACK_NUMBER
                    )
                query.contains(IdentifyE2ETestContract.MEDIUM_SOURCE_TITLE, ignoreCase = true) ->
                    catalogJson(
                        id = 7002,
                        title = IdentifyE2ETestContract.MEDIUM_CANDIDATE_TITLE,
                        artist = IdentifyE2ETestContract.MEDIUM_ARTIST,
                        album = IdentifyE2ETestContract.MEDIUM_ALBUM,
                        trackNumber = IdentifyE2ETestContract.MEDIUM_TRACK_NUMBER
                    )
                else -> """{"data":[]}"""
            }
            return jsonResponse(body)
        }

        private fun catalogJson(
            id: Long,
            title: String,
            artist: String,
            album: String,
            trackNumber: Int
        ): String = """
            {
              "data": [{
                "id": $id,
                "title": "$title",
                "duration": 5,
                "track_position": $trackNumber,
                "artist": {"name": "$artist"},
                "album": {"title": "$album"}
              }]
            }
        """.trimIndent()

        private fun jsonResponse(body: String, code: Int = 200): MockResponse = MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private companion object {
        const val FIXTURE_DIRECTORY = "__bestiapop_identify_e2e__"
        const val STATE_TIMEOUT_MS = 10_000L
    }
}
