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
        val track =
            OnlineCatalogTrack(
                id = "1",
                title = "Song",
                artist = "Artist",
                album = "Album",
                artworkUri = null,
                durationMs = 0L,
                audioUrl = "",
                provider = "test",
            )
        val tracks = CatalogSearchUiState(tracks = listOf(track))

        assertFalse(tracks.currentResultsAreEmpty())
        assertTrue(tracks.copy(category = CatalogCategory.ALBUMS).currentResultsAreEmpty())
    }

    @Test
    fun catalogSearchUiState_artistsDefaultsToEmpty() {
        val state = CatalogSearchUiState()
        assertTrue(state.artists.isEmpty())
        val withArtists =
            state.copy(
                artists =
                    listOf(
                        com.bestiapop.android.data.model.Artist(
                            name = "Queen",
                            songCount = 0,
                            albumCount = 15,
                        ),
                    ),
            )
        assertEquals(1, withArtists.artists.size)
        assertEquals("Queen", withArtists.artists.first().name)
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
        val state =
            CatalogSearchUiState(
                searchFilterArtist = "Daft Punk",
                searchFilterAlbum = "Discovery",
                searchFilterYear = "2001",
            )
        assertTrue(state.hasActiveFilters)
        val filters = state.searchFilters
        assertEquals("Daft Punk", filters.artist)
        assertEquals("Discovery", filters.album)
        assertEquals(2001, filters.year)
    }

    @Test
    fun catalogSearchUiState_hasActiveFiltersIgnoresBlankOrInvalidYear() {
        val blankState =
            CatalogSearchUiState(
                searchFilterArtist = "   ",
                searchFilterAlbum = "",
                searchFilterYear = "invalid",
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
                isLoading = true,
            ).isOpen,
        )
    }

    @Test
    fun collectionIdentity_distinguishesHomonymousAndReopenedCollections() {
        val first =
            CatalogCollectionUiState(
                selectionKey = "playlist:remote-a#1",
                title = "Mix",
                kind = CatalogCollectionKind.PLAYLIST,
            )
        val homonym = first.copy(selectionKey = "playlist:remote-b#2")
        val reopened = first.copy(selectionKey = "playlist:remote-a#3")

        assertNotEquals(first.selectionKey, homonym.selectionKey)
        assertNotEquals(first.selectionKey, reopened.selectionKey)
    }

    @Test
    fun toPlayableItems_resolvesLocalAndRemoteProperly() {
        val identityLocal =
            com.bestiapop.android.data.model.TrackIdentity(
                title = "Around the World",
                artist = "Daft Punk",
                album = "Homework",
                durationMs = 239000L,
            )
        val identityRemote =
            com.bestiapop.android.data.model.TrackIdentity(
                title = "Harder, Better, Faster, Stronger",
                artist = "Daft Punk",
                album = "Discovery",
                durationMs = 224000L,
            )
        val candidateLocal =
            com.bestiapop.android.data.model.CatalogTrackCandidate(
                identity = identityLocal,
                candidates = emptyList(),
            )
        val candidateRemote =
            com.bestiapop.android.data.model.CatalogTrackCandidate(
                identity = identityRemote,
                candidates = emptyList(),
            )

        val localSong =
            com.bestiapop.android.data.model.Song(
                id = 101L,
                title = "Around the World",
                artist = "Daft Punk",
                album = "Homework",
                durationMs = 239000L,
                trackNumber = 7,
                year = 1997,
                genre = "Electronic",
                dateAdded = 1000L,
                uriString = "content://music/101",
            )
        val localIndex =
            com.bestiapop.android.domain.util.TrackMatchKeys
                .buildLibraryIndex(listOf(localSong))

        val playables = listOf(candidateLocal, candidateRemote).toPlayableItems(localIndex)
        assertEquals(2, playables.size)
        assertTrue(playables[0] is com.bestiapop.android.data.model.PlayableItem.Local)
        assertEquals(101L, (playables[0] as com.bestiapop.android.data.model.PlayableItem.Local).song.id)
        assertTrue(playables[1] is com.bestiapop.android.data.model.PlayableItem.Remote)
        assertEquals(
            "Harder, Better, Faster, Stronger",
            (playables[1] as com.bestiapop.android.data.model.PlayableItem.Remote).identity.title,
        )
    }
}
