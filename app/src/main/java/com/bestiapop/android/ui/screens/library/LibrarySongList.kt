package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.PlayShuffleIconPair
import com.bestiapop.android.ui.components.SongListItem
import com.bestiapop.android.ui.components.formatSortRelevantInfo
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.theme.ListDensity

@Composable
fun LibrarySongList(
    items: List<LibraryListItem>,
    currentSongId: Long?,
    isSelectionMode: Boolean,
    selectedSongIds: Set<Long>,
    collapsedAlbumNames: Set<String> = emptySet(),
    sortOption: SortOption = SortOption.TITLE,
    onSongClick: (Song, Int) -> Unit,
    onSongLongClick: (Song) -> Unit,
    onToggleSelect: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onStartRadio: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit,
    onEditMetadata: (Song) -> Unit,
    onIdentify: (Song) -> Unit = {},
    onDeleteSong: (Song) -> Unit,
    onPlayAlbum: (String, List<Song>) -> Unit,
    onShuffleAlbum: (String, List<Song>) -> Unit,
    onToggleSelectAlbum: (List<Song>) -> Unit = {},
    onAlbumLongClick: (List<Song>) -> Unit = {},
    onToggleCollapseAlbum: (String) -> Unit = {},
    onEditAlbum: (String) -> Unit = {},
    onChangeAlbumCover: (String) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        EmptyListHint(
            text = "No se encontraron canciones",
            modifier = modifier.fillMaxSize()
        )
        return
    }

    val visibleItems = remember(items, collapsedAlbumNames) {
        filterCollapsedAlbumSongs(items, collapsedAlbumNames)
    }

    val onSongClickState = rememberUpdatedState(onSongClick)
    val onSongLongClickState = rememberUpdatedState(onSongLongClick)
    val onToggleSelectState = rememberUpdatedState(onToggleSelect)
    val onPlayNextState = rememberUpdatedState(onPlayNext)
    val onAddToQueueState = rememberUpdatedState(onAddToQueue)
    val onStartRadioState = rememberUpdatedState(onStartRadio)
    val onAddToPlaylistState = rememberUpdatedState(onAddToPlaylist)
    val onEditMetadataState = rememberUpdatedState(onEditMetadata)
    val onIdentifyState = rememberUpdatedState(onIdentify)
    val onDeleteSongState = rememberUpdatedState(onDeleteSong)
    val onPlayAlbumState = rememberUpdatedState(onPlayAlbum)
    val onShuffleAlbumState = rememberUpdatedState(onShuffleAlbum)
    val onToggleSelectAlbumState = rememberUpdatedState(onToggleSelectAlbum)
    val onAlbumLongClickState = rememberUpdatedState(onAlbumLongClick)
    val onToggleCollapseAlbumState = rememberUpdatedState(onToggleCollapseAlbum)
    val onEditAlbumState = rememberUpdatedState(onEditAlbum)
    val onChangeAlbumCoverState = rememberUpdatedState(onChangeAlbumCover)
    val onOpenAlbumState = rememberUpdatedState(onOpenAlbum)

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(
            items = visibleItems,
            key = { it.key },
            contentType = { it.contentType }
        ) { item ->
            when (item) {
                is LibraryListItem.AlbumHeader -> {
                    val albumIds = remember(item.albumSongs) {
                        item.albumSongs.map { it.id }.toSet()
                    }
                    val selectedCount = albumIds.count { selectedSongIds.contains(it) }
                    val selectionState = when {
                        selectedCount == 0 -> AlbumHeaderSelectionState.NONE
                        selectedCount == albumIds.size -> AlbumHeaderSelectionState.ALL
                        else -> AlbumHeaderSelectionState.PARTIAL
                    }
                    val playAlbum = remember(item.albumName, item.albumSongs) {
                        { onPlayAlbumState.value(item.albumName, item.albumSongs) }
                    }
                    val shuffleAlbum = remember(item.albumName, item.albumSongs) {
                        { onShuffleAlbumState.value(item.albumName, item.albumSongs) }
                    }
                    val toggleSelectAlbum = remember(item.albumSongs) {
                        { onToggleSelectAlbumState.value(item.albumSongs) }
                    }
                    val albumLongClick = remember(item.albumSongs) {
                        { onAlbumLongClickState.value(item.albumSongs) }
                    }
                    val toggleCollapse = remember(item.albumName) {
                        { onToggleCollapseAlbumState.value(item.albumName) }
                    }
                    val editAlbum = remember(item.albumName) {
                        { onEditAlbumState.value(item.albumName) }
                    }
                    val changeAlbumCover = remember(item.albumName) {
                        { onChangeAlbumCoverState.value(item.albumName) }
                    }
                    val openAlbum = remember(item.albumName) {
                        { onOpenAlbumState.value(item.albumName) }
                    }
                    TauonAlbumHeader(
                        albumName = item.albumName,
                        artistName = item.artistName,
                        artworkUri = item.artworkUri,
                        songCount = item.songCount,
                        isCollapsed = collapsedAlbumNames.contains(item.albumName),
                        isSelectionMode = isSelectionMode,
                        selectionState = selectionState,
                        onPlayAlbum = playAlbum,
                        onShuffleAlbum = shuffleAlbum,
                        onToggleSelect = toggleSelectAlbum,
                        onLongClick = albumLongClick,
                        onToggleCollapse = toggleCollapse,
                        onEditAlbum = editAlbum,
                        onChangeAlbumCover = changeAlbumCover,
                        onOpenAlbum = openAlbum
                    )
                }

                is LibraryListItem.SongRow -> {
                    LibrarySongRow(
                        song = item.song,
                        index = item.index,
                        currentSongId = currentSongId,
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedSongIds.contains(item.song.id),
                        sortOption = sortOption,
                        onSongClickState = onSongClickState,
                        onSongLongClickState = onSongLongClickState,
                        onToggleSelectState = onToggleSelectState,
                        onPlayNextState = onPlayNextState,
                        onAddToQueueState = onAddToQueueState,
                        onStartRadioState = onStartRadioState,
                        onAddToPlaylistState = onAddToPlaylistState,
                        onEditMetadataState = onEditMetadataState,
                        onIdentifyState = onIdentifyState,
                        onDeleteSongState = onDeleteSongState
                    )
                }
            }
        }
    }
}

