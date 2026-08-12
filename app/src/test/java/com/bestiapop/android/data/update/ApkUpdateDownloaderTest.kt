package com.bestiapop.android.data.update

import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.MockWebServerRule
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(MediumTest::class)
class ApkUpdateDownloaderTest {
    @get:Rule
    val server = MockWebServerRule()

    private lateinit var directory: File
    private lateinit var destination: File

    @Before
    fun createDestination() {
        directory = createTempDirectory("apk-update-").toFile()
        destination = File(directory, "BestiaPop-update.apk")
    }

    @After
    fun removeDestination() {
        directory.deleteRecursively()
    }

    @Test
    fun http500_failsAndRemovesPartAndFinalFiles() = runBlocking {
        destination.writeText("stale")
        partFile().writeText("stale-part")
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))

        val result = downloader().download(
            server.url("/update.apk").toString(),
            destination,
            TEST_USER_AGENT
        ) {}

        assertTrue(result.isFailure)
        assertEquals("Descarga APK HTTP 500", result.exceptionOrNull()?.message)
        assertNoPublishedFiles()
    }

    @Test
    fun truncatedKnownLength_failsAndRemovesPartAndFinalFiles() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("abc")
                .setHeader("Content-Length", 12)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        )

        val result = downloader().download(
            server.url("/truncated.apk").toString(),
            destination,
            TEST_USER_AGENT
        ) {}

        assertTrue(result.isFailure)
        assertNoPublishedFiles()
    }

    @Test
    fun validBody_isValidatedAndAtomicallyPublished() = runBlocking {
        val apkBytes = "controlled-valid-apk".toByteArray()
        var validatedBytes: ByteArray? = null
        var finalProgress: Float? = null
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setBody(okio.Buffer().write(apkBytes))
        )

        val result = downloader(
            validator = ApkValidator { apk ->
                runCatching {
                    validatedBytes = apk.readBytes()
                    check(apk.name.endsWith(".part"))
                }
            }
        ).download(
            server.url("/valid.apk").toString(),
            destination,
            TEST_USER_AGENT
        ) { finalProgress = it }

        assertTrue(result.isSuccess)
        assertEquals(destination, result.getOrThrow())
        assertArrayEquals(apkBytes, validatedBytes)
        assertArrayEquals(apkBytes, destination.readBytes())
        assertFalse(partFile().exists())
        assertEquals(1f, finalProgress ?: -1f, 0.001f)
        assertEquals(TEST_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
    }

    private fun downloader(
        validator: ApkValidator = ApkValidator { Result.success(Unit) }
    ): ApkUpdateDownloader = ApkUpdateDownloader(
        http = OkHttpClient.Builder()
            .callTimeout(2, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build(),
        validator = validator
    )

    private fun partFile(): File = File(directory, "${destination.name}.part")

    private fun assertNoPublishedFiles() {
        assertFalse(partFile().exists())
        assertFalse(destination.exists())
    }

    private companion object {
        const val TEST_USER_AGENT = "BestiaPop-Update-Test"
    }
}
