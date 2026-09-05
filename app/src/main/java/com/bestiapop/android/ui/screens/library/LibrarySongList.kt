package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.FastScrollContainer
import com.bestiapop.android.ui.components.FastScrollSections
import com.bestiapop.android.ui.components.PlayShuffleIconPair
import com.bestiapop.android.ui.components.SongListItem
import com.bestiapop.android.ui.components.SongOptionsMenu
import com.bestiapop.android.ui.components.SortEmphasizedTexts
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.bestiapop.android.ui.components.preloadArtworkSuspend
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryListModel
import com.bestiapop.android.ui.theme.ListDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

import com.bestiapop.android.data.preferences.FastScrollSettings

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
    fastScrollSettings: FastScrollSettings = FastScrollSettings(),
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
    listState: LazyListState = rememberLazyListState(),
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

    var menuSong by remember { mutableStateOf<Song?>(null) }
    val onOpenSongMenu: (Song) -> Unit = remember { { menuSong = it } }

    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = remember(density) {
        with(density) { ListDensity.artworkSong.roundToPx().coerceAtLeast(1) }
    }

    LaunchedEffect(visible, sizePx) {
        if (visible.isEmpty) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .collectLatest { (firstVisible, isScrollInProgress) ->
                // While scrolling actively, pause background preloading to keep 100% thread pool for visible items
                if (isScrollInProgress) return@collectLatest

                // Small resting window after scrolling stops
                delay(60)
                if (listState.isScrollInProgress) return@collectLatest

                // 1. Immediate proximity window (ahead +35, behind -10)
                val windowStart = (firstVisible - 10).coerceAtLeast(0)
                val windowEnd = (firstVisible + 35).coerceAtMost(visible.size - 1)
                val proximityUris = visible.uniqueArtworkUrisInRange(windowStart..windowEnd)

                for (uri in proximityUris) {
                    if (listState.isScrollInProgress) break
                    val decoded = preloadArtworkSuspend(context, uri, sizePx)
                    if (decoded) delay(25)
                }

                // 2. Idle background pacing: slowly pre-warm further ahead when completely idle
                if (!listState.isScrollInProgress) {
                    delay(120)
                    val idleStart = (windowEnd + 1).coerceAtMost(visible.size)
                    val idleEnd = (firstVisible + 200).coerceAtMost(visible.size - 1)
                    if (idleStart <= idleEnd) {
                        val idleUris = visible.uniqueArtworkUrisInRange(idleStart..idleEnd)
                        for (uri in idleUris) {
                            if (listState.isScrollInProgress) break
                            val decoded = preloadArtworkSuspend(context, uri, sizePx)
                            if (decoded) delay(60)
                        }
                    }
                }
            }
    }

    val sections = remember(visible, sortOption, emphasizeLastPlayed) {
        FastScrollSections.fromLibraryList(visible, sortOption, emphasizeLastPlayed)
    }

    FastScrollContainer(
        sections = sections,
        listState = listState,
        settings = fastScrollSettings,
        modifier = modifier.fillMaxSize()
    ) { listModifier ->
        LazyColumn(state = listState, modifier = listModifier) {
            items(
                count = visible.size,
                key = { visible.keyAt(it) },
                contentType = { visible.contentTypeAt(it) }
            ) { index ->
                when (val item = visible.itemAt(index)) {
                    is LibraryListItem.AlbumHeader -> {
                        LibraryAlbumHeaderRow(
                            item = item,
                            selectedSongIds = selectedSongIds,
                            isSelectionMode = isSelectionMode,
                            collapsedAlbumNames = collapsedAlbumNames,
                            onPlayAlbumState = onPlayAlbumState,
                            onShuffleAlbumState = onShuffleAlbumState,
                            onToggleSelectAlbumState = onToggleSelectAlbumState,
                            onAlbumLongClickState = onAlbumLongClickState,
                            onToggleCollapseAlbumState = onToggleCollapseAlbumState,
                            onEditAlbumState = onEditAlbumState,
                            onChangeAlbumCoverState = onChangeAlbumCoverState,
                            onIdentifyAlbumState = onIdentifyAlbumState,
                            onOpenAlbumState = onOpenAlbumState
                        )
                    }

                    is LibraryListItem.SongRow -> {
                        LibrarySongRow(
                            song = item.song,
                            index = item.index,
                            artworkUri = item.artworkUri,
                            isPlaying = playingIdState.value == item.song.id,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedSongIds.contains(item.song.id),
                            emphasis = item.emphasis,
                            onOptionsClick = onOpenSongMenu,
                            onSongClickState = onSongClickState,
                            onSongLongClickState = onSongLongClickState,
                            onToggleSelectState = onToggleSelectState
                        )
                    }
                }
            }
        }
    }

    val currentMenuSong = menuSong
    if (currentMenuSong != null) {
        SongOptionsMenu(
            onDismiss = { menuSong = null },
            onPlayNext = { onPlayNextState.value(currentMenuSong) },
            onAddToQueue = { onAddToQueueState.value(currentMenuSong) },
            onStartRadio = { onStartRadioState.value(currentMenuSong) },
            onAddToPlaylist = { onAddToPlaylistState.value(currentMenuSong) },
            onEditMetadata = { onEditMetadataState.value(currentMenuSong) },
            onEditLyrics = { onEditLyricsState.value(currentMenuSong) },
            onIdentify = { onIdentifyState.value(currentMenuSong) },
            onDelete = { onDeleteSongState.value(currentMenuSong) }
        )
    }
}

