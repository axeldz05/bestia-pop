package com.bestiapop.android.ui.state

import com.bestiapop.android.data.preferences.NAV_DOWNLOADS
import com.bestiapop.android.data.preferences.NAV_PLAYLISTS
import com.bestiapop.android.data.preferences.PrunedLibraryStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiNavigationStateTest {

    @Test
    fun libraryStack_updatesNestedPathAtomically() {
        val artist = LibraryBrowseStack().openArtist("Queen")
        val album = artist.openAlbum("Opera", fromNestedParent = true)

        assertEquals("Queen", album.artistName)
        assertEquals("Opera", album.albumName)
        assertEquals(artist, album.pop())
        assertEquals(LibraryBrowseStack(), album.pop().pop())

        val directAlbum = artist.openAlbum("Jazz", fromNestedParent = false)
        assertNull(directAlbum.artistName)
        assertEquals("Jazz", directAlbum.albumName)
    }

    @Test
    fun snapshotRoundTrip_usesPersistedTabAndTypedDestinations() {
        val state = UiNavigationState(
            selectedNavIndex = NAV_DOWNLOADS,
            libraryBrowseFilter = LibraryBrowseFilter.ARTISTS,
            libraryStack = LibraryBrowseStack(artistName = "Queen", albumName = "Opera"),
            playlistDetail = PlaylistDetailNav.ListenBrainz("mbid")
        )

        val snapshot = state.toSnapshot(persistedNavIndex = NAV_PLAYLISTS)
        val restored = UiNavigationState.fromSnapshot(snapshot)

        assertEquals(NAV_PLAYLISTS, restored.selectedNavIndex)
        assertEquals(state.libraryBrowseFilter, restored.libraryBrowseFilter)
        assertEquals(state.libraryStack, restored.libraryStack)
        assertEquals(state.playlistDetail, restored.playlistDetail)
    }

    @Test
    fun applyPruned_replacesAllStackLevelsTogether() {
        val stack = LibraryBrowseStack("Artist", "Album", "Genre")
        val pruned = stack.applyPruned(
            PrunedLibraryStack(albumName = null, artistName = "Artist", genreName = null)
        )

        assertEquals(LibraryBrowseStack(artistName = "Artist"), pruned)
    }
}
