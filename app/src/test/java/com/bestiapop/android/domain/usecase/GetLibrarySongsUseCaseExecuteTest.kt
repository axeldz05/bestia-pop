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
    fun execute_inheritsAlbumArtwork_skippingGenericUnknownAlbum() {
        val songs = listOf(
            song(1, "A", "X", album = "Real Album", artworkUri = "file:///cover"),
            song(2, "B", "X", album = "Real Album", artworkUri = null),
            song(3, "C", "Y", album = "Unknown Album", artworkUri = "file:///other"),
            song(4, "D", "Y", album = "Unknown Album", artworkUri = null)
        )
        val result = useCase.execute(songs, "", SortOption.TITLE, SortDirection.ASC)
        assertEquals("file:///cover", result.first { it.id == 2L }.artworkUri)
        assertEquals(null, result.first { it.id == 4L }.artworkUri)
    }
}
