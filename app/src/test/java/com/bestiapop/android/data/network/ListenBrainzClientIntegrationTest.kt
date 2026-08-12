package com.bestiapop.android.data.network

import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.MockWebServerRule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
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
class ListenBrainzClientIntegrationTest {

    @get:Rule
    val server = MockWebServerRule()

    @Before
    fun setUp() {
        configureClient(callTimeoutMs = 1_000)
    }

    @After
    fun tearDown() {
        ListenBrainzClient.resetTestOverrides()
    }

    @Test
    fun submitListens_200_sendsContractAndReturnsRateLimitState() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-RateLimit-Remaining", "17")
                .setHeader("X-RateLimit-Reset-In", "9")
                .setBody("""{"status":"ok"}""")
        )

        val result = ListenBrainzClient.submitListens(
            token = " fixture-token ",
            listens = listOf(
                ListenPayload(
                    listenedAt = 1_700_000_000L,
                    trackName = "Track A",
                    artistName = "Artist A",
                    releaseName = "Album A",
                    durationMs = 201_000L
                )
            )
        )

        assertEquals(SubmitListensResult.Success(17, 9), result)
        with(server.takeRequest()) {
            assertEquals("POST", method)
            assertEquals("/1/submit-listens", requestUrl?.encodedPath)
            assertEquals("Token fixture-token", getHeader("Authorization"))
            val json = JSONObject(body.readUtf8())
            assertEquals("single", json.getString("listen_type"))
            val metadata = json.getJSONArray("payload")
                .getJSONObject(0)
                .getJSONObject("track_metadata")
            assertEquals("Track A", metadata.getString("track_name"))
            assertEquals("Artist A", metadata.getString("artist_name"))
            assertEquals("Album A", metadata.getString("release_name"))
            assertEquals(
                201_000L,
                metadata.getJSONObject("additional_info").getLong("duration_ms")
            )
        }
    }

    @Test
    fun submitListens_401_returnsApiFailure() = runBlocking {
        enqueueJson(401, """{"error":"invalid token"}""")

        val result = ListenBrainzClient.submitListens("bad-token", fixtureListens())

        assertTrue(result is SubmitListensResult.Failure)
        with(result as SubmitListensResult.Failure) {
            assertEquals("invalid token", message)
            assertFalse(isNetworkError)
        }
    }

    @Test
    fun submitListens_500_returnsApiFailure() = runBlocking {
        enqueueJson(500, """{"error":"temporary failure"}""")

        val result = ListenBrainzClient.submitListens("token", fixtureListens())

        assertTrue(result is SubmitListensResult.Failure)
        with(result as SubmitListensResult.Failure) {
            assertEquals("temporary failure", message)
            assertFalse(isNetworkError)
        }
    }

    @Test
    fun submitListens_timeout_returnsNetworkFailure() = runBlocking {
        configureClient(callTimeoutMs = 150)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = ListenBrainzClient.submitListens("token", fixtureListens())

        assertTrue(result is SubmitListensResult.Failure)
        assertTrue((result as SubmitListensResult.Failure).isNetworkError)
    }

    @Test
    fun createdFor_returnsPlaylistSummariesAndUsesEncodedUser() = runBlocking {
        enqueueJson(200, CREATED_FOR_FIXTURE)

        val result = ListenBrainzClient.fetchCreatedForPlaylists(
            username = "fixture-user",
            token = "token",
            count = 7,
            offset = 2
        )

        assertTrue(result is LbApiResult.Success)
        val playlists = (result as LbApiResult.Success).data
        assertEquals(1, playlists.size)
        with(playlists.single()) {
            assertEquals(PLAYLIST_MBID, mbid)
            assertEquals("Fixture Mix", title)
            assertEquals(2, trackCount)
        }
        with(server.takeRequest()) {
            assertEquals("/1/user/fixture-user/playlists/createdfor", requestUrl?.encodedPath)
            assertEquals("7", requestUrl?.queryParameter("count"))
            assertEquals("2", requestUrl?.queryParameter("offset"))
            assertEquals("Token token", getHeader("Authorization"))
        }
    }

    @Test
    fun playlistDetail_returnsTracksAndRecordingIdentity() = runBlocking {
        enqueueJson(200, PLAYLIST_DETAIL_FIXTURE)

        val result = ListenBrainzClient.fetchPlaylist(PLAYLIST_MBID)

        assertTrue(result is LbApiResult.Success)
        val detail = (result as LbApiResult.Success).data
        assertEquals("Fixture Mix", detail.summary.title)
        assertEquals(1, detail.tracks.size)
        with(detail.tracks.single()) {
            assertEquals("Track B", title)
            assertEquals("Artist B", artist)
            assertEquals("Album B", album)
            assertEquals(RECORDING_MBID, recordingMbid)
        }
        assertEquals(
            "/1/playlist/$PLAYLIST_MBID",
            server.takeRequest().requestUrl?.encodedPath
        )
    }

    @Test
    fun cfRecommendations_returnsScoredRecordingPool() = runBlocking {
        enqueueJson(200, CF_FIXTURE)

        val result = ListenBrainzClient.fetchCfRecordingRecommendations(
            username = "fixture-user",
            count = 3,
            offset = 1,
            artistType = "similar"
        )

        assertTrue(result is LbApiResult.Success)
        val payload = (result as LbApiResult.Success).data
        assertEquals("fixture-user", payload.userName)
        assertEquals(1, payload.recordings.size)
        assertEquals(RECORDING_MBID, payload.recordings.single().recordingMbid)
        assertEquals(0.875, payload.recordings.single().score, 0.0001)
        with(server.takeRequest()) {
            assertEquals(
                "/1/cf/recommendation/user/fixture-user/recording",
                requestUrl?.encodedPath
            )
            assertEquals("3", requestUrl?.queryParameter("count"))
            assertEquals("1", requestUrl?.queryParameter("offset"))
            assertEquals("similar", requestUrl?.queryParameter("artist_type"))
        }
    }

    private fun configureClient(callTimeoutMs: Long) {
        ListenBrainzClient.configureForTest(
            http = OkHttpClient.Builder()
                .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                .build(),
            endpoints = ListenBrainzEndpoints(
                apiBaseUrl = server.url("/1").toString().trimEnd('/')
            )
        )
    }

    private fun enqueueJson(code: Int, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body.trimIndent())
        )
    }

    private fun fixtureListens(): List<ListenPayload> = listOf(
        ListenPayload(
            listenedAt = 1_700_000_000L,
            trackName = "Track A",
            artistName = "Artist A"
        )
    )

    private companion object {
        const val PLAYLIST_MBID = "11111111-2222-3333-4444-555555555555"
        const val RECORDING_MBID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

        val CREATED_FOR_FIXTURE = """
            {
              "playlists": [{
                "playlist": {
                  "identifier": "https://listenbrainz.invalid/playlist/$PLAYLIST_MBID",
                  "title": "Fixture Mix",
                  "annotation": "Anonymous fixture",
                  "num_tracks": 2
                }
              }]
            }
        """

        val PLAYLIST_DETAIL_FIXTURE = """
            {
              "playlist": {
                "identifier": "https://listenbrainz.invalid/playlist/$PLAYLIST_MBID",
                "title": "Fixture Mix",
                "track": [{
                  "title": "Track B",
                  "creator": "Artist B",
                  "album": "Album B",
                  "identifier": ["https://musicbrainz.invalid/recording/$RECORDING_MBID"]
                }]
              }
            }
        """

        val CF_FIXTURE = """
            {
              "payload": {
                "user_name": "fixture-user",
                "type": "similar",
                "last_updated": 1700000000,
                "total_mbid_count": 1,
                "mbids": [{
                  "recording_mbid": "$RECORDING_MBID",
                  "score": 0.875
                }]
              }
            }
        """
    }
}
