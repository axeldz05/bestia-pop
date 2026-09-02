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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.LocalAllowArtworkDecode
import com.bestiapop.android.ui.components.PlayShuffleIconPair
import com.bestiapop.android.ui.components.SongListItem
import com.bestiapop.android.ui.components.SortEmphasizedTexts
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryListModel
import com.bestiapop.android.ui.theme.ListDensity
import kotlinx.coroutines.flow.StateFlow

@Composable
@Suppress("UNUSED_PARAMETER")
fun LibrarySongList(
    list: LibraryListModel,
    currentSongId: Long?,
    isSelectionMode: Boolean,
    selectedSongIds: Set<Long>,
    collapsedAlbumNames: Set<String> = emptySet(),
    sortOption: SortOption = SortOption.TITLE,
    emphasizeLastPlayed: Boolean = false,
    emptySubtitle: String? = null,
    emptyText: String = "No se encontraron canciones",
    loading: Boolean = false,
    onSongClick: (Song, Int) -> Unit,
    onSongLongClick: (Song) -> Unit,
    onToggleSelect: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onStartRadio: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit,
    onEditMetadata: (Song) -> Unit,
    onEditLyrics: (Song) -> Unit = {},
    onIdentify: (Song) -> Unit = {},
    onDeleteSong: (Song) -> Unit,
    onPlayAlbum: (String, List<Long>) -> Unit,
    onShuffleAlbum: (String, List<Long>) -> Unit,
    onToggleSelectAlbum: (List<Long>) -> Unit = {},
    onAlbumLongClick: (List<Long>) -> Unit = {},
    onToggleCollapseAlbum: (String) -> Unit = {},
    onEditAlbum: (String) -> Unit = {},
    onChangeAlbumCover: (String) -> Unit = {},
    onIdentifyAlbum: (String) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    currentSongIdFlow: StateFlow<Long?>? = null,
    modifier: Modifier = Modifier
) {
    val visible = remember(list, collapsedAlbumNames) {
        list.collapsed(collapsedAlbumNames)
    }
    if (visible.isEmpty) {
        if (loading) {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            EmptyListHint(
                text = emptyText,
                subtitle = emptySubtitle,
                modifier = modifier.fillMaxSize()
            )
        }
        return
    }

    val playingIdState = remember { mutableStateOf(currentSongId) }
    LaunchedEffect(currentSongIdFlow, currentSongId) {
        val flow = currentSongIdFlow
        if (flow != null) {
            flow.collect { playingIdState.value = it }
        } else {
            playingIdState.value = currentSongId
        }
    }

    val onSongClickState = rememberUpdatedState(onSongClick)
    val onSongLongClickState = rememberUpdatedState(onSongLongClick)
    val onToggleSelectState = rememberUpdatedState(onToggleSelect)
    val onPlayNextState = rememberUpdatedState(onPlayNext)
    val onAddToQueueState = rememberUpdatedState(onAddToQueue)
    val onStartRadioState = rememberUpdatedState(onStartRadio)
    val onAddToPlaylistState = rememberUpdatedState(onAddToPlaylist)
    val onEditMetadataState = rememberUpdatedState(onEditMetadata)
    val onEditLyricsState = rememberUpdatedState(onEditLyrics)
    val onIdentifyState = rememberUpdatedState(onIdentify)
    val onDeleteSongState = rememberUpdatedState(onDeleteSong)
    val onPlayAlbumState = rememberUpdatedState(onPlayAlbum)
    val onShuffleAlbumState = rememberUpdatedState(onShuffleAlbum)
    val onToggleSelectAlbumState = rememberUpdatedState(onToggleSelectAlbum)
    val onAlbumLongClickState = rememberUpdatedState(onAlbumLongClick)
    val onToggleCollapseAlbumState = rememberUpdatedState(onToggleCollapseAlbum)
    val onEditAlbumState = rememberUpdatedState(onEditAlbum)
    val onChangeAlbumCoverState = rememberUpdatedState(onChangeAlbumCover)
    val onIdentifyAlbumState = rememberUpdatedState(onIdentifyAlbum)
    val onOpenAlbumState = rememberUpdatedState(onOpenAlbum)

    val listState = rememberLazyListState()
    var artworkReady by remember { mutableStateOf(false) }
    LaunchedEffect(visible.size) {
        artworkReady = false
        withFrameNanos { }
        artworkReady = true
    }
    val allowArtworkDecode = artworkReady && !listState.isScrollInProgress

    CompositionLocalProvider(LocalAllowArtworkDecode provides allowArtworkDecode) {
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        items(
            count = visible.size,
            key = { visible.keyAt(it) },
            contentType = { visible.contentTypeAt(it) }
        ) { index ->
            when (val item = visible.itemAt(index)) {
                is LibraryListItem.AlbumHeader -> {
                    val albumIds = item.songIds
                    val selectionState = remember(albumIds, selectedSongIds, isSelectionMode) {
                        albumHeaderSelectionState(albumIds, selectedSongIds, isSelectionMode)
                    }
                    val playAlbum = remember(item.albumName, albumIds) {
                        { onPlayAlbumState.value(item.albumName, albumIds) }
                    }
                    val shuffleAlbum = remember(item.albumName, albumIds) {
                        { onShuffleAlbumState.value(item.albumName, albumIds) }
                    }
                    val toggleSelectAlbum = remember(albumIds) {
                        { onToggleSelectAlbumState.value(albumIds) }
                    }
                    val albumLongClick = remember(albumIds) {
                        { onAlbumLongClickState.value(albumIds) }
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
                    val identifyAlbum = remember(item.albumName) {
                        { onIdentifyAlbumState.value(item.albumName) }
                    }
                    val openAlbum = remember(item.albumName) {
                        { onOpenAlbumState.value(item.albumName) }
                    }
                    TauonAlbumHeader(
                        title = item.displayName,
                        artistName = item.artistName,
                        artworkUri = item.artworkUri,
                        songCount = item.songCount,
                        sortHint = item.sortHint,
                        isCollapsed = item.matchesCollapsed(collapsedAlbumNames),
                        isSelectionMode = isSelectionMode,
                        selectionState = selectionState,
                        onPlayAlbum = playAlbum,
                        onShuffleAlbum = shuffleAlbum,
                        onToggleSelect = toggleSelectAlbum,
                        onLongClick = albumLongClick,
                        onToggleCollapse = toggleCollapse,
                        onEditAlbum = editAlbum,
                        onChangeAlbumCover = changeAlbumCover,
                        onIdentifyAlbum = identifyAlbum,
                        onOpenAlbum = openAlbum
                    )
                }

                is LibraryListItem.SongRow -> {
                    LibrarySongRow(
                        song = item.song,
                        index = item.index,
                        artworkUri = item.artworkUri,
                        playingIdState = playingIdState,
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedSongIds.contains(item.song.id),
                        emphasis = item.emphasis,
                        onSongClickState = onSongClickState,
                        onSongLongClickState = onSongLongClickState,
                        onToggleSelectState = onToggleSelectState,
                        onPlayNextState = onPlayNextState,
                        onAddToQueueState = onAddToQueueState,
                        onStartRadioState = onStartRadioState,
                        onAddToPlaylistState = onAddToPlaylistState,
                        onEditMetadataState = onEditMetadataState,
                        onEditLyricsState = onEditLyricsState,
                        onIdentifyState = onIdentifyState,
                        onDeleteSongState = onDeleteSongState
                    )
                }
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
    var hiding = false
    for (item in items) {
        when (item) {
            is LibraryListItem.AlbumHeader -> {
                hiding = item.matchesCollapsed(collapsedAlbumNames)
                result += item
            }
            is LibraryListItem.SongRow -> {
                if (!hiding) result += item
            }
        }
    }
    return result
}

internal fun LibraryListItem.AlbumHeader.matchesCollapsed(collapsed: Set<String>): Boolean =
    collapsed.contains(albumName) || (groupingKey.isNotBlank() && collapsed.contains(groupingKey))

internal fun albumHeaderSelectionState(
    albumIds: List<Long>,
    selectedSongIds: Set<Long>,
    isSelectionMode: Boolean
): AlbumHeaderSelectionState {
    if (!isSelectionMode || albumIds.isEmpty()) return AlbumHeaderSelectionState.NONE
    var any = false
    var all = true
    for (id in albumIds) {
        if (selectedSongIds.contains(id)) any = true else all = false
        if (any && !all) return AlbumHeaderSelectionState.PARTIAL
    }
    return if (all) AlbumHeaderSelectionState.ALL else AlbumHeaderSelectionState.NONE
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
    artworkUri: String?,
    playingIdState: State<Long?>,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    emphasis: SortEmphasizedTexts,
    onSongClickState: State<(Song, Int) -> Unit>,
    onSongLongClickState: State<(Song) -> Unit>,
    onToggleSelectState: State<(Song) -> Unit>,
    onPlayNextState: State<(Song) -> Unit>,
    onAddToQueueState: State<(Song) -> Unit>,
    onStartRadioState: State<(Song) -> Unit>,
    onAddToPlaylistState: State<(Song) -> Unit>,
    onEditMetadataState: State<(Song) -> Unit>,
    onEditLyricsState: State<(Song) -> Unit>,
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
    val onEditLyrics = remember(song.id) {
        { onEditLyricsState.value(songState.value) }
    }
    val onIdentify = remember(song.id) {
        { onIdentifyState.value(songState.value) }
    }
    val onDelete = remember(song.id) {
        { onDeleteSongState.value(songState.value) }
    }
    val isPlaying by remember(song.id) {
        derivedStateOf { playingIdState.value == song.id }
    }

    SongListItem(
        song = song,
        isCurrentPlaying = isPlaying,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        artworkUri = artworkUri,
        title = emphasis.title,
        subtitle = emphasis.subtitle,
        trailing = emphasis.trailing,
        trailingIsSortKey = emphasis.trailingIsSortKey,
        onClick = onClick,
        onLongClick = onLongClick,
        onToggleSelect = onToggleSelect,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onStartRadio = onStartRadio,
        onAddToPlaylist = onAddToPlaylist,
        onEditMetadata = onEditMetadata,
        onEditLyrics = onEditLyrics,
        onIdentify = onIdentify,
        onDelete = onDelete
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TauonAlbumHeader(
    /** Display text only: pass the override display name, not the grouping key. */
    title: String,
    artistName: String,
    artworkUri: String?,
    songCount: Int,
    sortHint: String? = null,
    isCollapsed: Boolean = false,
    isSelectionMode: Boolean = false,
    selectionState: AlbumHeaderSelectionState = AlbumHeaderSelectionState.NONE,
    showCollapseToggle: Boolean = true,
    onPlayAlbum: () -> Unit,
    onShuffleAlbum: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onToggleCollapse: () -> Unit = {},
    onEditAlbum: () -> Unit = {},
    onChangeAlbumCover: () -> Unit = {},
    onIdentifyAlbum: (() -> Unit)? = null,
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
                        text = title,
                        style = ListDensity.titleStyle,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = buildString {
                            append("$artistName • $songCount canciones")
                            if (!sortHint.isNullOrBlank()) append(" • $sortHint")
                        },
                        style = ListDensity.subtitleStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showCollapseToggle) {
                    IconButton(onClick = onToggleCollapse) {
                        Icon(
                            imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = if (isCollapsed) "Expandir álbum" else "Plegar álbum",
                            modifier = Modifier.size(22.dp)
                        )
                    }
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
                                },
                                onIdentifyAlbum = onIdentifyAlbum?.let { action ->
                                    {
                                        menuExpanded = false
                                        action()
                                    }
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
