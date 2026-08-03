package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetLibrarySongsUseCaseListItemsTest {

    private val useCase = GetLibrarySongsUseCase()

    private val songs = listOf(
        Song(id = 1, uriString = "u1", title = "A", artist = "Queen", album = "Opera"),
        Song(id = 2, uriString = "u2", title = "B", artist = "Queen", album = "Opera"),
        Song(id = 3, uriString = "u3", title = "C", artist = "Eagles", album = "Hotel")
    )

    @Test
    fun buildListItems_flat_preservesIndices() {
        val items = useCase.buildListItems(songs, LibraryViewMode.FLAT)

        assertEquals(3, items.size)
        assertTrue(items.all { it is LibraryListItem.SongRow })
        assertEquals(0, (items[0] as LibraryListItem.SongRow).index)
        assertEquals(1, (items[1] as LibraryListItem.SongRow).index)
        assertEquals(3L, (items[2] as LibraryListItem.SongRow).song.id)
    }

    @Test
    fun buildListItems_albumGroups_insertsHeadersAndSourceIndices() {
        val items = useCase.buildListItems(songs, LibraryViewMode.ALBUM_GROUPS)

        assertEquals(5, items.size)
        val header1 = items[0] as LibraryListItem.AlbumHeader
        assertEquals("Opera", header1.albumName)
        assertEquals(2, header1.songCount)
        assertEquals(0, (items[1] as LibraryListItem.SongRow).index)
        assertEquals(1, (items[2] as LibraryListItem.SongRow).index)

        val header2 = items[3] as LibraryListItem.AlbumHeader
        assertEquals("Hotel", header2.albumName)
        assertEquals(2, (items[4] as LibraryListItem.SongRow).index)
    }

    @Test
    fun buildListItems_empty_returnsEmpty() {
        assertTrue(useCase.buildListItems(emptyList(), LibraryViewMode.ALBUM_GROUPS).isEmpty())
    }
}
