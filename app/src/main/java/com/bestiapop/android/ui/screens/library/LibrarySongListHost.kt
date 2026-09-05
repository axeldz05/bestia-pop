package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.FastScrollSettings
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.state.LibraryListModel
import kotlinx.coroutines.flow.StateFlow

/** Shared song/album action callbacks for [LibrarySongList]. */
@Immutable
data class LibrarySongListActions(
    val onPlayNext: (Song) -> Unit,
    val onAddToQueue: (Song) -> Unit,
    val onStartRadio: (Song) -> Unit,
    val onAddToPlaylist: (Song) -> Unit,
    val onEditMetadata: (Song) -> Unit,
    val onEditLyrics: (Song) -> Unit,
    val onIdentify: (Song) -> Unit = {},
    val onDeleteSong: (Song) -> Unit,
    val onPlayAlbum: (String, List<Long>) -> Unit,
    val onShuffleAlbum: (String, List<Long>) -> Unit,
    val onToggleSelect: (Song) -> Unit = {},
    val onToggleSelectAlbum: (List<Long>) -> Unit = {},
    val onAlbumLongClick: (List<Long>) -> Unit = {},
    val onToggleCollapseAlbum: (String) -> Unit = {},
    val onEditAlbum: (String) -> Unit = {},
    val onChangeAlbumCover: (String) -> Unit = {},
    val onIdentifyAlbum: (String) -> Unit = {},
    val onOpenAlbum: (String) -> Unit = {}
)

/**
 * L3: fills common [LibrarySongList] args from [actions]; callers only pass deltas.
 * Raw [LibrarySongList] remains public for one-off layouts.
 */
@Composable
fun LibrarySongListHost(
    list: LibraryListModel,
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
    loading: Boolean = false,
    fastScrollSettings: FastScrollSettings = FastScrollSettings(),
    currentSongIdFlow: StateFlow<Long?>? = null,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    LibrarySongList(
        list = list,
        currentSongId = currentSongId,
        currentSongIdFlow = currentSongIdFlow,
        isSelectionMode = isSelectionMode,
        selectedSongIds = selectedSongIds,
        collapsedAlbumNames = collapsedAlbumNames,
        sortOption = sortOption,
        emphasizeLastPlayed = emphasizeLastPlayed,
        emptySubtitle = emptySubtitle,
        emptyText = emptyText,
        loading = loading,
        fastScrollSettings = fastScrollSettings,
        onSongClick = onSongClick,
        onSongLongClick = onSongLongClick,
        onToggleSelect = actions.onToggleSelect,
        onPlayNext = actions.onPlayNext,
        onAddToQueue = actions.onAddToQueue,
        onStartRadio = actions.onStartRadio,
        onAddToPlaylist = actions.onAddToPlaylist,
        onEditMetadata = actions.onEditMetadata,
        onEditLyrics = actions.onEditLyrics,
        onIdentify = actions.onIdentify,
        onDeleteSong = actions.onDeleteSong,
        onPlayAlbum = actions.onPlayAlbum,
        onShuffleAlbum = actions.onShuffleAlbum,
        onToggleSelectAlbum = actions.onToggleSelectAlbum,
        onAlbumLongClick = actions.onAlbumLongClick,
        onToggleCollapseAlbum = actions.onToggleCollapseAlbum,
        onEditAlbum = actions.onEditAlbum,
        onChangeAlbumCover = actions.onChangeAlbumCover,
        onIdentifyAlbum = actions.onIdentifyAlbum,
        onOpenAlbum = actions.onOpenAlbum,
        listState = listState,
        modifier = modifier
    )
}

