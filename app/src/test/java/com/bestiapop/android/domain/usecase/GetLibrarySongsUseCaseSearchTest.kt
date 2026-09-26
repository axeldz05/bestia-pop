package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption
import org.junit.Assert.assertEquals
import org.junit.Test

class GetLibrarySongsUseCaseSearchTest {
    private val useCase = GetLibrarySongsUseCase()

    private fun song(
        id: Long,
        title: String,
        artist: String,
        album: String = "Album",
        genre: String = "Rock",
        year: Int = 0,
        lyrics: String? = null,
    ) = Song(
        id = id,
        uriString = "file:///$id",
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        year = year,
        lyrics = lyrics,
    )

    @Test
    fun execute_decadeSearch_70s() {
        val songs =
            listOf(
                song(1, "Bohemian Rhapsody", "Queen", year = 1975),
                song(2, "Radio Ga Ga", "Queen", year = 1984),
                song(3, "Innuendo", "Queen", year = 1991),
            )

        val results70 = useCase.execute(songs, "70", SortOption.TITLE)
        assertEquals(listOf(1L), results70.map { it.id })

        val results80 = useCase.execute(songs, "los 80", SortOption.TITLE)
        assertEquals(listOf(2L), results80.map { it.id })
    }

    @Test
    fun execute_multiDecadeSearch_70y80() {
        val songs =
            listOf(
                song(1, "Song 70", "Artist A", year = 1978),
                song(2, "Song 80", "Artist B", year = 1985),
                song(3, "Song 90", "Artist C", year = 1995),
            )

        val results = useCase.execute(songs, "70 y 80", SortOption.TITLE)
        assertEquals(listOf(1L, 2L), results.map { it.id })
    }

    @Test
    fun execute_mixedQuery_artistAndDecade() {
        val songs =
            listOf(
                song(1, "Song 70", "Queen", year = 1975),
                song(2, "Song 80", "Queen", year = 1984),
                song(3, "Song 70 Other", "David Bowie", year = 1975),
            )

        val results = useCase.execute(songs, "queen 70s", SortOption.TITLE)
        assertEquals(listOf(1L), results.map { it.id })
    }

    @Test
    fun execute_bilingualGenreSearch() {
        val songs =
            listOf(
                song(1, "Symphony No. 5", "Beethoven", genre = "Classical"),
                song(2, "Around the World", "Daft Punk", genre = "Electronic"),
                song(3, "Main Theme", "John Williams", genre = "Soundtrack"),
            )

        val classicalResults = useCase.execute(songs, "musica clasica", SortOption.TITLE)
        assertEquals(listOf(1L), classicalResults.map { it.id })

        val electronicResults = useCase.execute(songs, "electronica", SortOption.TITLE)
        assertEquals(listOf(2L), electronicResults.map { it.id })

        val soundtrackResults = useCase.execute(songs, "banda sonora", SortOption.TITLE)
        assertEquals(listOf(3L), soundtrackResults.map { it.id })
    }

    @Test
    fun execute_phoneticTypoSearch() {
        val songs =
            listOf(
                song(1, "Sweet Child O' Mine", "Guns N' Roses"),
                song(2, "Another One Bites The Dust", "Queen"),
                song(3, "Back In Black", "AC/DC"),
            )

        val gunsResults = useCase.execute(songs, "gans an rous", SortOption.TITLE)
        assertEquals(listOf(1L), gunsResults.map { it.id })

        val queenResults = useCase.execute(songs, "kueen", SortOption.TITLE)
        assertEquals(listOf(2L), queenResults.map { it.id })
    }

    @Test
    fun execute_lyricsSearchFallback() {
        val songs =
            listOf(
                song(
                    id = 1,
                    title = "Track A",
                    artist = "Unknown",
                    lyrics = "Mama, just killed a man, put a gun against his head, pulled my trigger, now he's dead",
                ),
                song(
                    id = 2,
                    title = "Track B",
                    artist = "Unknown",
                    lyrics = "She's a killer queen, gunpowder, gelatin, dynamite with a laser beam",
                ),
            )

        val results = useCase.execute(songs, "mama just killed a man", SortOption.TITLE)
        assertEquals(listOf(1L), results.map { it.id })
    }
}
