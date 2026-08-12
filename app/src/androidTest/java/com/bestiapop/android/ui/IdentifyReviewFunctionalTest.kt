package com.bestiapop.android.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.ui.screens.IdentifyCandidateRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class IdentifyReviewFunctionalTest {

    private val composeTestRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(composeTestRule)

    private val candidate = IdentifyCandidate(
        track = OnlineCatalogTrack(
            identity = TrackIdentity(
                title = "Catalog match",
                artist = "Matched artist",
                album = "Matched album",
                durationMs = 181_000L
            ),
            id = "catalog-1",
            provider = "Deezer"
        ),
        score = 0.82f,
        reasons = listOf("Artista y título coinciden")
    )

    @Test
    fun candidatePreview_isIndependentFromSelectingCandidate() {
        var selections = 0
        var previews = 0

        composeTestRule.setContent {
            IdentifyCandidateRow(
                candidate = candidate,
                fileDurationMs = 180_000L,
                selected = false,
                isPlaying = false,
                isResolving = false,
                onClick = { selections++ },
                onPreview = { previews++ }
            )
        }

        composeTestRule.onNodeWithText("Catalog match").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Preview")
            .assertHasClickAction()
            .performClick()

        assertEquals(0, selections)
        assertEquals(1, previews)

        composeTestRule.onNodeWithText("Catalog match").performClick()
        assertEquals(1, selections)
        assertEquals(1, previews)
    }

    @Test
    fun playingCandidate_exposesPausePreviewAction() {
        var previews = 0

        composeTestRule.setContent {
            IdentifyCandidateRow(
                candidate = candidate,
                fileDurationMs = 180_000L,
                selected = true,
                isPlaying = true,
                isResolving = false,
                onClick = {},
                onPreview = { previews++ }
            )
        }

        composeTestRule
            .onNodeWithContentDescription("Pausar preview")
            .assertHasClickAction()
            .performClick()

        assertEquals(1, previews)
    }
}
