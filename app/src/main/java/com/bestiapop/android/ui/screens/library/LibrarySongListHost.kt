package com.bestiapop.android.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.state.LibraryListItem

/** Shared song/album action callbacks for [LibrarySongList]. */
data class LibrarySongListActions(
    val onPlayNext: (Song) -> Unit,
    val onAddToQueue: (Song) -> Unit,
    val onStartRadio: (Song) -> Unit,
    val onAddToPlaylist: (Song) -> Unit,
    val onEditMetadata: (Song) -> Unit,
    val onIdentify: (Song) -> Unit = {},
    val onDeleteSong: (Song) -> Unit,
    val onPlayAlbum: (String, List<Song>) -> Unit,
    val onShuffleAlbum: (String, List<Song>) -> Unit,
    val onToggleSelect: (Song) -> Unit,
    val onToggleSelectAlbum: (List<Song>) -> Unit,
    val onAlbumLongClick: (List<Song>) -> Unit,
    val onToggleCollapseAlbum: (String) -> Unit,
    val onEditAlbum: (String) -> Unit = {},
    val onChangeAlbumCover: (String) -> Unit = {},
    val onOpenAlbum: (String) -> Unit = {}
)

/**
 * L3: fills common [LibrarySongList] args from [actions]; callers only pass deltas.
 * Raw [LibrarySongList] remains public for one-off layouts.
 */
@Composable
fun LibrarySongListHost(
    items: List<LibraryListItem>,
    currentSongId: Long?,
    isSelectionMode: Boolean,
    selectedSongIds: Set<Long>,
    collapsedAlbumNames: Set<String>,
    sortOption: SortOption,
    actions: LibrarySongListActions,
    onSongClick: (Song, Int) -> Unit,
    onSongLongClick: (Song) -> Unit = actions.onToggleSelect,
    emphasizeLastPlayed: Boolean = false,
    emptySubtitle: String? = null,
    emptyText: String = "No se encontraron canciones",
    modifier: Modifier = Modifier
) {
    LibrarySongList(
        items = items,
        currentSongId = currentSongId,
        isSelectionMode = isSelectionMode,
        selectedSongIds = selectedSongIds,
        collapsedAlbumNames = collapsedAlbumNames,
        sortOption = sortOption,
        emphasizeLastPlayed = emphasizeLastPlayed,
        emptySubtitle = emptySubtitle,
        emptyText = emptyText,
        onSongClick = onSongClick,
        onSongLongClick = onSongLongClick,
        onToggleSelect = actions.onToggleSelect,
        onPlayNext = actions.onPlayNext,
        onAddToQueue = actions.onAddToQueue,
        onStartRadio = actions.onStartRadio,
        onAddToPlaylist = actions.onAddToPlaylist,
        onEditMetadata = actions.onEditMetadata,
        onIdentify = actions.onIdentify,
        onDeleteSong = actions.onDeleteSong,
        onPlayAlbum = actions.onPlayAlbum,
        onShuffleAlbum = actions.onShuffleAlbum,
        onToggleSelectAlbum = actions.onToggleSelectAlbum,
        onAlbumLongClick = actions.onAlbumLongClick,
        onToggleCollapseAlbum = actions.onToggleCollapseAlbum,
        onEditAlbum = actions.onEditAlbum,
        onChangeAlbumCover = actions.onChangeAlbumCover,
        onOpenAlbum = actions.onOpenAlbum,
        modifier = modifier
    )
}

class SongActionDialogsController(
    val onEdit: (Song) -> Unit,
    val onAddToPlaylist: (Song) -> Unit,
    val onDelete: (Song) -> Unit,
    val onDeleteMany: (List<Song>) -> Unit
)

/**
 * L2: owns edit / playlist / delete dialog state and hosts [SongActionDialogsHost].
 */
