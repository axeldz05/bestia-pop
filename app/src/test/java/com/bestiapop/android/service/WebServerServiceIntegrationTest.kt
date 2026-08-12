package com.bestiapop.android.service

import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.WifiTransferItem
import com.bestiapop.android.data.model.WifiTransferState
import com.bestiapop.android.testutil.MediumTest
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder

@Category(MediumTest::class)
class WebServerServiceIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    @Test
    fun existingFiles_returnsSanitizedUnionWithoutCaseDuplicates() {
        withServer(
            libraryNames = listOf("Música Ácida.mp3", "same.mp3"),
            managedNames = listOf("M_sica__cida.mp3", "SAME.MP3", "Otro Tema.flac")
        ) { harness ->
            val request = Request.Builder()
                .url(harness.url("/existing-files"))
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                val json = JSONArray(response.body!!.string())
                assertEquals(
                    listOf("m_sica__cida.mp3", "otro_tema.flac", "same.mp3"),
                    (0 until json.length()).map(json::getString)
                )
            }
        }
    }

    @Test
    fun accentedPathUpload_usesSafeBasenamePersistsAndCompletesTransfer() {
        val persistedPath = AtomicReference<String>()
        val persistedName = AtomicReference<String>()
        withServer(
            persistUpload = { path, safeName ->
                persistedPath.set(path)
                persistedName.set(safeName)
                WifiPersistedUpload(
                    identity = TrackIdentity(
                        title = "Música Ácida",
                        artist = "Artista",
                        artworkUri = "file:///cover.jpg"
                    ),
                    songId = 42L
                )
            }
        ) { harness ->
            val bodyBytes = "fake-audio-content".toByteArray()
            val url = harness.url("/upload-file").newBuilder()
                .addQueryParameter("name", "../../Música Ácida.mp3")
                .build()
            val request = Request.Builder()
                .url(url)
                .post(bodyBytes.toRequestBody(AUDIO_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertTrue(response.body!!.string().contains("M_sica__cida.mp3"))
            }

            assertEquals("M_sica__cida.mp3", persistedName.get())
            val published = File(persistedPath.get())
            assertEquals("M_sica__cida.mp3", published.name)
            assertEquals(bodyBytes.toList(), published.readBytes().toList())
            assertEquals(
                listOf(
                    WifiTransferState.UPLOADING,
                    WifiTransferState.PROCESSING,
                    WifiTransferState.DONE
                ),
                harness.distinctTransferStates()
            )
            val completed = harness.transfers.last()
            assertEquals(42L, completed.songId)
            assertEquals("Música Ácida", completed.title)
            assertEquals("Artista", completed.artist)
            assertEquals(100, completed.progressPercent)
        }
    }

    @Test
    fun uploadRejectsExternalHostAndWrongPortBeforeWriting() {
        withServer(advertisedHost = "192.168.1.20") { harness ->
            assertEquals(
                403,
                postOneByte(harness, host = "evil.example:${harness.port}")
            )
            assertEquals(
                403,
                postOneByte(harness, host = "localhost:${harness.port + 1}")
            )
            assertEquals(
                200,
                postOneByte(harness, host = "192.168.1.20:${harness.port}")
            )

            assertEquals(1, harness.storage.prepareCalls.get())
            assertEquals(WifiTransferState.DONE, harness.transfers.last().state)
        }
    }

    @Test
    fun declaredLengthOverLimit_returns413BeforePreparingStorage() {
        val testLimit = 64L * 1024
        withServer(maxUploadBytes = testLimit) { harness ->
            val statusLine = rawRequestStatus(
                port = harness.port,
                request = buildString {
                    append("POST /upload-file?name=too-large.mp3 HTTP/1.1\r\n")
                    append("Host: localhost:${harness.port}\r\n")
                    append("Content-Length: ${testLimit + 1}\r\n")
                    append("Connection: close\r\n\r\n")
                }
            )

            assertTrue(statusLine.contains("413"))
            assertEquals(0, harness.storage.prepareCalls.get())
            assertTrue(harness.transfers.isEmpty())
        }
    }

    @Test
    fun unknownLengthStreamOverLimit_returns413AndDeletesPartial() {
        val testLimit = 64L * 1024
        val persistCalls = AtomicInteger()
        withServer(
            maxUploadBytes = testLimit,
            persistUpload = { _, _ ->
                persistCalls.incrementAndGet()
                error("must not persist an oversized upload")
            }
        ) { harness ->
            val request = Request.Builder()
                .url(harness.url("/upload-file?name=stream.mp3"))
                .post(SyntheticUnknownLengthBody(testLimit + 1))
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(413, response.code)
                assertTrue(response.body!!.string().contains("Archivo demasiado grande"))
            }

            assertEquals(0, persistCalls.get())
            assertTrue(harness.storage.root.listFiles().orEmpty().isEmpty())
            assertEquals(
                listOf(WifiTransferState.UPLOADING, WifiTransferState.ERROR),
                harness.distinctTransferStates()
            )
            assertEquals("Archivo demasiado grande", harness.transfers.last().errorMessage)
        }
    }

    @Test
    fun persistenceFailure_returns500MarksErrorAndDeletesPublishedFile() {
        withServer(
            persistUpload = { _, _ -> throw IllegalStateException("Room insert failed") }
        ) { harness ->
            val request = Request.Builder()
                .url(harness.url("/upload-file?name=broken.mp3"))
                .post("audio".toRequestBody(AUDIO_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(500, response.code)
                assertTrue(response.body!!.string().contains("No se pudo guardar el archivo"))
            }

            assertTrue(harness.storage.root.listFiles().orEmpty().isEmpty())
            assertEquals(
                listOf(
                    WifiTransferState.UPLOADING,
                    WifiTransferState.PROCESSING,
                    WifiTransferState.ERROR
                ),
                harness.distinctTransferStates()
            )
            assertEquals("Room insert failed", harness.transfers.last().errorMessage)
            assertEquals("save_upload", harness.failures.single().phase)
        }
    }

    private fun postOneByte(harness: ServerHarness, host: String): Int {
        val request = Request.Builder()
            .url(harness.url("/upload-file?name=host-check.mp3"))
            .header("Host", host)
            .post(byteArrayOf(1).toRequestBody(AUDIO_TYPE))
            .build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun withServer(
        libraryNames: Collection<String> = emptyList(),
        managedNames: Collection<String> = emptyList(),
        advertisedHost: String = "127.0.0.1",
        maxUploadBytes: Long = 256L * 1024,
        persistUpload: suspend (String, String) -> WifiPersistedUpload = { _, safeName ->
            WifiPersistedUpload(
                identity = TrackIdentity(
                    title = safeName.substringBeforeLast("."),
                    artist = "Unknown Artist"
                ),
                songId = 1L
            )
        },
        block: (ServerHarness) -> Unit
    ) {
        val port = ServerSocket(0).use { it.localPort }
        val storage = TestUploadStorage(temporaryFolder.newFolder())
        val transfers = Collections.synchronizedList(mutableListOf<WifiTransferItem>())
        val failures = CopyOnWriteArrayList<ReportedFailure>()
        val boundary = WifiSyncHttpBoundary(
            port = port,
            advertisedHost = { advertisedHost },
            dashboardHtml = { "<html>WiFi test</html>" },
            listLibraryNames = { libraryNames },
            listManagedNames = { managedNames },
            prepareWrite = storage::prepare,
            persistUpload = persistUpload,
            onTransfer = transfers::add,
            onFailure = { error, phase, transferId ->
                failures += ReportedFailure(error, phase, transferId)
            },
            maxUploadBytes = maxUploadBytes,
            newTransferId = { "transfer-test" },
            nowMillis = { 123L }
        )
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            boundary.install(this)
        }.start(wait = false)
        val harness = ServerHarness(port, storage, transfers, failures)
        try {
            block(harness)
        } finally {
            server.stop(100, 1_000)
        }
    }

    private fun rawRequestStatus(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
        }

    private class SyntheticUnknownLengthBody(private val byteCount: Long) : RequestBody() {
        override fun contentType() = AUDIO_TYPE

        override fun contentLength(): Long = -1L

        override fun writeTo(sink: BufferedSink) {
            val chunk = ByteArray(8 * 1024) { 7 }
            var remaining = byteCount
            while (remaining > 0L) {
                val count = minOf(chunk.size.toLong(), remaining).toInt()
                sink.write(chunk, 0, count)
                remaining -= count
            }
        }
    }

    private class TestUploadStorage(val root: File) {
        val prepareCalls = AtomicInteger()

        fun prepare(safeName: String): WifiPendingUpload {
            prepareCalls.incrementAndGet()
            val staging = File(root, "staging-$safeName")
            val published = File(root, safeName)
            return WifiPendingUpload(
                stagingFile = staging,
                publish = {
                    staging.copyTo(published, overwrite = true)
                    assertTrue(staging.delete())
                    published.absolutePath
                },
                deletePartial = { publishedPath ->
                    staging.delete()
                    publishedPath?.let { File(it).delete() }
                }
            )
        }
    }

    private data class ReportedFailure(
        val error: Throwable,
        val phase: String,
        val transferId: String
    )

    private data class ServerHarness(
        val port: Int,
        val storage: TestUploadStorage,
        val transfers: List<WifiTransferItem>,
        val failures: List<ReportedFailure>
    ) {
        fun url(path: String): HttpUrl = "http://127.0.0.1:$port$path".toHttpUrl()

        fun distinctTransferStates(): List<WifiTransferState> =
            transfers.map(WifiTransferItem::state).distinct()
    }

    private companion object {
        val AUDIO_TYPE = "application/octet-stream".toMediaType()
    }
}
