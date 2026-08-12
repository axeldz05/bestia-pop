package com.bestiapop.android.ui.discover

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.ui.components.RadioModeControl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioModeControlFunctionalTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longPress_exposesKnownNewBothAndStopWithExactCallbacks() {
        val selectedModes = mutableListOf<RadioMode>()
        var active by mutableStateOf(true)
        var stopCalls = 0
        composeRule.setContent {
            MaterialTheme {
                RadioModeControl(
                    radioActive = active,
                    radioLoading = false,
                    onStartPreferred = {},
                    onStartMode = { selectedModes += it },
                    onStop = {
                        stopCalls++
                        active = false
                    }
                )
            }
        }

        selectMode("Solo conocidos")
        selectMode("Solo nuevos")
        selectMode("Ambos")

        openMenu()
        composeRule.onNodeWithText("Detener radio").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(
            listOf(RadioMode.KNOWN, RadioMode.NEW, RadioMode.BOTH),
            selectedModes
        )
        assertEquals(1, stopCalls)

        openMenu()
        composeRule.onNodeWithText("Detener radio").assertDoesNotExist()
    }

    private fun selectMode(label: String) {
        openMenu()
        composeRule.onNodeWithText(label).assertIsDisplayed().performClick()
    }

    private fun openMenu() {
        composeRule
            .onNodeWithContentDescription("Radio (mantener para modos)")
            .performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Solo conocidos").assertIsDisplayed()
        composeRule.onNodeWithText("Solo nuevos").assertIsDisplayed()
        composeRule.onNodeWithText("Ambos").assertIsDisplayed()
    }
}
