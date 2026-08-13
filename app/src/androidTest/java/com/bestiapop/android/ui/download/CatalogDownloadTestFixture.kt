package com.bestiapop.android.ui.download

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.network.ConnectivityObserver
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
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.service.DownloadNotificationHelper
import com.bestiapop.android.testutil.PcmWavFixture
import com.bestiapop.android.ui.MusicPlayerViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
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

internal data class CatalogFixtureTrack(
    val catalogId: Long,
    val title: String,
    val videoId: String,
    val trackNumber: Int
)

internal object CatalogDownloadTestContract {
    private val token = UUID.randomUUID().toString().replace("-", "")

    val SEARCH_QUERY = "bestiapop hermetic catalog fixture $token"
    val METERED_SEARCH_QUERY = "bestiapop metered catalog fixture $token"
    val TITLE = "BestiaPopFixtureCatalogTrack$token"
    val ARTIST = "BestiaPopFixtureArtist$token"
    val ALBUM = "BestiaPopFixtureAlbum$token"
    const val TRACK_NUMBER = 3
    val VIDEO_ID = "Bp${token.take(9)}"
    val SAVE_AS_TITLE = "$TITLE (2)"
    val FILE_NAME = fileNameFor(TITLE)
    val DOWNLOAD_ID: String = downloadIdFor(TITLE)
    val EXISTING_FILE_NAME = "BestiaPopFixtureOriginal$token.wav"
    const val EXISTING_LYRICS = "private fixture lyrics"
    const val EXISTING_GENRE = "Private fixture genre"
    const val EXISTING_YEAR = 1997
    const val EXISTING_DATE_ADDED = 123_456_789L
    const val EXISTING_LAST_PLAYED_AT = 987_654_321L

    val PRIMARY_TRACK = CatalogFixtureTrack(
        catalogId = 4242L,
        title = TITLE,
        videoId = VIDEO_ID,
        trackNumber = TRACK_NUMBER
    )
    val METERED_TRACKS: List<CatalogFixtureTrack> = List(4) { index ->
        CatalogFixtureTrack(
            catalogId = 4300L + index,
            title = "${TITLE}Metered${index + 1}",
            videoId = "Bm$index${token.take(8)}",
            trackNumber = index + 1
        )
    }
    val ALL_TRACKS = listOf(PRIMARY_TRACK) + METERED_TRACKS

    fun downloadIdFor(title: String): String = TrackMatchKeys.downloadIdFor(ARTIST, title)

    fun fileNameFor(title: String): String {
        val sanitized = "${ARTIST}_$title".replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return "$sanitized.wav"
    }

    fun owns(song: Song): Boolean =
        song.artist == ARTIST && song.title.contains(token)

    fun owns(file: File): Boolean = file.name.contains(token)
}

/**
 * Scenario-driven local HTTP boundary and exact persistent-state owner for
 * [CatalogDownloadFunctionalTest].
 *
 * MainActivity, BestiaPopApplication, Room, the process download coordinator and storage are the
 * production graph. Only MetadataFetcher/YouTubeExtractor endpoints, connectivity readings and
 * controlled localhost response behavior are replaced.
 */
internal class CatalogDownloadTestFixture : AutoCloseable {
    private enum class AudioScenario {
        GATED_SUCCESS,
        PARTIAL_CANCELLATION,
        FORBIDDEN_THEN_RECOVERY,
        METERED_AFTER_PERMIT
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as BestiaPopApplication
    private val repository = application.musicRepository
    private val musicDao = AppDatabase.getDatabase(context).musicDao()
    private val audioStore = MusicFileStore(context)
    private val libraryPreferences = LibraryPreferencesRepository(context)
    private val downloadPreferences = DownloadPreferencesRepository(context)
    private val notificationHelper = DownloadNotificationHelper(context)
    private val server = MockWebServer()
    private val newAudioBytes = PcmWavFixture.generate(durationMs = 3_000, toneHz = 440.0)
    private val originalAudioBytes = PcmWavFixture.generate(durationMs = 1_500, toneHz = 220.0)
    private val audioRequestCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val releaseByVideoId = ConcurrentHashMap<String, CountDownLatch>()

    @Volatile
    private var audioScenario = AudioScenario.GATED_SUCCESS

    @Volatile
    private var forbiddenRecovered = false

