package com.bestiapop.android.data.network

import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.MockWebServerRule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.TimeUnit

@Category(MediumTest::class)
class YouTubeExtractorCooldownTest {
    @get:Rule
    val server = MockWebServerRule()

    @Before
    fun setUp() {
        val localBaseUrl = server.url("/").toString()
        YouTubeExtractor.configureForTest(
            http =
                OkHttpClient
                    .Builder()
                    .callTimeout(1, TimeUnit.SECONDS)
                    .build(),
            endpoints =
                YouTubeEndpoints(
                    webBaseUrl = localBaseUrl,
                    googleApiBaseUrl = localBaseUrl,
                ),
        )
        YouTubeExtractor.resetClientCooldowns()
    }

    @After
    fun tearDown() {
        YouTubeExtractor.resetClientCooldowns()
        YouTubeExtractor.resetTestOverrides()
    }

    @Test
    fun reportClientHttpFailure_putsClientOnCooldown_onlyFor403And410() {
        assertFalse(YouTubeExtractor.isClientOnCooldown("VISIONOS"))

        // Non-auth HTTP code should not trigger cooldown
        YouTubeExtractor.reportClientHttpFailure("VISIONOS", 500)
        assertFalse(YouTubeExtractor.isClientOnCooldown("VISIONOS"))

        // 403 Forbidden triggers cooldown
        YouTubeExtractor.reportClientHttpFailure("VISIONOS", 403)
        assertTrue(YouTubeExtractor.isClientOnCooldown("VISIONOS"))

        // Reset clears cooldown
        YouTubeExtractor.resetClientCooldowns()
        assertFalse(YouTubeExtractor.isClientOnCooldown("VISIONOS"))

        // 410 Gone also triggers cooldown
        YouTubeExtractor.reportClientHttpFailure("VISIONOS", 410)
        assertTrue(YouTubeExtractor.isClientOnCooldown("VISIONOS"))
    }

    @Test
    fun extractAudioStreamDetailed_skipsClientOnCooldown_triesNextAudioOnlyClientFirst() =
        runBlocking {
            // Mark VISIONOS as cooled down
            YouTubeExtractor.reportClientHttpFailure("VISIONOS", 403)
            assertTrue(YouTubeExtractor.isClientOnCooldown("VISIONOS"))

            // Mock visitorData call (watch endpoint)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""<html>"visitorData":"test-visitor"</html>"""),
            )

            // Mock TVHTML5 audio-only response
            enqueueJson(AUDIO_ONLY_FIXTURE)

            val result = YouTubeExtractor.extractAudioStreamDetailed(VIDEO_ID)

            assertTrue(result is YouTubeExtractResult.Success)
            val stream = (result as YouTubeExtractResult.Success).result
            assertEquals(VIDEO_ID, stream.videoId)
            assertEquals("TVHTML5", stream.clientName)
            assertEquals("https://media.invalid/audio-itag140.m4a", stream.audioUrl)

            // Consume /watch request
            server.takeRequest()

