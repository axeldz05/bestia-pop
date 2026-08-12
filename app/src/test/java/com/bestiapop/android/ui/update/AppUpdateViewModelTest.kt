package com.bestiapop.android.ui.update

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.update.AppRelease
import com.bestiapop.android.testutil.MediumTest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class AppUpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun maybeCheckOnLaunch_withinThrottle_usesCacheWithoutNetwork() = runTest(dispatcher) {
        val now = AppUpdateViewModel.CHECK_INTERVAL_MS * 4
        val store = FakeStore(
            lastCheck = now - AppUpdateViewModel.CHECK_INTERVAL_MS + 1,
            cachedVersion = CURRENT_VERSION_NAME,
            cachedBody = "Notas cacheadas"
        )
        var fetchCount = 0
        val viewModel = viewModel(
            store = store,
            clockMs = now,
            gateway = AppUpdateGateway {
                fetchCount++
                Result.success(emptyList())
            }
        )

        viewModel.maybeCheckOnLaunch()
        advanceUntilIdle()

        assertEquals(0, fetchCount)
        assertEquals("Notas cacheadas", viewModel.notes.value.currentNotes)
        assertFalse(viewModel.notes.value.checked)
        assertTrue(viewModel.state.value is AppUpdateUiState.Idle)
    }

    @Test
    fun maybeCheckOnLaunch_newerRelease_setsAvailableAndCachesCurrentNotes() = runTest(dispatcher) {
        val now = AppUpdateViewModel.CHECK_INTERVAL_MS * 4
        val store = FakeStore()
        val newer = release(11, "1.1", notes = "Nueva versión")
        val current = release(10, CURRENT_VERSION_NAME, notes = "Versión instalada")
        val viewModel = viewModel(
            store = store,
            clockMs = now,
            gateway = AppUpdateGateway { Result.success(listOf(newer, current)) }
        )

        viewModel.maybeCheckOnLaunch()
        advanceUntilIdle()

        assertEquals(newer, (viewModel.state.value as AppUpdateUiState.Available).release)
        assertEquals(listOf(newer), viewModel.notes.value.newer)
        assertEquals("Versión instalada", viewModel.notes.value.currentNotes)
        assertEquals(now, store.lastCheck)
        assertEquals("Versión instalada", store.cachedBody)
    }

    @Test
    fun refreshOffline_keepsCachedNotesAndReportsNetworkError() = runTest(dispatcher) {
        val store = FakeStore(
            cachedVersion = CURRENT_VERSION_NAME,
            cachedBody = "Disponibles sin conexión"
        )
        val viewModel = viewModel(
            store = store,
            gateway = AppUpdateGateway {
                Result.failure(IllegalStateException("Sin red"))
            }
        )

        viewModel.refreshReleases(force = true)
        advanceUntilIdle()

        assertEquals("Disponibles sin conexión", viewModel.notes.value.currentNotes)
        assertEquals("Sin red", viewModel.notes.value.error)
        assertFalse(viewModel.notes.value.loading)
        assertTrue(viewModel.state.value is AppUpdateUiState.Idle)
    }

    @Test
    fun failedDownload_neverTransitionsToReadyToInstall() = runTest(dispatcher) {
        val installer = FakeInstaller(
            downloadResult = Result.failure(IllegalStateException("APK truncado"))
        )
        val viewModel = viewModel(installer = installer)

        viewModel.startUpdate(release(11, "1.1", notes = "Nueva versión"))
        advanceUntilIdle()

        assertEquals("APK truncado", (viewModel.state.value as AppUpdateUiState.Error).message)
        assertFalse(viewModel.state.value is AppUpdateUiState.ReadyToInstall)
        assertEquals(1, installer.downloadCount)
    }

    private fun viewModel(
        store: FakeStore = FakeStore(),
        clockMs: Long = AppUpdateViewModel.CHECK_INTERVAL_MS * 4,
        gateway: AppUpdateGateway = AppUpdateGateway { Result.success(emptyList()) },
        installer: AppUpdateInstallerBoundary = FakeInstaller()
    ): AppUpdateViewModel = AppUpdateViewModel(
        app,
        AppUpdateDependencies(
            repository = "owner/repository",
            gateway = gateway,
            store = store,
            clock = AppUpdateClock { clockMs },
            isDebugBuild = false,
            installer = installer,
            currentVersionCode = CURRENT_VERSION_CODE,
            currentVersionName = CURRENT_VERSION_NAME,
            userAgent = "BestiaPop-Test"
        )
    )

    private fun release(
        versionCode: Int,
        versionName: String,
        notes: String
    ) = AppRelease(
        versionCode = versionCode,
        versionName = versionName,
        tag = "v$versionName",
        notes = notes,
        htmlUrl = "https://example.invalid/releases/$versionName",
        publishedAtMs = null,
        apkUrl = "https://example.invalid/BestiaPop-$versionName.apk"
    )

    private class FakeStore(
        var lastCheck: Long = 0L,
        var cachedVersion: String? = null,
        var cachedBody: String? = null
    ) : AppUpdateStore {
        override suspend fun lastCheckAtMs(): Long = lastCheck

        override suspend fun setLastCheckAtMs(epochMs: Long) {
            lastCheck = epochMs
        }

        override suspend fun cachedNotes(versionName: String): String? =
            cachedBody.takeIf { cachedVersion == versionName }

        override suspend fun setCachedNotes(versionName: String, notes: String) {
            cachedVersion = versionName
            cachedBody = notes
        }
    }

    private class FakeInstaller(
        private val downloadResult: Result<File> = Result.success(File("update.apk"))
    ) : AppUpdateInstallerBoundary {
        var downloadCount = 0

        override fun canInstallPackages(context: Context): Boolean = true

        override fun updateFile(context: Context): File =
            File(context.cacheDir, "view-model-update.apk")

        override suspend fun download(
            context: Context,
            url: String,
            dest: File,
            userAgent: String,
            onProgress: (Float?) -> Unit
        ): Result<File> {
            downloadCount++
            return downloadResult
        }

        override fun unknownSourcesIntent(context: Context): Intent = Intent()

        override fun launchInstaller(context: Context, apk: File): Result<Unit> =
            Result.success(Unit)
    }

    private companion object {
        const val CURRENT_VERSION_CODE = 10
        const val CURRENT_VERSION_NAME = "1.0"
    }
}
