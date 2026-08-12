package com.bestiapop.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
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
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.ui.components.RemoteTrackPlaceholderRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class PlaylistRemoteRowFunctionalTest {

    private val composeTestRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(composeTestRule)

    @Test
    fun unmatchedRemote_downloadButton() {
        var downloaded = false
        composeTestRule.setContent {
            RemoteTrackPlaceholderRow(
                title = "Remote Hit",
                artist = "Artist B",
                badge = "Online",
                leadingIcon = Icons.Default.Cloud,
                highlighted = false,
                onClick = {},
                onDownload = { downloaded = true }
            )
        }
        composeTestRule.onNodeWithText("Remote Hit").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Descargar").performClick()
        assertTrue(downloaded)
    }

    @Test
    fun queuedRemote_cancelDownload() {
        var cancelled = false
        composeTestRule.setContent {
            RemoteTrackPlaceholderRow(
                title = "Remote Hit",
                artist = "Artist B",
                badge = "Online",
                leadingIcon = Icons.Default.Cloud,
                highlighted = false,
                download = ActiveDownload(
                    id = "artist b|remote hit",
                    source = ActiveDownloadSource.DISCOVER,
                    candidates = listOf(
                        OnlineCatalogTrack(
                            identity = TrackIdentity(title = "Remote Hit", artist = "Artist B"),
                            id = "r1"
                        )
                    ),
                    state = CandidateDownloadState.QUEUED
                ),
                onCancelDownload = { cancelled = true }
            )
        }
        composeTestRule.onNodeWithContentDescription("Cancelar descarga").performClick()
        assertTrue(cancelled)
    }
}
