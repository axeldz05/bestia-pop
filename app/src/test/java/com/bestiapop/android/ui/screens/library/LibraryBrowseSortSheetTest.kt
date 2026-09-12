package com.bestiapop.android.ui.screens.library

import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBrowseSortSheetTest {

    @Test
    fun libraryFilterButtonLabel_recent_returnsRecientes() {
        val label = libraryFilterButtonLabel(
            browseFilter = LibraryBrowseFilter.RECENT,
            sortOption = SortOption.DATE_ADDED,
            sortDirection = SortDirection.DESC
        )
        assertEquals("Recientes", label)
    }

    @Test
    fun libraryFilterButtonLabel_flatSongs_showsSortOptionAndArrow() {
        assertEquals(
            "Título ↓",
            libraryFilterButtonLabel(
                browseFilter = LibraryBrowseFilter.SONGS,
                sortOption = SortOption.TITLE,
                sortDirection = SortDirection.ASC
            )
        )
        assertEquals(
            "Título ↑",
            libraryFilterButtonLabel(
                browseFilter = LibraryBrowseFilter.SONGS,
                sortOption = SortOption.TITLE,
                sortDirection = SortDirection.DESC
            )
        )
        assertEquals(
            "Fecha ↑",
            libraryFilterButtonLabel(
                browseFilter = LibraryBrowseFilter.SONGS,
                sortOption = SortOption.DATE_ADDED,
                sortDirection = SortDirection.DESC
            )
        )
        assertEquals(
            "Fecha ↓",
            libraryFilterButtonLabel(
                browseFilter = LibraryBrowseFilter.SONGS,
                sortOption = SortOption.DATE_ADDED,
                sortDirection = SortDirection.ASC
            )
        )
        assertEquals(
            "Artista ↓",
            libraryFilterButtonLabel(
                browseFilter = LibraryBrowseFilter.SONGS,
                sortOption = SortOption.ARTIST,
                sortDirection = SortDirection.ASC
            )
        )
    }

    @Test
    fun libraryFilterButtonLabel_albumHeadersActive_showsAlbumPrefix() {
        assertEquals(
            "Álbum · Título ↓",
            libraryFilterButtonLabel(
                browseFilter = LibraryBrowseFilter.SONGS,
                sortOption = SortOption.TITLE,
                sortDirection = SortDirection.ASC,
                albumHeadersActive = true
            )
        )
        assertEquals(
            "Álbum · Artista ↑",
            libraryFilterButtonLabel(
                browseFilter = LibraryBrowseFilter.SONGS,
                sortOption = SortOption.ARTIST,
                sortDirection = SortDirection.DESC,
                albumHeadersActive = true
            )
        )
    }

    @Test
    fun libraryFilterButtonLabel_otherFilters_showsCorrectSortOption() {
        assertEquals(
            "Álbum ↓",
            libraryFilterButtonLabel(
                browseFilter = LibraryBrowseFilter.ALBUMS,
                sortOption = SortOption.ALBUM,
                sortDirection = SortDirection.ASC
            )
        )
        assertEquals(
            "Género ↓",
            libraryFilterButtonLabel(
                browseFilter = LibraryBrowseFilter.GENRES,
                sortOption = SortOption.GENRE,
                sortDirection = SortDirection.ASC
            )
        )
    }

    @Test
    fun libraryOrderSummary_and_tuneContentDescription() {
        val summary = libraryOrderSummary(
            browseFilter = LibraryBrowseFilter.SONGS,
            sortOption = SortOption.TITLE,
            sortDirection = SortDirection.ASC
        )
        assertEquals("Canciones · por título ↓", summary)

        val description = libraryTuneContentDescription(summary)
        assertEquals("Vista y orden. Canciones · por título ↓", description)
    }
}
