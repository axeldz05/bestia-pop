package com.bestiapop.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.ui.components.ActiveDownloadRow
import com.bestiapop.android.ui.components.DownloadStateTrailing
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class DownloadsUiFunctionalTest {

    private val composeTestRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(composeTestRule)

    private fun catalogTrack() = OnlineCatalogTrack(
        identity = TrackIdentity(title = "Night Drive", artist = "Nova", album = "EP"),
        id = "c1",
        provider = "Deezer"
    )

    private fun download(state: CandidateDownloadState, percent: Int = 0) = ActiveDownload(
        id = "nova|night drive",
        source = ActiveDownloadSource.CATALOG,
        candidates = listOf(catalogTrack()),
        state = state,
        progressPercent = percent,
        errorMessage = if (state == CandidateDownloadState.ERROR) "Interrumpida" else null
    )

    @Test
    fun queued_showCancelAction() {
        var dismissed = false
        composeTestRule.setContent {
            DownloadStateTrailing(
                state = CandidateDownloadState.QUEUED,
                onDismiss = { dismissed = true }
            )
        }
        composeTestRule.onNodeWithContentDescription("Cancelar descarga").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun downloading_showsPercentAndCancel() {
        var dismissed = false
        composeTestRule.setContent {
            DownloadStateTrailing(
                state = CandidateDownloadState.DOWNLOADING,
                percent = 42,
                onDismiss = { dismissed = true }
            )
        }
        composeTestRule.onNodeWithText("42%").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Cancelar descarga").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun errorRow_retryAndDismiss() {
        var retried = false
        var dismissed = false
        composeTestRule.setContent {
            ActiveDownloadRow(
                download = download(CandidateDownloadState.ERROR),
                isPreviewPlaying = false,
                isPreviewResolving = false,
                onPreview = {},
                onPlay = {},
                onRetry = { retried = true },
                onCycle = {},
                onDismiss = { dismissed = true }
            )
        }
        composeTestRule.onNodeWithText("Night Drive").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reintentar").performClick()
        assertTrue(retried)
        composeTestRule.onNodeWithContentDescription("Descartar").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun successTrailing_showsInLibraryLabel() {
        composeTestRule.setContent {
            DownloadStateTrailing(
                state = CandidateDownloadState.SUCCESS,
                successLabel = DownloadMessages.inLibrary
            )
        }
        composeTestRule.onNodeWithText(DownloadMessages.inLibrary).assertIsDisplayed()
    }

}
