package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.CatalogCategory
import com.bestiapop.android.data.model.OnlineCatalogTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogUiStateTest {

    @Test
    fun currentResultsAreEmpty_readsOnlySelectedCategory() {
        val track = OnlineCatalogTrack(
            id = "1",
            title = "Song",
            artist = "Artist",
            album = "Album",
            artworkUri = null,
            durationMs = 0L,
            audioUrl = "",
            provider = "test"
        )
        val tracks = CatalogSearchUiState(tracks = listOf(track))

        assertFalse(tracks.currentResultsAreEmpty())
        assertTrue(tracks.copy(category = CatalogCategory.ALBUMS).currentResultsAreEmpty())
    }

    @Test
    fun collection_isOpenOnlyWhenIdentityExists() {
        assertFalse(CatalogCollectionUiState().isOpen)
        assertTrue(
            CatalogCollectionUiState(
                selectionKey = "playlist:1#1",
                title = "Playlist",
                kind = CatalogCollectionKind.PLAYLIST,
                isLoading = true
            ).isOpen
        )
    }

    @Test
    fun collectionIdentity_distinguishesHomonymousAndReopenedCollections() {
        val first = CatalogCollectionUiState(
            selectionKey = "playlist:remote-a#1",
            title = "Mix",
            kind = CatalogCollectionKind.PLAYLIST
        )
        val homonym = first.copy(selectionKey = "playlist:remote-b#2")
        val reopened = first.copy(selectionKey = "playlist:remote-a#3")

        assertNotEquals(first.selectionKey, homonym.selectionKey)
        assertNotEquals(first.selectionKey, reopened.selectionKey)
    }
}
