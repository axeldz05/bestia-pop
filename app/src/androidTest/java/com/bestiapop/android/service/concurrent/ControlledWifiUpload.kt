package com.bestiapop.android.service.concurrent

import com.bestiapop.android.service.WebServerService
import com.bestiapop.android.service.wifi.WebServerServiceTestContract
import com.bestiapop.android.testutil.PcmWavFixture
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Localhost upload with an explicit midpoint gate, so the test can inspect both active services
 * while Ktor is blocked on real request bytes rather than relying on timing.
 */
internal class ControlledWifiUpload : AutoCloseable {
    private val bytes = PcmWavFixture.generate(durationMs = UPLOAD_DURATION_MS, toneHz = 440.0)
    private val firstChunkSent = CountDownLatch(1)
    private val releaseRemainder = CountDownLatch(1)
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var socket: Socket? = null
    private var response: Future<Int>? = null

    fun start() {
        check(response == null) { "Controlled WiFi upload already started" }
        response = executor.submit<Int> { upload() }
    }

    fun awaitFirstChunkSent() {
        check(firstChunkSent.await(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Controlled WiFi upload did not send its first chunk. ${diagnostics()}"
        }
    }

    fun release() {
        releaseRemainder.countDown()
    }

    fun awaitResponseCode(): Int =
        checkNotNull(response) { "Controlled WiFi upload was not started" }
            .get(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

    fun diagnostics(): String =
        "firstChunkSent=${firstChunkSent.count == 0L}, " +
            "remainderReleased=${releaseRemainder.count == 0L}, " +
            "responseDone=${response?.isDone}, socketClosed=${socket?.isClosed}"

    override fun close() {
        var failure: Throwable? = null
        release()
        response?.let { pending ->
            runCatching {
                if (!pending.isDone) {
                    pending.get(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            }.exceptionOrNull()?.let { failure = it }
        }
        runCatching { socket?.close() }
            .exceptionOrNull()
            ?.let { closeFailure ->
                if (failure == null) failure = closeFailure else failure?.addSuppressed(closeFailure)
            }
        executor.shutdownNow()
        runCatching {
            check(executor.awaitTermination(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "Controlled WiFi upload executor did not terminate"
            }
        }.exceptionOrNull()?.let { executorFailure ->
            if (failure == null) failure = executorFailure else failure?.addSuppressed(executorFailure)
        }
        failure?.let { throw it }
    }

    private fun upload(): Int {
        val activeSocket = Socket()
        socket = activeSocket
        return activeSocket.use { connected ->
            connected.connect(
                InetSocketAddress(LOOPBACK_HOST, WebServerService.PORT),
                HTTP_TIMEOUT_MS
            )
            connected.soTimeout = HTTP_TIMEOUT_MS
            val output = connected.getOutputStream().buffered()
            val requestHead = buildString {
                append(
                    "POST /upload-file?name=${WebServerServiceTestContract.FILE_NAME} " +
                        "HTTP/1.1\r\n"
                )
                append("Host: localhost:${WebServerService.PORT}\r\n")
                append("Connection: close\r\n")
                append("Content-Type: audio/wav\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            output.write(requestHead)
            output.write(bytes, 0, FIRST_CHUNK_BYTES)
            output.flush()
            firstChunkSent.countDown()

            check(releaseRemainder.await(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "Controlled WiFi upload remainder was not released"
            }
            output.write(bytes, FIRST_CHUNK_BYTES, bytes.size - FIRST_CHUNK_BYTES)
            output.flush()

            val responseBytes = connected.getInputStream().readBytes()
            val statusLine = responseBytes
                .toString(Charsets.ISO_8859_1)
                .lineSequence()
                .firstOrNull()
                .orEmpty()
            statusLine.substringAfter(' ', missingDelimiterValue = "")
                .substringBefore(' ')
                .toIntOrNull()
                ?: error("Malformed localhost HTTP status: $statusLine")
        }
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val UPLOAD_DURATION_MS = 10_000
        const val FIRST_CHUNK_BYTES = 128 * 1024
        const val HTTP_TIMEOUT_MS = 15_000
        const val GATE_TIMEOUT_MS = 20_000L
    }
}
