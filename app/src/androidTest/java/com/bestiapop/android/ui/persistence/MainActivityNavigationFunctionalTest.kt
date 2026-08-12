package com.bestiapop.android.ui.persistence

import android.os.SystemClock
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoActivityResumedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.NAV_WIFI
import com.bestiapop.android.testutil.DeviceAwakeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityNavigationFunctionalTest {
    private val activityRule = createAndroidComposeRule<MainActivity>()
    private val cleanStateRule = MainActivityStateRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(cleanStateRule)
        .around(activityRule)

    @Test
    fun rootBottomNavigation_switchesEveryProductionTab() {
        listOf("Playlists", "Descargas", "WiFi Sync", "Ajustes", "Biblioteca").forEach { tab ->
            activityRule
                .onNodeWithContentDescription(tab, useUnmergedTree = true)
                .performClick()
            assertTabSelected(tab)
        }
    }

    @Test
    fun settingsSubsection_systemBackAndArrowReturnToSettingsRoot() {
        activityRule
            .onNodeWithContentDescription("Ajustes", useUnmergedTree = true)
            .performClick()

        activityRule.onNodeWithText("Temas").performClick()
        activityRule
            .onNodeWithContentDescription("Volver", useUnmergedTree = true)
            .fetchSemanticsNode()
        pressBack()
        assertBackButtonAbsent()
        assertTabSelected("Ajustes")

        activityRule.onNodeWithText("Reproducción").performClick()
        activityRule
            .onNodeWithContentDescription("Volver", useUnmergedTree = true)
            .performClick()
        assertBackButtonAbsent()
        assertTabSelected("Ajustes")
    }

    @Test
    fun rootDoubleBack_warnsThenFinishesActivity() {
        lateinit var activity: MainActivity
        activityRule.activityRule.scenario.onActivity { activity = it }

        pressBack()
        activityRule.onNodeWithTag("root-exit-confirmation").fetchSemanticsNode()
        check(!activity.isFinishing)

        try {
            pressBack()
        } catch (_: NoActivityResumedException) {
            // Expected: the production BackHandler finishes the only resumed activity.
        }
        waitForActivityDestroyed(activity)
        activityRule.activityRule.scenario.close()
    }

    @Test
    fun selectedTab_isPersistedAcrossActivityRecreate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = LibraryPreferencesRepository(context)

        activityRule
            .onNodeWithContentDescription("WiFi Sync", useUnmergedTree = true)
            .performClick()
        activityRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { preferences.navSnapshotFlow.first().navIndex == NAV_WIFI }
        }

        activityRule.activityRule.scenario.recreate()

        assertTabSelected("WiFi Sync")
    }

    private fun assertTabSelected(label: String) {
        selectedTabNodes(label)
            .let { check(it.size == 1) { "Expected selected root tab: $label" } }
    }

    private fun selectedTabNodes(label: String) = activityRule
        .onAllNodes(
            isSelected() and hasAnyDescendant(hasContentDescription(label)),
            useUnmergedTree = true
        )
        .fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun assertBackButtonAbsent() {
        val backButtons = activityRule
            .onAllNodes(
                hasContentDescription("Volver"),
                useUnmergedTree = true
            )
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        check(backButtons.isEmpty()) { "Settings subsection remained open after Back" }
    }

    private fun waitForActivityDestroyed(activity: MainActivity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (!activity.isDestroyed && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50L)
        }
        check(activity.isDestroyed) { "MainActivity did not finish after the second root Back" }
    }

}
