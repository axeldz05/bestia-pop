package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.CatalogCategory
import com.bestiapop.android.data.model.OnlineCatalogTrack
import org.junit.Assert.assertEquals
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
    fun catalogSearchUiState_defaultsHaveFiltersClosedAndEmpty() {
        val state = CatalogSearchUiState()
        assertFalse(state.showSearchFilters)
        assertFalse(state.hasActiveFilters)
        assertEquals("", state.searchFilterArtist)
        assertEquals("", state.searchFilterAlbum)
        assertEquals("", state.searchFilterYear)
        assertEquals("", state.searchQueryDraft)
        assertFalse(state.searchFilters.hasAny)
    }

    @Test
    fun catalogSearchUiState_searchFiltersMapsArtistAlbumAndYear() {
        val state = CatalogSearchUiState(
            searchFilterArtist = "Daft Punk",
            searchFilterAlbum = "Discovery",
            searchFilterYear = "2001"
        )
        assertTrue(state.hasActiveFilters)
        val filters = state.searchFilters
        assertEquals("Daft Punk", filters.artist)
        assertEquals("Discovery", filters.album)
        assertEquals(2001, filters.year)
    }

    @Test
    fun catalogSearchUiState_hasActiveFiltersIgnoresBlankOrInvalidYear() {
        val blankState = CatalogSearchUiState(
            searchFilterArtist = "   ",
            searchFilterAlbum = "",
            searchFilterYear = "invalid"
        )
        assertFalse(blankState.hasActiveFilters)
        assertEquals(0, blankState.searchFilters.year)
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