            // Inspect player request: must have been made with client "7" (TVHTML5) to preserve minimal bandwidth
            val playerRequest = server.takeRequest()
            assertEquals("POST", playerRequest.method)
            assertEquals("7", playerRequest.getHeader("X-YouTube-Client-Name"))
            val bodyJson = JSONObject(playerRequest.body.readUtf8())
            assertEquals("TVHTML5", bodyJson.getJSONObject("context").getJSONObject("client").getString("clientName"))
        }

    @Test
    fun extractAudioStreamDetailed_skipsMultipleCooledDownClients_andFallsBackToAndroidMain() =
        runBlocking {
            // Mark all audio-only clients as cooled down
            YouTubeExtractor.reportClientHttpFailure("VISIONOS", 403)
            YouTubeExtractor.reportClientHttpFailure("TVHTML5", 403)
            YouTubeExtractor.reportClientHttpFailure("ANDROID_MUSIC", 403)
            assertTrue(YouTubeExtractor.isClientOnCooldown("VISIONOS"))
            assertTrue(YouTubeExtractor.isClientOnCooldown("TVHTML5"))
            assertTrue(YouTubeExtractor.isClientOnCooldown("ANDROID_MUSIC"))

            // Mock visitorData call (watch endpoint)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""<html>"visitorData":"test-visitor"</html>"""),
            )

            // Mock ANDROID_MAIN player response
            enqueueJson(ANDROID_PLAYER_FIXTURE)

            val result = YouTubeExtractor.extractAudioStreamDetailed(VIDEO_ID)

            assertTrue(result is YouTubeExtractResult.Success)
            val stream = (result as YouTubeExtractResult.Success).result
            assertEquals(VIDEO_ID, stream.videoId)
            assertEquals("ANDROID", stream.clientName)
            assertEquals("https://media.invalid/android-fmt18.mp4", stream.audioUrl)

            server.takeRequest()
            val playerRequest = server.takeRequest()
            assertEquals("POST", playerRequest.method)
            assertEquals("3", playerRequest.getHeader("X-YouTube-Client-Name"))
        }

    @Test
    fun extractAudioStreamDetailed_respectsExcludedClientsParameter() =
        runBlocking {
            // Pass audio-only clients as excludedClients
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""<html>"visitorData":"test-visitor"</html>"""),
            )
            enqueueJson(ANDROID_PLAYER_FIXTURE)

            val result =
                YouTubeExtractor.extractAudioStreamDetailed(
                    urlOrQuery = VIDEO_ID,
                    excludedClients = setOf("VISIONOS", "TVHTML5", "ANDROID_MUSIC"),
                )

            assertTrue(result is YouTubeExtractResult.Success)
            val stream = (result as YouTubeExtractResult.Success).result
            assertEquals("ANDROID", stream.clientName)

            server.takeRequest()
            val playerRequest = server.takeRequest()
            assertEquals("3", playerRequest.getHeader("X-YouTube-Client-Name"))
        }

    @Test
    fun extractAudioStreamDetailed_dynamicWaterfall_iteratesThroughClientsSequentiallyUntilAndroidMain() =
        runBlocking {
            // Mock visitorData call (watch endpoint)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""<html>"visitorData":"test-visitor"</html>"""),
            )

            // Enqueue failure (LOGIN_REQUIRED) for all audio-only clients in order:
            // 1. VISIONOS (101)
            enqueueJson(LOGIN_REQUIRED_FIXTURE)
            // 2. TV_DOWNGRADED (7)
            enqueueJson(LOGIN_REQUIRED_FIXTURE)
            // 3. ANDROID_MUSIC (21)
            enqueueJson(LOGIN_REQUIRED_FIXTURE)
            // 4. TV_EMBED (7)
            enqueueJson(LOGIN_REQUIRED_FIXTURE)
            // 5. ANDROID_MAIN (3) -> Succeeds with format 18 (Last Resort)
            enqueueJson(ANDROID_PLAYER_FIXTURE)

            val result = YouTubeExtractor.extractAudioStreamDetailed(VIDEO_ID)

            assertTrue(result is YouTubeExtractResult.Success)
            val stream = (result as YouTubeExtractResult.Success).result
            assertEquals(VIDEO_ID, stream.videoId)
            assertEquals("ANDROID", stream.clientName)
            assertEquals("https://media.invalid/android-fmt18.mp4", stream.audioUrl)

            // 1 watch request
            server.takeRequest()

            // 5 player requests in exact waterfall order
            val req1 = server.takeRequest()
            assertEquals("101", req1.getHeader("X-YouTube-Client-Name")) // VISIONOS

            val req2 = server.takeRequest()
            assertEquals("7", req2.getHeader("X-YouTube-Client-Name")) // TV_DOWNGRADED

            val req3 = server.takeRequest()
            assertEquals("21", req3.getHeader("X-YouTube-Client-Name")) // ANDROID_MUSIC

            val req4 = server.takeRequest()
            assertEquals("7", req4.getHeader("X-YouTube-Client-Name")) // TV_EMBED

            val req5 = server.takeRequest()
            assertEquals("3", req5.getHeader("X-YouTube-Client-Name")) // ANDROID_MAIN (Last Resort)
        }

    @Test
    fun extractAudioStreamDetailed_stopsAtFirstWorkingClient_savingBandwidthAndRequests() =
        runBlocking {
            // Mock visitorData call (watch endpoint)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""<html>"visitorData":"test-visitor"</html>"""),
            )

            // VISIONOS succeeds immediately with audio-only stream
            enqueueJson(AUDIO_ONLY_FIXTURE)

            val result = YouTubeExtractor.extractAudioStreamDetailed(VIDEO_ID)

            assertTrue(result is YouTubeExtractResult.Success)
            val stream = (result as YouTubeExtractResult.Success).result
            assertEquals(VIDEO_ID, stream.videoId)
            assertEquals("VISIONOS", stream.clientName)
            assertEquals("https://media.invalid/audio-itag140.m4a", stream.audioUrl)

            // Only 2 total requests were made: 1 watch + 1 player
            assertEquals(2, server.server.requestCount)
            val watchReq = server.takeRequest()
            assertTrue(watchReq.path?.contains("watch?v=") == true)

            val playerReq = server.takeRequest()
            assertEquals("101", playerReq.getHeader("X-YouTube-Client-Name"))
        }

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body.trimIndent()),
        )
    }

    private companion object {
        const val VIDEO_ID = "coolVid1234"

        val AUDIO_ONLY_FIXTURE = """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {
                "title": "Audio Track",
                "author": "Audio Artist",
                "lengthSeconds": "200"
              },
              "streamingData": {
                "adaptiveFormats": [{
                  "itag": 140,
                  "url": "https://media.invalid/audio-itag140.m4a",
                  "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                  "bitrate": 128000
                }]
              }
            }
        """

        val LOGIN_REQUIRED_FIXTURE = """
            {
              "playabilityStatus": {
                "status": "LOGIN_REQUIRED",
                "reason": "Inicia sesión para confirmar que no eres un bot"
              }
            }
        """

        val ANDROID_PLAYER_FIXTURE = """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {
                "title": "Fallback Track",
                "author": "Fallback Artist",
                "lengthSeconds": "180"
              },
              "streamingData": {
                "formats": [{
                  "itag": 18,
                  "url": "https://media.invalid/android-fmt18.mp4",
                  "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                  "bitrate": 96000
                }]
              }
            }
        """
    }
}