    private var expectedAudioRequests = CountDownLatch(1)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousInitialScanCompleted: Boolean? = null
    private var previousNavSnapshot: UiNavSnapshot? = null
    private var previousDownloadSettings: DownloadSettings? = null

    init {
        releaseByVideoId[CatalogDownloadTestContract.VIDEO_ID] = CountDownLatch(1)
    }

    fun prepare() {
        grantStartupPermissions()
        ConnectivityObserver.configureForTest(currentlyOnline = true, metered = false)
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
                cancelFixtureDownloads()
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

    fun destroyMainActivity() {
        scenario?.close()
        scenario = null
    }

    fun seedInterruptedPrimaryDownload() {
        val track = CatalogDownloadTestContract.PRIMARY_TRACK.toOnlineCatalogTrack()
        application.processDownloads.upsert(
            ActiveDownload.queued(
                id = CatalogDownloadTestContract.DOWNLOAD_ID,
                source = ActiveDownloadSource.CATALOG,
                candidates = listOf(track),
                lookupIdentity = track.identity
            ).asError(DownloadMessages.interrupted, interrupted = true)
        )
    }

    fun startPrimaryDownloadFromViewModel() {
        startDownloadsFromViewModel(listOf(CatalogDownloadTestContract.PRIMARY_TRACK))
    }

    fun startMeteredDownloadsFromViewModel() {
        startDownloadsFromViewModel(CatalogDownloadTestContract.METERED_TRACKS)
    }

    fun configureGatedSuccess() {
        checkNoAudioRequests()
        audioScenario = AudioScenario.GATED_SUCCESS
        expectedAudioRequests = CountDownLatch(1)
        releaseByVideoId.clear()
        releaseByVideoId[CatalogDownloadTestContract.VIDEO_ID] = CountDownLatch(1)
    }

    fun configurePartialCancellation() {
        checkNoAudioRequests()
        audioScenario = AudioScenario.PARTIAL_CANCELLATION
        expectedAudioRequests = CountDownLatch(1)
        releaseByVideoId.clear()
    }

    fun configureForbiddenThenRecovery() {
        checkNoAudioRequests()
        audioScenario = AudioScenario.FORBIDDEN_THEN_RECOVERY
        forbiddenRecovered = false
        expectedAudioRequests = CountDownLatch(EXHAUSTED_DOWNLOAD_ATTEMPTS)
        releaseByVideoId.clear()
    }

    fun configureMeteredAfterPermit() {
        checkNoAudioRequests()
        audioScenario = AudioScenario.METERED_AFTER_PERMIT
        expectedAudioRequests = CountDownLatch(3)
        releaseByVideoId.clear()
        CatalogDownloadTestContract.METERED_TRACKS
            .take(3)
            .forEach { releaseByVideoId[it.videoId] = CountDownLatch(1) }
        setDownloadOnMeteredAllowed(false)
        ConnectivityObserver.configureForTest(currentlyOnline = true, metered = false)
    }

    fun seedExistingSong(): Song = runBlocking {
        val pending = audioStore.prepareWrite(CatalogDownloadTestContract.EXISTING_FILE_NAME)
        pending.stagingFile.writeBytes(originalAudioBytes)
        val published = audioStore.canonicalize(pending.publish())
        val original = Song(
            uriString = published.uriString,
            title = CatalogDownloadTestContract.TITLE,
            artist = CatalogDownloadTestContract.ARTIST,
            album = "Original private album",
            genre = CatalogDownloadTestContract.EXISTING_GENRE,
            durationMs = 1_500L,
            year = CatalogDownloadTestContract.EXISTING_YEAR,
            trackNumber = 9,
            artworkUri = "file:///private-fixture-cover.jpg",
            lyrics = CatalogDownloadTestContract.EXISTING_LYRICS,
            folderPath = published.folderPath,
            dateAdded = CatalogDownloadTestContract.EXISTING_DATE_ADDED,
            lastPlayedAt = CatalogDownloadTestContract.EXISTING_LAST_PLAYED_AT
        )
        val id = musicDao.insertSong(original)
        check(id > 0L) { "Could not seed duplicate fixture Song. ${diagnostic()}" }
        original.copy(id = id)
    }

    fun releaseAudioDownload() {
        releaseByVideoId[CatalogDownloadTestContract.VIDEO_ID]?.countDown()
    }

    fun releaseOneMeteredPermit() {
        val first = CatalogDownloadTestContract.METERED_TRACKS.first()
        releaseByVideoId[first.videoId]?.countDown()
    }

    fun flipToMetered() {
        ConnectivityObserver.configureForTest(currentlyOnline = true, metered = true)
    }

    fun recoverForbiddenAudio() {
        forbiddenRecovered = true
    }

    fun awaitAudioRequest() {
        awaitLatch(expectedAudioRequests, "one fixture audio request")
    }

    fun awaitPartialBytesWritten() {
        awaitLatch(expectedAudioRequests, "partial audio request")
        val deadline = android.os.SystemClock.elapsedRealtime() + NETWORK_BOUNDARY_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val partial = context.cacheDir.listFiles().orEmpty().firstOrNull {
                CatalogDownloadTestContract.owns(it) &&
                    it.length() in 1 until newAudioBytes.size.toLong()
            }
            if (partial != null) return
            android.os.SystemClock.sleep(25L)
        }
        error("Partial audio bytes were not written to staging. ${diagnostic()}")
    }

