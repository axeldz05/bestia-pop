package com.bestiapop.android.ui.playlist

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
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
class PlaylistCrudE2ETest {
    private val composeRule = createEmptyComposeRule()
    private val fixture = PlaylistCrudTestFixture()
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
    fun createRename_addFromLibrary_removeAndDelete_persistsVisibleState() {
        ui.await("Playlists root") {
            ui.exists(hasText("Mis Playlists")) &&
                ui.exists(hasContentDescription("Crear Playlist"))
        }

        composeRule.onNodeWithContentDescription("Crear Playlist").performClick()
        composeRule.onNodeWithText("Nueva Playlist").assertIsDisplayed()
        composeRule.onNodeWithTag("playlist-name-input")
            .performTextInput(fixture.playlistName)
        composeRule.onNodeWithTag("playlist-description-input")
            .performTextInput(fixture.playlistDescription)
        closeSoftKeyboard()
        composeRule.onNodeWithText("Crear").performClick()

        ui.await("new playlist detail") {
            ui.exists(hasText(fixture.playlistName)) &&
                ui.exists(hasText("0 canciones")) &&
                ui.exists(hasText("Añadir canciones ahora"))
        }

        composeRule.onNodeWithContentDescription("Editar playlist").performClick()
        composeRule.onNodeWithText("Editar Playlist").assertIsDisplayed()
        composeRule.onNodeWithTag("playlist-name-input")
            .performTextReplacement(fixture.renamedPlaylistName)
        composeRule.onNodeWithTag("playlist-description-input")
            .performTextReplacement(fixture.renamedDescription)
        closeSoftKeyboard()
        composeRule.onNodeWithText("Guardar").performClick()

        ui.await("renamed playlist detail") {
            ui.exists(hasText(fixture.renamedPlaylistName)) &&
                ui.exists(hasText(fixture.renamedDescription))
        }
        fixture.verifyRenamedPlaylist()

        composeRule.onNodeWithText("Añadir canciones ahora").performClick()
        ui.await("real Library playlist-addition flow") {
            ui.exists(hasText("0 seleccionadas")) &&
                fixture.songTitles.all { ui.exists(hasText(it)) }
        }
        fixture.songTitles.forEach { title ->
            val selectableLibraryRows = composeRule.onAllNodes(
                hasText(title) and
                    SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
            )
            selectableLibraryRows.assertCountEquals(1)
            selectableLibraryRows[0].performClick()
        }
        ui.await("two songs selected for playlist addition") {
            ui.exists(hasText("2 seleccionadas")) &&
                ui.exists(hasText("Añadir a ${fixture.renamedPlaylistName}"))
        }
        composeRule.onNodeWithText("Añadir a ${fixture.renamedPlaylistName}").performClick()

        ui.await("playlist detail with two members") {
            ui.exists(hasText("2 canciones")) &&
                fixture.songTitles.all { ui.exists(hasText(it)) }
        }
        fixture.verifyPlaylistMembership(expectedCount = 2)

        composeRule.onAllNodesWithContentDescription("Opciones")[0].performClick()
        composeRule.onNodeWithText("Eliminar").performClick()

        fixture.verifyPlaylistMembership(expectedCount = 1)
        ui.await("playlist detail after removing one member") {
            ui.exists(hasText("1 canciones"))
        }

        composeRule.onNodeWithTag("playlist-detail-delete").performClick()
        composeRule.onNodeWithText("Eliminar Playlist").assertIsDisplayed()
        composeRule.onNodeWithText("Eliminar").performClick()

        ui.await("playlist deleted back at root") {
            ui.exists(hasText("Mis Playlists")) &&
                !ui.exists(hasText(fixture.renamedPlaylistName))
        }
        fixture.verifyPlaylistDeletedAndSongsKept()
    }

    private companion object {
        const val UI_TIMEOUT_MS = 15_000L
    }
}
