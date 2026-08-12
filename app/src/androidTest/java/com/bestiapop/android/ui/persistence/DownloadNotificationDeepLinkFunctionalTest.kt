package com.bestiapop.android.ui.persistence

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.service.DownloadNotificationHelper
import com.bestiapop.android.testutil.DeviceAwakeRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Uses an explicitly owned ActivityScenario because the production PendingIntent can alter the
 * target activity lifecycle. An ActivityScenarioRule would attempt to close that altered scenario
 * a second time during teardown.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DownloadNotificationDeepLinkFunctionalTest {
    private val composeRule = createEmptyComposeRule()
    private val cleanStateRule = MainActivityStateRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(cleanStateRule)
        .around(composeRule)

    @Test
    fun downloadNotificationPendingIntent_opensDownloadsTab() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val helper = DownloadNotificationHelper(context)
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java)
        )
        lateinit var launchedActivity: MainActivity
        scenario.onActivity { launchedActivity = it }

        try {
            helper.sync(listOf(activeDownload()))
            composeRule.waitUntil(timeoutMillis = 5_000) {
                notificationManager.activeNotifications.any {
                    it.id == DownloadNotificationHelper.NOTIFICATION_ID
                }
            }

            notificationManager.activeNotifications
                .first { it.id == DownloadNotificationHelper.NOTIFICATION_ID }
                .notification
                .contentIntent
                .send()

            composeRule.waitUntil(timeoutMillis = 5_000) {
                selectedDownloadsTabs().size == 1
            }
        } finally {
            helper.cancel()
            instrumentation.runOnMainSync {
                (liveMainActivities() + launchedActivity)
                    .distinct()
                    .filterNot { it.isDestroyed || it.isFinishing }
                    .forEach(MainActivity::finish)
            }
            awaitActivitiesDestroyed(launchedActivity)
        }
    }

    private fun selectedDownloadsTabs() = composeRule
        .onAllNodes(
            isSelected() and hasAnyDescendant(hasContentDescription("Descargas")),
            useUnmergedTree = true
        )
        .fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun awaitActivitiesDestroyed(launchedActivity: MainActivity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var allDestroyed = false
            instrumentation.runOnMainSync {
                allDestroyed = launchedActivity.isDestroyed && liveMainActivities().isEmpty()
            }
            if (allDestroyed) return
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50L)
        }
        error("MainActivity instances remained alive after notification deep-link")
    }

    private fun liveMainActivities(): List<MainActivity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return LIVE_STAGES
            .flatMap(monitor::getActivitiesInStage)
            .filterIsInstance<MainActivity>()
            .distinct()
    }

    private fun activeDownload() = ActiveDownload(
        id = "instrumented|download",
        source = ActiveDownloadSource.CATALOG,
        candidates = listOf(
            OnlineCatalogTrack(
                identity = TrackIdentity(
                    title = "Instrumented download",
                    artist = "BestiaPop"
                ),
                id = "instrumented-download",
                provider = "Test"
            )
        ),
        state = CandidateDownloadState.DOWNLOADING,
        progressPercent = 25
    )

    private companion object {
        val LIVE_STAGES = listOf(
            Stage.CREATED,
            Stage.STARTED,
            Stage.RESUMED,
            Stage.PAUSED,
            Stage.STOPPED,
            Stage.RESTARTED
        )
    }
}
