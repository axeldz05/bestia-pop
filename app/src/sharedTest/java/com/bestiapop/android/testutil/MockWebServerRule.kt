package com.bestiapop.android.testutil

import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.rules.ExternalResource
import java.util.concurrent.TimeUnit

/**
 * Owns one localhost [MockWebServer] per test.
 *
 * Tests enqueue explicit responses and use [takeRequest] for bounded request assertions. They may
 * access [server] directly when a dispatcher is part of the scenario.
 */
class MockWebServerRule : ExternalResource() {
    lateinit var server: MockWebServer
        private set

    override fun before() {
        server = MockWebServer()
        server.start()
    }

    override fun after() {
        if (::server.isInitialized) {
            server.shutdown()
        }
    }

    fun url(path: String = "/"): HttpUrl {
        require(path.startsWith('/')) { "MockWebServer paths must start with '/': $path" }
        return server.url(path)
    }

    fun enqueue(response: MockResponse) {
        server.enqueue(response)
    }

    fun takeRequest(timeoutMs: Long = 1_000L): RecordedRequest {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        return checkNotNull(server.takeRequest(timeoutMs, TimeUnit.MILLISECONDS)) {
            "No request received by MockWebServer within ${timeoutMs}ms"
        }
    }
}
