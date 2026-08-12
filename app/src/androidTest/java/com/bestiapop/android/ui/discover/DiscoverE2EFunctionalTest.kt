package com.bestiapop.android.ui.discover

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.bestiapop.android.data.model.ActiveDownloadSource
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
class DiscoverE2EFunctionalTest {
    private val composeRule = createEmptyComposeRule()
    private val fixture = DiscoverE2ETestFixture()
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
    fun paraTiAndCf_showMixedTracks_thenDiscoverDownloadRematchesCfInPlace() {
        ui.await("Para Ti and CF cards backed by fake ListenBrainz") {
            ui.exists(hasText(fixture.playlistTitle)) &&
                ui.exists(hasText("Recomendados para vos"))
        }

        composeRule.onNodeWithText(fixture.playlistTitle).performClick()
        awaitMixedDetail()
        composeRule.onNodeWithContentDescription("Volver").performClick()

        ui.await("Recomendados card after returning from Para Ti") {
            ui.exists(hasText("Recomendados para vos"))
        }
        composeRule.onNodeWithText("Recomendados para vos")
            .performScrollTo()
            .performClick()
        awaitMixedDetail()

        composeRule.onNodeWithContentDescription("Descargar").performClick()

        ui.await("CF remote downloaded and rematched without leaving detail") {
            fixture.remoteDownloadSucceeded() &&
                ui.exists(hasText("2 en biblioteca · 0 en stream")) &&
                !ui.exists(hasText("Stream"))
        }
        composeRule.onAllNodesWithText("2 en biblioteca · 0 en stream")
            .onFirst()
            .assertIsDisplayed()
        fixture.verifyExactlyOnePersistedRemoteAndFile(
            expectedSource = ActiveDownloadSource.DISCOVER
        )
    }

    @Test
    fun remoteStartedFromParaTi_saveWhileListeningPersistsOnceAndRematchesInPlace() {
        ui.await("Para Ti playlist from fake ListenBrainz") {
            ui.exists(hasText(fixture.playlistTitle))
        }
        composeRule.onNodeWithText(fixture.playlistTitle).performClick()
        awaitMixedDetail()

        composeRule.onNodeWithText(fixture.remoteTitle).performClick()

        ui.await("short remote WAV saved at low listening threshold") {
            fixture.saveWhileListeningSucceeded() &&
                ui.exists(hasText("2 en biblioteca · 0 en stream"))
        }
        fixture.verifyExactlyOnePersistedRemoteAndFile(
            expectedSource = ActiveDownloadSource.SAVE_WHILE_LISTENING
        )
    }

    private fun awaitMixedDetail() {
        ui.await("mixed Local and Remote detail") {
            ui.exists(hasText("1 en biblioteca · 1 en stream")) &&
                ui.exists(hasText(fixture.localTitle)) &&
                ui.exists(hasText(fixture.remoteTitle))
        }
        composeRule.onNodeWithText(fixture.localTitle).assertIsDisplayed()
        composeRule.onNodeWithText(fixture.remoteTitle).assertIsDisplayed()
    }

    private companion object {
        const val UI_TIMEOUT_MS = 25_000L
    }
}