    fun awaitForbiddenAttemptsExhausted() {
        awaitLatch(expectedAudioRequests, "$EXHAUSTED_DOWNLOAD_ATTEMPTS HTTP 403 requests")
    }

    fun awaitThreeTransfersHoldingPermits() {
        awaitLatch(expectedAudioRequests, "three audio requests holding transfer permits")
    }

    fun awaitCancellationFinished() {
        val deadline = android.os.SystemClock.elapsedRealtime() + CLEANUP_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val partialExists = context.cacheDir.listFiles().orEmpty().any {
                CatalogDownloadTestContract.owns(it) && it.length() > 0L
            }
            if (!partialExists) break
            android.os.SystemClock.sleep(25L)
        }
        runBlocking {
            application.processDownloads.flush()
        }
    }

    fun isDownloadingAt(percent: Int, title: String = CatalogDownloadTestContract.TITLE): Boolean =
        fixtureDownload(title)?.let {
            it.state == CandidateDownloadState.DOWNLOADING &&
                it.progressPercent == percent
        } == true

    fun isQueued(title: String): Boolean =
        fixtureDownload(title)?.state == CandidateDownloadState.QUEUED

    fun isError(title: String, expectedMessage: String? = null): Boolean =
        fixtureDownload(title)?.let {
            it.state == CandidateDownloadState.ERROR &&
                (expectedMessage == null || it.errorMessage == expectedMessage)
        } == true

    fun isDownloadComplete(title: String = CatalogDownloadTestContract.TITLE): Boolean =
        fixtureDownload(title)?.let {
            it.state == CandidateDownloadState.SUCCESS &&
                it.progressPercent == 100 &&
                it.resultSongId != null
        } == true

    fun isDownloadAbsent(title: String = CatalogDownloadTestContract.TITLE): Boolean =
        fixtureDownload(title) == null &&
            !application.processDownloads.isRunning(
                CatalogDownloadTestContract.downloadIdFor(title),
                CatalogDownloadTestContract.ARTIST,
                title
            )

    fun audioRequestCount(title: String = CatalogDownloadTestContract.TITLE): Int {
        val videoId = trackForTitle(title).videoId
        return audioRequestCounts[videoId]?.get() ?: 0
    }

    fun persistedSong(title: String = CatalogDownloadTestContract.TITLE): Song {
        val matches = runBlocking { fixtureSongs().filter { it.title == title } }
        check(matches.size == 1) {
            "Expected one fixture Song titled $title in Room, found ${matches.size}. ${diagnostic()}"
        }
        return matches.single()
    }

    fun verifyPersistedSongAndFile(song: Song) {
        check(song.id > 0L) { "Fixture Song has no Room id: $song" }
        check(song.title == CatalogDownloadTestContract.TITLE)
        check(song.artist == CatalogDownloadTestContract.ARTIST)
        check(song.album == CatalogDownloadTestContract.ALBUM)
        check(song.trackNumber == CatalogDownloadTestContract.TRACK_NUMBER)
        verifySongFile(song, CatalogDownloadTestContract.FILE_NAME, newAudioBytes)
    }

