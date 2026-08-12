package com.bestiapop.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.ui.components.MultiSelectActionBar
import com.bestiapop.android.ui.screens.library.LibrarySongList
import com.bestiapop.android.ui.state.LibraryViewMode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class LibraryScreenFunctionalTest {

    private val composeTestRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(composeTestRule)

    private val sampleSongs = listOf(
        Song(
            id = 1L,
            uriString = "content://media/1",
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            genre = "Rock"
        ),
        Song(
            id = 2L,
            uriString = "content://media/2",
            title = "Hotel California",
            artist = "Eagles",
            album = "Hotel California",
            genre = "Classic Rock"
        )
    )

    private val libraryItems = GetLibrarySongsUseCase()
        .buildListItems(sampleSongs, LibraryViewMode.FLAT)

    @Test
    fun multiSelectActionBar_displaysSelectedCountAndActions() {
        var playClicked = false

        composeTestRule.setContent {
            MultiSelectActionBar(
                selectedCount = 2,
                onPlaySelected = { playClicked = true },
                onEnqueueSelected = {},
                onAddToPlaylist = {},
                onIdentifySelected = {},
                onSimilarSelected = {},
                onDeleteSelected = {},
                onSelectAll = {},
                onClearSelection = {}
            )
        }

        composeTestRule.onNodeWithText("2 seleccionados").assertIsDisplayed()
        composeTestRule.onNodeWithText("Seleccionar todo").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Reproducir seleccionados").performClick()
        assert(playClicked)
    }

    @Test
    fun multiSelectActionBar_deleteDoesNotTriggerSelectAll() {
        var deleteClicked = false
        var selectAllClicked = false

        composeTestRule.setContent {
            MultiSelectActionBar(
                selectedCount = 2,
                onPlaySelected = {},
                onEnqueueSelected = {},
                onAddToPlaylist = {},
                onIdentifySelected = {},
                onSimilarSelected = {},
                onDeleteSelected = { deleteClicked = true },
                onSelectAll = { selectAllClicked = true },
                onClearSelection = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Eliminar seleccionados").performClick()
        assert(deleteClicked)
        assert(!selectAllClicked)
    }

    @Test
    fun librarySongList_rendersSongsCorrectly() {
        var clickedSong: Song? = null

        composeTestRule.setContent {
            LibrarySongList(
                items = libraryItems,
                currentSongId = null,
                isSelectionMode = false,
                selectedSongIds = emptySet(),
                onSongClick = { song, _ -> clickedSong = song },
                onSongLongClick = {},
                onToggleSelect = {},
                onPlayNext = {},
                onAddToQueue = {},
                onAddToPlaylist = {},
                onEditMetadata = {},
                onDeleteSong = {},
                onPlayAlbum = { _, _ -> },
                onShuffleAlbum = { _, _ -> }
            )
        }

        // Verify song titles are visible to user
        composeTestRule.onNodeWithText("Bohemian Rhapsody").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hotel California").assertIsDisplayed()

        // User clicks song
        composeTestRule.onNodeWithText("Bohemian Rhapsody").performClick()

        // Assert user interaction triggered playback callback
        assert(clickedSong?.title == "Bohemian Rhapsody")
    }
}