internal fun filterCollapsedAlbumSongs(
    items: List<LibraryListItem>,
    collapsedAlbumNames: Set<String>
): List<LibraryListItem> {
    if (collapsedAlbumNames.isEmpty()) return items
    val result = ArrayList<LibraryListItem>(items.size)
    var hidingAlbum: String? = null
    for (item in items) {
        when (item) {
            is LibraryListItem.AlbumHeader -> {
                hidingAlbum = if (collapsedAlbumNames.contains(item.albumName)) item.albumName else null
                result += item
            }
            is LibraryListItem.SongRow -> {
                if (hidingAlbum == null || item.song.album != hidingAlbum) {
                    result += item
                }
            }
        }
    }
    return result
}

enum class AlbumHeaderSelectionState {
    NONE,
    PARTIAL,
    ALL
}

@Composable
private fun LibrarySongRow(
    song: Song,
    index: Int,
    currentSongId: Long?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    sortOption: SortOption,
    onSongClickState: State<(Song, Int) -> Unit>,
    onSongLongClickState: State<(Song) -> Unit>,
    onToggleSelectState: State<(Song) -> Unit>,
    onPlayNextState: State<(Song) -> Unit>,
    onAddToQueueState: State<(Song) -> Unit>,
    onStartRadioState: State<(Song) -> Unit>,
    onAddToPlaylistState: State<(Song) -> Unit>,
    onEditMetadataState: State<(Song) -> Unit>,
    onIdentifyState: State<(Song) -> Unit>,
    onDeleteSongState: State<(Song) -> Unit>
) {
    val songState = rememberUpdatedState(song)
    val onClick = remember(song.id, index) {
        { onSongClickState.value(songState.value, index) }
    }
    val onLongClick = remember(song.id) {
        { onSongLongClickState.value(songState.value) }
    }
    val onToggleSelect = remember(song.id) {
        { onToggleSelectState.value(songState.value) }
    }
    val onPlayNext = remember(song.id) {
        { onPlayNextState.value(songState.value) }
    }
    val onAddToQueue = remember(song.id) {
        { onAddToQueueState.value(songState.value) }
    }
    val onStartRadio = remember(song.id) {
        { onStartRadioState.value(songState.value) }
    }
    val onAddToPlaylist = remember(song.id) {
        { onAddToPlaylistState.value(songState.value) }
    }
    val onEditMetadata = remember(song.id) {
        { onEditMetadataState.value(songState.value) }
    }
    val onIdentify = remember(song.id) {
        { onIdentifyState.value(songState.value) }
    }
    val onDelete = remember(song.id) {
        { onDeleteSongState.value(songState.value) }
    }
    val secondaryInfo = remember(song.genre, song.dateAdded, sortOption) {
        formatSortRelevantInfo(
            sortOption = sortOption,
            genre = song.genre,
            dateAdded = song.dateAdded,
            alreadyShowsArtist = true,
            alreadyShowsAlbum = true,
            alreadyShowsTitle = true
        )
    }

    SongListItem(
        song = song,
        isCurrentPlaying = currentSongId == song.id,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        secondaryInfo = secondaryInfo,
        onClick = onClick,
        onLongClick = onLongClick,
        onToggleSelect = onToggleSelect,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onStartRadio = onStartRadio,
        onAddToPlaylist = onAddToPlaylist,
        onEditMetadata = onEditMetadata,
        onIdentify = onIdentify,
        onDelete = onDelete
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TauonAlbumHeader(
    albumName: String,
    artistName: String,
    artworkUri: String?,
    songCount: Int,
    isCollapsed: Boolean = false,
    isSelectionMode: Boolean = false,
    selectionState: AlbumHeaderSelectionState = AlbumHeaderSelectionState.NONE,
    onPlayAlbum: () -> Unit,
    onShuffleAlbum: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onToggleCollapse: () -> Unit = {},
    onEditAlbum: () -> Unit = {},
    onChangeAlbumCover: () -> Unit = {},
    onOpenAlbum: () -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ListDensity.rowHorizontalPadding,
                vertical = ListDensity.rowVerticalPadding
            )
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelect()
                    } else {
                        onOpenAlbum()
                    }
                },
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(ListDensity.corner)
    ) {
        Row(
            modifier = Modifier.padding(ListDensity.rowInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    val toggleState = when (selectionState) {
                        AlbumHeaderSelectionState.NONE -> ToggleableState.Off
                        AlbumHeaderSelectionState.PARTIAL -> ToggleableState.Indeterminate
                        AlbumHeaderSelectionState.ALL -> ToggleableState.On
                    }
                    TriStateCheckbox(
                        state = toggleState,
                        onClick = onToggleSelect,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                ArtworkThumbnail(
                    artworkUri = artworkUri,
                    size = ListDensity.artworkAlbumHeader,
                    cornerRadius = ListDensity.corner
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = albumName,
                        style = ListDensity.titleStyle,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$artistName • $songCount canciones",
                        style = ListDensity.subtitleStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleCollapse) {
                    Icon(
                        imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = if (isCollapsed) "Expandir álbum" else "Plegar álbum",
                        modifier = Modifier.size(22.dp)
                    )
                }
                if (!isSelectionMode) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Opciones de álbum",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            AlbumEditCoverMenuItems(
                                onEditAlbum = {
                                    menuExpanded = false
                                    onEditAlbum()
                                },
                                onChangeCover = {
                                    menuExpanded = false
                                    onChangeAlbumCover()
                                }
                            )
                        }
                    }
                    PlayShuffleIconPair(
                        onPlay = onPlayAlbum,
                        onShuffle = onShuffleAlbum,
                        playDescription = "Reproducir álbum",
                        shuffleDescription = "Mezclar álbum"
                    )
                }
            }
        }
    }
}
