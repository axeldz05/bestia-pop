package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.preferences.FastScrollSettings
import com.bestiapop.android.data.preferences.SubmenuSwipeAction
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.FastScrollLazyColumn
import com.bestiapop.android.ui.components.FastScrollSections
import com.bestiapop.android.ui.components.LocalSubmenuGestureSettings
import com.bestiapop.android.ui.components.formatSortRelevantInfo
import com.bestiapop.android.ui.theme.ListDensity

/** Level 2: High-level LibraryGenreList accepting bundled [AggregateBrowseActions]. */
@Composable
fun LibraryGenreList(
    genres: List<GenreGroup>,
    actions: AggregateBrowseActions<GenreGroup>,
    sortOption: SortOption = SortOption.TITLE,
    fastScrollSettings: FastScrollSettings = FastScrollSettings(),
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    val sections = remember(genres, sortOption) {
        FastScrollSections.fromGenres(genres, sortOption)
    }

    FastScrollLazyColumn(
        items = genres,
        key = { it.name },
        sections = sections,
        emptyText = "Ningún género coincide",
        fastScrollSettings = fastScrollSettings,
        listState = listState,
        modifier = modifier
    ) { genre ->
        GenreListItem(
            genre = genre,
            sortOption = sortOption,
            onClick = { actions.onClick(genre) },
            onPlay = { actions.onPlay(genre) },
            onShuffle = { actions.onShuffle(genre) },
            onSwipeAction = actions.onSwipeAction?.let { cb -> { cb(genre) } }
        )
    }
}

/** Level 1: Low-level LibraryGenreList with individual primitive callbacks. */
@Composable
fun LibraryGenreList(
    genres: List<GenreGroup>,
    sortOption: SortOption = SortOption.TITLE,
    fastScrollSettings: FastScrollSettings = FastScrollSettings(),
    onGenreClick: (GenreGroup) -> Unit,
    onPlayGenre: (GenreGroup) -> Unit,
    onShuffleGenre: (GenreGroup) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    val actions = remember(onGenreClick, onPlayGenre, onShuffleGenre) {
        AggregateBrowseActions(
            onClick = onGenreClick,
            onPlay = onPlayGenre,
            onShuffle = onShuffleGenre
        )
    }
    LibraryGenreList(
        genres = genres,
        actions = actions,
        sortOption = sortOption,
        fastScrollSettings = fastScrollSettings,
        listState = listState,
        modifier = modifier
    )
}

@Composable
fun GenreListItem(
    genre: GenreGroup,
    sortOption: SortOption = SortOption.TITLE,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    swipeAction: SubmenuSwipeAction = LocalSubmenuGestureSettings.current.swipeLeftAction,
    onSwipeAction: (() -> Unit)? = null
) {
    val sortInfo = remember(genre.dateAdded, sortOption) {
        formatSortRelevantInfo(
            sortOption = sortOption,
            genre = null,
            dateAdded = genre.dateAdded,
            alreadyShowsArtist = false,
            alreadyShowsAlbum = false,
            alreadyShowsTitle = true
        )
    }
    val subtitle = remember(genre.songCount, sortInfo) {
        val base = "${genre.songCount} canciones"
        if (sortInfo.isNullOrBlank()) base else "$base • $sortInfo"
    }

    LibraryAggregateListItem(
        title = genre.name,
        subtitle = subtitle,
        artworkUri = genre.artworkUri,
        artworkCornerRadius = ListDensity.corner,
        fallbackIcon = Icons.Default.Audiotrack,
        playDescription = "Reproducir género",
        shuffleDescription = "Mezclar género",
        onClick = onClick,
        onPlay = onPlay,
        onShuffle = onShuffle,
        swipeAction = swipeAction,
        onSwipeAction = onSwipeAction
    )
}
