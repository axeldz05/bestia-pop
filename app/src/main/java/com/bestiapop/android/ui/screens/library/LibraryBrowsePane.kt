package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.FastScrollSettings
import com.bestiapop.android.domain.util.albumNamesMatch
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.SubmenuSwipeBox
import com.bestiapop.android.ui.screens.PlaylistsScreen
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import com.bestiapop.android.ui.state.LibraryListModel
import com.bestiapop.android.ui.state.LibraryViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Level 2: Bundled list states for all tabs in [LibraryBrowsePane].
 */
@Immutable
data class LibraryBrowseListStates(
    val songs: LazyListState,
    val albums: LazyListState,
    val artists: LazyListState,
    val genres: LazyListState,
    val playlists: LazyListState,
    val recent: LazyListState,
)

@Composable
fun rememberLibraryBrowseListStates(
    songs: LazyListState = rememberSaveable(key = "library_browse_songs", saver = LazyListState.Saver) { LazyListState() },
    albums: LazyListState = rememberSaveable(key = "library_browse_albums", saver = LazyListState.Saver) { LazyListState() },
    artists: LazyListState = rememberSaveable(key = "library_browse_artists", saver = LazyListState.Saver) { LazyListState() },
    genres: LazyListState = rememberSaveable(key = "library_browse_genres", saver = LazyListState.Saver) { LazyListState() },
    playlists: LazyListState = rememberSaveable(key = "library_browse_playlists", saver = LazyListState.Saver) { LazyListState() },
    recent: LazyListState = rememberSaveable(key = "library_browse_recent", saver = LazyListState.Saver) { LazyListState() },
): LibraryBrowseListStates =
    remember(songs, albums, artists, genres, playlists, recent) {
        LibraryBrowseListStates(
            songs = songs,
            albums = albums,
            artists = artists,
            genres = genres,
            playlists = playlists,
            recent = recent,
        )
    }

