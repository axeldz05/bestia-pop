package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.preferences.FastScrollSettings
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.AlbumHeader
import com.bestiapop.android.ui.components.AlbumHeaderActions
import com.bestiapop.android.ui.components.FastScrollLazyColumn
import com.bestiapop.android.data.preferences.SubmenuSwipeAction
import com.bestiapop.android.ui.components.FastScrollSections
import com.bestiapop.android.ui.components.formatSortRelevantInfo

/** Action bundle for album browse items. */
@Immutable
data class AlbumBrowseActions(
    val onAlbumClick: (Album) -> Unit,
    val onPlayAlbum: (Album) -> Unit,
    val onShuffleAlbum: (Album) -> Unit,
    val onEditAlbum: (Album) -> Unit,
    val onChangeAlbumCover: (Album) -> Unit,
    val onIdentifyAlbum: (Album) -> Unit = {},
    val onSwipeAlbum: ((Album) -> Unit)? = null
)

/**
 * Browse projection for albums: dense [AlbumHeader] rows (no big grid cards).
 * Level 2: High-level LibraryAlbumBrowseList accepting bundled [AlbumBrowseActions].
 */
@Composable
fun LibraryAlbumBrowseList(
    albums: List<Album>,
    actions: AlbumBrowseActions,
    sortOption: SortOption = SortOption.TITLE,
    fastScrollSettings: FastScrollSettings = FastScrollSettings(),
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    val sections = remember(albums, sortOption) {
        FastScrollSections.fromAlbums(albums, sortOption)
    }

    FastScrollLazyColumn(
        items = albums,
        key = { it.groupingKey.ifBlank { it.name } },
        sections = sections,
        emptyText = "Ningún álbum coincide",
        fastScrollSettings = fastScrollSettings,
        listState = listState,
        modifier = modifier
    ) { album ->
        val sortHint = remember(album.genre, album.dateAdded, sortOption) {
            formatSortRelevantInfo(
                sortOption = sortOption,
                genre = album.genre,
                dateAdded = album.dateAdded,
                alreadyShowsArtist = true,
                alreadyShowsAlbum = true,
                alreadyShowsTitle = true
            )
        }
        val headerActions = remember(album, actions) {
            AlbumHeaderActions(
                onPlay = { actions.onPlayAlbum(album) },
                onShuffle = { actions.onShuffleAlbum(album) },
                onOpen = { actions.onAlbumClick(album) },
                onEdit = { actions.onEditAlbum(album) },
                onChangeCover = { actions.onChangeAlbumCover(album) },
                onIdentify = { actions.onIdentifyAlbum(album) },
                onSwipeAction = actions.onSwipeAlbum?.let { action -> { action(album) } }
            )
        }
        AlbumHeader(
            title = album.displayName,
            artistName = album.artist,
            artworkUri = album.artworkUri,
            songCount = album.songCount,
            sortHint = sortHint,
            showCollapseToggle = false,
            actions = headerActions
        )
    }
}

/**
 * Level 1: Low-level LibraryAlbumBrowseList with individual primitive callbacks.
 */
@Composable
fun LibraryAlbumBrowseList(
    albums: List<Album>,
    sortOption: SortOption = SortOption.TITLE,
    fastScrollSettings: FastScrollSettings = FastScrollSettings(),
    onAlbumClick: (Album) -> Unit,
    onPlayAlbum: (Album) -> Unit,
    onShuffleAlbum: (Album) -> Unit,
    onEditAlbum: (Album) -> Unit,
    onChangeAlbumCover: (Album) -> Unit,
    onIdentifyAlbum: (Album) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    val actions = remember(onAlbumClick, onPlayAlbum, onShuffleAlbum, onEditAlbum, onChangeAlbumCover, onIdentifyAlbum) {
        AlbumBrowseActions(
            onAlbumClick = onAlbumClick,
            onPlayAlbum = onPlayAlbum,
            onShuffleAlbum = onShuffleAlbum,
            onEditAlbum = onEditAlbum,
            onChangeAlbumCover = onChangeAlbumCover,
            onIdentifyAlbum = onIdentifyAlbum
        )
    }
    LibraryAlbumBrowseList(
        albums = albums,
        actions = actions,
        sortOption = sortOption,
        fastScrollSettings = fastScrollSettings,
        listState = listState,
        modifier = modifier
    )
}
