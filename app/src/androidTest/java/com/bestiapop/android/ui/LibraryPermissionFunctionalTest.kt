package com.bestiapop.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.testutil.DeviceAwakeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real granted-permission boundary for first-install import.
 *
 * Revoking a dangerous permission kills the instrumented target process on current Android. A
 * denied-first-launch Activity test would therefore need a separate host invocation; it is
 * intentionally not faked here. [com.bestiapop.android.data.repository.SafImportFunctionalTest]
 * still validates that the independent tree-grant path does not use MediaStore.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class LibraryPermissionFunctionalTest {
    @get:Rule
    val deviceAwakeRule = DeviceAwakeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = LibraryPreferencesRepository(context)
    private val database = AppDatabase.getDatabase(context)
    private var originalInitialScanCompleted = true

    @Before
    fun prepareUnscannedLibraryWithPermission() = runBlocking {
        originalInitialScanCompleted = preferences.isInitialScanCompleted()
        withContext(Dispatchers.IO) {
            database.clearAllTables()
            preferences.setInitialScanCompleted(false)
        }
        listOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        ).forEach { permission ->
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
        }
    }

    @After
    fun restoreLibraryState() = runBlocking {
        withContext(Dispatchers.IO) {
            database.clearAllTables()
            preferences.setInitialScanCompleted(originalInitialScanCompleted)
        }
    }

    @Test
    fun grantedAudioPermission_firstLaunchCompletesInitialImportOnce() {
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            runBlocking {
                withTimeout(15_000L) {
                    while (!preferences.isInitialScanCompleted()) {
                        delay(25L)
                    }
                }
            }
            assertTrue(runBlocking { preferences.isInitialScanCompleted() })
        }
    }
}
