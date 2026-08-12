package com.bestiapop.android.ui.playlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.bestiapop.android.testutil.ComposeE2EProbe
import com.bestiapop.android.testutil.DeviceAwakeRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class PlaylistPendingDownloadE2ETest {
    private val composeRule = createEmptyComposeRule()
    private val fixture = PlaylistPendingDownloadTestFixture()
    private val ui = ComposeE2EProbe(composeRule, UI_TIMEOUT_MS, fixture::diagnostic)

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(composeRule)

    @Before
    fun setUp() {
        fixture.prepare()
        fixture.launchMainActivity()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun pendingRemote_downloadsIntoRoomAndBecomesLocalPlaylistMember() {
        ui.await("pending Remote in local playlist detail") {
            ui.exists(hasText(fixture.playlistName)) &&
                ui.exists(hasText("0 descargadas · 1 pendientes")) &&
                ui.exists(hasText("Pendiente de descarga")) &&
                ui.exists(hasText("Descargar 1 pendientes"))
        }
        composeRule.onNodeWithText("Pendiente de descarga").assertIsDisplayed()
        composeRule.onNodeWithText("Descargar 1 pendientes").performClick()

        ui.await("pending Remote converted to a local Song and playlist member") {
            fixture.conversionComplete() &&
                ui.exists(hasText("1 canciones")) &&
                ui.exists(hasText(fixture.title)) &&
                !ui.exists(hasText("Pendiente de descarga")) &&
                !ui.exists(hasText("Descargar 1 pendientes"))
        }

        fixture.verifyPersistedConversion()
    }

    private companion object {
        const val UI_TIMEOUT_MS = 20_000L
    }
}
