package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseLocalLibraryUseCaseTest {

    private val useCase = BrowseLocalLibraryUseCase()
    private val songs = listOf(
        Song(
            id = 1L,
            uriString = "/music/second.mp3",
            title = "Segunda",
            artist = "Björk",
            album = "Original",
            trackNumber = 2
        ),
        Song(
            id = 2L,
            uriString = "/music/first.mp3",
            title = "Árbol",
            artist = "Björk",
            album = "Original",
            trackNumber = 1
        ),
        Song(
            id = 3L,
            uriString = "/music/other.mp3",
            title = "Otra",
            artist = "Otro",
            album = "Álbum B"
        )
    )

    @Test
    fun snapshot_appliesAlbumOverrideAndStableOrdering() {
        val snapshot = useCase.snapshot(
            songs = songs,
            overrides = listOf(AlbumOverride("Original", "Nombre visible")),
            playlists = listOf(
                Playlist(id = 2L, name = "Zeta"),
                Playlist(id = 1L, name = "Alfa")
            )
        )

        assertEquals(
            "Nombre visible",
            snapshot.albums.first { it.name == "Original" }.displayName
        )
        assertEquals(listOf("Alfa", "Zeta"), snapshot.playlists.map(Playlist::name))
        assertEquals(
            listOf("Árbol", "Segunda"),
            useCase.songsForAlbum(snapshot, "Original").map(Song::title)
        )
    }

    @Test
    fun searchFoldsDiacriticsAndPaginationIsBounded() {
        val snapshot = useCase.snapshot(songs, emptyList(), emptyList())

        assertEquals(setOf("Árbol", "Segunda"), useCase.search(snapshot, "bjork").map(Song::title).toSet())
        assertEquals(listOf(3, 4), useCase.page(listOf(1, 2, 3, 4), page = 1, pageSize = 2))
        assertEquals(emptyList<Int>(), useCase.page(listOf(1), page = 50, pageSize = 1000))
    }
}
