package com.bestiapop.android.testutil

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(MediumTest::class)
class MockWebServerRuleSmokeTest {
    @get:Rule
    val server = MockWebServerRule()

    @Test
    fun queuedResponse_andCapturedRequest_stayOnLocalhost() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok"}""")
        )

        OkHttpClient().newCall(
            Request.Builder()
                .url(server.url("/health"))
                .build()
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"status":"ok"}""", response.body?.string())
        }

        assertEquals("/health", server.takeRequest().path)
    }
}
