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
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.AlbumHeader
import com.bestiapop.android.ui.components.AlbumHeaderActions
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.FastScrollContainer
import com.bestiapop.android.ui.components.FastScrollSections
import com.bestiapop.android.ui.components.PlayShuffleIconPair
import com.bestiapop.android.ui.components.SongItemActions
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

/** Level 2: High-level LibrarySongList accepting bundled [LibrarySongListActions]. */
@Composable
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
    actions: LibrarySongListActions,
    onSongClick: (Song, Int) -> Unit,
    onSongLongClick: (Song) -> Unit = actions.onToggleSelect,
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

    val actionsState = rememberUpdatedState(actions)
    val onSongClickState = rememberUpdatedState(onSongClick)
    val onSongLongClickState = rememberUpdatedState(onSongLongClick)

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
                            albumActionsState = rememberUpdatedState(actionsState.value.albumActions)
                        )
                    }

                    is LibraryListItem.SongRow -> {
                        val song = item.song
                        SongListItem(
                            song = song,
                            artworkUri = item.artworkUri,
                            isCurrentPlaying = playingIdState.value == song.id,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedSongIds.contains(song.id),
                            title = item.emphasis.title,
                            subtitle = item.emphasis.subtitle,
                            trailing = item.emphasis.trailing,
                            trailingIsSortKey = item.emphasis.trailingIsSortKey,
                            onClick = { onSongClickState.value(song, item.index) },
                            onLongClick = { onSongLongClickState.value(song) },
                            onToggleSelect = { actionsState.value.onToggleSelect(song) },
                            onOptionsClick = { onOpenSongMenu(song) }
                        )
                    }
                }
            }
        }
    }

    val currentMenuSong = menuSong
    if (currentMenuSong != null) {
        SongOptionsMenu(
            song = currentMenuSong,
            actions = actionsState.value.songActions,
            onDismiss = { menuSong = null }
        )
    }
}

/** Level 1: Low-level LibrarySongList with individual primitive callbacks for custom call sites. */
@Composable
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
    val actions = remember(
        onToggleSelect, onPlayNext, onAddToQueue, onStartRadio, onAddToPlaylist,
        onEditMetadata, onEditLyrics, onIdentify, onDeleteSong,
        onPlayAlbum, onShuffleAlbum, onToggleSelectAlbum, onAlbumLongClick,
        onToggleCollapseAlbum, onEditAlbum, onChangeAlbumCover, onIdentifyAlbum, onOpenAlbum
    ) {
        LibrarySongListActions(
            onToggleSelect = onToggleSelect,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onStartRadio = onStartRadio,
            onAddToPlaylist = onAddToPlaylist,
            onEditMetadata = onEditMetadata,
            onEditLyrics = onEditLyrics,
            onIdentify = onIdentify,
            onDeleteSong = onDeleteSong,
            onPlayAlbum = onPlayAlbum,
            onShuffleAlbum = onShuffleAlbum,
            onToggleSelectAlbum = onToggleSelectAlbum,
            onAlbumLongClick = onAlbumLongClick,
            onToggleCollapseAlbum = onToggleCollapseAlbum,
            onEditAlbum = onEditAlbum,
            onChangeAlbumCover = onChangeAlbumCover,
            onIdentifyAlbum = onIdentifyAlbum,
            onOpenAlbum = onOpenAlbum
        )
    }
    LibrarySongList(
        list = list,
        currentSongId = currentSongId,
        isSelectionMode = isSelectionMode,
        selectedSongIds = selectedSongIds,
        collapsedAlbumNames = collapsedAlbumNames,
        sortOption = sortOption,
        emphasizeLastPlayed = emphasizeLastPlayed,
        emptySubtitle = emptySubtitle,
        emptyText = emptyText,
        loading = loading,
        fastScrollSettings = fastScrollSettings,
        actions = actions,
        onSongClick = onSongClick,
        onSongLongClick = onSongLongClick,
        currentSongIdFlow = currentSongIdFlow,
        listState = listState,
        modifier = modifier
    )
}

@Composable
private fun LibraryAlbumHeaderRow(
    item: LibraryListItem.AlbumHeader,
    selectedSongIds: Set<Long>,
    isSelectionMode: Boolean,
    collapsedAlbumNames: Set<String>,
    albumActionsState: State<LibraryAlbumGroupActions>
) {
    val groupingKey = item.groupingKey
    val selectionState = if (isSelectionMode) {
        remember(groupingKey, selectedSongIds) {
            albumHeaderSelectionState(item.songIds, selectedSongIds, true)
        }
    } else {
        AlbumHeaderSelectionState.NONE
    }
    val headerActions = remember(groupingKey, albumActionsState) {
        AlbumHeaderActions(
            onPlay = { albumActionsState.value.onPlayAlbum(item.albumName, item.songIds) },
            onShuffle = { albumActionsState.value.onShuffleAlbum(item.albumName, item.songIds) },
            onOpen = { albumActionsState.value.onOpenAlbum(item.albumName) },
            onEdit = { albumActionsState.value.onEditAlbum(item.albumName) },
            onChangeCover = { albumActionsState.value.onChangeAlbumCover(item.albumName) },
            onIdentify = { albumActionsState.value.onIdentifyAlbum(item.albumName) },
            onToggleSelect = { albumActionsState.value.onToggleSelectAlbum(item.songIds) },
            onLongClick = { albumActionsState.value.onAlbumLongClick(item.songIds) },
            onToggleCollapse = { albumActionsState.value.onToggleCollapseAlbum(item.albumName) }
        )
    }
    AlbumHeader(
        title = item.displayName,
        artistName = item.artistName,
        subtitle = item.subtitle,
        artworkUri = item.artworkUri,
        songCount = item.songCount,
        sortHint = item.sortHint,
        isCollapsed = item.matchesCollapsed(collapsedAlbumNames),
        isSelectionMode = isSelectionMode,
        selectionState = selectionState,
        actions = headerActions
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

