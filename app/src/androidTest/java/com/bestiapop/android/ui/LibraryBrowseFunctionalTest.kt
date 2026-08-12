package com.bestiapop.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.ui.screens.library.LibraryFilterChipRow
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class LibraryBrowseFunctionalTest {

    private val composeTestRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(composeTestRule)

    @Test
    fun filterChips_singleSelection_projectsBrowseFilter() {
        var selected = LibraryBrowseFilter.SONGS
        composeTestRule.setContent {
            var current by remember { mutableStateOf(LibraryBrowseFilter.SONGS) }
            LibraryFilterChipRow(
                selected = current,
                onSelect = {
                    current = it
                    selected = it
                }
            )
        }

        composeTestRule.onNodeWithText("Canciones").assertIsDisplayed()
        composeTestRule.onNodeWithText("Álbumes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recientes").assertIsDisplayed()

        composeTestRule.onNodeWithText("Álbumes").performClick()
        assertEquals(LibraryBrowseFilter.ALBUMS, selected)
        composeTestRule.onNodeWithText("Álbumes").assertIsSelected()

        composeTestRule.onNodeWithText("Recientes").performClick()
        assertEquals(LibraryBrowseFilter.RECENT, selected)
        composeTestRule.onNodeWithText("Recientes").assertIsSelected()
    }
}