@Composable
private fun LibraryAlbumHeaderRow(
    item: LibraryListItem.AlbumHeader,
    selectedSongIds: Set<Long>,
    isSelectionMode: Boolean,
    collapsedAlbumNames: Set<String>,
    onPlayAlbumState: State<(String, List<Long>) -> Unit>,
    onShuffleAlbumState: State<(String, List<Long>) -> Unit>,
    onToggleSelectAlbumState: State<(List<Long>) -> Unit>,
    onAlbumLongClickState: State<(List<Long>) -> Unit>,
    onToggleCollapseAlbumState: State<(String) -> Unit>,
    onEditAlbumState: State<(String) -> Unit>,
    onChangeAlbumCoverState: State<(String) -> Unit>,
    onIdentifyAlbumState: State<(String) -> Unit>,
    onOpenAlbumState: State<(String) -> Unit>
) {
    val groupingKey = item.groupingKey
    val selectionState = if (isSelectionMode) {
        remember(groupingKey, selectedSongIds) {
            albumHeaderSelectionState(item.songIds, selectedSongIds, true)
        }
    } else {
        AlbumHeaderSelectionState.NONE
    }
    val playAlbum = remember(groupingKey) {
        { onPlayAlbumState.value(item.albumName, item.songIds) }
    }
    val shuffleAlbum = remember(groupingKey) {
        { onShuffleAlbumState.value(item.albumName, item.songIds) }
    }
    val toggleSelectAlbum = remember(groupingKey) {
        { onToggleSelectAlbumState.value(item.songIds) }
    }
    val albumLongClick = remember(groupingKey) {
        { onAlbumLongClickState.value(item.songIds) }
    }
    val toggleCollapse = remember(groupingKey) {
        { onToggleCollapseAlbumState.value(item.albumName) }
    }
    val editAlbum = remember(groupingKey) {
        { onEditAlbumState.value(item.albumName) }
    }
    val changeAlbumCover = remember(groupingKey) {
        { onChangeAlbumCoverState.value(item.albumName) }
    }
    val identifyAlbum = remember(groupingKey) {
        { onIdentifyAlbumState.value(item.albumName) }
    }
    val openAlbum = remember(groupingKey) {
        { onOpenAlbumState.value(item.albumName) }
    }
    TauonAlbumHeader(
        title = item.displayName,
        artistName = item.artistName,
        subtitle = item.subtitle,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibrarySongRow(
    song: Song,
    index: Int,
    artworkUri: String?,
    isPlaying: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    emphasis: SortEmphasizedTexts,
    onOptionsClick: (Song) -> Unit,
    onSongClickState: State<(Song, Int) -> Unit>,
    onSongLongClickState: State<(Song) -> Unit>,
    onToggleSelectState: State<(Song) -> Unit>
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
    val onOptions = remember(song.id) {
        { onOptionsClick(songState.value) }
    }
    val handleRowClick = remember(isSelectionMode, onClick, onToggleSelect) {
        if (isSelectionMode) onToggleSelect else onClick
    }

    val isHighlighted = isPlaying || isSelected
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val bgColor = when {
        isSelected -> primaryColor.copy(alpha = 0.25f)
        isPlaying -> primaryColor.copy(alpha = 0.15f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val titleColor = if (isHighlighted) primaryColor else onSurfaceColor
    val titleWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ListDensity.rowHorizontalPadding,
                vertical = ListDensity.rowVerticalPadding
            )
            .then(
                if (bgColor != androidx.compose.ui.graphics.Color.Transparent) {
                    Modifier
                        .clip(RoundedCornerShape(ListDensity.corner))
                        .background(bgColor)
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = handleRowClick,
                onLongClick = onLongClick
            )
            .padding(ListDensity.rowInnerPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(checkedColor = primaryColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        ArtworkThumbnail(
            artworkUri = artworkUri,
            size = ListDensity.artworkSong,
            contentDescription = song.title
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = emphasis.title,
                style = ListDensity.titleStyle,
                fontWeight = titleWeight,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (emphasis.subtitle.isNotEmpty()) {
                Text(
                    text = emphasis.subtitle,
                    style = ListDensity.subtitleStyle,
                    color = onSurfaceColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!emphasis.trailing.isNullOrEmpty()) {
            Text(
                text = emphasis.trailing,
                style = MaterialTheme.typography.labelMedium,
                color = if (emphasis.trailingIsSortKey) primaryColor else onSurfaceColor.copy(alpha = 0.5f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(min = 56.dp)
                    .padding(horizontal = 8.dp)
            )
        }

        if (!isSelectionMode) {
            IconButton(
                onClick = onOptions,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Opciones",
                    tint = onSurfaceColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TauonAlbumHeader(
    /** Display text only: pass the override display name, not the grouping key. */
    title: String,
    artistName: String,
    artworkUri: String?,
    songCount: Int,
    subtitle: String = "$artistName • $songCount canciones",
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
    val handleHeaderClick = remember(isSelectionMode, onToggleSelect, onOpenAlbum) {
        if (isSelectionMode) onToggleSelect else onOpenAlbum
    }
    val onOpenMenu = remember { { menuExpanded = true } }
    val onDismissMenu = remember { { menuExpanded = false } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ListDensity.rowHorizontalPadding,
                vertical = ListDensity.rowVerticalPadding
            )
            .clip(RoundedCornerShape(ListDensity.corner))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .combinedClickable(
                onClick = handleHeaderClick,
                onLongClick = onLongClick
            )
            .padding(ListDensity.rowInnerPadding),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = ListDensity.subtitleStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showCollapseToggle) {
            HeaderActionIcon(
                onClick = onToggleCollapse,
                icon = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = if (isCollapsed) "Expandir álbum" else "Plegar álbum"
            )
        }
        if (!isSelectionMode) {
            Box {
                HeaderActionIcon(
                    onClick = onOpenMenu,
                    icon = Icons.Default.MoreVert,
                    contentDescription = "Opciones de álbum"
                )
                if (menuExpanded) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = onDismissMenu
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
            }
            HeaderActionIcon(
                onClick = onPlayAlbum,
                icon = Icons.Default.PlayArrow,
                contentDescription = "Reproducir álbum",
                tint = MaterialTheme.colorScheme.primary
            )
            HeaderActionIcon(
                onClick = onShuffleAlbum,
                icon = Icons.Default.Shuffle,
                contentDescription = "Mezclar álbum"
            )
        }
    }
}

@Composable
private fun HeaderActionIcon(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}
