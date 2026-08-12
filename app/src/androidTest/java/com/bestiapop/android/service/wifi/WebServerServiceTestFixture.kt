package com.bestiapop.android.service.wifi

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.WifiTransferItem
import com.bestiapop.android.data.model.WifiTransferState
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.service.WebServerService
import com.bestiapop.android.testutil.PcmWavFixture
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking

internal object WebServerServiceTestContract {
    const val FILE_NAME = "__bestiapop_wifi_functional_fixture__.wav"
    const val NOTIFICATION_ID = 2001
    const val NOTIFICATION_CHANNEL_ID = "web_server_channel"
}

internal data class TestHttpResponse(
    val code: Int,
    val body: String
)

internal data class RunningWebServer(
    val serverState: String,
    val serviceInfo: ActivityManager.RunningServiceInfo,
    val notification: Notification
)

internal data class PlayableFixtureFile(
    val byteCount: Long,
    val durationMs: Int
)

/**
 * Owns only the persistent state created by [WebServerServiceFunctionalTest].
 *
 * Production Application, Room, storage and Service instances remain untouched except for the
 * reserved fixture row/file. All asynchronous Android and Ktor transitions use bounded polling.
 */
internal class WebServerServiceTestFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as BestiaPopApplication
    private val repository = application.musicRepository
    private val dao = AppDatabase.getDatabase(context).musicDao()
    private val audioStore = MusicFileStore(context)
    private var activityScenario: ActivityScenario<MainActivity>? = null

    fun prepare() {
        grantStartupPermissions()
        stopAndAwait()
        dismissFixtureTransfers()
        deleteFixtureArtifacts()
        assertPortAvailable()
        launchForegroundHost()
    }

    fun startAndAwait(): RunningWebServer {
        ContextCompat.startForegroundService(
            context,
            Intent(context, WebServerService::class.java)
        )

        return awaitValue("WebServerService serverState, foreground flag and notification") {
            val state = WebServerService.serverState.value ?: return@awaitValue null
            val info = serviceInfo()?.takeIf(ActivityManager.RunningServiceInfo::foreground)
                ?: return@awaitValue null
            val notification = webServerNotification() ?: return@awaitValue null
            RunningWebServer(state, info, notification)
        }
    }

    fun getExistingFiles(): TestHttpResponse =
        executeLocalHttp(method = "GET", path = "/existing-files")

    fun uploadGeneratedPcmWav(): TestHttpResponse {
        val wav = PcmWavFixture.generate(durationMs = 750, toneHz = 440.0)
        return executeLocalHttp(
            method = "POST",
            path = "/upload-file?name=${WebServerServiceTestContract.FILE_NAME}",
            body = wav,
            contentType = "audio/wav"
        )
    }

    fun awaitCompletedTransfer(): WifiTransferItem =
        awaitValue("WiFi transfer DONE") {
            WebServerService.transfers.value.firstOrNull {
                it.fileName == WebServerServiceTestContract.FILE_NAME &&
                    it.state == WifiTransferState.DONE
            }
        }

    fun awaitPersistedSong(songId: Long): Song =
        awaitValue("uploaded Song $songId in Room") {
            runBlocking { dao.getSongById(songId) }
        }

    fun verifyPlayable(song: Song): PlayableFixtureFile {
        val ref = audioStore.canonicalize(song.uriString, song.folderPath)
        val byteCount = audioStore.openRead(ref).useOrThrow(song) { descriptor ->
            descriptor.statSize
        }
        check(byteCount == -1L || byteCount > PcmWavFixture.HEADER_SIZE_BYTES) {
            "Fixture file is empty or truncated: bytes=$byteCount, uri=${song.uriString}"
        }

        val player = MediaPlayer()
        return try {
            audioStore.applyDataSource(player, ref)
            player.prepare()
            check(player.duration > 0) {
                "MediaPlayer prepared fixture but reported duration=${player.duration}"
            }
            PlayableFixtureFile(byteCount = byteCount, durationMs = player.duration)
        } finally {
            runCatching { player.reset() }
            player.release()
        }
    }

    fun deleteFixtureArtifacts() {
        val rows = runBlocking {
            repository.getAllSongsSync().filter(::isFixtureSong)
        }
        if (rows.isNotEmpty()) {
            runBlocking { repository.deleteSongsFromDevice(rows) }
        }

        // Also remove an orphan left between file publication and Room persistence.
        val expectedFile = File(
            StorageUtils.publicBestiaPopDir(),
            WebServerServiceTestContract.FILE_NAME
        )
        audioStore.delete(audioStore.canonicalize(expectedFile.absolutePath, expectedFile.parent.orEmpty()))
        context.cacheDir.listFiles()
            .orEmpty()
            .filter { it.name.endsWith("_${WebServerServiceTestContract.FILE_NAME}") }
            .forEach(File::delete)
    }

    fun awaitFixtureRemoved(songId: Long) {
        await("fixture Room row and managed file removal") {
            val rowRemoved = runBlocking { dao.getSongById(songId) } == null
            val fileRemoved = WebServerServiceTestContract.FILE_NAME.lowercase() !in
                audioStore.listManagedNames()
            rowRemoved && fileRemoved
        }
    }

    fun stopAndAwait() {
        context.stopService(Intent(context, WebServerService::class.java))
        await("WebServerService stop, foreground demotion and notification removal") {
            WebServerService.serverState.value == null &&
                serviceInfo()?.foreground != true &&
                webServerNotification() == null
        }
    }

    @Suppress("DEPRECATION")
    fun serviceInfo(): ActivityManager.RunningServiceInfo? {
        val component = ComponentName(context, WebServerService::class.java)
        return context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service == component }
    }

    fun webServerNotification(): Notification? =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.id == WebServerServiceTestContract.NOTIFICATION_ID }
            ?.notification

    fun diagnostics(): String {
        val info = runCatching { serviceInfo() }.getOrNull()
        val notifications = runCatching {
            context.getSystemService(NotificationManager::class.java)
                .activeNotifications
                .joinToString(prefix = "[", postfix = "]") {
                    "${it.id}:${it.notification.channelId}"
                }
        }.getOrElse { "[error=${it.javaClass.simpleName}]" }
        val transfers = WebServerService.transfers.value.joinToString(
            prefix = "[",
            postfix = "]"
        ) { "${it.fileName}:${it.state}" }
        return "serverState=${WebServerService.serverState.value}, " +
            "serviceRunning=${info != null}, serviceForeground=${info?.foreground}, " +
            "servicePid=${info?.pid}, notifications=$notifications, transfers=$transfers, " +
            "activityState=${activityScenario?.state}"
    }

    override fun close() {
        runCatching { stopAndAwait() }
        runCatching { deleteFixtureArtifacts() }
        dismissFixtureTransfers()
        runCatching { activityScenario?.close() }
        activityScenario = null
    }

    private fun executeLocalHttp(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = null
    ): TestHttpResponse {
        val responseBytes = Socket().use { socket ->
            socket.connect(
                InetSocketAddress(LOOPBACK_HOST, WebServerService.PORT),
                HTTP_TIMEOUT_MS
            )
            socket.soTimeout = HTTP_TIMEOUT_MS
            val requestHead = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: localhost:${WebServerService.PORT}\r\n")
                append("Connection: close\r\n")
                contentType?.let { append("Content-Type: $it\r\n") }
                append("Content-Length: ${body.size}\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            val output = socket.getOutputStream().buffered()
            output.write(requestHead)
            output.write(body)
            output.flush()
            socket.getInputStream().readBytes()
        }
        val headerEnd = responseBytes.indexOfHeaderEnd()
        check(headerEnd >= 0) { "Malformed localhost HTTP response: no header terminator" }
        val headerText = responseBytes.copyOfRange(0, headerEnd)
            .toString(Charsets.ISO_8859_1)
        val statusLine = headerText.lineSequence().firstOrNull().orEmpty()
        val statusCode = statusLine.substringAfter(' ', missingDelimiterValue = "")
            .substringBefore(' ')
            .toIntOrNull()
            ?: error("Malformed localhost HTTP status: $statusLine")
        val encodedBody = responseBytes.copyOfRange(headerEnd + HTTP_HEADER_END.size, responseBytes.size)
        val responseBody = if (
            headerText.lineSequence().any {
                it.equals("Transfer-Encoding: chunked", ignoreCase = true)
            }
        ) {
            encodedBody.decodeChunkedBody()
        } else {
            encodedBody
        }
        return TestHttpResponse(statusCode, responseBody.toString(Charsets.UTF_8))
    }

    private fun grantStartupPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requiredPermissions.forEach { permission ->
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    permission
                )
            }
        }
        await("startup permissions including POST_NOTIFICATIONS") {
            requiredPermissions.all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            } &&
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }
    }

    private fun launchForegroundHost() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        activityScenario = scenario
        scenario.moveToState(Lifecycle.State.RESUMED)
        await("MainActivity RESUMED and focused before foreground service start") {
            var foreground = false
            scenario.onActivity { activity ->
                foreground =
                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    activity.hasWindowFocus() &&
                    !activity.isFinishing &&
                    !activity.isDestroyed
            }
            foreground
        }
    }

    private fun assertPortAvailable() {
        try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(WebServerService.PORT))
            }
        } catch (error: Exception) {
            val localProbe = runCatching {
                getExistingFiles().let { "HTTP ${it.code}: ${it.body.take(DIAGNOSTIC_BODY_LIMIT)}" }
            }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            throw AssertionError(
                "TCP ${WebServerService.PORT} is occupied before WebServerService start; " +
                    "localhost probe=$localProbe; ${diagnostics()}",
                error
            )
        }
    }

    private fun dismissFixtureTransfers() {
        WebServerService.transfers.value
            .filter { it.fileName == WebServerServiceTestContract.FILE_NAME }
            .forEach { WebServerService.dismissTransfer(it.id) }
    }

    private fun isFixtureSong(song: Song): Boolean =
        SongPathNormalizer.fileName(song.uriString, song.folderPath)
            .equals(WebServerServiceTestContract.FILE_NAME, ignoreCase = true)

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + ASYNC_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Timed out waiting for $description; ${diagnostics()}")
    }

    private fun <T : Any> awaitValue(description: String, value: () -> T?): T {
        var result: T? = null
        await(description) {
            value()?.also { result = it } != null
        }
        return requireNotNull(result)
    }

    private fun <T> ParcelFileDescriptor?.useOrThrow(
        song: Song,
        block: (ParcelFileDescriptor) -> T
    ): T {
        val descriptor = this
            ?: throw AssertionError("Fixture file does not exist or cannot be read: ${song.uriString}")
        return descriptor.use(block)
    }

    private companion object {
        const val ASYNC_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 25L
        const val HTTP_TIMEOUT_MS = 10_000
        const val DIAGNOSTIC_BODY_LIMIT = 200
        const val LOOPBACK_HOST = "127.0.0.1"
        val HTTP_HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
    }
}

