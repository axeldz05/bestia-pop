package com.bestiapop.android.ui.identify

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
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
class IdentifyE2EFunctionalTest {
    private val composeRule = createEmptyComposeRule()
    private val fixture = IdentifyE2ETestFixture()
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
    fun identify_autoAppliesHighAndPersistsReviewedConflictAcrossFreshActivity() {
        ui.await("production library search action") {
            ui.exists(hasContentDescription("Buscar"))
        }

        composeRule.onNodeWithContentDescription("Buscar").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(IdentifyE2ETestContract.SEARCH_FILTER)
        closeSoftKeyboard()
        ui.await("fixture-only library filter") {
            ui.exists(hasText(IdentifyE2ETestContract.HIGH_SOURCE_TITLE)) &&
                ui.exists(hasText(IdentifyE2ETestContract.MEDIUM_SOURCE_TITLE)) &&
                ui.exists(
                    hasTestTag(IdentifyE2ETestContract.songOptionsTag(fixture.highSongId))
                ) &&
                ui.exists(
                    hasTestTag(IdentifyE2ETestContract.songOptionsTag(fixture.mediumSongId))
                )
        }

        identifyThroughSongOverflow(fixture.highSongId)
        ui.await("HIGH candidate auto-applied in Room with local duration") {
            fixture.highIdentityWasAutoApplied()
        }
        composeRule.onNodeWithTag("identify-review").assertDoesNotExist()

        identifyThroughSongOverflow(fixture.mediumSongId)
        ui.await("MEDIUM conflict review with one pending item") {
            ui.exists(hasTestTag("identify-review")) &&
                ui.exists(hasText("Revisar identidad")) &&
                ui.exists(hasText("Revisar 1 de 1")) &&
                ui.exists(hasText(IdentifyE2ETestContract.MEDIUM_CANDIDATE_TITLE))
        }
        composeRule.onNodeWithTag("identify-review").assertIsDisplayed()
        composeRule.onNodeWithText("Revisar 1 de 1").assertIsDisplayed()

        composeRule.onNodeWithTag("identify-review-close").performClick()
        ui.await("dismissed review persisted and exposed by pending banner") {
            !ui.exists(hasTestTag("identify-review")) &&
                ui.exists(hasText("1 por revisar")) &&
                fixture.mediumReviewIsPersisted()
        }

        fixture.relaunchMainActivity()
        ui.await("persisted review hydrated by a fresh Activity and ViewModel") {
            !ui.exists(hasTestTag("identify-review")) &&
                ui.exists(hasText("1 por revisar")) &&
                fixture.mediumReviewIsPersisted()
        }
        composeRule.onNodeWithTag("identify-review").assertDoesNotExist()
        composeRule.onNodeWithText("1 por revisar").performClick()

        ui.await("restored candidate review") {
            ui.exists(hasTestTag("identify-review")) &&
                ui.exists(hasText(IdentifyE2ETestContract.MEDIUM_CANDIDATE_TITLE)) &&
                ui.exists(hasText("Usar este"))
        }
        composeRule
            .onNodeWithText(IdentifyE2ETestContract.MEDIUM_CANDIDATE_TITLE)
            .performClick()
        composeRule.onNodeWithText("Usar este").performClick()

        ui.await("selected candidate persisted and review queue cleared") {
            fixture.mediumIdentityWasApplied() &&
                fixture.reviewQueueIsEmpty() &&
                !ui.exists(hasTestTag("identify-review")) &&
                !ui.exists(hasText("1 por revisar"))
        }
        fixture.assertPersistedIdentities()
    }

    @Test
    fun identifyContinuesAfterActivityAndViewModelAreDestroyed() {
        fixture.configureGatedSearch()
        fixture.startIdentifyFromViewModel(fixture.highSongId)
        fixture.awaitSearchRequest()
        fixture.destroyMainActivity()
        fixture.releaseSearch()
        ui.await("HIGH identity auto-applied without a ViewModel") {
            fixture.highIdentityWasAutoApplied()
        }
    }

    private fun identifyThroughSongOverflow(songId: Long) {
        composeRule
            .onNodeWithTag(
                IdentifyE2ETestContract.songOptionsTag(songId),
                useUnmergedTree = true
            )
            .performClick()
        composeRule.onNodeWithText("Identificar…").performClick()
    }

    private companion object {
        const val UI_TIMEOUT_MS = 15_000L
    }
}
