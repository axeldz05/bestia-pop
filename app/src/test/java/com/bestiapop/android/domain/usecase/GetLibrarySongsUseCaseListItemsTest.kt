package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.formatSortRelevantInfo
import com.bestiapop.android.ui.components.sortEmphasisFor
import com.bestiapop.android.ui.screens.library.filterCollapsedAlbumSongs
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("Hotel", header1.albumName)
        assertEquals(1, header1.songCount)
        assertEquals(0, (items[1] as LibraryListItem.SongRow).index)

        val header2 = items[2] as LibraryListItem.AlbumHeader
        assertEquals("Opera", header2.albumName)
        assertEquals(2, header2.songCount)
        assertEquals(1, (items[3] as LibraryListItem.SongRow).index)
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
        assertEquals(listOf(1, 2, 3), operaRows.map { it.index })
        assertEquals(listOf(4L, 3L, 1L, 2L), useCase.songsFromListItems(items).map { it.id })
    }

    @Test
    fun buildListItems_albumGroups_ordersBlocksLikeExtractAlbums() {
        val mixed = listOf(
            Song(id = 1, uriString = "u1", title = "Zed", artist = "B", album = "Zebra", dateAdded = 10),
            Song(id = 2, uriString = "u2", title = "Amy", artist = "A", album = "Alpha", dateAdded = 30),
            Song(id = 3, uriString = "u3", title = "Old", artist = "C", album = "Mid", dateAdded = 20)
        )
        val byName = useCase.buildListItems(
            mixed, LibraryViewMode.ALBUM_GROUPS, sortOption = SortOption.TITLE
        )
        assertEquals(
            listOf("Alpha", "Mid", "Zebra"),
            byName.filterIsInstance<LibraryListItem.AlbumHeader>().map { it.albumName }
        )
        val byDateDesc = useCase.buildListItems(
            mixed,
            LibraryViewMode.ALBUM_GROUPS,
            sortOption = SortOption.DATE_ADDED,
            sortDirection = SortDirection.DESC
        )
        assertEquals(
            listOf("Alpha", "Mid", "Zebra"),
            byDateDesc.filterIsInstance<LibraryListItem.AlbumHeader>().map { it.albumName }
        )
        val byArtist = useCase.buildListItems(
            mixed, LibraryViewMode.ALBUM_GROUPS, sortOption = SortOption.ARTIST
        )
        assertEquals(
            listOf("Alpha", "Zebra", "Mid"),
            byArtist.filterIsInstance<LibraryListItem.AlbumHeader>().map { it.albumName }
        )
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
    fun extractGenres_sortsKnownAndPutsUnknownLast() {
        val withMeta = listOf(
            Song(id = 1, uriString = "u1", title = "A", genre = "Rock", dateAdded = 10),
            Song(id = 2, uriString = "u2", title = "B", genre = "Pop", dateAdded = 40),
            Song(id = 3, uriString = "u3", title = "C", genre = "", dateAdded = 20),
            Song(id = 4, uriString = "u4", title = "D", genre = "Rock", dateAdded = 30)
        )
        val genres = useCase.extractGenres(withMeta)
        assertEquals(listOf("Pop", "Rock", Song.UNKNOWN_GENRE), genres.map { it.name })
        assertEquals(2, genres.first { it.name == "Rock" }.songCount)
        assertEquals(40L, genres.first { it.name == "Pop" }.dateAdded)
    }

    @Test
    fun extractAlbums_respectsSortOptionAndDirection() {
        val list = listOf(
            Song(id = 1, uriString = "u1", title = "A", artist = "Zed", album = "Beta", genre = "Rock", dateAdded = 10),
            Song(id = 2, uriString = "u2", title = "B", artist = "Amy", album = "Alpha", genre = "Pop", dateAdded = 50),
            Song(id = 3, uriString = "u3", title = "C", artist = "Bob", album = "Gamma", genre = "Jazz", dateAdded = 30)
        )
        assertEquals(
            listOf("Alpha", "Beta", "Gamma"),
            useCase.extractAlbums(list, sortOption = SortOption.TITLE).map { it.displayName }
        )
        assertEquals(
            listOf("Gamma", "Beta", "Alpha"),
            useCase.extractAlbums(list, sortOption = SortOption.TITLE, sortDirection = SortDirection.DESC)
                .map { it.displayName }
        )
        assertEquals(
            listOf("Alpha", "Gamma", "Beta"),
            useCase.extractAlbums(list, sortOption = SortOption.ARTIST).map { it.displayName }
        )
        assertEquals(
            listOf("Beta", "Gamma", "Alpha"),
            useCase.extractAlbums(list, sortOption = SortOption.DATE_ADDED).map { it.displayName }
        )
    }

    @Test
    fun extractArtists_respectsGenreAndDateSort() {
        val list = listOf(
            Song(id = 1, uriString = "u1", title = "A", artist = "Zed", genre = "Rock", dateAdded = 10),
            Song(id = 2, uriString = "u2", title = "B", artist = "Amy", genre = "Pop", dateAdded = 50),
            Song(id = 3, uriString = "u3", title = "C", artist = "Bob", genre = "Jazz", dateAdded = 30)
        )
        assertEquals(
            listOf("Bob", "Amy", "Zed"),
            useCase.extractArtists(list, sortOption = SortOption.GENRE).map { it.name }
        )
        assertEquals(
            listOf("Zed", "Bob", "Amy"),
            useCase.extractArtists(list, sortOption = SortOption.DATE_ADDED).map { it.name }
        )
    }

    @Test
    fun extractGenres_dateAddedKeepsUnknownLast() {
        val withMeta = listOf(
            Song(id = 1, uriString = "u1", title = "A", genre = "Rock", dateAdded = 10),
            Song(id = 2, uriString = "u2", title = "B", genre = "Pop", dateAdded = 40),
            Song(id = 3, uriString = "u3", title = "C", genre = "", dateAdded = 99)
        )
        val genres = useCase.extractGenres(
            withMeta,
            sortOption = SortOption.DATE_ADDED,
            sortDirection = SortDirection.DESC
        )
        assertEquals(
            listOf("Pop", "Rock", Song.UNKNOWN_GENRE),
            genres.map { it.name }
        )
    }

    @Test
    fun songsForBrowseProjection_albumsConcatenatesWithinAlbumOrder() {
        val list = listOf(
            Song(id = 1, uriString = "u1", title = "Z", artist = "A", album = "Beta", trackNumber = 2),
            Song(id = 2, uriString = "u2", title = "A", artist = "A", album = "Beta", trackNumber = 1),
            Song(id = 3, uriString = "u3", title = "X", artist = "B", album = "Alpha", trackNumber = 1)
        )
        val albums = useCase.extractAlbums(list)
        val projected = useCase.songsForBrowseProjection(
            filter = com.bestiapop.android.ui.state.LibraryBrowseFilter.ALBUMS,
            songs = list,
            albums = albums
        )
        // Albums sorted by display name: Alpha then Beta; within Beta by track
        assertEquals(listOf(3L, 2L, 1L), projected.map { it.id })
    }

    @Test
    fun songsForBrowseProjection_recent_sortsByLastPlayedDesc() {
        val list = listOf(
            Song(id = 1, uriString = "u1", title = "Old", lastPlayedAt = 10),
            Song(id = 2, uriString = "u2", title = "New", lastPlayedAt = 30),
            Song(id = 3, uriString = "u3", title = "Mid", lastPlayedAt = 20),
            Song(id = 4, uriString = "u4", title = "Never", lastPlayedAt = 0)
        )
        val projected = useCase.songsForBrowseProjection(
            filter = com.bestiapop.android.ui.state.LibraryBrowseFilter.RECENT,
            songs = list
        )
        assertEquals(listOf(2L, 3L, 1L), projected.map { it.id })
    }

    @Test
    fun filterCollapsedAlbumSongs_hidesRowsUnderCollapsedHeader() {
        val items = useCase.buildListItems(songs, LibraryViewMode.ALBUM_GROUPS)
        val filtered = filterCollapsedAlbumSongs(items, setOf("Opera"))

        assertEquals(3, filtered.size)
        assertTrue(filtered[0] is LibraryListItem.AlbumHeader)
        assertEquals("Hotel", (filtered[0] as LibraryListItem.AlbumHeader).albumName)
        assertTrue(filtered[1] is LibraryListItem.SongRow)
        assertTrue(filtered[2] is LibraryListItem.AlbumHeader)
        assertEquals("Opera", (filtered[2] as LibraryListItem.AlbumHeader).albumName)
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
    fun execute_sortsByDateAddedDescendingByDefault() {
        val list = listOf(
            Song(id = 1, uriString = "u1", title = "Old", dateAdded = 10),
            Song(id = 2, uriString = "u2", title = "New", dateAdded = 30),
            Song(id = 3, uriString = "u3", title = "Mid", dateAdded = 20)
        )
        val sorted = useCase.execute(list, "", SortOption.DATE_ADDED)
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun execute_respectsSortDirectionAscAndDesc() {
        val list = listOf(
            Song(id = 1, uriString = "u1", title = "Charlie", dateAdded = 10),
            Song(id = 2, uriString = "u2", title = "Alpha", dateAdded = 30),
            Song(id = 3, uriString = "u3", title = "Bravo", dateAdded = 20)
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            useCase.execute(list, "", SortOption.TITLE, SortDirection.ASC).map { it.id }
        )
        assertEquals(
            listOf(1L, 3L, 2L),
            useCase.execute(list, "", SortOption.TITLE, SortDirection.DESC).map { it.id }
        )
        assertEquals(
            listOf(1L, 3L, 2L),
            useCase.execute(list, "", SortOption.DATE_ADDED, SortDirection.ASC).map { it.id }
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            useCase.execute(list, "", SortOption.DATE_ADDED, SortDirection.DESC).map { it.id }
        )
    }

    @Test
    fun sortDirection_defaultFor_dateAddedDescOthersAsc() {
        assertEquals(SortDirection.DESC, SortDirection.defaultFor(SortOption.DATE_ADDED))
        assertEquals(SortDirection.ASC, SortDirection.defaultFor(SortOption.TITLE))
        assertEquals(SortDirection.ASC, SortDirection.defaultFor(SortOption.GENRE))
    }

    @Test
    fun sortEmphasisFor_mapsDominantFieldsAndSortKeyTrailing() {
        val song = Song(
            id = 1,
            uriString = "u1",
            title = "Song",
            artist = "Artist",
            album = "Album",
            genre = "Rock",
            durationMs = 125_000,
            trackNumber = 4,
            dateAdded = 1_700_000_000_000L
        )

        val byTitle = sortEmphasisFor(song, SortOption.TITLE)
        assertEquals("Song", byTitle.title)
        assertEquals("Artist • Album", byTitle.subtitle)
        assertEquals("2:05", byTitle.trailing)
        assertFalse(byTitle.trailingIsSortKey)

        val byArtist = sortEmphasisFor(song, SortOption.ARTIST)
        assertEquals("Artist", byArtist.title)
        assertEquals("Song • Album", byArtist.subtitle)
        assertFalse(byArtist.trailingIsSortKey)

        val byAlbum = sortEmphasisFor(song, SortOption.ALBUM)
        assertEquals("Album", byAlbum.title)
        assertEquals("Song • Artist", byAlbum.subtitle)
        assertEquals("4", byAlbum.trailing)
        assertFalse(byAlbum.trailingIsSortKey)

        val byGenre = sortEmphasisFor(song, SortOption.GENRE)
        assertEquals("Song", byGenre.title)
        assertEquals("Artist • Album", byGenre.subtitle)
        assertEquals("Rock", byGenre.trailing)
        assertTrue(byGenre.trailingIsSortKey)

        val byDate = sortEmphasisFor(song, SortOption.DATE_ADDED)
        assertEquals("Song", byDate.title)
        assertEquals("Artist • Album", byDate.subtitle)
        assertNotNull(byDate.trailing)
        assertTrue(byDate.trailingIsSortKey)

        val unknownGenre = sortEmphasisFor(
            song.copy(genre = "Unknown Genre"),
            SortOption.GENRE
        )
        assertEquals("2:05", unknownGenre.trailing)
        assertFalse(unknownGenre.trailingIsSortKey)
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
