package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumCoverVsPlaylistCoverTest {

    private val useCase = GetLibrarySongsUseCase()

    @Test
    fun albumOverride_changesAlbumArtworkWithoutTouchingSiblingSongFields() {
        val songs = listOf(
            Song(
                id = 1L,
                uriString = "file:///1",
                title = "A",
                artist = "Band",
                album = "LP",
                artworkUri = "file:///song-art"
            ),
            Song(
                id = 2L,
                uriString = "file:///2",
                title = "B",
                artist = "Band",
                album = "LP",
                artworkUri = "file:///song-art"
            )
        )
        val override = AlbumOverride(
            albumKey = "LP",
            displayName = "LP Remaster",
            artworkUri = "file:///album-override"
        )
        val albums = useCase.extractAlbums(
            songs = songs,
            overrides = mapOf("LP" to override),
            sortOption = SortOption.ALBUM,
            sortDirection = SortDirection.ASC
        )
        assertEquals(1, albums.size)
        assertEquals("LP Remaster", albums.single().displayName)
        assertEquals("file:///album-override", albums.single().artworkUri)
        // Songs themselves are unchanged by extractAlbums (override is aggregate-only).
        assertEquals("file:///song-art", songs[0].artworkUri)
        assertEquals("LP", songs[0].album)
    }
}
