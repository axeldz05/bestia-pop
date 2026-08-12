package com.bestiapop.android.ui.persistence

import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seeds only the version marker that survives Room's destructive downgrade and observes the actual
 * Android Toast accessibility event. No historical APK or destructive database migration is needed.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DatabaseDowngradeWarningFunctionalTest {

    @Test
    fun higherSchemaMarker_onFreshActivity_showsDataLossWarningToast() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = LibraryPreferencesRepository(context)
        val previousHighest = runBlocking { preferences.highestDbVersionSeen() }
        val previousInitialScan = runBlocking { preferences.isInitialScanCompleted() }
        val warningObserved = CountDownLatch(1)
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            runBlocking {
                preferences.setHighestDbVersionSeen(AppDatabase.VERSION + 1)
                // Keep this test focused and prevent an unrelated first-run scan/toast.
                preferences.setInitialScanCompleted(true)
            }
            instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
                if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                    event.text.joinToString(separator = " ").contains(WARNING_PREFIX)
                ) {
                    warningObserved.countDown()
                }
            }

            scenario = ActivityScenario.launch(MainActivity::class.java)

            assertTrue(
                "Did not observe the real downgrade warning Toast",
                warningObserved.await(TOAST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            )
        } finally {
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
            runCatching { scenario?.close() }
            runBlocking {
                preferences.setHighestDbVersionSeen(previousHighest)
                preferences.setInitialScanCompleted(previousInitialScan)
            }
        }
    }

    private companion object {
        const val WARNING_PREFIX = "Instalaste una versión más vieja de BestiaPop"
        const val TOAST_TIMEOUT_SECONDS = 10L
    }
}