@Composable
fun LibraryBrowsePane(
    selectedAlbumName: String?,
    selectedArtistName: String?,
    selectedGenreName: String?,
    activeFilter: LibraryBrowseFilter,
    isPlaylistAdditionMode: Boolean,
    isMultiSelectMode: Boolean,
    songList: LibraryListModel,
    catalogLoaded: Boolean,
    viewModel: MusicPlayerViewModel,
    currentSongIdFlow: StateFlow<Long?>,
    selectedSongIds: Set<Long>,
    collapsedAlbumNames: Set<String>,
    sortOption: SortOption,
    sortDirection: SortDirection,
    actions: LibrarySongListActions,
    onToggleSelect: (Song) -> Unit,
    searchQuery: String,
    albumBrowseActions: AlbumBrowseActions,
    artistBrowseActions: AggregateBrowseActions<Artist>,
    genreBrowseActions: AggregateBrowseActions<GenreGroup>,
    fastScrollSettings: FastScrollSettings,
    listStates: LibraryBrowseListStates = rememberLibraryBrowseListStates(),
    onAddSongsToPlaylist: (Playlist) -> Unit = {},
    onAddManyToPlaylist: ((List<Song>) -> Unit)? = null,
) {
    when {
        selectedAlbumName != null || selectedArtistName != null || selectedGenreName != null -> {
            val gestureSettings by viewModel.submenuGestureSettings.collectAsStateWithLifecycle()
            SubmenuSwipeBox(
                settings = gestureSettings,
                onSwipeRight = { viewModel.popLibraryNested() },
                canSwipeBack = true,
            ) {
                if (isMultiSelectMode || isPlaylistAdditionMode) {
                    NestedLibraryBrowse(
                        selectedAlbumName = selectedAlbumName,
                        selectedArtistName = selectedArtistName,
                        selectedGenreName = selectedGenreName,
                        viewMode = if (selectedAlbumName != null) LibraryViewMode.FLAT else LibraryViewMode.ALBUM_GROUPS,
                        viewModel = viewModel,
                        currentSongIdFlow = currentSongIdFlow,
                        isSelectionMode = true,
                        selectedSongIds = selectedSongIds,
                        collapsedAlbumNames = collapsedAlbumNames,
                        sortOption = sortOption,
                        sortDirection = sortDirection,
                        actions = actions,
                        onToggleSelect = onToggleSelect,
                        fastScrollSettings = fastScrollSettings,
                    )
                } else {
                    when {
                        selectedAlbumName != null -> {
                            LibraryAlbumDetailView(
                                albumName = selectedAlbumName,
                                viewModel = viewModel,
                                actions = actions,
                                onBack = { viewModel.popLibraryNested() },
                            )
                        }

                        selectedArtistName != null -> {
                            LibraryArtistDetailView(
                                artistName = selectedArtistName,
                                viewModel = viewModel,
                                onBack = { viewModel.popLibraryNested() },
                            )
                        }

                        selectedGenreName != null -> {
                            LibraryGenreDetailView(
                                genreName = selectedGenreName,
                                viewModel = viewModel,
                                actions = actions,
                                onBack = { viewModel.popLibraryNested() },
                            )
                        }
                    }
                }
            }
        }

        activeFilter == LibraryBrowseFilter.SONGS || isPlaylistAdditionMode -> {
            val onLibrarySongsClick =
                remember(
                    isPlaylistAdditionMode,
                    isMultiSelectMode,
                    songList,
                    searchQuery,
                    onToggleSelect,
                ) {
                    { song: Song, index: Int ->
                        if (isPlaylistAdditionMode || isMultiSelectMode) {
                            onToggleSelect(song)
                        } else {
                            if (searchQuery.isNotBlank()) {
                                viewModel.addRecentSearch(searchQuery)
                            }
                            viewModel.playCollection(songList.songsVisual, index)
                        }
                    }
                }
            LibrarySongListHost(
                list = songList,
                currentSongId = null,
                currentSongIdFlow = currentSongIdFlow,
                isSelectionMode = isMultiSelectMode || isPlaylistAdditionMode,
                selectedSongIds = selectedSongIds,
                collapsedAlbumNames = collapsedAlbumNames,
                sortOption = sortOption,
                actions = actions,
                onSongClick = onLibrarySongsClick,
                loading = !catalogLoaded,
                fastScrollSettings = fastScrollSettings,
                listState = listStates.songs,
            )
        }

        activeFilter == LibraryBrowseFilter.RECENT -> {
            LibraryRecentTab(
                viewModel = viewModel,
                currentSongIdFlow = currentSongIdFlow,
                isSelectionMode = isMultiSelectMode,
                selectedSongIds = selectedSongIds,
                sortOption = SortOption.DATE_ADDED,
                actions = actions,
                searchQuery = searchQuery,
                onToggleSelect = onToggleSelect,
                fastScrollSettings = fastScrollSettings,
                listState = listStates.recent,
            )
        }

        activeFilter == LibraryBrowseFilter.ALBUMS -> {
            LibraryAlbumsTab(
                viewModel = viewModel,
                sortOption = sortOption,
                actions = albumBrowseActions,
                fastScrollSettings = fastScrollSettings,
                listState = listStates.albums,
            )
        }

        activeFilter == LibraryBrowseFilter.ARTISTS -> {
            LibraryArtistsTab(
                viewModel = viewModel,
                sortOption = sortOption,
                actions = artistBrowseActions,
                fastScrollSettings = fastScrollSettings,
                listState = listStates.artists,
            )
        }

        activeFilter == LibraryBrowseFilter.GENRES -> {
            LibraryGenresTab(
                viewModel = viewModel,
                sortOption = sortOption,
                actions = genreBrowseActions,
                fastScrollSettings = fastScrollSettings,
                listState = listStates.genres,
            )
        }

        activeFilter == LibraryBrowseFilter.PLAYLISTS -> {
            PlaylistsScreen(
                viewModel = viewModel,
                searchQuery = searchQuery,
                listState = listStates.playlists,
                onAddSongsRequest = onAddSongsToPlaylist,
            )
        }
    }
}

@Composable
fun NestedAlbumDisplayName(
    viewModel: MusicPlayerViewModel,
    albumKey: String,
): String {
    val albums by viewModel.libraryProjection.albums.collectAsStateWithLifecycle()
    return albums
        .firstOrNull {
            albumNamesMatch(it.name, albumKey) || albumNamesMatch(it.displayName, albumKey)
        }?.displayName ?: albumKey
}

