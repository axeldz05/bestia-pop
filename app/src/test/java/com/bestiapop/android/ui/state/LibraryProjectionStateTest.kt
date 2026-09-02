package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun projections_skipRawSongEmissionsWhileOverlayOpen() = runTest {
        val rawSongs = MutableStateFlow(emptyList<Song>())
        val overlayOpen = MutableStateFlow(false)
        val query = MutableStateFlow("")
        val sort = MutableStateFlow(SortOption.TITLE)
        val direction = MutableStateFlow(SortDirection.ASC)
        val photos = MutableStateFlow(emptyMap<String, String>())
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val state = LibraryProjectionState(
            scope = backgroundScope,
            rawSongs = rawSongs,
            albumOverrides = MutableStateFlow(emptyList()),
            searchQuery = query,
            sortOption = sort,
            sortDirection = direction,
            artistPhotos = photos,
            useCase = GetLibrarySongsUseCase(),
            overlayOpen = overlayOpen,
            projectionDispatcher = dispatcher
        )
        val collector = backgroundScope.launch(dispatcher) {
            state.songs.collect {}
        }

        rawSongs.value = listOf(
            song(1, "Beta", "Queen", "Opera", "Rock"),
            song(2, "Alpha", "Queen", "Opera", "Rock")
        )
        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), state.songs.value.map(Song::title))

        overlayOpen.value = true
        runCurrent()
        rawSongs.value = listOf(song(3, "Gamma", "Muse", "Absolution", "Rock"))
        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), state.songs.value.map(Song::title))

        overlayOpen.value = false
        runCurrent()
        assertEquals(listOf("Gamma"), state.songs.value.map(Song::title))

        collector.cancel()
    }

    @Test
    fun songListItemsAndRecents_computeFromProjection_andSkipWhileOverlayOpen() = runTest {
        val rawSongs = MutableStateFlow(emptyList<Song>())
        val overlayOpen = MutableStateFlow(false)
        val viewMode = MutableStateFlow(LibraryViewMode.ALBUM_GROUPS)
        val browseFilter = MutableStateFlow(LibraryBrowseFilter.SONGS)
        val query = MutableStateFlow("")
        val sort = MutableStateFlow(SortOption.TITLE)
        val direction = MutableStateFlow(SortDirection.ASC)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val state = LibraryProjectionState(
            scope = backgroundScope,
            rawSongs = rawSongs,
            albumOverrides = MutableStateFlow(emptyList()),
            searchQuery = query,
            sortOption = sort,
            sortDirection = direction,
            artistPhotos = MutableStateFlow(emptyMap()),
            useCase = GetLibrarySongsUseCase(),
            overlayOpen = overlayOpen,
            viewMode = viewMode,
            browseFilter = browseFilter,
            projectionDispatcher = dispatcher
        )
        val collector = backgroundScope.launch(dispatcher) {
            combine(state.songs, state.songList, state.recentSongs, state.recentList) { _, _, _, _ -> }
                .collect {}
        }

        rawSongs.value = listOf(
            song(1, "Beta", "Queen", "Opera", "Rock").copy(lastPlayedAt = 10L),
            song(2, "Alpha", "Queen", "Opera", "Rock").copy(lastPlayedAt = 20L)
        )
        runCurrent()
        assertTrue(state.songList.value.segments.isNotEmpty())
        assertEquals(listOf("Alpha", "Beta"), state.recentSongs.value.map(Song::title))
        assertEquals(
            listOf("Alpha", "Beta"),
            state.recentList.value.songsVisual.map(Song::title)
        )

        overlayOpen.value = true
        runCurrent()
        rawSongs.value = listOf(
            song(3, "Gamma", "Muse", "Absolution", "Rock").copy(lastPlayedAt = 99L)
        )
        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), state.recentSongs.value.map(Song::title))
        assertTrue(state.songList.value.segments.isNotEmpty())

        overlayOpen.value = false
        runCurrent()
        assertEquals(listOf("Gamma"), state.recentSongs.value.map(Song::title))

        collector.cancel()
    }

    @Test
    fun catalogProjections_ignoreLastPlayedOnlyUpdates() = runTest {
        val rawSongs = MutableStateFlow(emptyList<Song>())
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val state = LibraryProjectionState(
            scope = backgroundScope,
            rawSongs = rawSongs,
            albumOverrides = MutableStateFlow(emptyList()),
            searchQuery = MutableStateFlow(""),
            sortOption = MutableStateFlow(SortOption.TITLE),
            sortDirection = MutableStateFlow(SortDirection.ASC),
            artistPhotos = MutableStateFlow(emptyMap()),
            useCase = GetLibrarySongsUseCase(),
            projectionDispatcher = dispatcher
        )
        val collector = backgroundScope.launch(dispatcher) {
            combine(state.songs, state.songList, state.recentSongs) { _, _, _ -> }
                .collect {}
        }

        val initial = listOf(
            song(1, "Beta", "Queen", "Opera", "Rock"),
            song(2, "Alpha", "Queen", "Opera", "Rock")
        )
        rawSongs.value = initial
        runCurrent()
        val catalogSongs = state.songs.value
        val list = state.songList.value
        assertTrue(state.recentSongs.value.isEmpty())

        rawSongs.value = listOf(
            initial[0].copy(lastPlayedAt = 50L),
            initial[1]
        )
        runCurrent()
        assertEquals(catalogSongs, state.songs.value)
        assertEquals(list, state.songList.value)
        assertEquals(listOf("Beta"), state.recentSongs.value.map(Song::title))

        collector.cancel()
    }

    @Test
    fun recentsFollowPlayStats_withoutRebuildingCatalog() = runTest {
        val rawSongs = MutableStateFlow(emptyList<Song>())
        val playStats = MutableStateFlow(emptyMap<Long, Long>())
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val state = LibraryProjectionState(
            scope = backgroundScope,
            rawSongs = rawSongs,
            albumOverrides = MutableStateFlow(emptyList()),
            searchQuery = MutableStateFlow(""),
            sortOption = MutableStateFlow(SortOption.TITLE),
            sortDirection = MutableStateFlow(SortDirection.ASC),
            artistPhotos = MutableStateFlow(emptyMap()),
            useCase = GetLibrarySongsUseCase(),
            playStats = playStats,
            projectionDispatcher = dispatcher
        )
        val collector = backgroundScope.launch(dispatcher) {
            combine(state.songs, state.songList, state.recentSongs) { _, _, _ -> }
                .collect {}
        }

        val initial = listOf(
            song(1, "Beta", "Queen", "Opera", "Rock"),
            song(2, "Alpha", "Queen", "Opera", "Rock")
        )
        rawSongs.value = initial
        runCurrent()
        val catalogSongs = state.songs.value
        val list = state.songList.value
        assertTrue(state.recentSongs.value.isEmpty())

        playStats.value = mapOf(1L to 50L)
        runCurrent()
        assertEquals(catalogSongs, state.songs.value)
        assertEquals(list, state.songList.value)
        assertEquals(listOf("Beta"), state.recentSongs.value.map(Song::title))

        collector.cancel()
    }

    @Test
    fun searchQuery_debouncesNonBlankBeforeReprojecting() = runTest {
        val rawSongs = MutableStateFlow(emptyList<Song>())
        val query = MutableStateFlow("")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val state = LibraryProjectionState(
            scope = backgroundScope,
            rawSongs = rawSongs,
            albumOverrides = MutableStateFlow(emptyList()),
            searchQuery = query,
            sortOption = MutableStateFlow(SortOption.TITLE),
            sortDirection = MutableStateFlow(SortDirection.ASC),
            artistPhotos = MutableStateFlow(emptyMap()),
            useCase = GetLibrarySongsUseCase(),
            projectionDispatcher = dispatcher
        )
        val collector = backgroundScope.launch(dispatcher) {
            state.songs.collect {}
        }

        rawSongs.value = listOf(
            song(1, "Alpha", "Queen", "Opera", "Rock"),
            song(2, "Beta", "Queen", "Opera", "Rock")
        )
        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), state.songs.value.map(Song::title))

        query.value = "alp"
        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), state.songs.value.map(Song::title))
        testScheduler.advanceTimeBy(LIBRARY_SEARCH_DEBOUNCE_MS)
        runCurrent()
        assertEquals(listOf("Alpha"), state.songs.value.map(Song::title))

        query.value = ""
        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), state.songs.value.map(Song::title))

        collector.cancel()
    }

    @Test
    fun projectCatalog_albumGroups_keepsPoolUnsortedByTitle() = runTest {
        val rawSongs = MutableStateFlow(emptyList<Song>())
        val viewMode = MutableStateFlow(LibraryViewMode.ALBUM_GROUPS)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val state = LibraryProjectionState(
            scope = backgroundScope,
            rawSongs = rawSongs,
            albumOverrides = MutableStateFlow(emptyList()),
            searchQuery = MutableStateFlow(""),
            sortOption = MutableStateFlow(SortOption.TITLE),
            sortDirection = MutableStateFlow(SortDirection.ASC),
            artistPhotos = MutableStateFlow(emptyMap()),
            useCase = GetLibrarySongsUseCase(),
            viewMode = viewMode,
            projectionDispatcher = dispatcher
        )
        val collector = backgroundScope.launch(dispatcher) {
            combine(state.songs, state.songList, state.albums) { _, _, _ -> }.collect {}
        }

        rawSongs.value = listOf(
            song(1, "Zebra", "B", "Beta", "Rock"),
            song(2, "Alpha", "A", "Alpha", "Rock")
        )
        runCurrent()

        assertEquals(listOf("Zebra", "Alpha"), state.songs.value.map(Song::title))
        assertEquals(listOf("Alpha", "Zebra"), state.songList.value.songsVisual.map(Song::title))
        assertEquals(listOf("Alpha", "Beta"), state.albums.value.map { it.name })
        assertTrue(state.catalogLoaded.value)

        collector.cancel()
    }

    @Test
    fun catalogLoaded_staysFalseUntilRawSongsEmit() = runTest {
        val rawSongs = MutableSharedFlow<List<Song>>(replay = 0)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val state = LibraryProjectionState(
            scope = backgroundScope,
            rawSongs = rawSongs,
            albumOverrides = MutableStateFlow(emptyList()),
            searchQuery = MutableStateFlow(""),
            sortOption = MutableStateFlow(SortOption.TITLE),
            sortDirection = MutableStateFlow(SortDirection.ASC),
            artistPhotos = MutableStateFlow(emptyMap()),
            useCase = GetLibrarySongsUseCase(),
            projectionDispatcher = dispatcher
        )
        val collector = backgroundScope.launch(dispatcher) {
            state.catalogLoaded.collect {}
        }
        runCurrent()
        assertFalse(state.catalogLoaded.value)

        rawSongs.emit(emptyList())
        runCurrent()
        assertTrue(state.catalogLoaded.value)

        collector.cancel()
    }

    @Test
    fun catalogLoaded_waitsForPrefsReady() = runTest {
        val rawSongs = MutableStateFlow(emptyList<Song>())
        val prefsReady = MutableStateFlow(false)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val state = LibraryProjectionState(
            scope = backgroundScope,
            rawSongs = rawSongs,
            albumOverrides = MutableStateFlow(emptyList()),
            searchQuery = MutableStateFlow(""),
            sortOption = MutableStateFlow(SortOption.TITLE),
            sortDirection = MutableStateFlow(SortDirection.ASC),
            artistPhotos = MutableStateFlow(emptyMap()),
            useCase = GetLibrarySongsUseCase(),
            prefsReady = prefsReady,
            projectionDispatcher = dispatcher
        )
        val collector = backgroundScope.launch(dispatcher) {
            combine(state.catalogLoaded, state.songs) { _, _ -> }.collect {}
        }
        rawSongs.value = listOf(song(1, "Alpha", "Queen", "Opera", "Rock"))
        runCurrent()
        assertFalse(state.catalogLoaded.value)
        assertTrue(state.songs.value.isEmpty())

        prefsReady.value = true
        runCurrent()
        assertTrue(state.catalogLoaded.value)
        assertEquals(listOf("Alpha"), state.songs.value.map(Song::title))

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
