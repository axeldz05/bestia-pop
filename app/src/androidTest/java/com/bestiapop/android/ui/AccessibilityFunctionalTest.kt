package com.bestiapop.android.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.ui.components.BottomPlayerBar
import com.bestiapop.android.ui.components.DownloadStateTrailing
import com.bestiapop.android.ui.components.PlayShuffleIconPair
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class AccessibilityFunctionalTest {

    private val composeTestRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(composeTestRule)

    @Test
    fun miniPlayer_primaryPlaybackActionsAreNamedClickableAndWired() {
        val calls = mutableListOf<String>()
        val currentItem = PlayableItem.Local(
            song = Song(
                id = 1L,
                uriString = "content://media/current",
                title = "Current track",
                artist = "Current artist",
                durationMs = 200_000L
            ),
            queueEntryId = "current-slot"
        )

        composeTestRule.setContent {
            BottomPlayerBar(
                currentItem = currentItem,
                isPlaying = false,
                progressMs = 25_000L,
                onPlayPauseClick = { calls += "play-pause" },
                onPreviousClick = { calls += "previous" },
                onNextClick = { calls += "next" },
                onBarClick = {}
            )
        }

        listOf("Previous", "Play/Pause", "Next").forEach { description ->
            composeTestRule
                .onNodeWithContentDescription(description)
                .assertHasClickAction()
                .performClick()
        }

        assertEquals(listOf("previous", "play-pause", "next"), calls)
    }

    @Test
    fun libraryPlaybackActions_useCallerProvidedAccessibleNames() {
        var played = false
        var shuffled = false

        composeTestRule.setContent {
            PlayShuffleIconPair(
                onPlay = { played = true },
                onShuffle = { shuffled = true },
                playDescription = "Reproducir biblioteca",
                shuffleDescription = "Mezclar biblioteca"
            )
        }

        composeTestRule
            .onNodeWithContentDescription("Reproducir biblioteca")
            .assertHasClickAction()
            .performClick()
        composeTestRule
            .onNodeWithContentDescription("Mezclar biblioteca")
            .assertHasClickAction()
            .performClick()

        assertEquals(true, played)
        assertEquals(true, shuffled)
    }

    @Test
    fun completedDownload_playAndClearActionsAreNamedClickableAndWired() {
        val calls = mutableListOf<String>()

        composeTestRule.setContent {
            DownloadStateTrailing(
                state = CandidateDownloadState.SUCCESS,
                onSuccessPlay = { calls += "play" },
                onDismiss = { calls += "clear" }
            )
        }

        composeTestRule
            .onNodeWithContentDescription("Reproducir")
            .assertHasClickAction()
            .performClick()
        composeTestRule
            .onNodeWithContentDescription("Limpiar")
            .assertHasClickAction()
            .performClick()

        assertEquals(listOf("play", "clear"), calls)
    }
}
