package com.bestiapop.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.ui.components.QueueLazyList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class QueueIdentityFunctionalTest {

    private val composeTestRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(composeTestRule)

    private val repeatedSong = Song(
        id = 7L,
        uriString = "content://media/repeated",
        title = "Repeated track",
        artist = "Same artist"
    )

    private val firstOccurrence = PlayableItem.Local(
        song = repeatedSong,
        queueEntryId = "slot-a"
    )
    private val secondOccurrence = PlayableItem.Local(
        song = repeatedSong,
        queueEntryId = "slot-b"
    )

    @Test
    fun exactDuplicate_reorderKeepsCurrentIndicatorOnQueueEntry() {
        composeTestRule.setContent {
            var items by remember {
                mutableStateOf(listOf(firstOccurrence, secondOccurrence))
            }
            val currentQueueEntryId = secondOccurrence.queueEntryId

            Column {
                Button(
                    onClick = { items = items.reversed() },
                    modifier = Modifier.testTag("reverse-queue")
                ) {
                    Text("Reverse")
                }
                QueueLazyList(
                    items = items,
                    isCurrentPlaying = { _, item ->
                        item.queueEntryId == currentQueueEntryId
                    },
                    onSkipTo = {},
                    onRemove = {},
                    modifier = Modifier.weight(1f),
                    showIndex = true
                )
            }
        }

        // The focused second slot replaces index "2" with the playing icon.
        composeTestRule.onNodeWithText("1").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("2").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription("Reproduciendo").assertIsDisplayed()

        composeTestRule.onNodeWithTag("reverse-queue").performClick()

        // After reorder, the same occurrence is first; focus did not follow the old index.
        composeTestRule.onAllNodesWithText("1").assertCountEquals(0)
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Reproduciendo").assertIsDisplayed()
    }

    @Test
    fun exactDuplicates_remainSeparatelyActionableSlots() {
        var removedIndex = -1

        composeTestRule.setContent {
            QueueLazyList(
                items = listOf(firstOccurrence, secondOccurrence),
                isCurrentPlaying = { _, _ -> false },
                onSkipTo = {},
                onRemove = { removedIndex = it },
                compact = true
            )
        }

        val removeActions = composeTestRule.onAllNodesWithContentDescription("Quitar")
        removeActions.assertCountEquals(2)
        removeActions[1].performClick()

        assertEquals(1, removedIndex)
    }
}