fun songsForCurrentLibrarySelection(
    viewModel: MusicPlayerViewModel,
    songList: LibraryListModel,
    selectedAlbumName: String?,
    selectedArtistName: String?,
    selectedGenreName: String?,
    activeFilter: LibraryBrowseFilter,
    songsViewMode: LibraryViewMode,
): List<Song> {
    val songs = viewModel.libraryProjection.songs.value
    return when {
        selectedAlbumName != null || selectedArtistName != null || selectedGenreName != null -> {
            libraryNestedSongs(viewModel, songs, selectedAlbumName, selectedArtistName, selectedGenreName)
        }

        activeFilter == LibraryBrowseFilter.RECENT -> {
            viewModel.libraryProjection.recentSongs.value
        }

        activeFilter == LibraryBrowseFilter.SONGS -> {
            songList.songsVisual
        }

        else -> {
            viewModel.songsForBrowseProjection(
                filter = activeFilter,
                songs = songs,
                viewMode = songsViewMode,
                albums = viewModel.libraryProjection.albums.value,
                artists = viewModel.libraryProjection.artists.value,
                genres = viewModel.libraryProjection.genres.value,
            )
        }
    }
}

fun libraryNestedSongs(
    viewModel: MusicPlayerViewModel,
    songs: List<Song>,
    selectedAlbumName: String?,
    selectedArtistName: String?,
    selectedGenreName: String?,
): List<Song> =
    when {
        selectedAlbumName != null -> viewModel.songsForAlbum(songs, selectedAlbumName)
        selectedArtistName != null -> viewModel.songsForArtist(songs, selectedArtistName)
        selectedGenreName != null -> viewModel.songsForGenre(songs, selectedGenreName)
        else -> emptyList()
    }

/** Nested album/artist/genre detail: build list items + play in view order. */
@Composable
fun NestedLibraryBrowse(
    selectedAlbumName: String?,
    selectedArtistName: String?,
    selectedGenreName: String?,
    viewMode: LibraryViewMode,
    viewModel: MusicPlayerViewModel,
    currentSongIdFlow: StateFlow<Long?>,
    isSelectionMode: Boolean,
    selectedSongIds: Set<Long>,
    collapsedAlbumNames: Set<String>,
    sortOption: SortOption,
    sortDirection: SortDirection,
    actions: LibrarySongListActions,
    onToggleSelect: (Song) -> Unit,
    fastScrollSettings: FastScrollSettings,
) {
    val songs by viewModel.libraryProjection.songs.collectAsStateWithLifecycle()
    val browseSongs =
        remember(songs, selectedAlbumName, selectedArtistName, selectedGenreName) {
            libraryNestedSongs(viewModel, songs, selectedAlbumName, selectedArtistName, selectedGenreName)
        }
    // Keyed on albums so an album rename refreshes the group headers, which read the override name.
    val albums by viewModel.libraryProjection.albums.collectAsStateWithLifecycle()
    val list by produceState(
        initialValue = LibraryListModel.EMPTY,
        browseSongs,
        viewMode,
        albums,
        sortOption,
        sortDirection,
    ) {
        value =
            withContext(Dispatchers.Default) {
                viewModel.buildLibraryListModel(browseSongs, viewMode, sortOption, sortDirection)
            }
    }
    val playQueue =
        remember(list, viewMode, browseSongs) {
            if (viewMode == LibraryViewMode.ALBUM_GROUPS) {
                list.songsVisual
            } else {
                browseSongs
            }
        }
    val onSongClick =
        remember(isSelectionMode, playQueue, onToggleSelect) {
            { song: Song, index: Int ->
                if (isSelectionMode) {
                    onToggleSelect(song)
                } else {
                    viewModel.playCollection(playQueue, index)
                }
            }
        }
    val nestedListState =
        rememberSaveable(
            selectedAlbumName,
            selectedArtistName,
            selectedGenreName,
            saver = LazyListState.Saver,
        ) {
            LazyListState()
        }
    LibrarySongListHost(
        list = list,
        currentSongId = null,
        currentSongIdFlow = currentSongIdFlow,
        isSelectionMode = isSelectionMode,
        selectedSongIds = selectedSongIds,
        collapsedAlbumNames = collapsedAlbumNames,
        sortOption = sortOption,
        actions = actions,
        onSongClick = onSongClick,
        fastScrollSettings = fastScrollSettings,
        listState = nestedListState,
    )
}
