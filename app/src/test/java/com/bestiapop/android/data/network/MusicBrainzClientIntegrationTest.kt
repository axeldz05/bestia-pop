package com.bestiapop.android.data.network

import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.MockWebServerRule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.TimeUnit

@Category(MediumTest::class)
class MusicBrainzClientIntegrationTest {

    @get:Rule
    val server = MockWebServerRule()

    @Before
    fun setUp() {
        MusicBrainzClient.configureForTest(
            http = OkHttpClient.Builder()
                .callTimeout(400, TimeUnit.MILLISECONDS)
                .readTimeout(400, TimeUnit.MILLISECONDS)
                .connectTimeout(400, TimeUnit.MILLISECONDS)
                .build(),
            endpoints = MusicBrainzEndpoints(
                apiBaseUrl = server.url("/ws/2").toString().trimEnd('/')
            ),
            minIntervalMs = 0L
        )
    }

    @After
    fun tearDown() {
        MusicBrainzClient.resetTestOverrides()
    }

    @Test
    fun timeout_doesNotRetryWithoutDuration() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val tracks = MusicBrainzClient.searchRecordings(
            query = "The Doors Roadhouse Blues",
            durationMs = 240_000L
        )
        assertTrue(tracks.isEmpty())
        assertEquals(1, server.server.requestCount)
    }

    @Test
    fun emptyRecordings_retriesWithoutDuration() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"recordings":[]}"""))
        server.enqueue(
            MockResponse().setBody(
                """
                {"recordings":[{
                  "id":"rec-1",
                  "title":"Roadhouse Blues",
                  "length":240000,
                  "artist-credit":[{"name":"The Doors","joinphrase":""}]
                }]}
                """.trimIndent()
            )
        )
        val tracks = MusicBrainzClient.searchRecordings(
            query = "The Doors Roadhouse Blues",
            durationMs = 240_000L
        )
        assertEquals(1, tracks.size)
        assertEquals("The Doors", tracks.single().artist)
        assertEquals(2, server.server.requestCount)
    }
}
