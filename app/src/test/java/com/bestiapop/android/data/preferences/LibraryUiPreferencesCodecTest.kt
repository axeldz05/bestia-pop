package com.bestiapop.android.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryUiPreferencesCodecTest {

    @Test
    fun sanitizeSortAndView_unknownFallsBackToDefault() {
        assertEquals(DEFAULT_SORT_OPTION_NAME, LibraryUiPreferencesCodec.sanitizeSortOptionName(null))
        assertEquals(DEFAULT_SORT_OPTION_NAME, LibraryUiPreferencesCodec.sanitizeSortOptionName("NOPE"))
        assertEquals("GENRE", LibraryUiPreferencesCodec.sanitizeSortOptionName("GENRE"))
        assertEquals(DEFAULT_VIEW_MODE_NAME, LibraryUiPreferencesCodec.sanitizeViewModeName(null))
        assertEquals(DEFAULT_VIEW_MODE_NAME, LibraryUiPreferencesCodec.sanitizeViewModeName("GRID"))
        assertEquals("FLAT", LibraryUiPreferencesCodec.sanitizeViewModeName("FLAT"))
    }

    @Test
    fun sanitizeNavSnapshot_roundTripKeepsValidFields() {
        val snap = LibraryUiPreferencesCodec.sanitizeNavSnapshot(
            navIndex = NAV_PLAYLISTS,
            libraryTab = LIBRARY_TAB_ARTISTS,
            libraryArtistName = "  Queen  ",
            libraryAlbumName = "A Night at the Opera",
            playlistDetailKind = PLAYLIST_DETAIL_LOCAL,
            playlistLocalId = 42L,
            playlistLbMbid = "should-clear"
        )
        assertEquals(NAV_PLAYLISTS, snap.navIndex)
        assertEquals(LIBRARY_TAB_ARTISTS, snap.libraryTab)
        assertEquals("Queen", snap.libraryArtistName)
        assertEquals("A Night at the Opera", snap.libraryAlbumName)
        assertEquals(PLAYLIST_DETAIL_LOCAL, snap.playlistDetailKind)
        assertEquals(42L, snap.playlistLocalId)
        assertNull(snap.playlistLbMbid)
    }

    @Test
    fun sanitizeNavSnapshot_invalidIndexTabAndKind_fallBack() {
        val snap = LibraryUiPreferencesCodec.sanitizeNavSnapshot(
            navIndex = 99,
            libraryTab = -1,
            playlistDetailKind = "weird",
            playlistLocalId = 0L,
            playlistLbMbid = "   "
        )
        assertEquals(NAV_LIBRARY, snap.navIndex)
        assertEquals(LIBRARY_TAB_SONGS, snap.libraryTab)
        assertEquals(PLAYLIST_DETAIL_NONE, snap.playlistDetailKind)
        assertNull(snap.playlistLocalId)
        assertNull(snap.playlistLbMbid)
    }

    @Test
    fun pruneOrphanPlaylistDetail_localWithoutId_andLbWithoutMbid() {
        val localOrphan = LibraryUiPreferencesCodec.pruneOrphanPlaylistDetail(
            UiNavSnapshot(playlistDetailKind = PLAYLIST_DETAIL_LOCAL, playlistLocalId = null)
        )
        assertEquals(PLAYLIST_DETAIL_NONE, localOrphan.playlistDetailKind)

        val lbOrphan = LibraryUiPreferencesCodec.pruneOrphanPlaylistDetail(
            UiNavSnapshot(playlistDetailKind = PLAYLIST_DETAIL_LB, playlistLbMbid = null)
        )
        assertEquals(PLAYLIST_DETAIL_NONE, lbOrphan.playlistDetailKind)

        val cf = LibraryUiPreferencesCodec.pruneOrphanPlaylistDetail(
            UiNavSnapshot(
                playlistDetailKind = PLAYLIST_DETAIL_CF,
                playlistLocalId = 9L,
                playlistLbMbid = "mbid"
            )
        )
        assertEquals(PLAYLIST_DETAIL_CF, cf.playlistDetailKind)
        assertNull(cf.playlistLocalId)
        assertNull(cf.playlistLbMbid)
    }

    @Test
    fun pruneLibraryStack_dropsMissingLevels() {
        val bothOk = LibraryUiPreferencesCodec.pruneLibraryStack(
            albumName = "Opera",
            artistName = "Queen",
            albumExists = { it == "Opera" },
            artistExists = { it == "Queen" }
        )
        assertEquals("Opera", bothOk.albumName)
        assertEquals("Queen", bothOk.artistName)

        val albumGone = LibraryUiPreferencesCodec.pruneLibraryStack(
            albumName = "Missing",
            artistName = "Queen",
            albumExists = { false },
            artistExists = { it == "Queen" }
        )
        assertNull(albumGone.albumName)
        assertEquals("Queen", albumGone.artistName)

        val artistGone = LibraryUiPreferencesCodec.pruneLibraryStack(
            albumName = "Opera",
            artistName = "Ghost",
            albumExists = { it == "Opera" },
            artistExists = { false }
        )
        assertEquals("Opera", artistGone.albumName)
        assertNull(artistGone.artistName)

        val bothGone = LibraryUiPreferencesCodec.pruneLibraryStack(
            albumName = "A",
            artistName = "B",
            albumExists = { false },
            artistExists = { false }
        )
        assertNull(bothGone.albumName)
        assertNull(bothGone.artistName)
    }
}
