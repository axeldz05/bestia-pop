package com.bestiapop.android.data.update

import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.MockWebServerRule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.TimeUnit

@Category(MediumTest::class)
class GitHubUpdateClientIntegrationTest {

    @get:Rule
    val server = MockWebServerRule()

    @Test
    fun fetchReleases_200_returnsPublishedReleaseAndSendsGitHubHeaders() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(RELEASES_FIXTURE.trimIndent())
        )

        val result = client().fetchReleases()

        assertTrue(result.isSuccess)
        val releases = result.getOrThrow()
        assertEquals(1, releases.size)
        with(releases.single()) {
            assertEquals(42, versionCode)
            assertEquals("2.4.0", versionName)
            assertEquals("Fixture changes", notes)
            assertEquals("https://downloads.invalid/BestiaPop-2.4.0.apk", apkUrl)
        }
        with(server.takeRequest()) {
            assertEquals("GET", method)
            assertEquals("/repos/fixture-owner/fixture-repo/releases", requestUrl?.encodedPath)
            assertEquals("30", requestUrl?.queryParameter("per_page"))
            assertEquals("BestiaPop-Test", getHeader("User-Agent"))
            assertEquals("application/vnd.github+json", getHeader("Accept"))
        }
    }

    @Test
    fun fetchReleases_404_returnsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))

        val result = client().fetchReleases()

        assertTrue(result.isFailure)
        assertEquals("GitHub HTTP 404", result.exceptionOrNull()?.message)
    }

    @Test
    fun fetchReleases_500_returnsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"Server error"}"""))

        val result = client().fetchReleases()

        assertTrue(result.isFailure)
        assertEquals("GitHub HTTP 500", result.exceptionOrNull()?.message)
    }

    @Test
    fun fetchReleases_timeout_returnsFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = client(callTimeoutMs = 150).fetchReleases()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.InterruptedIOException)
    }

    private fun client(callTimeoutMs: Long = 1_000): GitHubUpdateClient {
        val localHttp = OkHttpClient.Builder()
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val localUrl = server.url(original.url.encodedPath)
                    .newBuilder()
                    .encodedQuery(original.url.encodedQuery)
                    .build()
                chain.proceed(original.newBuilder().url(localUrl).build())
            }
            .build()
        return GitHubUpdateClient(
            repository = "fixture-owner/fixture-repo",
            userAgent = "BestiaPop-Test",
            http = localHttp
        )
    }

    private companion object {
        val RELEASES_FIXTURE = """
            [{
              "tag_name": "v2.4.0",
              "body": "versionCode: 42\n\nFixture changes",
              "draft": false,
              "prerelease": false,
              "assets": [{
                "name": "BestiaPop-2.4.0.apk",
                "browser_download_url": "https://downloads.invalid/BestiaPop-2.4.0.apk"
              }]
            }]
        """
    }
}
