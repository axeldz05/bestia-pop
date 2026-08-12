package com.bestiapop.android.ui.update

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BuildConfig
import com.bestiapop.android.data.update.ApkUpdateDownloader
import com.bestiapop.android.data.update.ApkValidator
import com.bestiapop.android.data.update.GitHubUpdateClient
import com.bestiapop.android.testutil.MockWebServerRule
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AppUpdateFunctionalTest {
    @get:Rule
    val server = MockWebServerRule()

    @get:Rule
    val compose = createComposeRule()

    private val app: Application
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as Application
    private lateinit var directory: File

    @Before
    fun createDownloadDirectory() {
        directory = File(app.cacheDir, "update-ui-${System.nanoTime()}")
        check(directory.mkdirs())
    }

    @After
    fun removeDownloadDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun githubAvailable_validDownload_launchesInstallerBoundary() {
        val apkBytes = "controlled-functional-apk".toByteArray()
        enqueueRelease()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setBody(okio.Buffer().write(apkBytes))
        )
        val installer = RecordingInstaller(
            destination = File(directory, "valid.apk"),
            expectedApk = apkBytes
        )
        val viewModel = createViewModel(installer)
        showUpdateUi(viewModel)

        compose.onNodeWithText("Actualizar a $UPDATE_VERSION")
            .performScrollTo()
            .performClick()
        compose.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MS) {
            installer.launchCount.get() == 1
        }

        assertEquals(1, installer.launchCount.get())
        assertTrue(installer.destination.exists())
        assertTrue(viewModel.state.value is AppUpdateUiState.Idle)
        assertEquals("/repos/owner/repository/releases", server.takeRequest().path?.substringBefore('?'))
        assertEquals("/fixture.apk", server.takeRequest().path)
    }

    @Test
    fun githubAvailable_truncatedDownload_showsErrorAndNeverLaunchesInstaller() {
        enqueueRelease()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("short")
                .setHeader("Content-Length", 50)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        )
        val installer = RecordingInstaller(
            destination = File(directory, "truncated.apk"),
            expectedApk = "unused".toByteArray()
        )
        val viewModel = createViewModel(installer)
        showUpdateUi(viewModel)

        compose.onNodeWithText("Actualizar a $UPDATE_VERSION")
            .performScrollTo()
            .performClick()
        compose.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MS) {
            viewModel.state.value is AppUpdateUiState.Error
        }

        compose.onNodeWithText("Actualización").assertIsDisplayed()
        assertEquals(0, installer.launchCount.get())
        assertFalse(installer.destination.exists())
        assertFalse(File(directory, "${installer.destination.name}.part").exists())
    }

    private fun showUpdateUi(viewModel: AppUpdateViewModel) {
        compose.setContent {
            val state by viewModel.state.collectAsState()
            val readyApk = (state as? AppUpdateUiState.ReadyToInstall)?.apkFile
            MaterialTheme {
                Box {
                    AppUpdateScreen(viewModel)
                    AppUpdateDialogs(
                        state = state,
                        onConfirmUpdate = viewModel::confirmUpdate,
                        onDismiss = viewModel::dismiss
                    )
                }
            }
            LaunchedEffect(readyApk) {
                readyApk?.let(viewModel::launchInstaller)
            }
        }
        compose.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MS) {
            compose.onAllNodesWithText("Actualizar a $UPDATE_VERSION")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        compose.onNodeWithText("Actualizar a $UPDATE_VERSION")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun createViewModel(installer: RecordingInstaller): AppUpdateViewModel {
        val apiHttp = OkHttpClient.Builder()
            .callTimeout(2, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val localUrl = server.url(original.url.encodedPath)
                    .newBuilder()
                    .encodedQuery(original.url.encodedQuery)
                    .build()
                chain.proceed(original.newBuilder().url(localUrl).build())
            }
            .build()
        val client = GitHubUpdateClient(
            repository = "owner/repository",
            userAgent = "BestiaPop-Functional-Test",
            http = apiHttp
        )
        return AppUpdateViewModel(
            app,
            AppUpdateDependencies(
                repository = "owner/repository",
                gateway = AppUpdateGateway(client::fetchReleases),
                store = MemoryStore(),
                clock = AppUpdateClock { 1_800_000_000_000L },
                isDebugBuild = true,
                installer = installer,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
                userAgent = "BestiaPop-Functional-Test"
            )
        )
    }

    private fun enqueueRelease() {
        val updateCode = BuildConfig.VERSION_CODE + 1
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [{
                      "tag_name": "v$UPDATE_VERSION",
                      "body": "versionCode: $updateCode\n\nActualización funcional",
                      "draft": false,
                      "prerelease": false,
                      "assets": [{
                        "name": "BestiaPop-$UPDATE_VERSION.apk",
                        "browser_download_url": "${server.url("/fixture.apk")}"
                      }]
                    }]
                    """.trimIndent()
                )
        )
    }

    private inner class RecordingInstaller(
        val destination: File,
        expectedApk: ByteArray
    ) : AppUpdateInstallerBoundary {
        val launchCount = AtomicInteger()
        private val http = OkHttpClient.Builder()
            .callTimeout(3, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        private val downloader = ApkUpdateDownloader(
            http = http,
            validator = ApkValidator { apk ->
                runCatching {
                    check(apk.readBytes().contentEquals(expectedApk)) {
                        "El APK funcional no coincide con el fixture"
                    }
                }
            }
        )

        override fun canInstallPackages(context: Context): Boolean = true

        override fun updateFile(context: Context): File = destination

        override suspend fun download(
            context: Context,
            url: String,
            dest: File,
            userAgent: String,
            onProgress: (Float?) -> Unit
        ): Result<File> = downloader.download(url, dest, userAgent, onProgress)

        override fun unknownSourcesIntent(context: Context): Intent = Intent()

        override fun launchInstaller(context: Context, apk: File): Result<Unit> = runCatching {
            check(apk == destination && apk.exists())
            launchCount.incrementAndGet()
        }
    }

    private class MemoryStore : AppUpdateStore {
        private var checkedAt = 0L
        private var notesVersion: String? = null
        private var notes: String? = null

        override suspend fun lastCheckAtMs(): Long = checkedAt

        override suspend fun setLastCheckAtMs(epochMs: Long) {
            checkedAt = epochMs
        }

        override suspend fun cachedNotes(versionName: String): String? =
            notes.takeIf { notesVersion == versionName }

        override suspend fun setCachedNotes(versionName: String, notes: String) {
            notesVersion = versionName
            this.notes = notes
        }
    }

    private companion object {
        const val UPDATE_VERSION = "99.0-functional"
        const val ASYNC_TIMEOUT_MS = 10_000L
    }
}
