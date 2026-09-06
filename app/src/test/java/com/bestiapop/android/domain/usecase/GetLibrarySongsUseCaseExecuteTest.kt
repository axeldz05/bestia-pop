package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetLibrarySongsUseCaseExecuteTest {

    private val useCase = GetLibrarySongsUseCase()

    private fun song(
        id: Long,
        title: String,
        artist: String,
        album: String = "Album",
        genre: String = "Rock",
        dateAdded: Long = id,
        artworkUri: String? = null
    ) = Song(
        id = id,
        uriString = "file:///$id",
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        dateAdded = dateAdded,
        artworkUri = artworkUri
    )

    @Test
    fun execute_filtersByTitleArtistAlbumGenre_normalized() {
        val songs = listOf(
            song(1, "Canción", "Artist", "Disco", "Pop"),
            song(2, "Other", "Nova", "Night", "Electronic"),
            song(3, "X", "Y", "Z", "Jazz")
        )

        assertEquals(
            listOf(1L),
            useCase.execute(songs, "cancion", SortOption.TITLE).map { it.id }
        )
        assertEquals(
            listOf(2L),
            useCase.execute(songs, "nova", SortOption.TITLE).map { it.id }
        )
        assertEquals(
            listOf(2L),
            useCase.execute(songs, "night", SortOption.TITLE).map { it.id }
        )
        assertEquals(
            listOf(3L),
            useCase.execute(songs, "jazz", SortOption.TITLE).map { it.id }
        )
        assertTrue(useCase.execute(songs, "!!!", SortOption.TITLE).isEmpty())
    }

    @Test
    fun execute_findsEitherScriptOfBilingualTitle() {
        val songs = listOf(
            song(1, "夜鷹 (Yodaka)", "きのこ帝国", "eureka"),
            song(2, "Other", "Nova", "Night")
        )
        assertEquals(
            listOf(1L),
            useCase.execute(songs, "yodaka", SortOption.TITLE).map { it.id }
        )
        assertEquals(
            listOf(1L),
            useCase.execute(songs, "夜鷹", SortOption.TITLE).map { it.id }
        )
    }

    @Test
    fun execute_usesPrecomputedHaystackWhenProvided() {
        val songs = listOf(
            song(1, "Canción", "Artist", "Disco", "Pop"),
            song(2, "Other", "Nova", "Night", "Electronic")
        )
        val haystack = songs.associate { it.id to useCase.searchHaystack(it) }
        assertEquals(
            listOf(1L),
            useCase.execute(songs, "cancion", SortOption.TITLE, haystackById = haystack).map { it.id }
        )
    }

    @Test
    fun execute_sortDirection_andDateAddedDefaultDesc() {
        val songs = listOf(
            song(1, "B", "A", dateAdded = 10),
            song(2, "A", "B", dateAdded = 20)
        )
        assertEquals(
            listOf(2L, 1L),
            useCase.execute(songs, "", SortOption.DATE_ADDED, SortDirection.DESC).map { it.id }
        )
        assertEquals(
            listOf(2L, 1L),
            useCase.execute(songs, "", SortOption.TITLE, SortDirection.ASC).map { it.id }
        )
        assertEquals(
            listOf(1L, 2L),
            useCase.execute(songs, "", SortOption.TITLE, SortDirection.DESC).map { it.id }
        )
    }

    @Test
    fun projectCatalog_inheritsAlbumArtwork_skippingGenericUnknownAlbum() {
        val songs = listOf(
            song(1, "A", "X", album = "Real Album", artworkUri = "file:///cover"),
            song(2, "B", "X", album = "Real Album", artworkUri = null),
            song(3, "C", "Y", album = "Unknown Album", artworkUri = "file:///other"),
            song(4, "D", "Y", album = "Unknown Album", artworkUri = null)
        )
        val result = useCase.projectCatalog(
            songs,
            "",
            SortOption.TITLE,
            SortDirection.ASC,
            emptyMap(),
            com.bestiapop.android.ui.state.LibraryViewMode.FLAT
        )
        assertEquals(null, result.songs.first { it.id == 2L }.artworkUri)
        assertEquals(
            "file:///cover",
            result.list.toListItems().filterIsInstance<com.bestiapop.android.ui.state.LibraryListItem.SongRow>()
                .first { it.song.id == 2L }.artworkUri
        )
        assertEquals(
            null,
            result.list.toListItems().filterIsInstance<com.bestiapop.android.ui.state.LibraryListItem.SongRow>()
                .first { it.song.id == 4L }.artworkUri
        )
    }

    @Test
    fun projectCatalog_albumGroups_visualOrderIsAlbumThenTrack_notGlobalTitle() {
        val songs = listOf(
            song(1, "Zebra", "B", album = "Beta"),
            song(2, "Alpha", "A", album = "Alpha")
        ).mapIndexed { index, item ->
            if (index == 0) item.copy(trackNumber = 1) else item.copy(trackNumber = 1)
        }
        val result = useCase.projectCatalog(
            songs,
            "",
            SortOption.TITLE,
            SortDirection.ASC,
            emptyMap(),
            com.bestiapop.android.ui.state.LibraryViewMode.ALBUM_GROUPS
        )
        assertEquals(listOf(2L, 1L), result.list.songsVisual.map { it.id })
        assertEquals(listOf(1L, 2L), result.songs.map { it.id })
        assertEquals(listOf("Alpha", "Beta"), result.albums.map { it.name })
        assertEquals(listOf(2L), result.list.segments.first().songIds)
    }

    @Test
    fun execute_filtersByMultipleTokensRegardlessOfWordOrder() {
        val songs = listOf(
            song(1, "Creep", "Radiohead", "Pablo Honey", "Rock"),
            song(2, "Karma Police", "Radiohead", "OK Computer", "Rock"),
            song(3, "Creep", "Stone Temple Pilots", "Core", "Grunge")
        )

        // Title and artist in inverted order
        assertEquals(
            listOf(1L),
            useCase.execute(songs, "Radiohead Creep", SortOption.TITLE).map { it.id }
        )
        // Artist and album in different order
        assertEquals(
            listOf(2L),
            useCase.execute(songs, "Computer Radiohead", SortOption.TITLE).map { it.id }
        )
    }
}