@Composable
fun rememberSongActionDialogs(
    viewModel: MusicPlayerViewModel,
    playlists: List<Playlist>,
    onAfterPlaylistAdd: () -> Unit = {},
    onAfterDelete: (List<Song>) -> Unit = {},
    playlistSongIds: (Song) -> List<Long> = { listOf(it.id) },
    onSelectPlaylist: ((Playlist, Song) -> Unit)? = null
): SongActionDialogsController {
    var editingSong by remember { mutableStateOf<Song?>(null) }
    var songForPlaylistAddition by remember { mutableStateOf<Song?>(null) }
    var songsForDeletion by remember { mutableStateOf<List<Song>?>(null) }

    SongActionDialogsHost(
        editingSong = editingSong,
        songForPlaylistAddition = songForPlaylistAddition,
        songsForDeletion = songsForDeletion,
        playlists = playlists,
        viewModel = viewModel,
        onDismissEdit = { editingSong = null },
        onDismissPlaylist = { songForPlaylistAddition = null },
        onDismissDelete = { songsForDeletion = null },
        onAfterPlaylistAdd = onAfterPlaylistAdd,
        onAfterDelete = onAfterDelete,
        playlistSongIds = playlistSongIds,
        onSelectPlaylist = onSelectPlaylist
    )

    return remember {
        SongActionDialogsController(
            onEdit = { editingSong = it },
            onAddToPlaylist = { songForPlaylistAddition = it },
            onDelete = { songsForDeletion = listOf(it) },
            onDeleteMany = { songsForDeletion = it }
        )
    }
}

/**
 * L3: edit / add-to-playlist / delete dialogs for song actions.
 */
@Composable
fun SongActionDialogsHost(
    editingSong: Song?,
    songForPlaylistAddition: Song?,
    songsForDeletion: List<Song>?,
    playlists: List<Playlist>,
    viewModel: MusicPlayerViewModel,
    onDismissEdit: () -> Unit,
    onDismissPlaylist: () -> Unit,
    onDismissDelete: () -> Unit,
    onAfterPlaylistAdd: () -> Unit = {},
    onAfterDelete: (List<Song>) -> Unit = {},
    playlistSongIds: (Song) -> List<Long> = { listOf(it.id) },
    onSelectPlaylist: ((Playlist, Song) -> Unit)? = null
) {
    editingSong?.let { song ->
        EditSongMetadataDialog(
            song = song,
            onDismiss = onDismissEdit,
            onConfirm = { title, artist, album, genre, year, trackNumber ->
                viewModel.updateSongMetadata(song.id, title, artist, album, genre, year, trackNumber)
                onDismissEdit()
            }
        )
    }

    songForPlaylistAddition?.let { song ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = onDismissPlaylist,
            onSelectPlaylist = { playlist ->
                if (onSelectPlaylist != null) {
                    onSelectPlaylist(playlist, song)
                } else {
                    viewModel.addSongsToPlaylist(playlist.id, playlistSongIds(song))
                }
                onDismissPlaylist()
                onAfterPlaylistAdd()
            },
            onCreateNewPlaylist = onDismissPlaylist
        )
    }

    songsForDeletion?.let { targetSongs ->
        ConfirmDeleteSongsDialog(
            songCount = targetSongs.size,
            onDismiss = onDismissDelete,
            onConfirmDeleteFromApp = {
                viewModel.deleteSongsFromApp(targetSongs)
                onDismissDelete()
                onAfterDelete(targetSongs)
            },
            onConfirmDeleteFromDevice = {
                viewModel.deleteSongsFromDevice(targetSongs)
                onDismissDelete()
                onAfterDelete(targetSongs)
            }
        )
    }
}

/**
 * L3: album metadata editor. Merge confirmation is hosted once in [com.bestiapop.android.ui.screens.MainScreen]
 * (shared VM state). Cover-only picker stays at the call site.
 */
@Composable
fun AlbumEditDialogsHost(
    albumForEdit: Album?,
    viewModel: MusicPlayerViewModel,
    onDismissEdit: () -> Unit
) {
    albumForEdit?.let { album ->
        EditAlbumMetadataDialog(
            album = album,
            onDismiss = onDismissEdit,
            onSaveAlbumOnly = { displayName, artist, genre, year, artworkUri ->
                viewModel.requestSaveAlbumMetadata(
                    source = album,
                    displayName = displayName,
                    artist = artist,
                    genre = genre,
                    year = year,
                    artworkUri = artworkUri,
                    propagateToSongs = false
                )
                onDismissEdit()
            },
            onSaveAlbumAndSongs = { displayName, artist, genre, year, artworkUri ->
                viewModel.requestSaveAlbumMetadata(
                    source = album,
                    displayName = displayName,
                    artist = artist,
                    genre = genre,
                    year = year,
                    artworkUri = artworkUri,
                    propagateToSongs = true
                )
                onDismissEdit()
            }
        )
    }
}
