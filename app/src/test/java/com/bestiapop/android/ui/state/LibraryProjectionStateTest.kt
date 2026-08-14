package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryProjectionStateTest {

    @Test
    fun projections_shareTypedInputs_andKeepOverridesAndPhotosIndependent() = runTest {
        val rawSongs = MutableStateFlow(emptyList<Song>())
        val overrides = MutableStateFlow(emptyList<AlbumOverride>())
        val query = MutableStateFlow("")
        val sort = MutableStateFlow(SortOption.TITLE)
        val direction = MutableStateFlow(SortDirection.ASC)
        val photos = MutableStateFlow(emptyMap<String, String>())
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val state = LibraryProjectionState(
            scope = backgroundScope,
            rawSongs = rawSongs,
            albumOverrides = overrides,
            searchQuery = query,
            sortOption = sort,
            sortDirection = direction,
            artistPhotos = photos,
            useCase = GetLibrarySongsUseCase(),
            projectionDispatcher = dispatcher
        )
        val collector = backgroundScope.launch(dispatcher) {
            combine(state.songs, state.albums, state.artists, state.genres) { _, _, _, _ -> }
                .collect {}
        }

        rawSongs.value = listOf(
            song(1, "Beta", "Queen", "Opera", "Rock"),
            song(2, "Alpha", "Queen", "Opera", "Rock")
        )
        runCurrent()

        assertEquals(listOf("Alpha", "Beta"), state.songs.value.map(Song::title))
        assertEquals(listOf("Opera"), state.albums.value.map { it.name })
        assertEquals(listOf("Queen"), state.artists.value.map { it.name })
        assertEquals(listOf("Rock"), state.genres.value.map { it.name })

        overrides.value = listOf(
            AlbumOverride(albumKey = "Opera", displayName = "A Night at the Opera")
        )
        photos.value = mapOf("Queen" to "file:///queen.jpg")
        runCurrent()

        assertEquals("A Night at the Opera", state.albums.value.single().displayName)
        assertEquals("file:///queen.jpg", state.artists.value.single().photoUri)

        collector.cancel()
    }

    private fun song(
        id: Long,
        title: String,
        artist: String,
        album: String,
        genre: String
    ): Song = Song(
        id = id,
        uriString = "file:///$id.mp3",
        title = title,
        artist = artist,
        album = album,
        genre = genre
    )
}