    fun verifyOverwrite(existing: Song) {
        val rows = runBlocking { fixtureSongs().filter { it.title == CatalogDownloadTestContract.TITLE } }
        check(rows.size == 1) { "Overwrite created ${rows.size} matching Songs. ${diagnostic()}" }
        val overwritten = rows.single()
        check(overwritten.id == existing.id) {
            "Overwrite changed Room id ${existing.id} -> ${overwritten.id}"
        }
        check(overwritten.lyrics == CatalogDownloadTestContract.EXISTING_LYRICS)
        check(overwritten.genre == CatalogDownloadTestContract.EXISTING_GENRE)
        check(overwritten.year == CatalogDownloadTestContract.EXISTING_YEAR)
        check(overwritten.dateAdded == CatalogDownloadTestContract.EXISTING_DATE_ADDED)
        check(overwritten.lastPlayedAt == CatalogDownloadTestContract.EXISTING_LAST_PLAYED_AT)
        verifySongFile(overwritten, CatalogDownloadTestContract.FILE_NAME, newAudioBytes)
        check(!fileFor(existing).exists()) {
            "Overwrite left the obsolete original file ${fileFor(existing).absolutePath}"
        }
    }

    fun verifySaveAs(existing: Song) {
        val rows = runBlocking { fixtureSongs() }
        val original = rows.singleOrNull { it.id == existing.id }
            ?: error("Save As removed the original Song. ${diagnostic()}")
        val copy = rows.singleOrNull { it.title == CatalogDownloadTestContract.SAVE_AS_TITLE }
            ?: error("Save As copy missing. ${diagnostic()}")
        check(rows.size == 2) { "Save As expected two fixture Songs, found ${rows.size}" }
        check(original.id == existing.id)
        check(original.title == CatalogDownloadTestContract.TITLE)
        check(readSongBytes(original).contentEquals(originalAudioBytes)) {
            "Save As modified original bytes"
        }
        verifySongFile(
            copy,
            CatalogDownloadTestContract.fileNameFor(CatalogDownloadTestContract.SAVE_AS_TITLE),
            newAudioBytes
        )
        check(fileFor(original).absolutePath != fileFor(copy).absolutePath) {
            "Save As reused the original file"
        }
    }

    fun verifyOriginalUnchanged(existing: Song) {
        val current = runBlocking { musicDao.getSongById(existing.id) }
            ?: error("Original duplicate Song disappeared. ${diagnostic()}")
        check(current == existing) {
            "Original duplicate Song changed after cancel.\nexpected=$existing\nactual=$current"
        }
        check(readSongBytes(current).contentEquals(originalAudioBytes)) {
            "Original duplicate bytes changed after cancel"
        }
        check(runBlocking { fixtureSongs() }.size == 1)
    }

    fun verifyNoPublicationOrPartial(title: String = CatalogDownloadTestContract.TITLE) {
        check(runBlocking { fixtureSongs().none { it.title == title } }) {
            "Unexpected Room row for cancelled/blocked track $title. ${diagnostic()}"
        }
        val expectedName = CatalogDownloadTestContract.fileNameFor(title)
        check(audioStore.listManaged().none { it.name == expectedName }) {
            "Unexpected published file $expectedName. ${diagnostic()}"
        }
        check(context.cacheDir.listFiles().orEmpty().none { file ->
            CatalogDownloadTestContract.owns(file) && file.length() > 0L
        }) {
            "Unexpected namespaced staging partial. ${diagnostic()}"
        }
    }

    fun verifyMeteredTrackNeverRequestedOrPublished() {
        val blocked = CatalogDownloadTestContract.METERED_TRACKS.last()
        check(audioRequestCount(blocked.title) == 0) {
            "Metered-blocked transfer reached audio HTTP. ${diagnostic()}"
        }
        check(isError(blocked.title, DownloadMessages.blockedOnMetered)) {
            "Metered-blocked transfer did not retain the expected ERROR. ${diagnostic()}"
        }
        verifyNoPublicationOrPartial(blocked.title)
    }

