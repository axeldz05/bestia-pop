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
    fun sanitizeBrowseFilter_prefersName_andMapsLegacyTab() {
        assertEquals("GENRES", LibraryUiPreferencesCodec.sanitizeBrowseFilterName("GENRES"))
        assertEquals("RECENT", LibraryUiPreferencesCodec.sanitizeBrowseFilterName("RECENT"))
        assertEquals(
            DEFAULT_BROWSE_FILTER_NAME,
            LibraryUiPreferencesCodec.sanitizeBrowseFilterName("NOPE")
        )
        assertEquals(
            "ALBUMS",
            LibraryUiPreferencesCodec.sanitizeBrowseFilterName(null, LIBRARY_TAB_ALBUMS)
        )
        assertEquals(
            "ARTISTS",
            LibraryUiPreferencesCodec.sanitizeBrowseFilterName(null, LIBRARY_TAB_ARTISTS)
        )
        assertEquals(
            "SONGS",
            LibraryUiPreferencesCodec.sanitizeBrowseFilterName(null, LIBRARY_TAB_SONGS)
        )
        assertEquals(
            "SONGS",
            LibraryUiPreferencesCodec.sanitizeBrowseFilterName(null, -1)
        )
        // Explicit name wins over legacy tab
        assertEquals(
            "GENRES",
            LibraryUiPreferencesCodec.sanitizeBrowseFilterName("GENRES", LIBRARY_TAB_ARTISTS)
        )
    }

    @Test
    fun sanitizeNavSnapshot_roundTripKeepsValidFields() {
        val snap = LibraryUiPreferencesCodec.sanitizeNavSnapshot(
            navIndex = NAV_PLAYLISTS,
            browseFilterName = "ARTISTS",
            libraryArtistName = "  Queen  ",
            libraryAlbumName = "A Night at the Opera",
            libraryGenreName = " Rock ",
            playlistDetailKind = PLAYLIST_DETAIL_LOCAL,
            playlistLocalId = 42L,
            playlistLbMbid = "should-clear"
        )
        assertEquals(NAV_PLAYLISTS, snap.navIndex)
        assertEquals("ARTISTS", snap.browseFilterName)
        assertEquals("Queen", snap.libraryArtistName)
        assertEquals("A Night at the Opera", snap.libraryAlbumName)
        assertEquals("Rock", snap.libraryGenreName)
        assertEquals(PLAYLIST_DETAIL_LOCAL, snap.playlistDetailKind)
        assertEquals(42L, snap.playlistLocalId)
        assertNull(snap.playlistLbMbid)
    }

    @Test
    fun sanitizeNavSnapshot_legacyTabWithoutFilterName() {
        val snap = LibraryUiPreferencesCodec.sanitizeNavSnapshot(
            libraryTab = LIBRARY_TAB_ALBUMS
        )
        assertEquals("ALBUMS", snap.browseFilterName)
    }

    @Test
    fun sanitizeNavSnapshot_invalidIndexTabAndKind_fallBack() {
        val snap = LibraryUiPreferencesCodec.sanitizeNavSnapshot(
            navIndex = 99,
            browseFilterName = "weird",
            libraryTab = -1,
            playlistDetailKind = "weird",
            playlistLocalId = 0L,
            playlistLbMbid = "   "
        )
        assertEquals(NAV_LIBRARY, snap.navIndex)
        assertEquals(DEFAULT_BROWSE_FILTER_NAME, snap.browseFilterName)
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
            genreName = "Rock",
            albumExists = { it == "Opera" },
            artistExists = { it == "Queen" },
            genreExists = { it == "Rock" }
        )
        assertEquals("Opera", bothOk.albumName)
        assertEquals("Queen", bothOk.artistName)
        assertEquals("Rock", bothOk.genreName)

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
            genreName = "C",
            albumExists = { false },
            artistExists = { false },
            genreExists = { false }
        )
        assertNull(bothGone.albumName)
        assertNull(bothGone.artistName)
        assertNull(bothGone.genreName)
    }
}
