package com.bestiapop.android.ui.state

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LibraryBrowseListStatesSaveableTest {

    private val dummyScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Suppress("UNCHECKED_CAST")
    private val saver = LazyListState.Saver as Saver<LazyListState, Any>

    @Test
    fun lazyListStateSaver_preservesFirstVisibleItemIndexAndOffset() {
        val originalState = LazyListState(
            firstVisibleItemIndex = 35,
            firstVisibleItemScrollOffset = 120
        )

        val saved = with(saver) {
            dummyScope.save(originalState)
        }
        assertNotNull("Saved state must not be null", saved)

        val restoredState = saver.restore(saved!!)
        assertNotNull("Restored state must not be null", restoredState)
        assertEquals(35, restoredState!!.firstVisibleItemIndex)
        assertEquals(120, restoredState.firstVisibleItemScrollOffset)
    }

    @Test
    fun browseTabStates_savedAndRestoredThroughRegistry_preservesAlbumScrollPosition() {
        // 1. User scrolls albums list to index 28, offset 50
        val albumListState = LazyListState(
            firstVisibleItemIndex = 28,
            firstVisibleItemScrollOffset = 50
        )
        val songsListState = LazyListState(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 0)

        // 2. Simulated tab 0 (Biblioteca) SaveableStateRegistry
        val tab0Registry = SaveableStateRegistry(restoredValues = null) { true }
        tab0Registry.registerProvider("library_browse_songs") {
            with(saver) { dummyScope.save(songsListState) }
        }
        tab0Registry.registerProvider("library_browse_albums") {
            with(saver) { dummyScope.save(albumListState) }
        }

        // 3. User enters an album, then switches to "Descubrir" tab.
        // SaveableStateHolder performs save on tab 0
        val tab0SavedState = tab0Registry.performSave()

        // 4. User switches back to "Biblioteca" tab.
        // SaveableStateHolder restores registry for tab 0 from saved values.
        val tab0RestoredRegistry = SaveableStateRegistry(restoredValues = tab0SavedState) { true }

        val restoredAlbumData = tab0RestoredRegistry.consumeRestored("library_browse_albums")
        assertNotNull("Restored album data must be present", restoredAlbumData)
        val restoredAlbumState = saver.restore(restoredAlbumData!!)
        assertNotNull("Restored album state must not be null", restoredAlbumState)

        // 5. User exits the album -> albums tab receives restored state
        assertEquals(28, restoredAlbumState!!.firstVisibleItemIndex)
        assertEquals(50, restoredAlbumState.firstVisibleItemScrollOffset)
    }
}
