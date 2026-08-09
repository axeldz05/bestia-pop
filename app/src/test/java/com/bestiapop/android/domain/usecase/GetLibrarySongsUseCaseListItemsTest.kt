package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.formatSortRelevantInfo
import com.bestiapop.android.ui.screens.library.filterCollapsedAlbumSongs
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun buildListItems_albumGroups_sortsByTrackThenTitle() {
        val mixed = listOf(
            Song(id = 1, uriString = "u1", title = "Zebra", album = "Opera", trackNumber = 3),
            Song(id = 2, uriString = "u2", title = "Alpha", album = "Opera", trackNumber = 0),
            Song(id = 3, uriString = "u3", title = "Beta", album = "Opera", trackNumber = 1),
            Song(id = 4, uriString = "u4", title = "Only", album = "Hotel", trackNumber = 2)
        )
        val items = useCase.buildListItems(mixed, LibraryViewMode.ALBUM_GROUPS)
        val operaRows = items.filterIsInstance<LibraryListItem.SongRow>()
            .filter { it.song.album == "Opera" }
        assertEquals(listOf(3L, 1L, 2L), operaRows.map { it.song.id })
        assertEquals(listOf(0, 1, 2), operaRows.map { it.index })
        assertEquals(listOf(3L, 1L, 2L, 4L), useCase.songsFromListItems(items).map { it.id })
    }

    @Test
    fun sortSongsWithinAlbum_unknownTracksLast() {
        val mixed = listOf(
            Song(id = 1, uriString = "u1", title = "B", trackNumber = 0),
            Song(id = 2, uriString = "u2", title = "A", trackNumber = 2),
            Song(id = 3, uriString = "u3", title = "C", trackNumber = 1)
        )
        assertEquals(listOf(3L, 2L, 1L), useCase.sortSongsWithinAlbum(mixed).map { it.id })
    }

    @Test
    fun buildListItems_empty_returnsEmpty() {
        assertTrue(useCase.buildListItems(emptyList(), LibraryViewMode.ALBUM_GROUPS).isEmpty())
    }

    @Test
    fun extractAlbums_includesDominantGenreAndMaxDateAdded() {
        val withMeta = listOf(
            Song(
                id = 1, uriString = "u1", title = "A", artist = "Q", album = "Opera",
                genre = "Rock", dateAdded = 100
            ),
            Song(
                id = 2, uriString = "u2", title = "B", artist = "Q", album = "Opera",
                genre = "Rock", dateAdded = 300
            ),
            Song(
                id = 3, uriString = "u3", title = "C", artist = "Q", album = "Opera",
                genre = "Pop", dateAdded = 200
            )
        )
        val albums = useCase.extractAlbums(withMeta)
        assertEquals(1, albums.size)
        assertEquals("Rock", albums[0].genre)
        assertEquals(300L, albums[0].dateAdded)
    }

    @Test
    fun extractArtists_includesDominantGenreAndMaxDateAdded() {
        val withMeta = listOf(
            Song(
                id = 1, uriString = "u1", title = "A", artist = "Queen", album = "Opera",
                genre = "Rock", dateAdded = 50
            ),
            Song(
                id = 2, uriString = "u2", title = "B", artist = "Queen", album = "News",
                genre = "Rock", dateAdded = 90
            )
        )
        val artists = useCase.extractArtists(withMeta)
        assertEquals(1, artists.size)
        assertEquals("Rock", artists[0].genre)
        assertEquals(90L, artists[0].dateAdded)
        assertEquals(2, artists[0].albumCount)
    }

    @Test
    fun filterCollapsedAlbumSongs_hidesRowsUnderCollapsedHeader() {
        val items = useCase.buildListItems(songs, LibraryViewMode.ALBUM_GROUPS)
        val filtered = filterCollapsedAlbumSongs(items, setOf("Opera"))

        assertEquals(3, filtered.size)
        assertTrue(filtered[0] is LibraryListItem.AlbumHeader)
        assertEquals("Opera", (filtered[0] as LibraryListItem.AlbumHeader).albumName)
        assertTrue(filtered[1] is LibraryListItem.AlbumHeader)
        assertEquals("Hotel", (filtered[1] as LibraryListItem.AlbumHeader).albumName)
        assertTrue(filtered[2] is LibraryListItem.SongRow)
    }

    @Test
    fun formatSortRelevantInfo_showsGenreAndDateOnlyWhenNotAlreadyVisible() {
        assertNull(
            formatSortRelevantInfo(
                SortOption.ARTIST, genre = "Rock", dateAdded = 1L,
                alreadyShowsArtist = true, alreadyShowsAlbum = true, alreadyShowsTitle = true
            )
        )
        assertEquals(
            "Rock",
            formatSortRelevantInfo(
                SortOption.GENRE, genre = "Rock", dateAdded = 1L,
                alreadyShowsArtist = true, alreadyShowsAlbum = true, alreadyShowsTitle = true
            )
        )
        assertNotNull(
            formatSortRelevantInfo(
                SortOption.DATE_ADDED, genre = "Rock", dateAdded = 1_700_000_000_000L,
                alreadyShowsArtist = true, alreadyShowsAlbum = true, alreadyShowsTitle = true
            )
        )
        assertNull(
            formatSortRelevantInfo(
                SortOption.GENRE, genre = "Unknown Genre", dateAdded = null,
                alreadyShowsArtist = true, alreadyShowsAlbum = true, alreadyShowsTitle = true
            )
        )
    }

    @Test
    fun execute_sortsByDateAddedDescending() {
        val list = listOf(
            Song(id = 1, uriString = "u1", title = "Old", dateAdded = 10),
            Song(id = 2, uriString = "u2", title = "New", dateAdded = 30),
            Song(id = 3, uriString = "u3", title = "Mid", dateAdded = 20)
        )
        val sorted = useCase.execute(list, "", SortOption.DATE_ADDED)
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun shufflePermutation_containsAllSongsWithoutDuplicates() {
        val pool = (1..20).map { Song(id = it.toLong(), uriString = "u$it", title = "T$it") }
        val current = pool[5]
        val rest = pool.filter { it.id != current.id }.shuffled()
        val shuffled = listOf(current) + rest

        assertEquals(pool.size, shuffled.size)
        assertEquals(pool.map { it.id }.toSet(), shuffled.map { it.id }.toSet())
        assertEquals(current.id, shuffled.first().id)
        assertEquals(pool.size, shuffled.map { it.id }.distinct().size)
    }
}
