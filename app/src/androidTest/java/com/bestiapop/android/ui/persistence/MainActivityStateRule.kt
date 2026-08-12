package com.bestiapop.android.ui.persistence

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.UiNavSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.rules.ExternalResource

/**
 * Resets only state exercised by full-app navigation tests.
 *
 * The production Application and process graph remain in use; no test application or fake graph
 * is installed.
 */
class MainActivityStateRule : ExternalResource() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val libraryPreferences = LibraryPreferencesRepository(context)

    override fun before() {
        grantStartupPermissions()
        resetState()
    }

    override fun after() {
        context.getSystemService(NotificationManager::class.java).cancelAll()
        resetState()
    }

    private fun resetState() = runBlocking {
        withContext(Dispatchers.IO) {
            AppDatabase.getDatabase(context).clearAllTables()
            libraryPreferences.setInitialScanCompleted()
            libraryPreferences.setNavSnapshot(UiNavSnapshot())
        }
    }

    private fun grantStartupPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_AUDIO)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
}
