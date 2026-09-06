package com.bestiapop.android.data.preferences

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.state.LibraryBrowseFilter
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
    fun sanitizeSortDirection_defaultsBySortOption() {
        assertEquals("ASC", LibraryUiPreferencesCodec.defaultSortDirectionName("TITLE"))
        assertEquals("DESC", LibraryUiPreferencesCodec.defaultSortDirectionName("DATE_ADDED"))
        assertEquals("DESC", LibraryUiPreferencesCodec.sanitizeSortDirectionName(null, "DATE_ADDED"))
        assertEquals("ASC", LibraryUiPreferencesCodec.sanitizeSortDirectionName(null, "ARTIST"))
        assertEquals("DESC", LibraryUiPreferencesCodec.sanitizeSortDirectionName("DESC", "TITLE"))
        assertEquals("ASC", LibraryUiPreferencesCodec.sanitizeSortDirectionName("NOPE", "GENRE"))
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

    @Test
    fun libraryStackLookups_matchIgnoreCaseAfterOnePass() {
        val songs = listOf(
            Song(id = 1, uriString = "u1", title = "A", artist = "Queen", album = "Opera", genre = "Rock"),
            Song(id = 2, uriString = "u2", title = "B", artist = "Eagles", album = "Hotel", genre = "")
        )
        val lookups = LibraryStackLookups.fromSongs(songs)
        val kept = LibraryUiPreferencesCodec.pruneLibraryStack(
            albumName = "opera",
            artistName = "eagles",
            genreName = Song.UNKNOWN_GENRE,
            albumExists = lookups.albumExists,
            artistExists = lookups.artistExists,
            genreExists = lookups.genreExists
        )
        assertEquals("opera", kept.albumName)
        assertEquals("eagles", kept.artistName)
        assertEquals(Song.UNKNOWN_GENRE, kept.genreName)

        val dropped = LibraryUiPreferencesCodec.pruneLibraryStack(
            albumName = "Missing",
            artistName = "Ghost",
            genreName = "Jazz",
            albumExists = lookups.albumExists,
            artistExists = lookups.artistExists,
            genreExists = lookups.genreExists
        )
        assertNull(dropped.albumName)
        assertNull(dropped.artistName)
        assertNull(dropped.genreName)
    }

    @Test
    fun blobsSettings_default_containsAllEntriesEnabled() {
        val defaultSettings = LibraryBlobsSettings()
        assertEquals(LibraryBrowseFilter.entries.size, defaultSettings.items.size)
        assertEquals(LibraryBrowseFilter.entries, defaultSettings.enabledFilters)
        assertEquals(LibraryBrowseFilter.SONGS, defaultSettings.primaryFilter)
    }

    @Test
    fun blobsSettings_encodeAndDecode_roundTrip() {
        val custom = LibraryBlobsSettings(
            items = listOf(
                LibraryBlobConfig(LibraryBrowseFilter.ALBUMS, enabled = true),
                LibraryBlobConfig(LibraryBrowseFilter.SONGS, enabled = false),
                LibraryBlobConfig(LibraryBrowseFilter.ARTISTS, enabled = true),
                LibraryBlobConfig(LibraryBrowseFilter.GENRES, enabled = false),
                LibraryBlobConfig(LibraryBrowseFilter.PLAYLISTS, enabled = true),
                LibraryBlobConfig(LibraryBrowseFilter.RECENT, enabled = false)
            )
        )
        val encoded = LibraryUiPreferencesCodec.encodeBlobsSettings(custom)
        assertEquals("ALBUMS:1,SONGS:0,ARTISTS:1,GENRES:0,PLAYLISTS:1,RECENT:0", encoded)

        val decoded = LibraryUiPreferencesCodec.decodeBlobsSettings(encoded)
        assertEquals(listOf(LibraryBrowseFilter.ALBUMS, LibraryBrowseFilter.ARTISTS, LibraryBrowseFilter.PLAYLISTS), decoded.enabledFilters)
        assertEquals(LibraryBrowseFilter.ALBUMS, decoded.primaryFilter)
        assertEquals(custom.items, decoded.items)
    }

    @Test
    fun blobsSettings_decode_handlesCorruptEmptyAndMissingEntries() {
        val empty = LibraryUiPreferencesCodec.decodeBlobsSettings("")
        assertEquals(LibraryBrowseFilter.entries, empty.enabledFilters)
        assertEquals(LibraryBrowseFilter.SONGS, empty.primaryFilter)

        val partial = LibraryUiPreferencesCodec.decodeBlobsSettings("ARTISTS:1,RECENT:0")
        assertEquals(LibraryBrowseFilter.ARTISTS, partial.primaryFilter)
        assertEquals(LibraryBrowseFilter.ARTISTS, partial.items[0].filter)
        assertEquals(LibraryBrowseFilter.RECENT, partial.items[1].filter)
        // All missing entries should be appended and enabled by default
        assertEquals(LibraryBrowseFilter.entries.size, partial.items.size)
        assertEquals(true, partial.items.find { it.filter == LibraryBrowseFilter.SONGS }?.enabled)

        // All disabled forces first item to be enabled
        val allDisabled = LibraryUiPreferencesCodec.decodeBlobsSettings("SONGS:0,ALBUMS:0,ARTISTS:0,GENRES:0,PLAYLISTS:0,RECENT:0")
        assertEquals(listOf(LibraryBrowseFilter.SONGS), allDisabled.enabledFilters)
        assertEquals(LibraryBrowseFilter.SONGS, allDisabled.primaryFilter)
    }
}