private fun ByteArray.indexOfHeaderEnd(): Int {
    if (size < 4) return -1
    for (index in 0..size - 4) {
        if (this[index] == '\r'.code.toByte() &&
            this[index + 1] == '\n'.code.toByte() &&
            this[index + 2] == '\r'.code.toByte() &&
            this[index + 3] == '\n'.code.toByte()
        ) {
            return index
        }
    }
    return -1
}

private fun ByteArray.decodeChunkedBody(): ByteArray {
    val decoded = ByteArrayOutputStream(size)
    var offset = 0
    while (offset < size) {
        val lineEnd = indexOfCrlf(offset)
        check(lineEnd >= 0) { "Malformed chunked localhost response: missing chunk size" }
        val sizeText = copyOfRange(offset, lineEnd)
            .toString(Charsets.US_ASCII)
            .substringBefore(';')
        val chunkSize = sizeText.toIntOrNull(16)
            ?: error("Malformed chunk size: $sizeText")
        if (chunkSize == 0) break
        val chunkStart = lineEnd + 2
        val chunkEnd = chunkStart + chunkSize
        check(chunkEnd + 2 <= size) { "Malformed chunked localhost response: truncated body" }
        decoded.write(this, chunkStart, chunkSize)
        offset = chunkEnd + 2
    }
    return decoded.toByteArray()
}

private fun ByteArray.indexOfCrlf(start: Int): Int {
    for (index in start until lastIndex) {
        if (this[index] == '\r'.code.toByte() && this[index + 1] == '\n'.code.toByte()) {
            return index
        }
    }
    return -1
}
