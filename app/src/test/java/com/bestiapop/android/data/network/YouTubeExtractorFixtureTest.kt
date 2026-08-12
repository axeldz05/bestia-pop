package com.bestiapop.android.data.network

import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.MockWebServerRule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.TimeUnit

@Category(MediumTest::class)
class YouTubeExtractorFixtureTest {

    @get:Rule
    val server = MockWebServerRule()

    @Before
    fun setUp() {
        val localBaseUrl = server.url("/").toString()
        YouTubeExtractor.configureForTest(
            http = OkHttpClient.Builder()
                .callTimeout(1, TimeUnit.SECONDS)
                .build(),
            endpoints = YouTubeEndpoints(
                webBaseUrl = localBaseUrl,
                googleApiBaseUrl = localBaseUrl
            )
        )
    }

    @After
    fun tearDown() {
        YouTubeExtractor.resetTestOverrides()
    }

    @Test
    fun searchFixture_returnsCatalogTrackWithoutLiveNetwork() = runBlocking {
        enqueueJson(SEARCH_FIXTURE)

        val tracks = YouTubeExtractor.searchYouTube("anonymous fixture")

        assertEquals(1, tracks.size)
        with(tracks.single()) {
            assertEquals(VIDEO_ID, id)
            assertEquals("Fixture Song", title)
            assertEquals("Fixture Artist", artist)
            assertEquals(203_000L, durationMs)
            assertEquals("https://fixtures.invalid/thumb.jpg", artworkUri)
            assertEquals("YouTube", provider)
        }
        with(server.takeRequest()) {
            assertEquals("POST", method)
            assertEquals("/youtubei/v1/search", requestUrl?.encodedPath)
            assertEquals("28", getHeader("X-YouTube-Client-Name"))
            assertEquals(
                "anonymous fixture",
                JSONObject(body.readUtf8()).getString("query")
            )
        }
    }

    @Test
    fun playerFixture_returnsAudioStreamAndProfileUserAgentWithoutLiveNetwork() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html>"visitorData":"fixture-visitor"</html>""")
        )
        enqueueJson(PLAYER_FIXTURE)

        val result = YouTubeExtractor.extractAudioStreamDetailed(VIDEO_ID)

        assertTrue(result is YouTubeExtractResult.Success)
        val stream = (result as YouTubeExtractResult.Success).result
        assertEquals(VIDEO_ID, stream.videoId)
        assertEquals("Fixture Song", stream.title)
        assertEquals("Fixture Artist", stream.artist)
        assertEquals(203_000L, stream.durationMs)
        assertEquals("https://media.invalid/fixture-audio.m4a", stream.audioUrl)
        assertTrue(stream.userAgent.isNotBlank())

        with(server.takeRequest()) {
            assertEquals("GET", method)
            assertEquals("/watch", requestUrl?.encodedPath)
            assertEquals(VIDEO_ID, requestUrl?.queryParameter("v"))
        }
        with(server.takeRequest()) {
            assertEquals("POST", method)
            assertEquals("/youtubei/v1/player", requestUrl?.encodedPath)
            assertEquals(VIDEO_ID, JSONObject(body.readUtf8()).getString("videoId"))
        }
    }

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body.trimIndent())
        )
    }

    private companion object {
        const val VIDEO_ID = "abc123DEF45"

        val SEARCH_FIXTURE = """
            {
              "contents": {
                "sectionListRenderer": {
                  "contents": [{
                    "itemSectionRenderer": {
                      "contents": [{
                        "videoRenderer": {
                          "videoId": "$VIDEO_ID",
                          "title": {"runs": [{"text": "Fixture Artist - Fixture Song (Official Audio)"}]},
                          "ownerText": {"runs": [{"text": "Fixture Artist - Topic"}]},
                          "lengthText": {"simpleText": "3:23"},
                          "thumbnail": {
                            "thumbnails": [{"url": "https://fixtures.invalid/thumb.jpg"}]
                          }
                        }
                      }]
                    }
                  }]
                }
              }
            }
        """

        val PLAYER_FIXTURE = """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {
                "title": "Fixture Artist - Fixture Song (Official Audio)",
                "author": "Fixture Artist - Topic",
                "lengthSeconds": "203",
                "thumbnail": {
                  "thumbnails": [{"url": "https://fixtures.invalid/player-thumb.jpg"}]
                }
              },
              "streamingData": {
                "adaptiveFormats": [{
                  "url": "https://media.invalid/fixture-audio.m4a",
                  "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                  "bitrate": 128000
                }]
              }
            }
        """
    }
}