class SongActionDialogsController(
    val onEdit: (Song) -> Unit,
    val onEditLyrics: (Song) -> Unit,
    val onAddToPlaylist: (Song) -> Unit,
    val onAddManyToPlaylist: (List<Song>) -> Unit = { songs -> songs.firstOrNull()?.let(onAddToPlaylist) },
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
    var editingLyricsSong by remember { mutableStateOf<Song?>(null) }
    var songsForPlaylistAddition by remember { mutableStateOf<List<Song>?>(null) }
    var songsForDeletion by remember { mutableStateOf<List<Song>?>(null) }

    SongActionDialogsHost(
        editingSong = editingSong,
        editingLyricsSong = editingLyricsSong,
        songsForPlaylistAddition = songsForPlaylistAddition,
        songsForDeletion = songsForDeletion,
        playlists = playlists,
        viewModel = viewModel,
        onDismissEdit = { editingSong = null },
        onDismissLyrics = { editingLyricsSong = null },
        onDismissPlaylist = { songsForPlaylistAddition = null },
        onDismissDelete = { songsForDeletion = null },
        onAfterPlaylistAdd = onAfterPlaylistAdd,
        onAfterDelete = onAfterDelete,
        playlistSongIds = playlistSongIds,
        onSelectPlaylist = onSelectPlaylist
    )

    return remember {
        SongActionDialogsController(
            onEdit = { editingSong = it },
            onEditLyrics = { editingLyricsSong = it },
            onAddToPlaylist = { songsForPlaylistAddition = listOf(it) },
            onAddManyToPlaylist = { songsForPlaylistAddition = it },
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
    editingLyricsSong: Song?,
    songForPlaylistAddition: Song? = null,
    songsForPlaylistAddition: List<Song>? = songForPlaylistAddition?.let { listOf(it) },
    songsForDeletion: List<Song>?,
    playlists: List<Playlist>,
    viewModel: MusicPlayerViewModel,
    onDismissEdit: () -> Unit,
    onDismissLyrics: () -> Unit,
    onDismissPlaylist: () -> Unit,
    onDismissDelete: () -> Unit,
    onAfterPlaylistAdd: () -> Unit = {},
    onAfterDelete: (List<Song>) -> Unit = {},
    playlistSongIds: (Song) -> List<Long> = { listOf(it.id) },
    onSelectPlaylist: ((Playlist, Song) -> Unit)? = null
) {
    val currentItem by viewModel.currentItem.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

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

    editingLyricsSong?.let { song ->
        var lyricsSong by remember(song.id) { mutableStateOf(song) }
        LaunchedEffect(song.id) {
            viewModel.songById(song.id)?.let { lyricsSong = it }
        }
        val isCurrent = (currentItem as? PlayableItem.Local)?.song?.id == song.id
        EditLyricsDialog(
            song = lyricsSong,
            isCurrent = isCurrent,
            isPlaying = isPlaying,
            durationMs = if (isCurrent) currentItem?.durationMs ?: lyricsSong.durationMs else lyricsSong.durationMs,
            positionMsFlow = viewModel.playbackPositionMs,
            onDismiss = onDismissLyrics,
            onSave = { lyrics ->
                viewModel.updateSongLyrics(song.id, lyrics)
                onDismissLyrics()
            },
            onPlayPause = {
                if (isCurrent) viewModel.togglePlayPause() else viewModel.playSong(lyricsSong)
            },
            onSeek = viewModel::seekTo,
            onFetchOnline = { onResult -> viewModel.fetchSongLyrics(lyricsSong, onResult) }
        )
    }

    val targetPlaylistSongs = songsForPlaylistAddition ?: songForPlaylistAddition?.let { listOf(it) }
    targetPlaylistSongs?.takeIf { it.isNotEmpty() }?.let { songs ->
        val songIds = if (songs.size == 1) playlistSongIds(songs.first()) else songs.map { it.id }
        val defaultCoverUri = songs.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) }
        AddToPlaylistDialog(
            playlists = playlists,
            songCount = songs.size,
            defaultCoverUri = defaultCoverUri,
            onDismiss = onDismissPlaylist,
            onSelectPlaylist = { playlist, openAfter ->
                if (onSelectPlaylist != null && songs.size == 1) {
                    onSelectPlaylist(playlist, songs.first())
                } else {
                    viewModel.addSongsToPlaylist(playlist.id, songIds)
                }
                if (openAfter) {
                    viewModel.openLocalPlaylist(playlist.id)
                }
                onDismissPlaylist()
                onAfterPlaylistAdd()
            },
            onCreatePlaylist = { name, desc, coverUri, openAfter ->
                viewModel.createPlaylist(name, desc, coverUri, initialSongIds = songIds) { newId ->
                    if (openAfter) {
                        viewModel.openLocalPlaylist(newId)
                    }
                }
                onDismissPlaylist()
                onAfterPlaylistAdd()
            }
        )
    }

    // takeIf: an empty list is non-null, which rendered "¿Cómo deseas eliminar 0 canción(es)?".
    songsForDeletion?.takeIf { it.isNotEmpty() }?.let { targetSongs ->
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