    fun diagnostic(): String {
        val rows = application.processDownloads.downloads.value
            .filter { download ->
                download.artist == CatalogDownloadTestContract.ARTIST ||
                    download.title.contains("BestiaPopFixture")
            }
            .joinToString {
                "${it.id}:${it.displayLabel}:${it.state}:${it.progressPercent}:" +
                    it.errorMessage.orEmpty()
            }
        val songs = runCatching {
            runBlocking { fixtureSongs() }
                .joinToString { "${it.id}:${it.title}:${it.uriString}" }
        }.getOrElse { "Room diagnostic failed: ${it.message}" }
        val requests = CatalogDownloadTestContract.ALL_TRACKS.joinToString {
            "${it.title}=${audioRequestCounts[it.videoId]?.get() ?: 0}"
        }
        val managed = audioStore.listManaged()
            .filter(CatalogDownloadTestContract::owns)
            .joinToString { "${it.name}:${it.length()}" }
        val cache = context.cacheDir.listFiles()
            .orEmpty()
            .filter(CatalogDownloadTestContract::owns)
            .joinToString { "${it.name}:${it.length()}" }
        return "scenario=$audioScenario, downloads=[$rows], fixtureSongs=[$songs], " +
            "audioRequests=[$requests], managed=[$managed], cache=[$cache], " +
            "server=${server.hostName}:${server.port}"
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
        releaseAllAudioResponses()
        cleanup {
            runBlocking {
                withTimeout(CLEANUP_TIMEOUT_MS) {
                    cancelFixtureDownloads()
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
        cleanup { ConnectivityObserver.resetTestOverrides() }
        cleanup { MetadataFetcher.resetTestOverrides() }
        cleanup { YouTubeExtractor.resetTestOverrides() }
        cleanup { server.shutdown() }
        firstFailure?.let { throw it }
    }

    private fun startDownloadsFromViewModel(tracks: List<CatalogFixtureTrack>) {
        val currentScenario = checkNotNull(scenario) { "MainActivity was not launched" }
        currentScenario.onActivity { activity ->
            val viewModel = ViewModelProvider(activity)[MusicPlayerViewModel::class.java]
            tracks.forEach { track ->
                viewModel.downloadOnlineTrack(track.toOnlineCatalogTrack())
            }
        }
    }

    private fun CatalogFixtureTrack.toOnlineCatalogTrack(): OnlineCatalogTrack =
        OnlineCatalogTrack(
            identity = TrackIdentity(
                title = title,
                artist = CatalogDownloadTestContract.ARTIST,
                album = CatalogDownloadTestContract.ALBUM,
                durationMs = 3_000L,
                trackNumber = trackNumber
            ),
            id = catalogId.toString(),
            provider = "Deezer"
        )

    private fun setDownloadOnMeteredAllowed(allowed: Boolean) {
        runBlocking {
            downloadPreferences.setDownloadOnMeteredNetwork(allowed)
            downloadPreferences.settingsFlow.first {
                it.downloadOnMeteredNetwork == allowed
            }
        }
    }

    private fun checkNoAudioRequests() {
        check(audioRequestCounts.values.sumOf(AtomicInteger::get) == 0) {
            "Scenario must be configured before starting audio. ${diagnostic()}"
        }
    }

    private fun awaitLatch(latch: CountDownLatch, description: String) {
        check(latch.await(NETWORK_BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Timed out waiting for $description. ${diagnostic()}"
        }
    }

    private fun releaseAllAudioResponses() {
        releaseByVideoId.values.forEach(CountDownLatch::countDown)
    }

    private fun trackForTitle(title: String): CatalogFixtureTrack =
        CatalogDownloadTestContract.ALL_TRACKS.firstOrNull { it.title == title }
            ?: if (title == CatalogDownloadTestContract.SAVE_AS_TITLE) {
                CatalogDownloadTestContract.PRIMARY_TRACK
            } else {
                error("Unknown fixture title $title")
            }

    private suspend fun fixtureSongs(): List<Song> =
        repository.getAllSongsSync().filter(CatalogDownloadTestContract::owns)

    private fun fixtureDownload(title: String) = application.processDownloads.findByTrack(
        downloadId = CatalogDownloadTestContract.downloadIdFor(title),
        artist = CatalogDownloadTestContract.ARTIST,
        title = title
    )

    private suspend fun cancelFixtureDownloads() {
        val ids = buildSet {
            add(CatalogDownloadTestContract.DOWNLOAD_ID)
            CatalogDownloadTestContract.METERED_TRACKS.forEach {
                add(CatalogDownloadTestContract.downloadIdFor(it.title))
            }
            application.processDownloads.downloads.value
                .filter { download ->
                    download.artist == CatalogDownloadTestContract.ARTIST ||
                        download.title.contains("BestiaPopFixture")
                }
                .mapTo(this) { it.id }
        }
        ids.forEach { application.processDownloads.cancelAndJoin(it) }
    }

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

        audioStore.listManaged()
            .filter(CatalogDownloadTestContract::owns)
            .forEach { file ->
                runCatching {
                    audioStore.delete(audioStore.canonicalize(file.absolutePath, file.parent.orEmpty()))
                }.exceptionOrNull()?.let(::recordFailure)
            }

        context.cacheDir.listFiles()
            .orEmpty()
            .filter(CatalogDownloadTestContract::owns)
            .forEach { file ->
                runCatching {
                    check(!file.exists() || file.delete()) {
                        "Could not delete namespaced cache file ${file.absolutePath}"
                    }
                }.exceptionOrNull()?.let(::recordFailure)
            }

        firstFailure?.let { throw it }
    }

    private fun fileFor(song: Song): File {
        val path = SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
        return checkNotNull(path?.let(::File)) {
            "Fixture Song is not backed by a filesystem path: $song"
        }
    }

    private fun readSongBytes(song: Song): ByteArray {
        val ref = audioStore.canonicalize(song.uriString, song.folderPath)
        val descriptor = checkNotNull(audioStore.openRead(ref)) {
            "Stored fixture audio cannot be opened: $ref"
        }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun verifySongFile(song: Song, expectedFileName: String, expectedBytes: ByteArray) {
        check(SongPathNormalizer.fileName(song.uriString, song.folderPath) == expectedFileName) {
            "Unexpected stored filename: uri=${song.uriString}, folder=${song.folderPath}"
        }
        val actual = readSongBytes(song)
        check(actual.contentEquals(expectedBytes)) {
            "Stored fixture bytes differ: expected=${expectedBytes.size}, actual=${actual.size}"
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
            val path = request.requestUrl?.encodedPath.orEmpty()
            return when {
                path == "/search" -> catalogSearchResponse(request)
                path in setOf("/search/album", "/search/playlist", "/search/artist", "/search/track") ->
                    jsonResponse("""{"data":[]}""")
                path == "/api/get" -> MockResponse().setResponseCode(404)
                path == "/api/search" -> jsonResponse("[]")
                path == "/youtubei/v1/search" -> youtubeSearchResponse(request)
                path == "/youtubei/v1/player" -> youtubePlayerResponse(request)
                path == "/watch" -> MockResponse()
                    .setResponseCode(200)
                    .setBody("""<html>"visitorData":"fixture-visitor"</html>""")
                path.startsWith("/audio/") -> audioResponse(path)
                path in setOf("/cover.jpg", "/results") -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun catalogSearchResponse(request: RecordedRequest): MockResponse {
            val url = request.requestUrl
            val tracks = when (url?.queryParameter("q")) {
                CatalogDownloadTestContract.SEARCH_QUERY ->
                    listOf(CatalogDownloadTestContract.PRIMARY_TRACK)
                CatalogDownloadTestContract.METERED_SEARCH_QUERY ->
                    CatalogDownloadTestContract.METERED_TRACKS
                else -> emptyList()
            }
            return when {
                tracks.isNotEmpty() -> jsonResponse(catalogSearchJson(tracks))
                url?.queryParameter("term") != null -> jsonResponse("""{"results":[]}""")
                else -> jsonResponse("""{"data":[]}""")
            }
        }

        private fun youtubeSearchResponse(request: RecordedRequest): MockResponse {
            val query = runCatching {
                JSONObject(request.body.readUtf8()).optString("query")
            }.getOrDefault("")
            val track = CatalogDownloadTestContract.ALL_TRACKS.firstOrNull {
                query == "${CatalogDownloadTestContract.ARTIST} ${it.title}"
            } ?: CatalogDownloadTestContract.PRIMARY_TRACK.takeIf {
                query == "${CatalogDownloadTestContract.ARTIST} " +
                    CatalogDownloadTestContract.SAVE_AS_TITLE
            }
            return if (track != null) {
                jsonResponse(youtubeSearchJson(track))
            } else {
                jsonResponse("""{"contents":{"sectionListRenderer":{"contents":[]}}}""")
            }
        }

        private fun youtubePlayerResponse(request: RecordedRequest): MockResponse {
            val videoId = runCatching {
                JSONObject(request.body.readUtf8()).optString("videoId")
            }.getOrDefault("")
            val track = CatalogDownloadTestContract.ALL_TRACKS.firstOrNull {
                it.videoId == videoId
            } ?: return jsonResponse(
                """{"playabilityStatus":{"status":"ERROR","reason":"fixture only"}}"""
            )
            val audioUrl = server.url("/audio/${track.videoId}.wav").toString()
            return jsonResponse(youtubePlayerJson(track, audioUrl))
        }

        private fun audioResponse(path: String): MockResponse {
            val videoId = path.substringAfterLast('/').substringBeforeLast('.')
            val track = CatalogDownloadTestContract.ALL_TRACKS.firstOrNull {
                it.videoId == videoId
            } ?: return MockResponse().setResponseCode(404)
            audioRequestCounts.computeIfAbsent(videoId) { AtomicInteger() }.incrementAndGet()
            return when (audioScenario) {
                AudioScenario.GATED_SUCCESS -> {
                    expectedAudioRequests.countDown()
                    gatedSuccessResponse(track)
                }
                AudioScenario.PARTIAL_CANCELLATION -> {
                    expectedAudioRequests.countDown()
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "audio/wav")
                        .setBody(Buffer().write(newAudioBytes))
                        .throttleBody(
                            PARTIAL_CHUNK_BYTES,
                            PARTIAL_CHUNK_PERIOD_SECONDS,
                            TimeUnit.SECONDS
                        )
                }
                AudioScenario.FORBIDDEN_THEN_RECOVERY -> {
                    if (forbiddenRecovered) {
                        successResponse()
                    } else {
                        expectedAudioRequests.countDown()
                        MockResponse().setResponseCode(403).setBody("expired fixture CDN")
                    }
                }
                AudioScenario.METERED_AFTER_PERMIT -> {
                    if (track in CatalogDownloadTestContract.METERED_TRACKS.take(3)) {
                        expectedAudioRequests.countDown()
                        gatedSuccessResponse(track)
                    } else {
                        successResponse()
                    }
                }
            }
        }

        private fun gatedSuccessResponse(track: CatalogFixtureTrack): MockResponse {
            val released = releaseByVideoId[track.videoId]?.await(
                NETWORK_BOUNDARY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            ) == true
            return if (released) {
                successResponse()
            } else {
                MockResponse()
                    .setResponseCode(504)
                    .setBody("Fixture audio was not released before timeout")
            }
        }

        private fun successResponse(): MockResponse = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "audio/wav")
            .setBody(Buffer().write(newAudioBytes))

        private fun catalogSearchJson(tracks: List<CatalogFixtureTrack>): String {
            val data = tracks.joinToString(",") { track ->
                """
                {
                  "id": ${track.catalogId},
                  "title": "${track.title}",
                  "duration": 3,
                  "track_position": ${track.trackNumber},
                  "artist": {"name": "${CatalogDownloadTestContract.ARTIST}"},
                  "album": {
                    "title": "${CatalogDownloadTestContract.ALBUM}",
                    "cover_xl": "${server.url("/cover.jpg")}"
                  }
                }
                """.trimIndent()
            }
            return """{"data":[$data]}"""
        }

        private fun youtubeSearchJson(track: CatalogFixtureTrack): String = """
            {
              "contents": {
                "sectionListRenderer": {
                  "contents": [{
                    "itemSectionRenderer": {
                      "contents": [{
                        "videoRenderer": {
                          "videoId": "${track.videoId}",
                          "title": {"runs": [{"text": "${CatalogDownloadTestContract.ARTIST} - ${track.title} (Official Audio)"}]},
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

        private fun youtubePlayerJson(track: CatalogFixtureTrack, audioUrl: String): String = """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {
                "title": "${track.title}",
                "author": "${CatalogDownloadTestContract.ARTIST}",
                "lengthSeconds": "3"
              },
              "streamingData": {
                "adaptiveFormats": [{
                  "url": "$audioUrl",
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
        const val EXHAUSTED_DOWNLOAD_ATTEMPTS = 5
        const val CLEANUP_TIMEOUT_MS = 15_000L
        const val NETWORK_BOUNDARY_TIMEOUT_MS = 30_000L
        const val PARTIAL_CHUNK_BYTES = 16L * 1024L
        const val PARTIAL_CHUNK_PERIOD_SECONDS = 2L
    }
}
