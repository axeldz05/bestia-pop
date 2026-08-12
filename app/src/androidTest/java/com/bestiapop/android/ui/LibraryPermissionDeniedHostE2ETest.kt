package com.bestiapop.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.persistence.HostOrchestratedProcessDeathTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Denied-permission half of [LibraryPermissionFunctionalTest].
 *
 * Revoking READ_MEDIA_AUDIO kills the target process, so the host script must revoke it before this
 * instrumentation starts. Keeping this class host-only prevents order-dependent permission state in
 * the regular connected suite.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
@HostOrchestratedProcessDeathTest
class LibraryPermissionDeniedHostE2ETest {

    @Test
    @HostOrchestratedProcessDeathTest
    fun deniedAudioPermission_firstLaunchKeepsImportPendingAndActivityUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = LibraryPreferencesRepository(context)
        val database = AppDatabase.getDatabase(context)

        assertEquals(
            "Host must revoke READ_MEDIA_AUDIO before instrumentation starts",
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
        )
        runBlocking {
            database.clearAllTables()
            preferences.setInitialScanCompleted(false)
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val deny = awaitPermissionDenyButton()
            assertTrue("Permission controller denied click", deny.performAction(AccessibilityNodeInfo.ACTION_CLICK))

            await("permission denial keeps the Activity resumed") {
                var resumed = false
                scenario.onActivity { resumed = it.lifecycle.currentState == Lifecycle.State.RESUMED }
                resumed
            }
            assertFalse(runBlocking { preferences.isInitialScanCompleted() })
            assertTrue(runBlocking { database.musicDao().getAllSongs().isEmpty() })
        }
    }

    private fun awaitPermissionDenyButton(): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            findDenyButton(automation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Runtime audio permission dialog did not expose its deny button")
    }

    private fun findDenyButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        val viewId = node.viewIdResourceName.orEmpty()
        if (viewId.endsWith(":id/permission_deny_button") && node.isClickable) return node
        for (index in 0 until node.childCount) {
            findDenyButton(node.getChild(index))?.let { return it }
        }
        return null
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private companion object {
        const val UI_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
